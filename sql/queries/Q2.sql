-- Q2: How much of its weekly quota has each group booked this week?
-- Concept: aggregate (SUM, COUNT) over an outer join
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q2.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

SELECT g.group_name,
       g.weekly_gpu_hour_quota                                       AS quota,
       ROUND(COALESCE(SUM(v.gpu_hours), 0), 1)                       AS booked,
       ROUND(100 * COALESCE(SUM(v.gpu_hours), 0) / g.weekly_gpu_hour_quota, 1) AS pct_used,
       COUNT(v.reservation_id)                                       AS reservations,
       COUNT(DISTINCT v.user_id)                                     AS active_users
  FROM research_group g
  LEFT JOIN v_reservation_hours v
         ON v.group_id = g.group_id AND v.res_status <> 'CANCELLED'
        AND v.start_time >= date_trunc('week', :'now'::timestamp)
        AND v.start_time <  date_trunc('week', :'now'::timestamp) + INTERVAL '7 days'
 GROUP BY g.group_id
 ORDER BY pct_used DESC;
