import java.io.PrintStream;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe statistics of one benchmark run.
 *
 * Commits are only counted while the measurement window is open, so warm-up
 * and shutdown do not distort the throughput.
 */
public class BenchmarkMetrics {

    private static final TransactionType[] TYPES = TransactionType.values();

    private final LongAdder[] commits = new LongAdder[TYPES.length];
    /** Sum of the latencies (first attempt until commit, incl. restarts) per type, in ns. */
    private final LongAdder[] latencyNanos = new LongAdder[TYPES.length];
    private final LongAdder deadlockRestarts = new LongAdder();
    private final LongAdder otherRestarts = new LongAdder();

    private volatile boolean measuring = false;
    private volatile long startNanos;
    private volatile long endNanos;

    public TableStats tableBefore;
    public TableStats tableAfter;

    public BenchmarkMetrics() {
        for (int i = 0; i < TYPES.length; i++) {
            commits[i] = new LongAdder();
            latencyNanos[i] = new LongAdder();
        }
    }

    public void beginMeasurement() {
        startNanos = System.nanoTime();
        endNanos = 0;
        measuring = true;
    }

    public void endMeasurement() {
        endNanos = System.nanoTime();
        measuring = false;
    }

    public boolean isMeasuring() {
        return measuring;
    }

    public void recordCommit(TransactionType type, long latency) {
        if (!measuring) {
            return;
        }
        commits[type.ordinal()].increment();
        latencyNanos[type.ordinal()].add(latency);
    }

    public void recordRestart(boolean deadlock) {
        if (!measuring) {
            return;
        }
        if (deadlock) {
            deadlockRestarts.increment();
        } else {
            otherRestarts.increment();
        }
    }

    public long committed(TransactionType t) {
        return commits[t.ordinal()].sum();
    }

    public long committedTotal() {
        long s = 0;
        for (LongAdder a : commits) {
            s += a.sum();
        }
        return s;
    }

    public long deadlockRestarts() {
        return deadlockRestarts.sum();
    }

    public long otherRestarts() {
        return otherRestarts.sum();
    }

    public double elapsedSeconds() {
        long end = endNanos > 0 ? endNanos : System.nanoTime();
        return Math.max(1e-9, (end - startNanos) / 1e9);
    }

    public double throughput() {
        return committedTotal() / elapsedSeconds();
    }

    /** Average latency of one transaction of this type in ms (incl. restarts). */
    public double avgLatencyMs(TransactionType t) {
        long n = committed(t);
        return n == 0 ? 0 : latencyNanos[t.ordinal()].sum() / 1e6 / n;
    }

    /** Share of the total transaction time spent in this type (in %). */
    public double timeSharePercent(TransactionType t) {
        long total = 0;
        for (LongAdder a : latencyNanos) {
            total += a.sum();
        }
        return total == 0 ? 0 : 100.0 * latencyNanos[t.ordinal()].sum() / total;
    }

    public void printSummary(PrintStream out, BenchmarkConfig cfg) {
        long total = committedTotal();
        out.println("===============================================================");
        out.println(" RESULT: " + cfg.label);
        out.println("===============================================================");
        out.printf(" Threads:            %d%n", cfg.threads);
        out.printf(" Mode:               %s%n", cfg.mode());
        out.printf(" Optimizations:      %s%n", cfg.optimizations());
        out.printf(" Measured time:      %.2f s%n", elapsedSeconds());
        out.printf(" Committed tx:       %d%n", total);
        out.printf(" Throughput:         %.1f tx/s%n", throughput());
        out.printf(" Deadlock restarts:  %d%n", deadlockRestarts());
        out.printf(" Other restarts:     %d%n", otherRestarts());
        out.println("---------------------------------------------------------------");
        out.println(" Type          committed   share   avg latency   share of time");
        for (TransactionType t : TYPES) {
            long n = committed(t);
            out.printf(" %-12s %10d  %5.1f%%  %9.3f ms   %6.1f%%%n",
                    t, n, total == 0 ? 0 : 100.0 * n / total, avgLatencyMs(t), timeSharePercent(t));
        }
        if (tableBefore != null && tableAfter != null) {
            out.println("---------------------------------------------------------------");
            out.println(" Table blocking_data   before -> after");
            out.printf(" Size:                 %s -> %s%n", tableBefore.sizePretty(), tableAfter.sizePretty());
            out.printf(" Full scan time:       %.2f ms -> %.2f ms%n", tableBefore.scanMs, tableAfter.scanMs);
            out.printf(" HOT updates in run:   %d of %d updates%n",
                    tableAfter.hotUpdates - tableBefore.hotUpdates, tableAfter.updates - tableBefore.updates);
        }
        out.println("===============================================================");
        out.flush();
    }
}
