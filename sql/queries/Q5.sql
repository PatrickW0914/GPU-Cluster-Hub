-- Q5: Who are the top 3 GPU consumers of each group in the last 28 days?
-- Concept: ranking (window function RANK)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q5.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

WITH usage AS (
    SELECT r.group_id, r.user_id,
           SUM(EXTRACT(EPOCH FROM (COALESCE(j.end_time, :'now'::timestamp) - j.start_time)) / 3600.0
               * (SELECT COUNT(*) FROM reservation_gpu x WHERE x.reservation_id = r.reservation_id)) AS gpu_hours,
           AVG(j.avg_gpu_util_pct)                                                                    AS avg_util,
           COUNT(*) FILTER (WHERE j.job_state IN ('FAILED','TIMEOUT'))::numeric / COUNT(*)            AS fail_rate
      FROM job j JOIN reservation r ON r.reservation_id = j.reservation_id
     WHERE j.start_time >= :'now'::timestamp - INTERVAL '28 days' AND j.start_time < :'now'::timestamp
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
