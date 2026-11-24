-- =====================================================================
--  GPU Cluster Hub -- constraint and business-rule test suite
--
--  Run AFTER sql/01_schema.sql and data/load_data.sql, from the project root:
--      psql -d gpuhub -f tests/constraint_tests.sql
--
--  Everything runs inside one transaction that is ROLLED BACK at the end,
--  and every test case is isolated in its own sub-transaction, so the
--  database is unchanged afterwards.
--
--  expect_error(id, description, expected SQLSTATE, sql) passes when the
--  statement fails with exactly that SQLSTATE.  Deferred constraints are
--  forced with SET CONSTRAINTS ALL IMMEDIATE, i.e. checked as at COMMIT.
--  expect_ok(id, description, sql) passes when the statement succeeds.
-- =====================================================================
\set ON_ERROR_STOP on
\pset footer off
SET search_path TO gpuhub;
SET client_min_messages = warning;

BEGIN;

CREATE TEMP TABLE test_result (
    seq       SERIAL,
    test_id   TEXT,
    category  TEXT,
    descr     TEXT,
    expected  TEXT,
    got       TEXT,
    outcome   TEXT,
    message   TEXT
);

CREATE FUNCTION pg_temp.expect_error(p_id TEXT, p_cat TEXT, p_descr TEXT,
                                     p_expected TEXT, p_sql TEXT)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
        EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
        RAISE EXCEPTION 'statement succeeded' USING ERRCODE = 'GH999';
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO test_result (test_id, category, descr, expected, got, outcome, message)
        VALUES (p_id, p_cat, p_descr, p_expected, SQLSTATE,
                CASE WHEN SQLSTATE = p_expected THEN 'PASS' ELSE 'FAIL' END,
                left(SQLERRM, 110));
    END;
    EXECUTE 'SET CONSTRAINTS ALL DEFERRED';
END $$;

CREATE FUNCTION pg_temp.expect_ok(p_id TEXT, p_cat TEXT, p_descr TEXT, p_sql TEXT)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
        EXECUTE 'SET CONSTRAINTS ALL IMMEDIATE';
        RAISE EXCEPTION 'ok - undo' USING ERRCODE = 'GH999';   -- roll the test back
    EXCEPTION WHEN OTHERS THEN
        INSERT INTO test_result (test_id, category, descr, expected, got, outcome, message)
        VALUES (p_id, p_cat, p_descr, 'success', CASE WHEN SQLSTATE = 'GH999' THEN 'success' ELSE SQLSTATE END,
                CASE WHEN SQLSTATE = 'GH999' THEN 'PASS' ELSE 'FAIL' END,
                CASE WHEN SQLSTATE = 'GH999' THEN 'committed cleanly (then rolled back by the test)'
                     ELSE left(SQLERRM, 110) END);
    END;
    EXECUTE 'SET CONSTRAINTS ALL DEFERRED';
END $$;

-- ---------------------------------------------------------------------
-- Fixtures (rolled back at the end).  Far-future dates avoid sample data.
-- ---------------------------------------------------------------------
INSERT INTO cluster_user (user_id, net_id, full_name, email, user_role) VALUES
    (9001, 'tfaculty', 'Dr. Test Faculty', 'tfaculty@gpuhub.example.edu', 'Faculty'),
    (9002, 'tstudent', 'Test Student',     'tstudent@gpuhub.example.edu', 'PhD'),
    (9003, 'toutside', 'Test Outsider',    'toutside@gpuhub.example.edu', 'MS');
INSERT INTO research_group (group_id, group_name, department, pi_user_id, weekly_gpu_hour_quota) VALUES
    (9001, 'Test Lab',       'Testing', 9001, 10000),
    (9002, 'Tiny Quota Lab', 'Testing', 9001, 10);
INSERT INTO membership (user_id, group_id, member_role) VALUES
    (9001, 9001, 'Manager'), (9002, 9001, 'Member'),
    (9001, 9002, 'Manager'), (9002, 9002, 'Member');

-- two bookable GPUs on the same UP node, and one GPU of each unavailable kind
CREATE TEMP TABLE fx AS
SELECT (SELECT MIN(gpu_id) FROM v_gpu_inventory
         WHERE gpu_status = 'AVAILABLE' AND node_status = 'UP' AND hostname = 'gpu-a100-02')        AS g1,
       (SELECT MIN(gpu_id) + 1 FROM v_gpu_inventory
         WHERE gpu_status = 'AVAILABLE' AND node_status = 'UP' AND hostname = 'gpu-a100-02')        AS g2,
       (SELECT MIN(gpu_id) FROM gpu WHERE gpu_status = 'MAINTENANCE')                               AS g_maint,
       (SELECT MIN(gpu_id) FROM v_gpu_inventory WHERE node_status = 'DRAIN')                        AS g_drain;

-- an existing confirmed booking on g1: 2027-03-01 10:00 to 14:00
INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
VALUES (9001, 9002, 9001, '2027-03-01 10:00', '2027-03-01 14:00', 'fixture booking', 'CONFIRMED');
INSERT INTO reservation_gpu VALUES (9001, (SELECT g1 FROM fx));
-- a cancelled booking on g2 in the same window
INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
VALUES (9002, 9002, 9001, '2027-03-01 10:00', '2027-03-01 14:00', 'fixture cancelled', 'CANCELLED');
INSERT INTO reservation_gpu VALUES (9002, (SELECT g2 FROM fx));
-- a job (in the fixture reservation) that loads a model version through a verified env
INSERT INTO job (job_id, reservation_id, env_id, job_name, submit_time, start_time, job_state)
SELECT 900001, 9001, es.env_id, 'fixture-job', '2027-03-01 09:30', '2027-03-01 10:05', 'RUNNING'
  FROM env_supports_version es JOIN ai_model m USING (model_id)
 WHERE m.model_name = 'Llama-3.1-8B-Instruct' AND es.version_tag = 'bf16'
 ORDER BY es.env_id LIMIT 1;
INSERT INTO job_loads_version
SELECT 900001, model_id, 'bf16', 'inference' FROM ai_model WHERE model_name = 'Llama-3.1-8B-Instruct';
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

-- =====================================================================
-- A. Keys, NOT NULL, UNIQUE, CHECK
-- =====================================================================
SELECT pg_temp.expect_error('A01','PRIMARY KEY','duplicate gpu_type_id', '23505',
  $$INSERT INTO gpu_type VALUES (1,'X-GPU','NVIDIA','Test',16,8.0,10,true)$$);
SELECT pg_temp.expect_error('A02','UNIQUE','duplicate net_id', '23505',
  $$INSERT INTO cluster_user (net_id, full_name, email, user_role)
    VALUES ('tstudent','Dup','dup@gpuhub.example.edu','PhD')$$);
SELECT pg_temp.expect_error('A03','UNIQUE','two GPUs in the same node slot', '23505',
  $$INSERT INTO gpu (node_id, slot_index, gpu_type_id, serial_number, installed_on)
    SELECT node_id, slot_index, gpu_type_id, 'NEWSERIAL1', CURRENT_DATE FROM gpu WHERE gpu_id = 1$$);
SELECT pg_temp.expect_error('A04','UNIQUE','duplicate model_name', '23505',
  $$INSERT INTO ai_model (model_name, task_category, framework, license, maintainer_id)
    VALUES ('YOLOv8x','Detection','PyTorch','MIT',9002)$$);
SELECT pg_temp.expect_error('A05','NOT NULL','user without e-mail', '23502',
  $$INSERT INTO cluster_user (net_id, full_name, user_role) VALUES ('noemail','No Email','MS')$$);
SELECT pg_temp.expect_error('A06','CHECK','malformed e-mail', '23514',
  $$INSERT INTO cluster_user (net_id, full_name, email, user_role)
    VALUES ('bademail','Bad','not-an-email','MS')$$);
SELECT pg_temp.expect_error('A07','CHECK','unknown user_role', '23514',
  $$INSERT INTO cluster_user (net_id, full_name, email, user_role)
    VALUES ('badrole','Bad','badrole@gpuhub.example.edu','Professor')$$);
SELECT pg_temp.expect_error('A08','CHECK','reservation ends before it starts', '23514',
  $$INSERT INTO reservation (user_id, group_id, start_time, end_time, purpose)
    VALUES (9002, 9001, '2027-03-02 10:00', '2027-03-02 09:00', 'bad window')$$);
SELECT pg_temp.expect_error('A09','CHECK','reservation longer than 72 h', '23514',
  $$INSERT INTO reservation (user_id, group_id, start_time, end_time, purpose)
    VALUES (9002, 9001, '2027-03-02 10:00', '2027-03-06 10:00', 'too long')$$);
SELECT pg_temp.expect_error('A10','CHECK','COMPLETED job without end_time', '23514',
  $$UPDATE job SET job_state = 'COMPLETED', exit_code = 0 WHERE job_id = 900001$$);
SELECT pg_temp.expect_error('A11','CHECK','FAILED job with exit code 0', '23514',
  $$UPDATE job SET job_state = 'FAILED', end_time = '2027-03-01 11:00', exit_code = 0 WHERE job_id = 900001$$);
SELECT pg_temp.expect_error('A12','CHECK','GPU utilization above 100 %', '23514',
  $$UPDATE job SET avg_gpu_util_pct = 140 WHERE job_id = 900001$$);
SELECT pg_temp.expect_error('A13','CHECK','negative weekly quota', '23514',
  $$UPDATE research_group SET weekly_gpu_hour_quota = -5 WHERE group_id = 9001$$);

-- =====================================================================
-- B. Foreign keys (references, RESTRICT, CASCADE, deferred)
-- =====================================================================
SELECT pg_temp.expect_error('B01','FOREIGN KEY','GPU in a node that does not exist', '23503',
  $$INSERT INTO gpu (node_id, slot_index, gpu_type_id, serial_number, installed_on)
    VALUES (99999, 0, 1, 'GHOST1', CURRENT_DATE)$$);
SELECT pg_temp.expect_error('B02','FOREIGN KEY','job loads a model version that does not exist', '23503',
  $$INSERT INTO job_loads_version VALUES (900001, 1, 'no-such-tag', 'inference')$$);
SELECT pg_temp.expect_error('B03','FK (aggregation)','user books GPU time for a group they are not in', '23503',
  $$INSERT INTO reservation (user_id, group_id, start_time, end_time, purpose)
    VALUES (9003, 9001, '2027-03-02 10:00', '2027-03-02 12:00', 'outsider')$$);
SELECT pg_temp.expect_error('B04','FK RESTRICT','delete a node that still has GPUs', '23503',
  $$DELETE FROM compute_node WHERE hostname = 'gpu-a100-02'$$);
SELECT pg_temp.expect_error('B05','FK RESTRICT','delete a model whose versions were used by jobs', '23503',
  $$DELETE FROM ai_model WHERE model_name = 'Llama-3.1-8B-Instruct'$$);
SELECT pg_temp.expect_error('B06','FK RESTRICT','delete a user who has reservations', '23503',
  $$DELETE FROM cluster_user WHERE user_id = 9002$$);
SELECT pg_temp.expect_ok('B07','FK CASCADE','delete an unused model: versions, staged copies, compat rows cascade',
  $$INSERT INTO ai_model (model_id, model_name, task_category, framework, license, maintainer_id)
        VALUES (9001, 'Tmp-Model', 'Other', 'PyTorch', 'MIT', 9002);
    INSERT INTO model_version VALUES (9001, 'v1', 0.1, 'fp32', 2, 0.4, NULL, false);
    INSERT INTO env_supports_version (env_id, model_id, version_tag) VALUES (1, 9001, 'v1');
    INSERT INTO node_hosts_version (node_id, model_id, version_tag, local_path) VALUES (1, 9001, 'v1', '/scratch/models/tmp/v1');
    DELETE FROM ai_model WHERE model_id = 9001;
    DO $d$ BEGIN
      IF EXISTS (SELECT 1 FROM model_version WHERE model_id = 9001)
         OR EXISTS (SELECT 1 FROM env_supports_version WHERE model_id = 9001)
         OR EXISTS (SELECT 1 FROM node_hosts_version WHERE model_id = 9001)
      THEN RAISE EXCEPTION 'cascade did not remove dependents'; END IF; END $d$;$$);
SELECT pg_temp.expect_ok('B08','ON UPDATE CASCADE','rename a version_tag; staged copies and job loads follow',
  $$UPDATE model_version SET version_tag = 'bf16-r2'
     WHERE version_tag = 'bf16' AND model_id = (SELECT model_id FROM ai_model WHERE model_name = 'Llama-3.1-8B-Instruct');
    DO $d$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM job_loads_version WHERE job_id = 900001 AND version_tag = 'bf16-r2')
      THEN RAISE EXCEPTION 'update did not cascade'; END IF; END $d$;$$);
SELECT pg_temp.expect_error('B09','DEFERRED FK','new group whose PI is not a member (checked at COMMIT)', '23503',
  $$INSERT INTO research_group (group_name, department, pi_user_id, weekly_gpu_hour_quota)
    VALUES ('Orphan Lab', 'Testing', 9001, 100)$$);
SELECT pg_temp.expect_ok('B10','DEFERRED FK','new group + PI membership in one transaction',
  $$INSERT INTO research_group (group_id, group_name, department, pi_user_id, weekly_gpu_hour_quota)
        VALUES (9003, 'New Lab', 'Testing', 9001, 100);
    INSERT INTO membership (user_id, group_id, member_role) VALUES (9001, 9003, 'Manager')$$);
SELECT pg_temp.expect_error('B11','DEFERRED FK','remove the PI''s membership while the PI still leads the group', '23503',
  $$DELETE FROM membership WHERE user_id = 9001 AND group_id = 9001$$);

-- =====================================================================
-- C. Business rules enforced by triggers
-- =====================================================================
SELECT pg_temp.expect_error('C01','GH008 PI faculty','make a PhD student the PI of a group', 'GH008',
  $$UPDATE research_group SET pi_user_id = 9002 WHERE group_id = 9001$$);
SELECT pg_temp.expect_error('C02','GH008 PI faculty','change a PI''s role to Postdoc', 'GH008',
  $$UPDATE cluster_user SET user_role = 'Postdoc' WHERE user_id = 9001$$);
SELECT pg_temp.expect_error('C03','GH001 no double booking','book a GPU that is taken 12:00-16:00 (overlaps 10:00-14:00)', 'GH001',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
        VALUES (9100, 9002, 9001, '2027-03-01 12:00', '2027-03-01 16:00', 'overlap', 'PENDING');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx$$);
SELECT pg_temp.expect_ok('C04','GH001 no double booking','back-to-back booking 14:00-18:00 on the same GPU is allowed',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
        VALUES (9100, 9002, 9001, '2027-03-01 14:00', '2027-03-01 18:00', 'adjacent', 'PENDING');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx$$);
SELECT pg_temp.expect_ok('C05','GH001 no double booking','a CANCELLED booking does not block its GPU',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
        VALUES (9100, 9002, 9001, '2027-03-01 11:00', '2027-03-01 13:00', 'reuse cancelled slot', 'PENDING');
    INSERT INTO reservation_gpu SELECT 9100, g2 FROM fx$$);
SELECT pg_temp.expect_error('C06','GH001 no double booking','re-activate the cancelled booking onto a now-taken slot', 'GH001',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
        VALUES (9100, 9002, 9001, '2027-03-01 11:00', '2027-03-01 13:00', 'takes the slot', 'PENDING');
    INSERT INTO reservation_gpu SELECT 9100, g2 FROM fx;
    UPDATE reservation SET res_status = 'CONFIRMED' WHERE reservation_id = 9002$$);
SELECT pg_temp.expect_error('C07','GH001 no double booking','move an existing booking onto a taken window', 'GH001',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose, res_status)
        VALUES (9100, 9002, 9001, '2027-03-01 15:00', '2027-03-01 17:00', 'later', 'PENDING');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx;
    UPDATE reservation SET start_time = '2027-03-01 13:00' WHERE reservation_id = 9100$$);
SELECT pg_temp.expect_error('C08','GH002 bookable GPU','book a GPU that is in MAINTENANCE', 'GH002',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
        VALUES (9100, 9002, 9001, '2027-03-03 10:00', '2027-03-03 12:00', 'maint');
    INSERT INTO reservation_gpu SELECT 9100, g_maint FROM fx$$);
SELECT pg_temp.expect_error('C09','GH002 bookable GPU','book a GPU on a DRAIN node', 'GH002',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
        VALUES (9100, 9002, 9001, '2027-03-03 10:00', '2027-03-03 12:00', 'drain');
    INSERT INTO reservation_gpu SELECT 9100, g_drain FROM fx$$);
SELECT pg_temp.expect_error('C10','GH003 weekly quota','6 h x 2 GPUs = 12 GPU-h against a 10 GPU-h quota', 'GH003',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
        VALUES (9100, 9002, 9002, '2027-03-03 10:00', '2027-03-03 16:00', 'too much');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx;
    INSERT INTO reservation_gpu SELECT 9100, g2 FROM fx$$);
SELECT pg_temp.expect_ok('C11','GH003 weekly quota','4 h x 2 GPUs = 8 GPU-h fits the 10 GPU-h quota',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
        VALUES (9100, 9002, 9002, '2027-03-03 10:00', '2027-03-03 14:00', 'fits');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx;
    INSERT INTO reservation_gpu SELECT 9100, g2 FROM fx$$);
SELECT pg_temp.expect_error('C12','GH003 weekly quota','lowering the quota does not touch history, but extending a booking re-checks it', 'GH003',
  $$INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
        VALUES (9100, 9002, 9002, '2027-03-03 10:00', '2027-03-03 14:00', 'fits');
    INSERT INTO reservation_gpu SELECT 9100, g1 FROM fx;
    UPDATE reservation SET end_time = '2027-03-03 23:00' WHERE reservation_id = 9100$$);
SELECT pg_temp.expect_error('C13','GH004 >= 1 GPU','reservation committed with no GPU', 'GH004',
  $$INSERT INTO reservation (user_id, group_id, start_time, end_time, purpose)
    VALUES (9002, 9001, '2027-03-04 10:00', '2027-03-04 12:00', 'empty')$$);
SELECT pg_temp.expect_error('C14','GH004 >= 1 GPU','remove the last GPU from a reservation', 'GH004',
  $$DELETE FROM reservation_gpu WHERE reservation_id = 9001$$);
SELECT pg_temp.expect_error('C15','GH005 job window','job starts before its reservation', 'GH005',
  $$INSERT INTO job (reservation_id, env_id, job_name, submit_time, start_time, job_state)
    VALUES (9001, 1, 'early', '2027-03-01 08:00', '2027-03-01 09:00', 'RUNNING')$$);
SELECT pg_temp.expect_error('C16','GH005 job window','job ends after its reservation', 'GH005',
  $$UPDATE job SET job_state = 'COMPLETED', end_time = '2027-03-01 15:00', exit_code = 0 WHERE job_id = 900001$$);
SELECT pg_temp.expect_error('C17','GH005 job window','job under a CANCELLED reservation', 'GH005',
  $$INSERT INTO job (reservation_id, env_id, job_name, submit_time, job_state)
    VALUES (9002, 1, 'ghost', '2027-03-01 09:00', 'QUEUED')$$);
SELECT pg_temp.expect_error('C18','GH006 env compatibility','load a JAX checkpoint in a PyTorch environment', 'GH006',
  $$INSERT INTO job_loads_version
    SELECT 900001, model_id, 'jax-bf16', 'inference' FROM ai_model WHERE model_name = 'gemma-2-9b-it'$$);
SELECT pg_temp.expect_error('C19','GH006 env compatibility','switch a job to an env that cannot run what it loads', 'GH006',
  $$UPDATE job SET env_id = (SELECT env_id FROM software_env WHERE env_name = 'legacy-py36-cu102')
     WHERE job_id = 900001$$);
SELECT pg_temp.expect_error('C20','GH007 node storage','stage 600 GB of checkpoints on the 0.48 TB debug node', 'GH007',
  $$INSERT INTO ai_model (model_id, model_name, task_category, framework, license, maintainer_id)
        VALUES (9001, 'Huge-Model', 'LLM', 'PyTorch', 'MIT', 9002);
    INSERT INTO model_version VALUES (9001, 'v1', 300, 'bf16', 600, 600, NULL, false);
    INSERT INTO node_hosts_version (node_id, model_id, version_tag, local_path)
        SELECT node_id, 9001, 'v1', '/scratch/models/huge/v1' FROM compute_node WHERE hostname = 'gpu-debug-01'$$);
SELECT pg_temp.expect_error('C21','GH007 node storage','shrink a node''s NVMe below what is staged on it', 'GH007',
  $$UPDATE compute_node SET local_nvme_tb = 0.01 WHERE hostname = 'gpu-a100-01'$$);

-- =====================================================================
-- D. Multi-statement transaction: all or nothing
-- =====================================================================
-- Booking = INSERT reservation + one INSERT per GPU.  The 2nd GPU clashes,
-- so the whole booking must disappear, including the 1st GPU row.
DO $$
DECLARE
    before_r INTEGER; before_g INTEGER; after_r INTEGER; after_g INTEGER;
    st TEXT := 'none';
BEGIN
    SELECT COUNT(*) INTO before_r FROM reservation;
    SELECT COUNT(*) INTO before_g FROM reservation_gpu;
    BEGIN
        INSERT INTO reservation (reservation_id, user_id, group_id, start_time, end_time, purpose)
            VALUES (9200, 9002, 9001, '2027-03-01 12:00', '2027-03-01 13:00', 'atomic test');
        INSERT INTO reservation_gpu SELECT 9200, g2 FROM fx;     -- free (only a cancelled booking)
        INSERT INTO reservation_gpu SELECT 9200, g1 FROM fx;     -- taken -> GH001
    EXCEPTION WHEN OTHERS THEN
        st := SQLSTATE;
    END;
    SELECT COUNT(*) INTO after_r FROM reservation;
    SELECT COUNT(*) INTO after_g FROM reservation_gpu;
    INSERT INTO test_result (test_id, category, descr, expected, got, outcome, message)
    VALUES ('D01', 'TRANSACTION', '2-GPU booking whose 2nd GPU clashes is rolled back as a whole',
            'GH001 + no rows left', st || format(' + %s/%s rows left', after_r - before_r, after_g - before_g),
            CASE WHEN st = 'GH001' AND after_r = before_r AND after_g = before_g THEN 'PASS' ELSE 'FAIL' END,
            format('reservations %s -> %s, reservation_gpu %s -> %s', before_r, after_r, before_g, after_g));
END $$;

-- =====================================================================
-- Report
-- =====================================================================
\echo
\echo '==================== TEST RESULTS ===================='
SELECT test_id AS id, category, descr AS "test", expected, got, outcome
  FROM test_result ORDER BY seq;
SELECT COUNT(*) FILTER (WHERE outcome = 'PASS') AS passed,
       COUNT(*) FILTER (WHERE outcome = 'FAIL') AS failed,
       COUNT(*) AS total
  FROM test_result;
\echo 'Error messages raised (first 110 chars):'
SELECT test_id AS id, message FROM test_result ORDER BY seq;

ROLLBACK;
