/**
 * Homework 3 - Part 2.3: optimized variant (all optimizations enabled).
 *
 * Kept as an entry point for compatibility; the implementation lives in
 * {@link WorkloadRunner} (shared engine) with {@link BenchmarkConfig#optimized()}.
 *
 * Usage: java -cp "bin;lib/postgresql-42.2.18.jar" OptimizedBenchmark [--threads N] [--duration S | --tx K]
 */
public class OptimizedBenchmark {
    public static void main(String[] args) {
        String[] a = new String[args.length + 2];
        a[0] = "--run";
        a[1] = "optimized";
        System.arraycopy(args, 0, a, 2, args.length);
        BenchmarkSuite.main(a);
    }
}
