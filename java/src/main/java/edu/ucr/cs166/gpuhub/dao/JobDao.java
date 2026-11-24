package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Job;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for job. */
public final class JobDao extends Dao {

    private static final String SELECT = """
            SELECT j.job_id, j.reservation_id, j.env_id, j.job_name, j.submit_time, j.start_time,
                   j.end_time, j.job_state, j.exit_code, j.peak_vram_gb, j.avg_gpu_util_pct
              FROM job j""";

    public JobDao(Connection c) {
        super(c);
    }

    /** Inserts the job and returns the generated id (a BIGINT, so long instead of int). */
    public long insert(Job j) throws SQLException {
        return queryOne("""
                INSERT INTO job (reservation_id, env_id, job_name, submit_time, start_time, end_time,
                                 job_state, exit_code, peak_vram_gb, avg_gpu_util_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING job_id""",
                rs -> rs.getLong("job_id"),
                j.reservationId(), j.envId(), j.jobName(), j.submitTime(), j.startTime(), j.endTime(),
                j.jobState(), j.exitCode(), j.peakVramGb(), j.avgGpuUtilPct()).orElseThrow();
    }

    public Optional<Job> findById(long jobId) throws SQLException {
        return queryOne(SELECT + " WHERE j.job_id = ?", JobDao::read, jobId);
    }

    /** Every filter may be null. userId finds the jobs in that user's reservations. */
    public List<Job> search(String jobName, String state, Integer reservationId, Integer userId, Integer envId)
            throws SQLException {
        Filter f = new Filter()
                .andContains("j.job_name ILIKE ?", jobName)
                .and("j.job_state = ?", state)
                .and("j.reservation_id = ?", reservationId)
                .and("EXISTS (SELECT 1 FROM reservation r"
                        + " WHERE r.reservation_id = j.reservation_id AND r.user_id = ?)", userId)
                .and("j.env_id = ?", envId);
        return queryAll(SELECT + f.where() + " ORDER BY j.submit_time DESC, j.job_id",
                JobDao::read, f.params());
    }

    /** Returns false if the job no longer exists. */
    public boolean update(Job j) throws SQLException {
        return execute("""
                UPDATE job
                   SET reservation_id = ?, env_id = ?, job_name = ?, submit_time = ?, start_time = ?,
                       end_time = ?, job_state = ?, exit_code = ?, peak_vram_gb = ?, avg_gpu_util_pct = ?
                 WHERE job_id = ?""",
                j.reservationId(), j.envId(), j.jobName(), j.submitTime(), j.startTime(),
                j.endTime(), j.jobState(), j.exitCode(), j.peakVramGb(), j.avgGpuUtilPct(),
                j.jobId()) == 1;
    }

    /** Deletes the job and (by cascade) its model loads. Returns false if it no longer exists. */
    public boolean delete(long jobId) throws SQLException {
        return execute("DELETE FROM job WHERE job_id = ?", jobId) == 1;
    }

    private static Job read(ResultSet rs) throws SQLException {
        return new Job(
                rs.getLong("job_id"), rs.getInt("reservation_id"), rs.getInt("env_id"),
                rs.getString("job_name"),
                rs.getObject("submit_time", LocalDateTime.class),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class),
                rs.getString("job_state"),
                rs.getObject("exit_code", Integer.class), // getInt would turn NULL into 0
                rs.getBigDecimal("peak_vram_gb"), rs.getBigDecimal("avg_gpu_util_pct"));
    }
}
