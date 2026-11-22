/**
 * Homework 3 - Part 1 / 2.1: baseline variant (no optimizations).
 *
 * Kept as an entry point for compatibility; the implementation lives in
 * {@link WorkloadRunner} (shared engine) with {@link BenchmarkConfig#baseline()}.
 *
 * Usage: java -cp "bin;lib/postgresql-42.2.18.jar" ExtendedBlockingBenchmark [--threads N] [--duration S | --tx K]
 */
public class ExtendedBlockingBenchmark {
    public static void main(String[] args) {
        String[] a = new String[args.length + 2];
        a[0] = "--run";
        a[1] = "baseline";
        System.arraycopy(args, 0, a, 2, args.length);
        BenchmarkSuite.main(a);
    }
}
