-- Q7: How did model loads develop per task over the last 6 weeks?
-- Concept: trend report (conditional aggregates, COUNT ... FILTER)
--
-- The app runs this file as a PreparedStatement: every :'name' becomes a ? parameter.
-- In psql it runs with these defaults; override them with, for example, -v now='2026-10-19 12:00'
--     psql -d gpuhub -f sql/queries/Q7.sql
\if :{?now}
\else
  \set now '2026-11-02 09:00'
\endif
SET search_path TO gpuhub;

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
 WHERE j.submit_time >= date_trunc('week', :'now'::timestamp) - INTERVAL '5 weeks'
 GROUP BY 1 ORDER BY 1;
