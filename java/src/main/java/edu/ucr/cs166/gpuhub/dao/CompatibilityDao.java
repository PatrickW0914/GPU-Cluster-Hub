package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Compatibility;
import edu.ucr.cs166.gpuhub.entity.CompatibilityRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for env_supports_version (environment M:N model version). */
public final class CompatibilityDao extends Dao {

    private static final String SELECT_ROW = """
            SELECT s.env_id, s.model_id, s.version_tag, s.verified_on, s.notes,
                   e.env_name, e.cuda_version, m.model_name
              FROM env_supports_version s
              JOIN software_env e ON e.env_id = s.env_id
              JOIN ai_model m     ON m.model_id = s.model_id""";

    public CompatibilityDao(Connection c) {
        super(c);
    }

    /** The key is chosen by the caller, so there is no generated id to return. */
    public void insert(Compatibility s) throws SQLException {
        execute("""
                INSERT INTO env_supports_version (env_id, model_id, version_tag, verified_on, notes)
                VALUES (?, ?, ?, ?, ?)""",
                s.envId(), s.modelId(), s.versionTag(), s.verifiedOn(), s.notes());
    }

    public Optional<CompatibilityRow> findById(int envId, int modelId, String versionTag) throws SQLException {
        return queryOne(SELECT_ROW + " WHERE s.env_id = ? AND s.model_id = ? AND s.version_tag = ?",
                CompatibilityDao::readRow, envId, modelId, versionTag);
    }

    /**
     * Every filter may be null. runsOnNodeId finds the environments whose CUDA version is
     * supported by that node's driver.
     */
    public List<CompatibilityRow> search(Integer envId, VersionKey version, Integer runsOnNodeId)
            throws SQLException {
        Filter f = new Filter()
                .and("s.env_id = ?", envId)
                .andPair("(s.model_id = ? AND s.version_tag = ?)",
                        version == null ? null : version.modelId(),
                        version == null ? null : version.versionTag())
                .and("e.cuda_version <= (SELECT n.max_cuda_version FROM compute_node n WHERE n.node_id = ?)",
                        runsOnNodeId);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY m.model_name, s.version_tag, e.env_name",
                CompatibilityDao::readRow, f.params());
    }

    /** Changes the date and notes. Returns false if the record no longer exists. */
    public boolean update(Compatibility s) throws SQLException {
        return execute("""
                UPDATE env_supports_version SET verified_on = ?, notes = ?
                 WHERE env_id = ? AND model_id = ? AND version_tag = ?""",
                s.verifiedOn(), s.notes(), s.envId(), s.modelId(), s.versionTag()) == 1;
    }

    /** Returns false if the record no longer exists. */
    public boolean delete(Compatibility s) throws SQLException {
        return execute("DELETE FROM env_supports_version WHERE env_id = ? AND model_id = ? AND version_tag = ?",
                s.envId(), s.modelId(), s.versionTag()) == 1;
    }

    private static CompatibilityRow readRow(ResultSet rs) throws SQLException {
        return new CompatibilityRow(
                new Compatibility(rs.getInt("env_id"), rs.getInt("model_id"), rs.getString("version_tag"),
                        rs.getObject("verified_on", LocalDate.class), rs.getString("notes")),
                rs.getString("env_name"), rs.getBigDecimal("cuda_version"), rs.getString("model_name"));
    }
}
