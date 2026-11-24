package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.StagedCopy;
import edu.ucr.cs166.gpuhub.entity.StagedCopyRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for node_hosts_version (node M:N model version). */
public final class StagedCopyDao extends Dao {

    private static final String SELECT_ROW = """
            SELECT h.node_id, h.model_id, h.version_tag, h.local_path, h.staged_on,
                   n.hostname, m.model_name, m.task_category, v.checkpoint_size_gb
              FROM node_hosts_version h
              JOIN compute_node n  ON n.node_id = h.node_id
              JOIN model_version v ON (v.model_id, v.version_tag) = (h.model_id, h.version_tag)
              JOIN ai_model m      ON m.model_id = h.model_id""";

    public StagedCopyDao(Connection c) {
        super(c);
    }

    /** The key is chosen by the caller, so there is no generated id. A full node fails with GH007. */
    public void insert(StagedCopy h) throws SQLException {
        execute("""
                INSERT INTO node_hosts_version (node_id, model_id, version_tag, local_path, staged_on)
                VALUES (?, ?, ?, ?, ?)""",
                h.nodeId(), h.modelId(), h.versionTag(), h.localPath(), h.stagedOn());
    }

    public Optional<StagedCopyRow> findById(int nodeId, int modelId, String versionTag) throws SQLException {
        return queryOne(SELECT_ROW + " WHERE h.node_id = ? AND h.model_id = ? AND h.version_tag = ?",
                StagedCopyDao::readRow, nodeId, modelId, versionTag);
    }

    /** Every filter may be null. taskCategory is the model's task. */
    public List<StagedCopyRow> search(Integer nodeId, VersionKey version, String taskCategory, String path)
            throws SQLException {
        Filter f = new Filter()
                .and("h.node_id = ?", nodeId)
                .andPair("(h.model_id = ? AND h.version_tag = ?)",
                        version == null ? null : version.modelId(),
                        version == null ? null : version.versionTag())
                .and("m.task_category = ?", taskCategory)
                .andContains("h.local_path ILIKE ?", path);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY n.hostname, m.model_name, h.version_tag",
                StagedCopyDao::readRow, f.params());
    }

    /** Changes the path and date. Returns false if the copy no longer exists. */
    public boolean update(StagedCopy h) throws SQLException {
        return execute("""
                UPDATE node_hosts_version SET local_path = ?, staged_on = ?
                 WHERE node_id = ? AND model_id = ? AND version_tag = ?""",
                h.localPath(), h.stagedOn(), h.nodeId(), h.modelId(), h.versionTag()) == 1;
    }

    /** Returns false if the copy no longer exists. */
    public boolean delete(StagedCopy h) throws SQLException {
        return execute("DELETE FROM node_hosts_version WHERE node_id = ? AND model_id = ? AND version_tag = ?",
                h.nodeId(), h.modelId(), h.versionTag()) == 1;
    }

    private static StagedCopyRow readRow(ResultSet rs) throws SQLException {
        return new StagedCopyRow(
                new StagedCopy(rs.getInt("node_id"), rs.getInt("model_id"), rs.getString("version_tag"),
                        rs.getString("local_path"), rs.getObject("staged_on", LocalDate.class)),
                rs.getString("hostname"), rs.getString("model_name"), rs.getString("task_category"),
                rs.getBigDecimal("checkpoint_size_gb"));
    }
}
