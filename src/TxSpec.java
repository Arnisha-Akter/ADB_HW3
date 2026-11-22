import java.util.Random;

/**
 * One transaction of the workload (Task 1.1), fully determined before execution:
 * its type, the tuples it touches and the values it writes.
 *
 * Generating the transaction up front means a restart after a deadlock re-executes
 * exactly the same transaction (same tuples, same write order), and that all
 * benchmark variants can be fed the identical stream (same seed).
 */
public final class TxSpec {

    public final TransactionType type;
    /** Tuples read (POINT_READ) or updated (WRITE_UPDATE), in execution order. */
    public final int[] ids;
    /** Values written (WRITE_UPDATE: one per id, INSERT: one). */
    public final double[] values;

    private TxSpec(TransactionType type, int[] ids, double[] values) {
        this.type = type;
        this.ids = ids;
        this.values = values;
    }

    /** Dice p in [0,1) i.i.d. and build the transaction (Task 1.1 a-e). */
    public static TxSpec generate(Random rand) {
        double p = rand.nextDouble();
        TransactionType type = TransactionType.dice(p);
        int n = BenchmarkConfig.QUERIES_PER_TASK;
        switch (type) {
            case POINT_READ:
                return new TxSpec(type, distinctIds(rand, n), new double[0]);
            case INSERT:
                return new TxSpec(type, new int[0], new double[] { rand.nextDouble() * BenchmarkConfig.MAX_VALUE });
            case FULL_SCAN:
                return new TxSpec(type, new int[0], new double[0]);
            case WRITE_UPDATE:
            default: {
                // Write order is the diced order. It is NOT sorted (no canonical locking, see hints).
                int[] ids = distinctIds(rand, n);
                double[] vals = new double[n];
                for (int i = 0; i < n; i++) {
                    vals[i] = rand.nextDouble() * BenchmarkConfig.MAX_VALUE;
                }
                return new TxSpec(TransactionType.WRITE_UPDATE, ids, vals);
            }
        }
    }

    private static int[] distinctIds(Random rand, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int id;
            boolean dup;
            do {
                id = rand.nextInt(BenchmarkConfig.NUM_TUPLES);
                dup = false;
                for (int j = 0; j < i; j++) {
                    if (result[j] == id) {
                        dup = true;
                        break;
                    }
                }
            } while (dup);
            result[i] = id;
        }
        return result;
    }
}
