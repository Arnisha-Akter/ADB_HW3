-- =====================================================================
-- Homework 3 - Task 2.2: bottleneck analysis on the PostgreSQL side
-- Run in psql or pgAdmin against the benchmark database.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) Execution plans of the four transaction types
-- ---------------------------------------------------------------------
EXPLAIN (ANALYZE, BUFFERS) SELECT data_value FROM blocking_data WHERE data_id = 4711;
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(data_value) FROM blocking_data;

-- Writes inside a transaction that is rolled back (does not change the data):
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) UPDATE blocking_data SET data_value = 1.0, modified_by = 0 WHERE data_id = 4711;
EXPLAIN (ANALYZE, BUFFERS) INSERT INTO blocking_data VALUES (999999, 1.0, 0);
ROLLBACK;

-- ---------------------------------------------------------------------
-- 2) Table bloat: run before and after a benchmark (also printed by the suite)
--    Without free space on the pages, every UPDATE moves the row to a new
--    page -> table grows, dead tuples pile up, the full scan gets slower.
-- ---------------------------------------------------------------------
SELECT pg_size_pretty(pg_relation_size('blocking_data')) AS table_size,
       n_live_tup, n_dead_tup, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / nullif(n_tup_upd, 0), 1) AS hot_update_pct,
       last_autovacuum
FROM pg_stat_user_tables
WHERE relname = 'blocking_data';

SELECT reloptions FROM pg_class WHERE relname = 'blocking_data';  -- shows fillfactor

-- ---------------------------------------------------------------------
-- 3) Logging: lock waits, deadlocks, slow statements
--    (needs superuser; writes to the PostgreSQL log file, see
--     SHOW log_directory; / SHOW data_directory;)
-- ---------------------------------------------------------------------
ALTER SYSTEM SET log_lock_waits = on;              -- logs every lock wait longer than deadlock_timeout
ALTER SYSTEM SET deadlock_timeout = '200ms';
ALTER SYSTEM SET log_min_duration_statement = 5;   -- logs statements slower than 5 ms (= the full scans)
ALTER SYSTEM SET log_checkpoints = on;
SELECT pg_reload_conf();

-- ... run the benchmark, then look at the log ...

-- Reset afterwards (logging itself costs throughput):
-- ALTER SYSTEM RESET log_lock_waits;
-- ALTER SYSTEM RESET deadlock_timeout;
-- ALTER SYSTEM RESET log_min_duration_statement;
-- ALTER SYSTEM RESET log_checkpoints;
-- SELECT pg_reload_conf();

-- ---------------------------------------------------------------------
-- 4) Which statement consumes the most database time? (pg_stat_statements)
--    One-time setup: in postgresql.conf set
--        shared_preload_libraries = 'pg_stat_statements'
--    restart PostgreSQL, then:
-- ---------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT pg_stat_statements_reset();

-- ... run the benchmark ...

SELECT calls,
       round(total_exec_time::numeric, 1)                                   AS total_ms,
       round(mean_exec_time::numeric, 3)                                    AS mean_ms,
       round((100 * total_exec_time / sum(total_exec_time) OVER ())::numeric, 1) AS pct_of_db_time,
       left(query, 70)                                                      AS query
FROM pg_stat_statements
WHERE query ILIKE '%blocking_data%'
ORDER BY total_exec_time DESC
LIMIT 10;

-- ---------------------------------------------------------------------
-- 5) Live view while the benchmark runs: who waits on what?
-- ---------------------------------------------------------------------
SELECT wait_event_type, wait_event, state, count(*)
FROM pg_stat_activity
WHERE datname = current_database() AND pid <> pg_backend_pid()
GROUP BY 1, 2, 3
ORDER BY 4 DESC;

-- Blocked sessions and who blocks them:
SELECT pid, pg_blocking_pids(pid) AS blocked_by, wait_event, left(query, 60) AS query
FROM pg_stat_activity
WHERE cardinality(pg_blocking_pids(pid)) > 0;
