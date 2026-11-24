-- Q3: Which staged checkpoints were not loaded on their node in the last 30 days?
-- Concept: correlated subquery (NOT EXISTS)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q3.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

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
           AND j.submit_time >= :'now'::timestamp - INTERVAL '30 days')
 GROUP BY n.hostname
 ORDER BY reclaimable_gb DESC;
