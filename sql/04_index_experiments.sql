-- =====================================================================
--  GPU Cluster Hub -- index experiments (Phase 2)
--
--  Runs queries of the application with and without the index that should help them.
--  Each index is dropped inside a transaction that is ROLLED BACK, so the schema does
--  not change. Use the large dataset; run from the project root:
--
--      psql -X -d gpuhub_large -f sql/04_index_experiments.sql
--
--  For every variant the script prints the plan (EXPLAIN (ANALYZE, BUFFERS)) and the
--  median execution time of 5 runs after one warm-up run, so all data is in memory.
--  A summary follows at the end. Discussion: docs/indexing_results.md
--
--  The whole script takes about 4 minutes, most of it for one variant of experiment 6.
-- =====================================================================
\set ON_ERROR_STOP on
\pset pager off
\pset footer off
SET search_path TO gpuhub;
SET client_min_messages = warning;

\echo '== Dataset and index sizes'
SELECT version();
SELECT c.relname AS name, CASE c.relkind WHEN 'r' THEN 'table' ELSE 'index' END AS kind,
       c.reltuples::int AS rows, pg_size_pretty(pg_relation_size(c.oid)) AS size
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'gpuhub' AND c.relkind IN ('r', 'i')
   AND (c.relname LIKE 'idx%' OR c.relname IN ('reservation', 'reservation_gpu', 'job', 'job_loads_version'))
 ORDER BY kind DESC, pg_relation_size(c.oid) DESC;

-- ---------------------------------------------------------------------
-- Tools. They are temporary: they disappear when this session ends.
-- ---------------------------------------------------------------------
CREATE TEMP TABLE result (seq SERIAL, experiment TEXT, variant TEXT, median_ms NUMERIC, buffers BIGINT);

-- Runs the query once to warm up and then p_runs times; returns the median execution time and
-- the buffers (8 kB pages) read. With p_runs = 1 there is no warm-up (for a very slow variant).
CREATE FUNCTION pg_temp.bench(p_experiment TEXT, p_variant TEXT, p_sql TEXT, p_runs INT DEFAULT 5)
RETURNS TABLE (experiment TEXT, variant TEXT, median_ms NUMERIC, buffers BIGINT)
LANGUAGE plpgsql AS $$
DECLARE
    plan  JSON;
    times NUMERIC[] := '{}';
BEGIN
    IF p_runs > 1 THEN EXECUTE p_sql; END IF;                             -- warm-up
    FOR i IN 1..p_runs LOOP
        EXECUTE 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' || p_sql INTO plan;
        times := times || (plan -> 0 ->> 'Execution Time')::numeric;
    END LOOP;
    experiment := p_experiment;
    variant    := p_variant;
    SELECT ROUND(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 3) INTO median_ms FROM unnest(times) AS t;
    buffers := (plan -> 0 -> 'Plan' ->> 'Shared Hit Blocks')::bigint
             + (plan -> 0 -> 'Plan' ->> 'Shared Read Blocks')::bigint;
    RETURN NEXT;
END $$;

-- The result table must survive the ROLLBACKs, so each transaction hands its numbers over
-- through psql variables (\gset) and they are stored afterwards.

-- The application's queries (sql/queries), with the demo time and parameters filled in.
CREATE TEMP VIEW q2 AS
SELECT g.group_name,
       g.weekly_gpu_hour_quota                                       AS quota,
       ROUND(COALESCE(SUM(v.gpu_hours), 0), 1)                       AS booked,
       ROUND(100 * COALESCE(SUM(v.gpu_hours), 0) / g.weekly_gpu_hour_quota, 1) AS pct_used,
       COUNT(v.reservation_id)                                       AS reservations,
       COUNT(DISTINCT v.user_id)                                     AS active_users
  FROM research_group g
  LEFT JOIN v_reservation_hours v
         ON v.group_id = g.group_id AND v.res_status <> 'CANCELLED'
        AND v.start_time >= date_trunc('week', '2026-11-02 09:00'::timestamp)
        AND v.start_time <  date_trunc('week', '2026-11-02 09:00'::timestamp) + INTERVAL '7 days'
 GROUP BY g.group_id
 ORDER BY pct_used DESC;

CREATE TEMP VIEW q5 AS
WITH usage AS (
    SELECT r.group_id, r.user_id,
           SUM(EXTRACT(EPOCH FROM (COALESCE(j.end_time, '2026-11-02 09:00'::timestamp) - j.start_time)) / 3600.0
               * (SELECT COUNT(*) FROM reservation_gpu x WHERE x.reservation_id = r.reservation_id)) AS gpu_hours,
           AVG(j.avg_gpu_util_pct)                                                                    AS avg_util,
           COUNT(*) FILTER (WHERE j.job_state IN ('FAILED','TIMEOUT'))::numeric / COUNT(*)            AS fail_rate
      FROM job j JOIN reservation r ON r.reservation_id = j.reservation_id
     WHERE j.start_time >= '2026-11-02 09:00'::timestamp - INTERVAL '28 days' AND j.start_time < '2026-11-02 09:00'::timestamp
     GROUP BY r.group_id, r.user_id),
ranked AS (
    SELECT u.*, RANK() OVER (PARTITION BY group_id ORDER BY gpu_hours DESC) AS rnk FROM usage u)
SELECT g.group_name, rnk, cu.net_id,
       ROUND(gpu_hours, 1) AS gpu_hours, ROUND(avg_util, 1) AS avg_util_pct,
       ROUND(100 * fail_rate, 1) AS fail_pct
  FROM ranked k
  JOIN research_group g ON g.group_id = k.group_id
  JOIN cluster_user cu  ON cu.user_id = k.user_id
 WHERE rnk <= 3
 ORDER BY g.group_name, rnk;

CREATE TEMP VIEW q6 AS
WITH res AS (
    SELECT v.reservation_id, v.user_id, v.gpu_hours AS booked,
           COALESCE((SELECT SUM(EXTRACT(EPOCH FROM (LEAST(j.end_time, v.end_time) - j.start_time)) / 3600.0)
                       FROM job j
                      WHERE j.reservation_id = v.reservation_id AND j.start_time IS NOT NULL
                        AND j.end_time IS NOT NULL), 0) * v.gpu_count AS used
      FROM v_reservation_hours v
     WHERE v.res_status = 'COMPLETED'
       AND v.start_time >= '2026-11-02 09:00'::timestamp - INTERVAL '28 days')
SELECT u.net_id, u.full_name,
       COUNT(*)                                   AS low_use_bookings,
       ROUND(SUM(booked), 1)                      AS booked_gpu_h,
       ROUND(SUM(used), 1)                        AS used_gpu_h,
       ROUND(SUM(booked - used), 1)               AS idle_gpu_h
  FROM res JOIN cluster_user u ON u.user_id = res.user_id
 WHERE used < 0.30 * booked
 GROUP BY u.user_id
HAVING SUM(booked - used) >= 20
 ORDER BY idle_gpu_h DESC
 LIMIT 10;

CREATE TEMP VIEW q7 AS
SELECT to_char(date_trunc('week', j.submit_time), 'YYYY-MM-DD') AS week_of,
       COUNT(*) FILTER (WHERE m.task_category = 'LLM')        AS llm,
       COUNT(*) FILTER (WHERE m.task_category = 'Diffusion')  AS diffusion,
       COUNT(*) FILTER (WHERE m.task_category = 'Vision')     AS vision,
       COUNT(*) FILTER (WHERE m.task_category = 'Detection')  AS detection,
       COUNT(*) FILTER (WHERE m.task_category = 'Multimodal') AS multimodal,
       COUNT(*) FILTER (WHERE m.task_category = 'Audio')      AS audio,
       COUNT(DISTINCT j.job_id)                               AS jobs
  FROM job j
  JOIN job_loads_version jl ON jl.job_id = j.job_id
  JOIN ai_model m           ON m.model_id = jl.model_id
 WHERE j.submit_time >= date_trunc('week', '2026-11-02 09:00'::timestamp) - INTERVAL '5 weeks'
 GROUP BY 1 ORDER BY 1;

CREATE TEMP VIEW q8 AS
WITH target AS (
    SELECT mv.model_id, mv.version_tag, mv.min_vram_gb
      FROM model_version mv JOIN ai_model m USING (model_id)
     WHERE m.model_name = 'Llama-3.1-8B-Instruct' AND mv.version_tag = 'bf16'),
candidate_nodes AS (
    SELECT DISTINCT n.node_id, n.hostname, t.vram_gb,
           CEIL(tg.min_vram_gb / t.vram_gb)::int AS gpus_needed
      FROM target tg
      JOIN node_hosts_version h    ON (h.model_id, h.version_tag) = (tg.model_id, tg.version_tag)
      JOIN compute_node n          ON n.node_id = h.node_id AND n.node_status = 'UP'
      JOIN gpu g                   ON g.node_id = n.node_id
      JOIN gpu_type t              ON t.gpu_type_id = g.gpu_type_id
     WHERE EXISTS (SELECT 1 FROM env_supports_version es JOIN software_env e USING (env_id)
                    WHERE (es.model_id, es.version_tag) = (tg.model_id, tg.version_tag)
                      AND e.cuda_version <= n.max_cuda_version)),
slots AS (
    SELECT s AS slot_start, s + make_interval(hours => '6'::int) AS slot_end
      FROM generate_series(date_trunc('hour', '2026-11-02 09:00'::timestamp) + INTERVAL '1 hour',
                           '2026-11-02 09:00'::timestamp + INTERVAL '7 days', INTERVAL '1 hour') AS s),
free_count AS (
    SELECT c.hostname, c.gpus_needed, s.slot_start, s.slot_end,
           COUNT(*) AS free_gpus
      FROM candidate_nodes c
      CROSS JOIN slots s
      JOIN gpu g ON g.node_id = c.node_id AND g.gpu_status = 'AVAILABLE'
     WHERE NOT EXISTS (
            SELECT 1 FROM reservation_gpu rg JOIN reservation r USING (reservation_id)
             WHERE rg.gpu_id = g.gpu_id AND r.res_status <> 'CANCELLED'
               AND tsrange(r.start_time, r.end_time) && tsrange(s.slot_start, s.slot_end))
     GROUP BY c.hostname, c.gpus_needed, s.slot_start, s.slot_end)
SELECT DISTINCT ON (hostname) hostname, slot_start, slot_end, gpus_needed, free_gpus
  FROM free_count
 WHERE free_gpus >= gpus_needed
 ORDER BY hostname, slot_start;

CREATE TEMP VIEW slot_finder AS
WITH target AS (
    SELECT mv.model_id, mv.version_tag, mv.min_vram_gb
      FROM model_version mv JOIN ai_model m ON m.model_id = mv.model_id
     WHERE m.model_name = 'Llama-3.1-8B-Instruct' AND mv.version_tag = 'bf16'),
candidate AS (
    SELECT n.node_id, n.hostname, t.gpu_type_id, t.type_name, t.vram_gb, t.fp16_tflops, tg.min_vram_gb,
           CEIL(tg.min_vram_gb / t.vram_gb)::int AS gpus_needed,
           STRING_AGG(e.env_name, ', ' ORDER BY e.env_name) AS envs
      FROM target tg
      JOIN node_hosts_version h    ON (h.model_id, h.version_tag) = (tg.model_id, tg.version_tag)
      JOIN compute_node n          ON n.node_id = h.node_id AND n.node_status = 'UP'
      JOIN env_supports_version es ON (es.model_id, es.version_tag) = (tg.model_id, tg.version_tag)
      JOIN software_env e          ON e.env_id = es.env_id AND e.cuda_version <= n.max_cuda_version
      JOIN gpu_type t              ON EXISTS (SELECT 1 FROM gpu g
                                               WHERE g.node_id = n.node_id AND g.gpu_type_id = t.gpu_type_id)
     GROUP BY n.node_id, t.gpu_type_id, tg.min_vram_gb),
wanted AS (
    SELECT c.*
      FROM candidate c
      LEFT JOIN gpu_type lo ON lo.type_name = ''
     WHERE '' = '' OR (c.vram_gb >= lo.vram_gb AND c.fp16_tflops >= lo.fp16_tflops)),
bookable AS (
    SELECT w.node_id, w.gpu_type_id, g.gpu_id
      FROM wanted w
      JOIN gpu g ON g.node_id = w.node_id AND g.gpu_type_id = w.gpu_type_id AND g.gpu_status = 'AVAILABLE'),
start_times AS (
    SELECT w.node_id, w.gpu_type_id, date_trunc('hour', '2026-11-02 09:00'::timestamp) + INTERVAL '1 hour' AS slot_start
      FROM wanted w
    UNION
    SELECT b.node_id, b.gpu_type_id, r.end_time
      FROM bookable b
      JOIN reservation_gpu rg ON rg.gpu_id = b.gpu_id
      JOIN reservation r      ON r.reservation_id = rg.reservation_id AND r.res_status <> 'CANCELLED'
     WHERE r.end_time > date_trunc('hour', '2026-11-02 09:00'::timestamp) + INTERVAL '1 hour'
       AND r.end_time <= '2026-11-02 09:00'::timestamp + make_interval(days => '7'::int)),
slots AS (
    SELECT s.*, s.slot_start + make_interval(hours => '6'::int) AS slot_end
      FROM start_times s),
free AS (
    SELECT s.node_id, s.gpu_type_id, s.slot_start, s.slot_end,
           COUNT(*)::int                       AS free_gpus,
           ARRAY_AGG(b.gpu_id ORDER BY b.gpu_id) AS free_ids
      FROM slots s
      JOIN bookable b ON b.node_id = s.node_id AND b.gpu_type_id = s.gpu_type_id
     WHERE NOT EXISTS (
            SELECT 1 FROM reservation_gpu rg JOIN reservation r ON r.reservation_id = rg.reservation_id
             WHERE rg.gpu_id = b.gpu_id AND r.res_status <> 'CANCELLED'
               AND tsrange(r.start_time, r.end_time) && tsrange(s.slot_start, s.slot_end))
     GROUP BY s.node_id, s.gpu_type_id, s.slot_start, s.slot_end),
ranked AS (
    SELECT w.*, f.slot_start, f.slot_end, f.free_gpus,
           f.free_ids[1:w.gpus_needed] AS gpu_ids,
           ROW_NUMBER() OVER (PARTITION BY w.node_id, w.gpu_type_id ORDER BY f.slot_start)::int AS option_no
      FROM free f
      JOIN wanted w ON w.node_id = f.node_id AND w.gpu_type_id = f.gpu_type_id
     WHERE f.free_gpus >= w.gpus_needed)
SELECT node_id, hostname, option_no, slot_start, slot_end, type_name, vram_gb, min_vram_gb,
       gpus_needed, free_gpus, gpu_ids, envs
  FROM ranked
 WHERE option_no <= 3
 ORDER BY slot_start, gpus_needed, fp16_tflops DESC, hostname;


-- The quota check of one group: BookingPanel (ReservationDao.weekUsage) and trigger GH003.
CREATE TEMP VIEW one_group AS
SELECT SUM(EXTRACT(EPOCH FROM (o.end_time - o.start_time)) / 3600.0) AS used
  FROM reservation o
  JOIN reservation_gpu og ON og.reservation_id = o.reservation_id
 WHERE o.group_id = 4 AND o.res_status <> 'CANCELLED'
   AND o.start_time >= date_trunc('week', TIMESTAMP '2026-11-02 09:00')
   AND o.start_time <  date_trunc('week', TIMESTAMP '2026-11-02 09:00') + INTERVAL '7 days';

-- The overlap check of one GPU: trigger GH001. No booking overlaps this window.
CREATE TEMP VIEW gh001 AS
SELECT o.reservation_id
  FROM reservation_gpu og
  JOIN reservation o ON o.reservation_id = og.reservation_id
 WHERE og.gpu_id = 25 AND o.reservation_id <> -1 AND o.res_status <> 'CANCELLED'
   AND tsrange(o.start_time, o.end_time) && tsrange('2026-12-01 10:00', '2026-12-01 14:00')
 LIMIT 1;

-- The same check in a busy week (25 bookings overlap the window) for a GPU that is free then.
CREATE TEMP VIEW gh001_busy AS
SELECT o.reservation_id
  FROM reservation_gpu og
  JOIN reservation o ON o.reservation_id = og.reservation_id
 WHERE og.gpu_id = 1 AND o.reservation_id <> -1 AND o.res_status <> 'CANCELLED'
   AND tsrange(o.start_time, o.end_time) && tsrange('2026-11-03 10:00', '2026-11-03 14:00')
 LIMIT 1;

-- Which nodes host a model version: the search on the Nodes screen.
CREATE TEMP VIEW hosts AS
SELECT h.node_id FROM node_hosts_version h WHERE h.model_id = 1 AND h.version_tag = 'bf16';


-- ---------------------------------------------------------------------
\echo '\n== Experiment 1: Q2, weekly quota of all groups, and idx_res_group_start'
-- ---------------------------------------------------------------------
\echo '\n-- 1: Q2 with idx_res_group_start'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q2;
SELECT * FROM pg_temp.bench('1', 'Q2 with idx_res_group_start', 'SELECT * FROM q2') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 1: Q2 without it'
BEGIN;
DROP INDEX idx_res_group_start;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q2;
SELECT * FROM pg_temp.bench('1', 'Q2 without it', 'SELECT * FROM q2') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 1: Q2 what if: index on start_time'
BEGIN;
CREATE INDEX what_if_start ON reservation (start_time);
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q2;
SELECT * FROM pg_temp.bench('1', 'Q2 what if: index on start_time', 'SELECT * FROM q2') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 2: Quota of one group (GH003), and idx_res_group_start'
-- ---------------------------------------------------------------------
\echo '\n-- 2: one group with idx_res_group_start'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM one_group;
SELECT * FROM pg_temp.bench('2', 'one group with idx_res_group_start', 'SELECT * FROM one_group') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 2: one group without it'
BEGIN;
DROP INDEX idx_res_group_start;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM one_group;
SELECT * FROM pg_temp.bench('2', 'one group without it', 'SELECT * FROM one_group') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 3: GH001 overlap check, idx_res_period and idx_resgpu_gpu'
-- ---------------------------------------------------------------------
\echo '\n-- 3: GH001 with both'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001;
SELECT * FROM pg_temp.bench('3', 'GH001 with both', 'SELECT * FROM gh001') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 without idx_res_period'
BEGIN;
DROP INDEX idx_res_period;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001;
SELECT * FROM pg_temp.bench('3', 'GH001 without idx_res_period', 'SELECT * FROM gh001') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 without idx_resgpu_gpu'
BEGIN;
DROP INDEX idx_resgpu_gpu;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001;
SELECT * FROM pg_temp.bench('3', 'GH001 without idx_resgpu_gpu', 'SELECT * FROM gh001') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 without both'
BEGIN;
DROP INDEX idx_res_period;
DROP INDEX idx_resgpu_gpu;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001;
SELECT * FROM pg_temp.bench('3', 'GH001 without both', 'SELECT * FROM gh001') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 busy week, with both'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001_busy;
SELECT * FROM pg_temp.bench('3', 'GH001 busy week, with both', 'SELECT * FROM gh001_busy') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 busy week, without idx_res_period'
BEGIN;
DROP INDEX idx_res_period;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001_busy;
SELECT * FROM pg_temp.bench('3', 'GH001 busy week, without idx_res_period', 'SELECT * FROM gh001_busy') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 busy week, without idx_resgpu_gpu'
BEGIN;
DROP INDEX idx_resgpu_gpu;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001_busy;
SELECT * FROM pg_temp.bench('3', 'GH001 busy week, without idx_resgpu_gpu', 'SELECT * FROM gh001_busy') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 3: GH001 busy week, without both'
BEGIN;
DROP INDEX idx_res_period;
DROP INDEX idx_resgpu_gpu;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM gh001_busy;
SELECT * FROM pg_temp.bench('3', 'GH001 busy week, without both', 'SELECT * FROM gh001_busy') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 4: Q8 slot search, and idx_res_period'
-- ---------------------------------------------------------------------
\echo '\n-- 4: Q8 with idx_res_period'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q8;
SELECT * FROM pg_temp.bench('4', 'Q8 with idx_res_period', 'SELECT * FROM q8') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 4: Q8 without it'
BEGIN;
DROP INDEX idx_res_period;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q8;
SELECT * FROM pg_temp.bench('4', 'Q8 without it', 'SELECT * FROM q8') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 4: Q8 forced to look up by GPU (idx_resgpu_gpu)'
BEGIN;
SET LOCAL enable_hashjoin = off;
SET LOCAL enable_mergejoin = off;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q8;
SELECT * FROM pg_temp.bench('4', 'Q8 forced to look up by GPU (idx_resgpu_gpu)', 'SELECT * FROM q8') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 4: Q8 forced to look up by period (idx_res_period)'
BEGIN;
SET LOCAL enable_hashjoin = off;
SET LOCAL enable_mergejoin = off;
DROP INDEX idx_resgpu_gpu;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q8;
SELECT * FROM pg_temp.bench('4', 'Q8 forced to look up by period (idx_res_period)', 'SELECT * FROM q8') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 5: Slot finder (the app's version of Q8), and idx_res_period'
-- ---------------------------------------------------------------------
\echo '\n-- 5: slot finder with idx_res_period'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM slot_finder;
SELECT * FROM pg_temp.bench('5', 'slot finder with idx_res_period', 'SELECT * FROM slot_finder') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 5: slot finder without it'
BEGIN;
DROP INDEX idx_res_period;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM slot_finder;
SELECT * FROM pg_temp.bench('5', 'slot finder without it', 'SELECT * FROM slot_finder') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 6: Job reports'
-- ---------------------------------------------------------------------
\echo '\n-- 6: Q7 with idx_job_submit'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q7;
SELECT * FROM pg_temp.bench('6', 'Q7 with idx_job_submit', 'SELECT * FROM q7') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q7 without it'
BEGIN;
DROP INDEX idx_job_submit;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q7;
SELECT * FROM pg_temp.bench('6', 'Q7 without it', 'SELECT * FROM q7') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q7 with it, random_page_cost = 1.1 (SSD)'
BEGIN;
SET LOCAL random_page_cost = 1.1;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q7;
SELECT * FROM pg_temp.bench('6', 'Q7 with it, random_page_cost = 1.1 (SSD)', 'SELECT * FROM q7') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q7 without it, random_page_cost = 1.1 (SSD)'
BEGIN;
SET LOCAL random_page_cost = 1.1;
DROP INDEX idx_job_submit;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q7;
SELECT * FROM pg_temp.bench('6', 'Q7 without it, random_page_cost = 1.1 (SSD)', 'SELECT * FROM q7') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q5 (no index on start_time)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q5;
SELECT * FROM pg_temp.bench('6', 'Q5 (no index on start_time)', 'SELECT * FROM q5') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q5 what if: index on job.start_time'
BEGIN;
CREATE INDEX what_if_job_start ON job (start_time);
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q5;
SELECT * FROM pg_temp.bench('6', 'Q5 what if: index on job.start_time', 'SELECT * FROM q5') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q6 with idx_job_reservation'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q6;
SELECT * FROM pg_temp.bench('6', 'Q6 with idx_job_reservation', 'SELECT * FROM q6') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 6: Q6 without it'
BEGIN;
DROP INDEX idx_job_reservation;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM q6;
SELECT * FROM pg_temp.bench('6', 'Q6 without it', 'SELECT * FROM q6', 1) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 7: A small table: node_hosts_version (279 rows), and idx_hosts_version'
-- ---------------------------------------------------------------------
\echo '\n-- 7: hosts with idx_hosts_version'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM hosts;
SELECT * FROM pg_temp.bench('7', 'hosts with idx_hosts_version', 'SELECT * FROM hosts') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);
\echo '\n-- 7: hosts without it'
BEGIN;
DROP INDEX idx_hosts_version;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM hosts;
SELECT * FROM pg_temp.bench('7', 'hosts without it', 'SELECT * FROM hosts') \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms, buffers) VALUES (:'experiment', :'variant', :median_ms, :buffers);

-- ---------------------------------------------------------------------
\echo '\n== Experiment 8: write cost -- inserting 20000 reservations'
-- Uses a copy of reservation with the same rows and indexes, without foreign keys and triggers, so
-- that only the table and its indexes are measured. The copy disappears with the ROLLBACK. (Inserting
-- into the real table and rolling back would leave dead rows in it.)
-- ---------------------------------------------------------------------
CREATE TEMP TABLE new_rows AS
SELECT reservation_id + 1000000 AS reservation_id, user_id, group_id,
       start_time + INTERVAL '3650 days' AS start_time, end_time + INTERVAL '3650 days' AS end_time,
       purpose, res_status, created_at
  FROM reservation ORDER BY random() LIMIT 20000;
ANALYZE new_rows;

CREATE FUNCTION pg_temp.bench_insert(p_variant TEXT, p_drop TEXT[])
RETURNS TABLE (experiment TEXT, variant TEXT, median_ms NUMERIC)
LANGUAGE plpgsql AS $$
DECLARE
    plan  JSON;
    times NUMERIC[] := '{}';
    idx   TEXT;
BEGIN
    CREATE TABLE gpuhub.reservation_copy (LIKE gpuhub.reservation INCLUDING ALL);
    INSERT INTO gpuhub.reservation_copy SELECT * FROM gpuhub.reservation;
    -- LIKE names the copied indexes after their columns, so find them by their definition
    FOR idx IN SELECT i.indexname FROM pg_indexes i, unnest(p_drop) AS d
                WHERE i.schemaname = 'gpuhub' AND i.tablename = 'reservation_copy'
                  AND i.indexdef LIKE '%' || d || '%' LOOP
        EXECUTE format('DROP INDEX gpuhub.%I', idx);
    END LOOP;
    FOR i IN 1..6 LOOP                                                     -- the first is the warm-up
        EXECUTE 'EXPLAIN (ANALYZE, FORMAT JSON) INSERT INTO gpuhub.reservation_copy
                 SELECT reservation_id + ' || i * 1000000 || ', user_id, group_id, start_time, end_time,
                        purpose, res_status, created_at FROM new_rows' INTO plan;
        IF i > 1 THEN times := times || (plan -> 0 ->> 'Execution Time')::numeric; END IF;
    END LOOP;
    experiment := '8';
    variant    := p_variant || ' (' || (SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'gpuhub'
                                          AND tablename = 'reservation_copy') || ' indexes)';
    SELECT ROUND(percentile_cont(0.5) WITHIN GROUP (ORDER BY t)::numeric, 1) INTO median_ms FROM unnest(times) AS t;
    RETURN NEXT;
END $$;

BEGIN;
SELECT * FROM pg_temp.bench_insert('insert with every index', ARRAY[]::TEXT[]) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms) VALUES (:'experiment', :'variant', :median_ms);
BEGIN;
SELECT * FROM pg_temp.bench_insert('insert without idx_res_period (GiST)', ARRAY['tsrange']::TEXT[]) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms) VALUES (:'experiment', :'variant', :median_ms);
BEGIN;
SELECT * FROM pg_temp.bench_insert('insert without idx_res_group_start', ARRAY['(group_id, start_time)']::TEXT[]) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms) VALUES (:'experiment', :'variant', :median_ms);
BEGIN;
SELECT * FROM pg_temp.bench_insert('insert without idx_res_user_group', ARRAY['(user_id, group_id)']::TEXT[]) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms) VALUES (:'experiment', :'variant', :median_ms);
BEGIN;
SELECT * FROM pg_temp.bench_insert('insert with the primary key only', ARRAY['tsrange', '(group_id, start_time)', '(user_id, group_id)']::TEXT[]) \gset
ROLLBACK;
INSERT INTO result (experiment, variant, median_ms) VALUES (:'experiment', :'variant', :median_ms);

-- ---------------------------------------------------------------------
\echo '\n== Summary: median execution time of 5 runs (Q6 without its index: 1 run)'
-- ---------------------------------------------------------------------
SELECT experiment AS "no", variant, median_ms, buffers FROM result ORDER BY seq;

\echo '== The schema is unchanged: 13 indexes named idx_..., no what_if index'
SELECT COUNT(*) FILTER (WHERE indexname LIKE 'idx%') AS idx, COUNT(*) FILTER (WHERE indexname LIKE 'what_if%') AS what_if
  FROM pg_indexes WHERE schemaname = 'gpuhub';
