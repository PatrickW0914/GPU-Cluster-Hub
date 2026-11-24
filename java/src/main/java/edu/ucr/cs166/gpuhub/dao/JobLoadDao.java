package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.JobLoad;
import edu.ucr.cs166.gpuhub.entity.JobLoadRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** SQL for job_loads_version (job M:N model version). */
public final class JobLoadDao extends Dao {

    private static final String SELECT_ROW = """
            SELECT l.job_id, l.model_id, l.version_tag, l.usage_role,
                   j.job_name, u.net_id, e.env_name, m.model_name
              FROM job_loads_version l
              JOIN job j          ON j.job_id = l.job_id
              JOIN reservation r  ON r.reservation_id = j.reservation_id
              JOIN cluster_user u ON u.user_id = r.user_id
              JOIN software_env e ON e.env_id = j.env_id
              JOIN ai_model m     ON m.model_id = l.model_id""";

    public JobLoadDao(Connection c) {
        super(c);
    }

    /** The key is chosen by the caller. A version not verified for the job's environment fails with GH006. */
    public void insert(JobLoad l) throws SQLException {
        execute("INSERT INTO job_loads_version (job_id, model_id, version_tag, usage_role) VALUES (?, ?, ?, ?)",
                l.jobId(), l.modelId(), l.versionTag(), l.usageRole());
    }

    public Optional<JobLoadRow> findById(long jobId, int modelId, String versionTag) throws SQLException {
        return queryOne(SELECT_ROW + " WHERE l.job_id = ? AND l.model_id = ? AND l.version_tag = ?",
                JobLoadDao::readRow, jobId, modelId, versionTag);
    }

    /** Every filter may be null. userText is the NetID or name of the job's user. */
    public List<JobLoadRow> search(Integer jobId, String userText, VersionKey version, String usageRole)
            throws SQLException {
        Filter f = new Filter()
                .and("l.job_id = ?", jobId)
                .andContains("(u.net_id ILIKE ? OR u.full_name ILIKE ?)", userText)
                .andPair("(l.model_id = ? AND l.version_tag = ?)",
                        version == null ? null : version.modelId(),
                        version == null ? null : version.versionTag())
                .and("l.usage_role = ?", usageRole);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY l.job_id DESC, m.model_name, l.version_tag",
                JobLoadDao::readRow, f.params());
    }

    /** Changes the usage role. Returns false if the load no longer exists. */
    public boolean update(JobLoad l) throws SQLException {
        return execute("""
                UPDATE job_loads_version SET usage_role = ?
                 WHERE job_id = ? AND model_id = ? AND version_tag = ?""",
                l.usageRole(), l.jobId(), l.modelId(), l.versionTag()) == 1;
    }

    /** Returns false if the load no longer exists. */
    public boolean delete(JobLoad l) throws SQLException {
        return execute("DELETE FROM job_loads_version WHERE job_id = ? AND model_id = ? AND version_tag = ?",
                l.jobId(), l.modelId(), l.versionTag()) == 1;
    }

    private static JobLoadRow readRow(ResultSet rs) throws SQLException {
        return new JobLoadRow(
                new JobLoad(rs.getLong("job_id"), rs.getInt("model_id"), rs.getString("version_tag"),
                        rs.getString("usage_role")),
                rs.getString("job_name"), rs.getString("net_id"), rs.getString("env_name"),
                rs.getString("model_name"));
    }
}
