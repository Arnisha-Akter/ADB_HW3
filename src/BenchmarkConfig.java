/**
 * All knobs of one benchmark run.
 *
 * Every optimization from Part 2 is an individual switch, so the suite can
 * measure the baseline, the fully optimized variant, and each optimization
 * on its own (ablation study).
 */
public class BenchmarkConfig {

    /** Same table size as in Homework 2 (Blocking.java: num_tuple = 50000). Do not increase. */
    public static final int NUM_TUPLES = 50000;
    public static final double MAX_VALUE = 50.0;
    /** num_queries_per_task from Homework 2. */
    public static final int QUERIES_PER_TASK = 3;

    public String label = "Baseline";
    public int threads = 4;

    // ---- Continuous-stream mode (Part 1) ----
    /** Measured window in seconds. */
    public int durationSeconds = 30;
    /** Unmeasured warm-up before the window (JIT, buffer cache, connections). */
    public int warmupSeconds = 5;

    // ---- Fixed-stream mode (Part 2) ----
    /** If > 0: run exactly this many pre-generated transactions and measure the time until all committed. */
    public int fixedTransactions = 0;

    /** Seed of the workload generator: every variant sees the same transaction stream. */
    public long seed = 42L;

    // ---- Output ----
    /** Print the result (sum) of every read transaction to the console, as required in Task 1.1. */
    public boolean printResults = true;
    /** Print one status line per second. */
    public boolean statusLines = true;

    // ---- Optimizations (Part 2.3) ----
    /** Prepare statements once per connection instead of once per transaction. */
    public boolean cacheStatements = false;
    /** Write console output through one large buffer instead of a synchronous flush per line. */
    public boolean bufferedOutput = false;
    /** SET synchronous_commit = off (no WAL fsync wait on COMMIT; trades durability of the last ms). */
    public boolean asyncCommit = false;
    /** fillfactor = 70 + aggressive autovacuum: enables HOT updates and keeps the table from bloating. */
    public boolean tunedTable = false;
    /** Max. number of concurrently running full table scans (0 = unlimited). */
    public int scanPermits = 0;

    public boolean isFixedStream() {
        return fixedTransactions > 0;
    }

    public static BenchmarkConfig baseline() {
        BenchmarkConfig c = new BenchmarkConfig();
        c.label = "Baseline";
        return c;
    }

    public static BenchmarkConfig optimized() {
        BenchmarkConfig c = new BenchmarkConfig();
        c.label = "Optimized";
        c.cacheStatements = true;
        c.bufferedOutput = true;
        c.asyncCommit = true;
        c.tunedTable = true;
        c.scanPermits = 0; // measured separately in the ablation, enable only if it helps on your machine
        return c;
    }

    public BenchmarkConfig copy() {
        BenchmarkConfig c = new BenchmarkConfig();
        c.label = label;
        c.threads = threads;
        c.durationSeconds = durationSeconds;
        c.warmupSeconds = warmupSeconds;
        c.fixedTransactions = fixedTransactions;
        c.seed = seed;
        c.printResults = printResults;
        c.statusLines = statusLines;
        c.cacheStatements = cacheStatements;
        c.bufferedOutput = bufferedOutput;
        c.asyncCommit = asyncCommit;
        c.tunedTable = tunedTable;
        c.scanPermits = scanPermits;
        return c;
    }

    public BenchmarkConfig withLabel(String l) { BenchmarkConfig c = copy(); c.label = l; return c; }
    public BenchmarkConfig withThreads(int t) { BenchmarkConfig c = copy(); c.threads = t; return c; }

    /** Short description of the active optimizations, e.g. "cache+buffer+async". */
    public String optimizations() {
        StringBuilder sb = new StringBuilder();
        if (cacheStatements) sb.append("cache+");
        if (bufferedOutput) sb.append("buffer+");
        if (asyncCommit) sb.append("async+");
        if (tunedTable) sb.append("table+");
        if (scanPermits > 0) sb.append("scan").append(scanPermits).append('+');
        return sb.length() == 0 ? "none" : sb.substring(0, sb.length() - 1);
    }

    public String mode() {
        return isFixedStream() ? "fixed(" + fixedTransactions + " tx)" : "continuous(" + durationSeconds + " s)";
    }

    @Override
    public String toString() {
        return label + " [threads=" + threads + ", mode=" + mode() + ", optimizations=" + optimizations() + "]";
    }
}
