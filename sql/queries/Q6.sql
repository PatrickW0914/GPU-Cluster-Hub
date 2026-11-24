-- Q6: Who wastes the most booked GPU time (bookings under 30% used, at least 20 idle GPU-hours, top 10, last 28 days)?
-- Concept: report (CTE, GROUP BY, HAVING)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q6.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

WITH res AS (
    SELECT v.reservation_id, v.user_id, v.gpu_hours AS booked,
           COALESCE((SELECT SUM(EXTRACT(EPOCH FROM (LEAST(j.end_time, v.end_time) - j.start_time)) / 3600.0)
                       FROM job j
                      WHERE j.reservation_id = v.reservation_id AND j.start_time IS NOT NULL
                        AND j.end_time IS NOT NULL), 0) * v.gpu_count AS used
      FROM v_reservation_hours v
     WHERE v.res_status = 'COMPLETED'
       AND v.start_time >= :'now'::timestamp - INTERVAL '28 days')
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
