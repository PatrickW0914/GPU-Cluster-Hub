-- Q4: Which researchers used every bookable GPU type in the last 28 days?
-- Concept: relational division (NOT EXISTS ... EXCEPT)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q4.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

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
           AND j.start_time >= :'now'::timestamp - INTERVAL '28 days')
 ORDER BY u.net_id;
