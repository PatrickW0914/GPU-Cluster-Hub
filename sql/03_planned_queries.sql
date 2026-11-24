-- =====================================================================
--  GPU Cluster Hub -- planned queries and reports (Phase 1 preview)
--  These are the queries the Phase 2 Java app will run through JDBC
--  PreparedStatements; here the parameters are psql variables.
--
--  Run from the project root:  psql -d gpuhub -f sql/03_planned_queries.sql
-- =====================================================================
SET search_path TO gpuhub;
\pset footer off

-- Parameters (the sample data is a snapshot taken at 2026-11-02 09:00)
\set now        '''2026-11-02 09:00'''
\set model      '''Llama-3.1-8B-Instruct'''
\set version    '''bf16'''
\set hours      6

-- ---------------------------------------------------------------------
-- Q1  [multi-relation join]  "Where can I run model X right now?"
--     Nodes that already host the checkpoint, are UP, have an environment
--     verified for it whose CUDA the node driver supports, and the number
--     of GPUs free at :now versus the number the model needs.
-- ---------------------------------------------------------------------
\echo '\n== Q1  Where can I run Llama-3.1-8B-Instruct (bf16) right now? =='
SELECT n.hostname,
       t.type_name,
       t.vram_gb                                                   AS vram_per_gpu,
       CEIL(mv.min_vram_gb / t.vram_gb)::int                       AS gpus_needed,
       COUNT(DISTINCT g.gpu_id) FILTER (WHERE NOT EXISTS (
            SELECT 1 FROM reservation_gpu rg JOIN reservation r USING (reservation_id)
             WHERE rg.gpu_id = g.gpu_id AND r.res_status <> 'CANCELLED'
               AND tsrange(r.start_time, r.end_time) @> :now::timestamp)) AS gpus_free_now,
       STRING_AGG(DISTINCT e.env_name, ', ')                       AS usable_envs,
       h.local_path
  FROM ai_model m
  JOIN model_version mv        ON mv.model_id = m.model_id
  JOIN node_hosts_version h    ON (h.model_id, h.version_tag) = (mv.model_id, mv.version_tag)
  JOIN compute_node n          ON n.node_id = h.node_id
  JOIN gpu g                   ON g.node_id = n.node_id AND g.gpu_status = 'AVAILABLE'
  JOIN gpu_type t              ON t.gpu_type_id = g.gpu_type_id
  JOIN env_supports_version es ON (es.model_id, es.version_tag) = (mv.model_id, mv.version_tag)
  JOIN software_env e          ON e.env_id = es.env_id AND e.cuda_version <= n.max_cuda_version
 WHERE m.model_name = :model AND mv.version_tag = :version AND n.node_status = 'UP'
 GROUP BY n.hostname, t.type_name, t.vram_gb, mv.min_vram_gb, h.local_path
 ORDER BY gpus_free_now DESC, t.vram_gb DESC;

-- ---------------------------------------------------------------------
-- Q2  [aggregate]  This week's GPU-hours per group against its quota
-- ---------------------------------------------------------------------
\echo '\n== Q2  GPU-hours booked this week vs. weekly quota =='
SELECT g.group_name,
       g.weekly_gpu_hour_quota                                       AS quota,
       ROUND(COALESCE(SUM(v.gpu_hours), 0), 1)                       AS booked,
       ROUND(100 * COALESCE(SUM(v.gpu_hours), 0) / g.weekly_gpu_hour_quota, 1) AS pct_used,
       COUNT(v.reservation_id)                                       AS reservations,
       COUNT(DISTINCT v.user_id)                                     AS active_users
  FROM research_group g
  LEFT JOIN v_reservation_hours v
         ON v.group_id = g.group_id AND v.res_status <> 'CANCELLED'
        AND v.start_time >= date_trunc('week', :now::timestamp)
        AND v.start_time <  date_trunc('week', :now::timestamp) + INTERVAL '7 days'
 GROUP BY g.group_id
 ORDER BY pct_used DESC;

-- ---------------------------------------------------------------------
-- Q3  [correlated subquery / NOT EXISTS]  Reclaimable storage: staged
--     checkpoints that no job has loaded in the last 30 days
-- ---------------------------------------------------------------------
\echo '\n== Q3  Staged checkpoints unused for 30 days (reclaimable NVMe) =='
SELECT n.hostname,
       COUNT(*)                                  AS idle_checkpoints,
       SUM(mv.checkpoint_size_gb)                AS reclaimable_gb,
       STRING_AGG(m.model_name || ':' || mv.version_tag, ', ' ORDER BY mv.checkpoint_size_gb DESC) AS checkpoints
  FROM node_hosts_version h
  JOIN compute_node n   ON n.node_id = h.node_id
  JOIN model_version mv ON (mv.model_id, mv.version_tag) = (h.model_id, h.version_tag)
  JOIN ai_model m       ON m.model_id = mv.model_id
 WHERE NOT EXISTS (
        SELECT 1
          FROM job_loads_version jl
          JOIN job j          ON j.job_id = jl.job_id
          JOIN reservation r  ON r.reservation_id = j.reservation_id
          JOIN reservation_gpu rg ON rg.reservation_id = r.reservation_id
          JOIN gpu gg         ON gg.gpu_id = rg.gpu_id
         WHERE (jl.model_id, jl.version_tag) = (h.model_id, h.version_tag)
           AND gg.node_id = h.node_id
           AND j.submit_time >= :now::timestamp - INTERVAL '30 days')
 GROUP BY n.hostname
 ORDER BY reclaimable_gb DESC;

-- ---------------------------------------------------------------------
-- Q4  [condition across ALL related records -- relational division]
--     Researchers who, in the last 28 days, ran a job on EVERY GPU
--     type (H100, A100-80GB, L40S, ...) currently bookable in the cluster (useful for picking
--     people to benchmark a model across hardware)
-- ---------------------------------------------------------------------
\echo '\n== Q4  Researchers who used every bookable GPU type in the last 28 days =='
SELECT u.net_id, u.full_name, u.user_role
  FROM cluster_user u
 WHERE NOT EXISTS (
        SELECT t.type_name FROM gpu_type t
         WHERE EXISTS (SELECT 1 FROM v_gpu_inventory g
                        WHERE g.gpu_type_id = t.gpu_type_id
                          AND g.gpu_status = 'AVAILABLE' AND g.node_status = 'UP')
        EXCEPT
        SELECT t2.type_name
          FROM reservation r
          JOIN job j              ON j.reservation_id = r.reservation_id AND j.start_time IS NOT NULL
          JOIN reservation_gpu rg ON rg.reservation_id = r.reservation_id
          JOIN gpu g2             ON g2.gpu_id = rg.gpu_id
          JOIN gpu_type t2        ON t2.gpu_type_id = g2.gpu_type_id
         WHERE r.user_id = u.user_id
           AND j.start_time >= :now::timestamp - INTERVAL '28 days')
 ORDER BY u.net_id;

-- ---------------------------------------------------------------------
-- Q5  [ranking]  Top 3 GPU consumers per group over the last 4 weeks
--     (actual job GPU-hours, not booked hours), with efficiency
-- ---------------------------------------------------------------------
\echo '\n== Q5  Top 3 GPU consumers per group, last 28 days =='
WITH usage AS (
    SELECT r.group_id, r.user_id,
           SUM(EXTRACT(EPOCH FROM (COALESCE(j.end_time, :now::timestamp) - j.start_time)) / 3600.0
               * (SELECT COUNT(*) FROM reservation_gpu x WHERE x.reservation_id = r.reservation_id)) AS gpu_hours,
           AVG(j.avg_gpu_util_pct)                                                                    AS avg_util,
           COUNT(*) FILTER (WHERE j.job_state IN ('FAILED','TIMEOUT'))::numeric / COUNT(*)            AS fail_rate
      FROM job j JOIN reservation r ON r.reservation_id = j.reservation_id
     WHERE j.start_time >= :now::timestamp - INTERVAL '28 days' AND j.start_time < :now::timestamp
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

-- ---------------------------------------------------------------------
-- Q6  [actionable report]  Booked-but-idle GPU time: completed
--     reservations in the last 4 weeks where jobs used < 30 % of the
--     booked GPU time, summarized per user
-- ---------------------------------------------------------------------
\echo '\n== Q6  Users with booked-but-idle GPU time (< 30% used), last 28 days =='
WITH res AS (
    SELECT v.reservation_id, v.user_id, v.gpu_hours AS booked,
           COALESCE((SELECT SUM(EXTRACT(EPOCH FROM (LEAST(j.end_time, v.end_time) - j.start_time)) / 3600.0)
                       FROM job j
                      WHERE j.reservation_id = v.reservation_id AND j.start_time IS NOT NULL
                        AND j.end_time IS NOT NULL), 0) * v.gpu_count AS used
      FROM v_reservation_hours v
     WHERE v.res_status = 'COMPLETED'
       AND v.start_time >= :now::timestamp - INTERVAL '28 days')
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

-- ---------------------------------------------------------------------
-- Q7  [trend]  Weekly jobs and model loads by task category, last 6 weeks
-- ---------------------------------------------------------------------
\echo '\n== Q7  Weekly model loads by task category (last 6 weeks) =='
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
 WHERE j.submit_time >= date_trunc('week', :now::timestamp) - INTERVAL '5 weeks'
 GROUP BY 1 ORDER BY 1;

-- ---------------------------------------------------------------------
-- Q8  [advanced feature preview]  Earliest slot in the next 7 days for
--     :hours hours on GPUs that can run model X: same node, enough total
--     VRAM, checkpoint staged, compatible env, no overlapping booking.
-- ---------------------------------------------------------------------
\echo '\n== Q8  Earliest 6-hour slot (next 7 days) to run Llama-3.1-8B-Instruct bf16 =='
WITH target AS (
    SELECT mv.model_id, mv.version_tag, mv.min_vram_gb
      FROM model_version mv JOIN ai_model m USING (model_id)
     WHERE m.model_name = :model AND mv.version_tag = :version),
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
    SELECT s AS slot_start, s + make_interval(hours => :hours) AS slot_end
      FROM generate_series(date_trunc('hour', :now::timestamp) + INTERVAL '1 hour',
                           :now::timestamp + INTERVAL '7 days', INTERVAL '1 hour') AS s),
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
