-- Slot finder (advanced feature): the earliest times at which a model version can run for some
-- hours on one node, and which GPUs to book. Started from Q8; see docs/advanced_feature.md.
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v hours=24
--     psql -d gpuhub -f sql/queries/slot_finder.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
\if :{?model}
\else
  \set model 'Llama-3.1-70B-Instruct'
\endif
\if :{?version}
\else
  \set version 'bf16'
\endif
\if :{?hours}
\else
  \set hours 6
\endif
\if :{?days}
\else
  \set days 7
\endif
\if :{?min_type}
\else
  \set min_type ''
\endif
SET search_path TO gpuhub;

WITH target AS (
    -- the model version to run
    SELECT mv.model_id, mv.version_tag, mv.min_vram_gb
      FROM model_version mv JOIN ai_model m ON m.model_id = mv.model_id
     WHERE m.model_name = :'model' AND mv.version_tag = :'version'),
candidate AS (
    -- UP nodes that host it, with a verified environment their driver can run;
    -- one row per GPU type in the node, because one booking should use equal GPUs
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
    -- optional minimum GPU type: at least its VRAM and its speed
    SELECT c.*
      FROM candidate c
      LEFT JOIN gpu_type lo ON lo.type_name = :'min_type'
     WHERE :'min_type' = '' OR (c.vram_gb >= lo.vram_gb AND c.fp16_tflops >= lo.fp16_tflops)),
bookable AS (
    SELECT w.node_id, w.gpu_type_id, g.gpu_id
      FROM wanted w
      JOIN gpu g ON g.node_id = w.node_id AND g.gpu_type_id = w.gpu_type_id AND g.gpu_status = 'AVAILABLE'),
start_times AS (
    -- The earliest slot begins either at the next full hour or at the moment a booking of one of
    -- these GPUs ends, so no other times have to be tried. (UNION also removes duplicates.)
    SELECT w.node_id, w.gpu_type_id, date_trunc('hour', :'now'::timestamp) + INTERVAL '1 hour' AS slot_start
      FROM wanted w
    UNION
    SELECT b.node_id, b.gpu_type_id, r.end_time
      FROM bookable b
      JOIN reservation_gpu rg ON rg.gpu_id = b.gpu_id
      JOIN reservation r      ON r.reservation_id = rg.reservation_id AND r.res_status <> 'CANCELLED'
     WHERE r.end_time > date_trunc('hour', :'now'::timestamp) + INTERVAL '1 hour'
       AND r.end_time <= :'now'::timestamp + make_interval(days => :'days'::int)),
slots AS (
    SELECT s.*, s.slot_start + make_interval(hours => :'hours'::int) AS slot_end
      FROM start_times s),
free AS (
    -- the GPUs that no booking overlaps during the whole slot
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
