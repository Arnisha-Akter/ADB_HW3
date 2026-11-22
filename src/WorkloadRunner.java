import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark engine for Homework 3. Both the baseline and the optimized variant run
 * through this class; they only differ in their {@link BenchmarkConfig}.
 *
 * Two modes:
 *  - Continuous stream (Part 1): N worker threads, each one starts the next transaction
 *    the moment its previous one committed (closed system). The number of in-flight
 *    transactions is therefore constant (= N): new and committed transactions are in
 *    equilibrium. Throughput is measured in a fixed window after a warm-up.
 *  - Fixed stream (Part 2): a given stream of K pre-generated transactions is processed
 *    by N threads; we measure the time until ALL K transactions committed. With N = 1
 *    this is the trivial serial execution.
 *
 * Every transaction is retried until it commits (deadlock 40P01, serialization failure
 * 40001, duplicate key on insert, lost connection, ...), so every transaction eventually commits.
 */
public class WorkloadRunner {

    static final String SQL_POINT_READ = "SELECT data_value FROM blocking_data WHERE data_id = ?";
    static final String SQL_INSERT = "INSERT INTO blocking_data VALUES (?, ?, ?)";
    static final String SQL_SCAN = "SELECT sum(data_value) FROM blocking_data";
    static final String SQL_UPDATE = "UPDATE blocking_data SET data_value = ?, modified_by = ? WHERE data_id = ?";

    static final String DEADLOCK_DETECTED = "40P01";
    static final String SERIALIZATION_FAILURE = "40001";
    static final String UNIQUE_VIOLATION = "23505";

    /** Buffered console stream shared by all workers (optimization "bufferedOutput"). */
    private static final PrintStream BUFFERED_OUT =
            new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false);

    private final BenchmarkConfig cfg;
    private final BenchmarkMetrics metrics = new BenchmarkMetrics();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger nextInsertId = new AtomicInteger(BenchmarkConfig.NUM_TUPLES);
    private final AtomicInteger streamPosition = new AtomicInteger(0);
    private final AtomicInteger finishedStream = new AtomicInteger(0);
    private final Semaphore scanGate;
    private final PrintStream out;
    private List<TxSpec> stream;

    public WorkloadRunner(BenchmarkConfig cfg) {
        this.cfg = cfg;
        this.scanGate = cfg.scanPermits > 0 ? new Semaphore(cfg.scanPermits, true) : null;
        this.out = cfg.bufferedOutput ? BUFFERED_OUT : System.out;
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    public BenchmarkMetrics run() {
        try (Connection admin = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL)) {
            prepareDatabase(admin);
            metrics.tableBefore = TableStats.read(admin);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prepare database: " + e.getMessage(), e);
        }

        if (cfg.isFixedStream()) {
            // The workload itself (input of the benchmark), generated before the clock starts.
            Random rand = new Random(cfg.seed);
            stream = new ArrayList<>(cfg.fixedTransactions);
            for (int i = 0; i < cfg.fixedTransactions; i++) {
                stream.add(TxSpec.generate(rand));
            }
        }

        System.out.println(">>> Starting " + cfg);

        // Connections are opened before the measurement starts (not part of the task).
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < cfg.threads; i++) {
            workers.add(new Worker(i));
        }
        CountDownLatch ready = new CountDownLatch(cfg.threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(cfg.threads);
        List<Thread> threads = new ArrayList<>();
        for (Worker w : workers) {
            Thread t = new Thread(() -> {
                boolean signalled = false;
                try {
                    w.connect();
                    ready.countDown();
                    signalled = true;
                    go.await();
                    w.loop();
                } catch (Exception e) {
                    System.err.println("Worker " + w.id + " failed: " + e);
                } finally {
                    if (!signalled) ready.countDown(); // never block the start on a failed worker
                    w.close();
                    done.countDown();
                }
            }, "worker-" + w.id);
            threads.add(t);
            t.start();
        }

        try {
            ready.await();
            running.set(true);
            if (cfg.isFixedStream()) {
                metrics.beginMeasurement();
                go.countDown();
                long lastStatus = System.currentTimeMillis();
                while (!done.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (cfg.statusLines && System.currentTimeMillis() - lastStatus >= 1000) {
                        lastStatus = System.currentTimeMillis();
                        status(String.format("[STATUS] %.0f s | %d / %d committed | %.1f tx/s | %d deadlock restarts",
                                metrics.elapsedSeconds(), finishedStream.get(), cfg.fixedTransactions,
                                metrics.throughput(), metrics.deadlockRestarts()));
                    }
                }
                metrics.endMeasurement();
            } else {
                go.countDown();
                if (cfg.warmupSeconds > 0) {
                    status("[STATUS] warm-up " + cfg.warmupSeconds + " s (not measured)");
                    Thread.sleep(cfg.warmupSeconds * 1000L);
                }
                metrics.beginMeasurement();
                long deadline = System.nanoTime() + cfg.durationSeconds * 1_000_000_000L;
                while (true) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    long sleepMs = Math.min(1000, Math.max(1, left / 1_000_000));
                    Thread.sleep(sleepMs);
                    if (cfg.statusLines && sleepMs >= 500) {
                        status(String.format("[STATUS] %4.1f s | %d committed | %.1f tx/s | %d deadlock restarts",
                                metrics.elapsedSeconds(), metrics.committedTotal(), metrics.throughput(),
                                metrics.deadlockRestarts()));
                    }
                }
                metrics.endMeasurement();
                running.set(false);
                done.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.endMeasurement();
        } finally {
            running.set(false);
            for (Thread t : threads) {
                try {
                    t.join(10_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            out.flush();
        }

        try (Connection admin = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL)) {
            metrics.tableAfter = TableStats.read(admin);
            deleteInsertedTuples(admin);
        } catch (SQLException e) {
            System.err.println("Warning: cleanup failed: " + e.getMessage());
        }
        return metrics;
    }

    /** Status lines go to stderr: they stay visible when the sum output (stdout) is redirected
     *  into a log file, and in the console they are not mixed into a half-written buffer. */
    private void status(String line) {
        out.flush();
        System.err.println(">>> " + line + "   (" + cfg.label.trim() + ", " + cfg.threads + " threads)");
        System.err.flush();
    }

    // ------------------------------------------------------------------
    // Database preparation: identical start state for every run
    // ------------------------------------------------------------------

    private void prepareDatabase(Connection con) throws SQLException {
        con.setAutoCommit(true);
        try (Statement st = con.createStatement()) {
            boolean recreate;
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM blocking_data WHERE data_id < " + BenchmarkConfig.NUM_TUPLES)) {
                recreate = !rs.next() || rs.getInt(1) != BenchmarkConfig.NUM_TUPLES;
            } catch (SQLException e) {
                recreate = true;
            }

            if (recreate) {
                System.out.println("Creating blocking_data with " + BenchmarkConfig.NUM_TUPLES + " tuples ...");
                st.execute("DROP TABLE IF EXISTS blocking_data");
                st.execute("CREATE TABLE blocking_data (data_id integer PRIMARY KEY, data_value float, modified_by integer)");
                con.setAutoCommit(false);
                Random rand = new Random(123456);
                try (PreparedStatement ps = con.prepareStatement(SQL_INSERT)) {
                    for (int i = 0; i < BenchmarkConfig.NUM_TUPLES; i++) {
                        ps.setInt(1, i);
                        ps.setDouble(2, rand.nextDouble() * BenchmarkConfig.MAX_VALUE);
                        ps.setInt(3, 0);
                        ps.addBatch();
                        if ((i + 1) % 5000 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
                con.commit();
                con.setAutoCommit(true);
            }

            // Remove the tuples inserted by previous runs (Task 1.1 c).
            deleteInsertedTuples(con);

            if (cfg.tunedTable) {
                // Free space on every page -> UPDATEs can be HOT (no index on data_value),
                // autovacuum reclaims dead versions early -> table and scan time stay small.
                st.execute("ALTER TABLE blocking_data SET (fillfactor = 70, "
                        + "autovacuum_vacuum_scale_factor = 0.0, autovacuum_vacuum_threshold = 5000, "
                        + "autovacuum_vacuum_cost_delay = 0)");
            } else {
                st.execute("ALTER TABLE blocking_data RESET (fillfactor, autovacuum_vacuum_scale_factor, "
                        + "autovacuum_vacuum_threshold, autovacuum_vacuum_cost_delay)");
            }
            // Rewrite the table: removes all bloat of previous runs and applies the fillfactor.
            st.execute("VACUUM FULL ANALYZE blocking_data");
        }
    }

    private void deleteInsertedTuples(Connection con) throws SQLException {
        boolean auto = con.getAutoCommit();
        con.setAutoCommit(true);
        try (Statement st = con.createStatement()) {
            int n = st.executeUpdate("DELETE FROM blocking_data WHERE data_id >= " + BenchmarkConfig.NUM_TUPLES);
            if (n > 0) {
                System.out.println("Removed " + n + " inserted tuples.");
            }
        } finally {
            con.setAutoCommit(auto);
        }
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private final class Worker {
        final int id;
        final Random rand;
        Connection con;
        PreparedStatement psPointRead, psInsert, psScan, psUpdate;

        Worker(int id) {
            this.id = id;
            this.rand = new Random(cfg.seed * 1_000_003L + id);
        }

        void connect() throws SQLException {
            con = ConnectionFactory.getDefaultParameterConnection(ConnectionFactory.POSTGRESQL);
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            if (cfg.asyncCommit) {
                try (Statement st = con.createStatement()) {
                    st.execute("SET synchronous_commit = off");
                }
                con.commit();
            }
            if (cfg.cacheStatements) {
                psPointRead = con.prepareStatement(SQL_POINT_READ);
                psInsert = con.prepareStatement(SQL_INSERT);
                psScan = con.prepareStatement(SQL_SCAN);
                psUpdate = con.prepareStatement(SQL_UPDATE);
            }
        }

        void close() {
            closeQuietly(psPointRead);
            closeQuietly(psInsert);
            closeQuietly(psScan);
            closeQuietly(psUpdate);
            psPointRead = psInsert = psScan = psUpdate = null;
            closeQuietly(con);
            con = null;
        }

        void loop() {
            if (cfg.isFixedStream()) {
                int i;
                while ((i = streamPosition.getAndIncrement()) < stream.size()) {
                    executeUntilCommitted(stream.get(i), true);
                    finishedStream.incrementAndGet();
                }
            } else {
                while (running.get()) {
                    // Continuous stream: a finished transaction is immediately replaced by a new one.
                    executeUntilCommitted(TxSpec.generate(rand), false);
                }
            }
        }

        /** Executes the transaction and restarts it until it commits. */
        void executeUntilCommitted(TxSpec tx, boolean mustFinish) {
            long t0 = System.nanoTime();
            int attempt = 0;
            while (true) {
                try {
                    if (con == null || con.isClosed()) {
                        close();
                        connect();
                    }
                    double result = execute(tx);
                    con.commit();
                    metrics.recordCommit(tx.type, System.nanoTime() - t0);
                    printResult(tx, result);
                    return;
                } catch (SQLException e) {
                    rollbackQuietly();
                    String state = e.getSQLState();
                    boolean deadlock = DEADLOCK_DETECTED.equals(state);
                    metrics.recordRestart(deadlock);
                    boolean expected = deadlock || SERIALIZATION_FAILURE.equals(state) || UNIQUE_VIOLATION.equals(state);
                    if (!expected) {
                        System.err.println("Worker " + id + " restarts " + tx.type + " after error [" + state + "]: "
                                + e.getMessage());
                    }
                    if (!mustFinish && !running.get()) {
                        return; // continuous mode: benchmark is over
                    }
                    backoff(attempt++, expected);
                }
            }
        }

        /** Runs the statements of one transaction; returns the sum for read transactions. */
        double execute(TxSpec tx) throws SQLException {
            switch (tx.type) {
                case POINT_READ: {
                    double sum = 0;
                    PreparedStatement ps = cfg.cacheStatements ? psPointRead : con.prepareStatement(SQL_POINT_READ);
                    try {
                        for (int dataId : tx.ids) { // one SQL statement per tuple (Task 1.1 b)
                            ps.setInt(1, dataId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) sum += rs.getDouble(1);
                            }
                        }
                    } finally {
                        if (!cfg.cacheStatements) ps.close();
                    }
                    return sum;
                }
                case INSERT: {
                    PreparedStatement ps = cfg.cacheStatements ? psInsert : con.prepareStatement(SQL_INSERT);
                    try {
                        ps.setInt(1, nextInsertId.getAndIncrement()); // fresh id on every attempt
                        ps.setDouble(2, tx.values[0]);
                        ps.setInt(3, id);
                        ps.executeUpdate();
                    } finally {
                        if (!cfg.cacheStatements) ps.close();
                    }
                    return 0;
                }
                case FULL_SCAN: {
                    if (scanGate != null) scanGate.acquireUninterruptibly();
                    try {
                        PreparedStatement ps = cfg.cacheStatements ? psScan : con.prepareStatement(SQL_SCAN);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? rs.getDouble(1) : 0;
                        } finally {
                            if (!cfg.cacheStatements) ps.close();
                        }
                    } finally {
                        if (scanGate != null) scanGate.release();
                    }
                }
                case WRITE_UPDATE:
                default: {
                    PreparedStatement ps = cfg.cacheStatements ? psUpdate : con.prepareStatement(SQL_UPDATE);
                    try {
                        for (int i = 0; i < tx.ids.length; i++) { // 3 single-tuple updates, diced order
                            ps.setDouble(1, tx.values[i]);
                            ps.setInt(2, id);
                            ps.setInt(3, tx.ids[i]);
                            ps.executeUpdate();
                        }
                    } finally {
                        if (!cfg.cacheStatements) ps.close();
                    }
                    return 0;
                }
            }
        }

        /** Task 1.1 b/d: read transactions output their sum to the console at completion. */
        void printResult(TxSpec tx, double result) {
            if (!cfg.printResults) return;
            if (tx.type == TransactionType.POINT_READ) {
                out.println("[W" + id + "] point read " + tx.ids[0] + "," + tx.ids[1] + "," + tx.ids[2] + " sum=" + result);
            } else if (tx.type == TransactionType.FULL_SCAN) {
                out.println("[W" + id + "] full scan sum=" + result);
            }
        }

        void rollbackQuietly() {
            try {
                if (con != null && !con.isClosed()) con.rollback();
            } catch (SQLException ignored) {
            }
        }

        /** Randomized exponential backoff: avoids two restarted transactions colliding again (livelock). */
        void backoff(int attempt, boolean expected) {
            if (expected && attempt == 0) return; // first restart immediately
            long cap = expected ? Math.min(50, 1L << Math.min(attempt, 6)) : Math.min(2000, 100L << Math.min(attempt, 4));
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(cap + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
