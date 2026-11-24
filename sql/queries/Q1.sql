-- Q1: Which running nodes host this model version with a usable environment, and how many GPUs are needed and free?
-- Concept: multi-table join (8 tables) with grouping
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q1.sql
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
SET search_path TO gpuhub;

SELECT n.hostname,
       t.type_name,
       t.vram_gb                                                   AS vram_per_gpu,
       CEIL(mv.min_vram_gb / t.vram_gb)::int                       AS gpus_needed,
       COUNT(DISTINCT g.gpu_id) FILTER (WHERE NOT EXISTS (
            SELECT 1 FROM reservation_gpu rg JOIN reservation r USING (reservation_id)
             WHERE rg.gpu_id = g.gpu_id AND r.res_status <> 'CANCELLED'
               AND tsrange(r.start_time, r.end_time) @> :'now'::timestamp)) AS gpus_free_now,
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
 WHERE m.model_name = :'model' AND mv.version_tag = :'version' AND n.node_status = 'UP'
 GROUP BY n.hostname, t.type_name, t.vram_gb, mv.min_vram_gb, h.local_path
 ORDER BY gpus_free_now DESC, t.vram_gb DESC;
