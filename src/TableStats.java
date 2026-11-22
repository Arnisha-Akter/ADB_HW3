import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Snapshot of the physical state of blocking_data (Task 2.2).
 *
 * Shows table bloat: every UPDATE in PostgreSQL writes a new row version. Without
 * free space on the page (fillfactor) and timely vacuuming, the table grows during
 * the run and the full table scan gets slower and slower.
 */
public class TableStats {

    public long sizeBytes;
    public long updates;
    public long hotUpdates;
    public long deadTuples;
    /** Average time of SELECT sum(data_value) FROM blocking_data, measured from the client, in ms. */
    public double scanMs;

    public static TableStats read(Connection con) {
        TableStats s = new TableStats();
        try (Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_relation_size('blocking_data')")) {
                if (rs.next()) s.sizeBytes = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT n_tup_upd, n_tup_hot_upd, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'blocking_data'")) {
                if (rs.next()) {
                    s.updates = rs.getLong(1);
                    s.hotUpdates = rs.getLong(2);
                    s.deadTuples = rs.getLong(3);
                }
            }
            int reps = 5;
            long t0 = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                try (ResultSet rs = st.executeQuery("SELECT sum(data_value) FROM blocking_data")) {
                    rs.next();
                }
            }
            s.scanMs = (System.nanoTime() - t0) / 1e6 / reps;
        } catch (SQLException e) {
            System.err.println("Warning: could not read table statistics: " + e.getMessage());
        }
        return s;
    }

    public String sizePretty() {
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
