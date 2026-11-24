-- Q8: What is the earliest slot in the next 7 days to run this model version for this long?
-- Concept: time-slot search (CTEs, generate_series, range overlap)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q8.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
\if :{?model}
\else
  \set model 'Llama-3.1-8B-Instruct'
\endif
\if :{?version}
\else
  \set version 'bf16'
\endif
\if :{?hours}
\else
  \set hours 6
\endif
SET search_path TO gpuhub;

WITH target AS (
    SELECT mv.model_id, mv.version_tag, mv.min_vram_gb
      FROM model_version mv JOIN ai_model m USING (model_id)
     WHERE m.model_name = :'model' AND mv.version_tag = :'version'),
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
    SELECT s AS slot_start, s + make_interval(hours => :'hours'::int) AS slot_end
      FROM generate_series(date_trunc('hour', :'now'::timestamp) + INTERVAL '1 hour',
                           :'now'::timestamp + INTERVAL '7 days', INTERVAL '1 hour') AS s),
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
