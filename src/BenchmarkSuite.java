import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command line entry point for Homework 3.
 *
 * Windows:  java -cp "bin;lib/postgresql-42.2.18.jar" BenchmarkSuite <mode> [options]
 * macOS:    java -cp "bin:lib/postgresql-42.2.18.jar" BenchmarkSuite <mode> [options]
 *
 * Modes
 *   --demo                 Task 1.4: 30 s live demo (continuous stream, optimized, 4 threads)
 *   --scaling              Task 1.3: throughput for 1,2,4,8,16 threads, baseline and optimized
 *   --serial               Task 2.1: trivial serial execution of the fixed stream (1 thread)
 *   --compare              Task 2.1/2.3/2.4: serial vs. concurrent, baseline vs. optimized,
 *                          plus every optimization on its own (ablation)
 *   --all                  --scaling followed by --compare
 *   --run baseline|optimized   one single run with the options below
 *
 * Options
 *   --threads N | --threads 1,2,4,8   number of worker threads (list for --scaling)
 *   --duration S           measured seconds (continuous mode)
 *   --warmup S             unmeasured warm-up seconds (continuous mode)
 *   --tx K                 fixed stream with K transactions (instead of continuous)
 *   --reps R               repetitions per configuration (mean, min, max are reported)
 *   --seed X               workload seed
 *   --scan-permits P       limit concurrent full scans to P (optimized / --run)
 *   --no-print             do NOT print the sums (debugging only; Task 1.1 requires printing)
 *
 * Every finished configuration is appended to results/results.csv.
 */
public class BenchmarkSuite {

    private static final String CSV = "results/results.csv";

    // parsed options
    private static int[] threadList = null;
    private static Integer duration = null;
    private static Integer warmup = null;
    private static Integer tx = null;
    private static int reps = 3;
    private static Long seed = null;
    private static Integer scanPermits = null;
    private static boolean noPrint = false;

    // overall progress of the suite
    private static int plannedRuns = 0;
    private static int finishedRuns = 0;
    private static final long SUITE_START = System.currentTimeMillis();

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        String mode = args.length > 0 ? args[0] : "--demo";
        String runVariant = "optimized";

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--threads": threadList = parseList(args[++i]); break;
                case "--duration": duration = Integer.parseInt(args[++i]); break;
                case "--warmup": warmup = Integer.parseInt(args[++i]); break;
                case "--tx": tx = Integer.parseInt(args[++i]); break;
                case "--reps": reps = Integer.parseInt(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--scan-permits": scanPermits = Integer.parseInt(args[++i]); break;
                case "--no-print": noPrint = true; break;
                case "baseline": case "optimized": runVariant = a; break;
                default:
                    System.err.println("Unknown option: " + a);
                    printUsage();
                    return;
            }
        }

        int nThreadsScaling = threadList != null ? threadList.length : 5;
        switch (mode) {
            case "--scaling": plannedRuns = 2 * nThreadsScaling * reps; break;
            case "--serial": plannedRuns = reps; break;
            case "--compare": plannedRuns = 9 * reps; break;
            case "--all": plannedRuns = 2 * nThreadsScaling * reps + 9 * reps; break;
            case "--run": plannedRuns = reps; break;
            default: plannedRuns = 0;
        }

        switch (mode) {
            case "--demo": runDemo(); break;
            case "--scaling": runScaling(); break;
            case "--serial": runSerial(); break;
            case "--compare": runCompare(); break;
            case "--all": runScaling(); runCompare(); break;
            case "--run": runSingle(runVariant); break;
            default: printUsage();
        }
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    /** Task 1.4 */
    static void runDemo() {
        BenchmarkConfig c = apply(BenchmarkConfig.optimized().withLabel("Live demo (optimized)"));
        if (scanPermits != null) c.scanPermits = scanPermits;
        c.threads = threadList != null ? threadList[0] : 4;
        c.durationSeconds = duration != null ? duration : 30;
        c.warmupSeconds = warmup != null ? warmup : 0;
        c.fixedTransactions = 0;
        System.out.println("=================================================================");
        System.out.println(" HOMEWORK 3 LIVE DEMO: continuous stream, " + c.threads + " threads, " + c.durationSeconds + " s");
        System.out.println(" Workload: 60% point reads | 10% inserts | 10% full scans | 20% updates");
        System.out.println("=================================================================");
        BenchmarkMetrics m = new WorkloadRunner(c).run();
        m.printSummary(System.out, c);
        appendCsv(c, m, 1);
    }

    /** Task 1.3: does throughput scale linearly with the number of threads? */
    static void runScaling() {
        int[] threads = threadList != null ? threadList : new int[] { 1, 2, 4, 8, 16 };
        List<String[]> rows = new ArrayList<>();
        for (BenchmarkConfig base : new BenchmarkConfig[] { BenchmarkConfig.baseline(), BenchmarkConfig.optimized() }) {
            double oneThread = 0;
            for (int t : threads) {
                BenchmarkConfig c = apply(base.withThreads(t));
                c.fixedTransactions = 0;
                c.durationSeconds = duration != null ? duration : 20;
                c.warmupSeconds = warmup != null ? warmup : 5;
                Stats s = repeat(c);
                if (t == threads[0]) oneThread = s.mean / threads[0];
                rows.add(new String[] { base.label, String.valueOf(t), s.toString(),
                        f2(s.mean / oneThread), f2((double) t), f1(100.0 * s.mean / (oneThread * t)) + "%" });
            }
        }
        printTable("THREAD SCALING (Task 1.3) - continuous stream, " + reps + " reps each, CPU cores: "
                        + Runtime.getRuntime().availableProcessors(),
                new String[] { "Variant", "Threads", "Throughput tx/s (mean [min-max])", "Speedup", "Linear", "Efficiency" },
                rows);
    }

    /** Task 2.1 */
    static void runSerial() {
        BenchmarkConfig c = apply(BenchmarkConfig.baseline().withLabel("Serial baseline"));
        c.threads = 1;
        c.fixedTransactions = tx != null ? tx : 20000;
        Stats s = repeat(c);
        printTable("SERIAL EXECUTION (Task 2.1)",
                new String[] { "Variant", "Threads", "Transactions", "Time s (mean)", "Throughput tx/s (mean [min-max])" },
                List.<String[]>of(new String[] { c.label, "1", String.valueOf(c.fixedTransactions), f2(s.meanSeconds), s.toString() }));
    }

    /** Task 2.1, 2.3, 2.4: same fixed stream, serial vs. concurrent, plus ablation. */
    static void runCompare() {
        int n = threadList != null ? threadList[0] : 8;
        int k = tx != null ? tx : 20000;

        BenchmarkConfig base = BenchmarkConfig.baseline();
        BenchmarkConfig opt = BenchmarkConfig.optimized();
        if (scanPermits != null) opt.scanPermits = scanPermits;

        List<BenchmarkConfig> configs = new ArrayList<>();
        configs.add(base.withLabel("Serial baseline").withThreads(1));
        configs.add(opt.withLabel("Serial optimized").withThreads(1));
        configs.add(base.withLabel("Concurrent baseline").withThreads(n));

        BenchmarkConfig c;
        c = base.withLabel("  + statement caching").withThreads(n); c.cacheStatements = true; configs.add(c);
        c = base.withLabel("  + buffered output").withThreads(n); c.bufferedOutput = true; configs.add(c);
        c = base.withLabel("  + async commit").withThreads(n); c.asyncCommit = true; configs.add(c);
        c = base.withLabel("  + fillfactor/HOT").withThreads(n); c.tunedTable = true; configs.add(c);
        c = base.withLabel("  + scan limit (2)").withThreads(n); c.scanPermits = 2; configs.add(c);
        configs.add(opt.withLabel("Concurrent optimized").withThreads(n));

        // Unmeasured warm-up run (JIT, PostgreSQL buffer cache).
        BenchmarkConfig w = apply(base.withLabel("warm-up").withThreads(n));
        w.fixedTransactions = Math.max(1000, k / 10);
        w.printResults = false;
        w.statusLines = false;
        new WorkloadRunner(w).run();

        List<String[]> rows = new ArrayList<>();
        double serialTime = 0;
        for (BenchmarkConfig cfg : configs) {
            BenchmarkConfig a = apply(cfg);
            a.fixedTransactions = k;
            Stats s = repeat(a);
            if (serialTime == 0) serialTime = s.meanSeconds;
            rows.add(new String[] { a.label.trim(), String.valueOf(a.threads), a.optimizations(), f2(s.meanSeconds),
                    s.toString(), f2(serialTime / s.meanSeconds) + "x", String.valueOf(s.deadlocks) });
        }
        printTable("SERIAL vs. CONCURRENT (Task 2.1 / 2.3 / 2.4) - same stream of " + k + " transactions, "
                        + reps + " reps each",
                new String[] { "Variant", "Threads", "Optimizations", "Time s (mean)", "Throughput tx/s (mean [min-max])",
                        "Speedup vs serial", "Deadlock restarts" },
                rows);
    }

    static void runSingle(String variant) {
        BenchmarkConfig c = apply("baseline".equals(variant) ? BenchmarkConfig.baseline() : BenchmarkConfig.optimized());
        if (scanPermits != null && !"baseline".equals(variant)) c.scanPermits = scanPermits;
        c.threads = threadList != null ? threadList[0] : 4;
        Stats s = repeat(c);
        System.out.println("Mean throughput: " + s);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Applies the command line options to a configuration. */
    static BenchmarkConfig apply(BenchmarkConfig c) {
        BenchmarkConfig a = c.copy();
        if (duration != null) a.durationSeconds = duration;
        if (warmup != null) a.warmupSeconds = warmup;
        if (tx != null) a.fixedTransactions = tx;
        if (seed != null) a.seed = seed;
        if (noPrint) a.printResults = false;
        return a;
    }

    static final class Stats {
        double mean, min = Double.MAX_VALUE, max = 0, meanSeconds;
        long deadlocks;

        @Override
        public String toString() {
            return f1(mean) + " [" + f1(min) + "-" + f1(max) + "]";
        }
    }

    /** Runs a configuration reps times; same seed -> same transaction stream each time. */
    static Stats repeat(BenchmarkConfig c) {
        Stats s = new Stats();
        for (int r = 1; r <= reps; r++) {
            printProgressBanner(c, r);
            BenchmarkMetrics m = new WorkloadRunner(c).run();
            m.printSummary(System.out, c);
            finishedRuns++;
            appendCsv(c, m, r);
            double tps = m.throughput();
            s.mean += tps / reps;
            s.meanSeconds += m.elapsedSeconds() / reps;
            s.min = Math.min(s.min, tps);
            s.max = Math.max(s.max, tps);
            s.deadlocks += m.deadlockRestarts();
        }
        return s;
    }

    /** Big, easy to spot line before every run: which run, how far, how long still. */
    static void printProgressBanner(BenchmarkConfig c, int rep) {
        long elapsedS = (System.currentTimeMillis() - SUITE_START) / 1000;
        String eta = "estimating ...";
        if (finishedRuns > 0 && plannedRuns > finishedRuns) {
            long left = elapsedS * (plannedRuns - finishedRuns) / finishedRuns;
            eta = "about " + mmss(left) + " left";
        }
        String total = plannedRuns > 0 ? " of " + plannedRuns : "";
        show("");
        show("#################################################################################");
        show(String.format("### RUN %d%s | %s | threads=%d | repetition %d/%d",
                finishedRuns + 1, total, c.label.trim(), c.threads, rep, reps));
        show(String.format("### elapsed %s | %s", mmss(elapsedS), eta));
        show("#################################################################################");
        System.out.flush();
    }

    /** stdout redirected into a file (System.console() == null)? Then also show the line in the console. */
    static final boolean STDOUT_REDIRECTED = System.console() == null;

    static void show(String line) {
        System.out.println(line);
        if (STDOUT_REDIRECTED) {
            System.err.println(line);
        }
    }

    static String mmss(long s) {
        return String.format("%d:%02d min", s / 60, s % 60);
    }

    static void appendCsv(BenchmarkConfig c, BenchmarkMetrics m, int rep) {
        File f = new File(CSV);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        boolean header = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (header) {
                w.println("timestamp,label,mode,threads,optimizations,rep,seconds,committed,throughput_tps,"
                        + "deadlock_restarts,other_restarts,lat_point_ms,lat_insert_ms,lat_scan_ms,lat_update_ms,"
                        + "time_share_scan_pct,table_mb_before,table_mb_after,scan_ms_before,scan_ms_after,cpu_cores");
            }
            w.printf("%s,%s,%s,%d,%s,%d,%.3f,%d,%.1f,%d,%d,%.4f,%.4f,%.4f,%.4f,%.1f,%.2f,%.2f,%.3f,%.3f,%d%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    c.label.trim().replace(',', ' '), c.mode(), c.threads, c.optimizations(), rep,
                    m.elapsedSeconds(), m.committedTotal(), m.throughput(), m.deadlockRestarts(), m.otherRestarts(),
                    m.avgLatencyMs(TransactionType.POINT_READ), m.avgLatencyMs(TransactionType.INSERT),
                    m.avgLatencyMs(TransactionType.FULL_SCAN), m.avgLatencyMs(TransactionType.WRITE_UPDATE),
                    m.timeSharePercent(TransactionType.FULL_SCAN),
                    m.tableBefore == null ? 0 : m.tableBefore.sizeBytes / 1048576.0,
                    m.tableAfter == null ? 0 : m.tableAfter.sizeBytes / 1048576.0,
                    m.tableBefore == null ? 0 : m.tableBefore.scanMs,
                    m.tableAfter == null ? 0 : m.tableAfter.scanMs,
                    Runtime.getRuntime().availableProcessors());
        } catch (IOException e) {
            System.err.println("Could not write " + CSV + ": " + e.getMessage());
        }
    }

    static void printTable(String title, String[] header, List<String[]> rows) {
        int[] width = new int[header.length];
        for (int i = 0; i < header.length; i++) width[i] = header[i].length();
        for (String[] r : rows) for (int i = 0; i < r.length; i++) width[i] = Math.max(width[i], r[i].length());
        StringBuilder line = new StringBuilder();
        for (int wd : width) line.append("-".repeat(wd + 3));
        show("");
        show(line.toString());
        show(" " + title);
        show(line.toString());
        show(row(header, width));
        show(line.toString());
        for (String[] r : rows) show(row(r, width));
        show(line.toString());
        show(" (all runs are also in " + CSV + ")");
    }

    static String row(String[] r, int[] width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) {
            sb.append(' ').append(String.format("%-" + width[i] + "s", r[i])).append(" |");
        }
        return sb.toString();
    }

    static int[] parseList(String s) {
        String[] p = s.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }

    static String f1(double d) { return String.format("%.1f", d); }
    static String f2(double d) { return String.format("%.2f", d); }

    static void printUsage() {
        System.out.println("ADB Homework 3 - Benchmark Suite");
        System.out.println("  BenchmarkSuite --demo     [--threads 4] [--duration 30]          (Task 1.4)");
        System.out.println("  BenchmarkSuite --scaling  [--threads 1,2,4,8,16] [--duration 20] [--reps 3] (Task 1.3)");
        System.out.println("  BenchmarkSuite --serial   [--tx 20000] [--reps 3]                (Task 2.1)");
        System.out.println("  BenchmarkSuite --compare  [--threads 8] [--tx 20000] [--reps 3]  (Task 2.1/2.3/2.4)");
        System.out.println("  BenchmarkSuite --all");
        System.out.println("  BenchmarkSuite --run baseline|optimized [--threads N] [--duration S | --tx K]");
        System.out.println("Options: --warmup S --seed X --scan-permits P --no-print");
    }
}
