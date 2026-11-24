package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.ModelVersion;
import edu.ucr.cs166.gpuhub.entity.VersionRow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for model_version, a weak entity with the composite key (model_id, version_tag). */
public final class ModelVersionDao extends Dao {

    private static final String SELECT = """
            SELECT v.model_id, v.version_tag, v.param_count_b, v.weight_precision,
                   v.min_vram_gb, v.checkpoint_size_gb, v.released_on, v.is_deprecated
              FROM model_version v""";

    /** For lists: adds the model's name and how many nodes and environments have the version. */
    private static final String SELECT_ROW = """
            SELECT v.model_id, v.version_tag, v.param_count_b, v.weight_precision,
                   v.min_vram_gb, v.checkpoint_size_gb, v.released_on, v.is_deprecated,
                   m.model_name,
                   (SELECT COUNT(*) FROM node_hosts_version h
                     WHERE (h.model_id, h.version_tag) = (v.model_id, v.version_tag)) AS node_count,
                   (SELECT COUNT(*) FROM env_supports_version s
                     WHERE (s.model_id, s.version_tag) = (v.model_id, v.version_tag)) AS env_count
              FROM model_version v
              JOIN ai_model m ON m.model_id = v.model_id""";

    public ModelVersionDao(Connection c) {
        super(c);
    }

    /** The key is chosen by the caller, so there is no generated id to return. */
    public void insert(ModelVersion v) throws SQLException {
        execute("""
                INSERT INTO model_version (model_id, version_tag, param_count_b, weight_precision,
                                           min_vram_gb, checkpoint_size_gb, released_on, is_deprecated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                v.modelId(), v.versionTag(), v.paramCountB(), v.weightPrecision(),
                v.minVramGb(), v.checkpointSizeGb(), v.releasedOn(), v.isDeprecated());
    }

    public Optional<ModelVersion> findById(int modelId, String versionTag) throws SQLException {
        return queryOne(SELECT + " WHERE v.model_id = ? AND v.version_tag = ?",
                ModelVersionDao::read, modelId, versionTag);
    }

    /**
     * Every filter may be null. maxVramGb finds the versions that fit in that much VRAM,
     * nodeId the ones staged on that node, envId the ones verified for that environment.
     */
    public List<VersionRow> search(Integer modelId, String precision, BigDecimal maxVramGb, Boolean deprecated,
                                   Integer nodeId, Integer envId) throws SQLException {
        Filter f = new Filter()
                .and("v.model_id = ?", modelId)
                .and("v.weight_precision = ?", precision)
                .and("v.min_vram_gb <= ?", maxVramGb)
                .and("v.is_deprecated = ?", deprecated)
                .and("""
                        EXISTS (SELECT 1 FROM node_hosts_version h
                                 WHERE (h.model_id, h.version_tag) = (v.model_id, v.version_tag)
                                   AND h.node_id = ?)""", nodeId)
                .and("""
                        EXISTS (SELECT 1 FROM env_supports_version s
                                 WHERE (s.model_id, s.version_tag) = (v.model_id, v.version_tag)
                                   AND s.env_id = ?)""", envId);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY m.model_name, v.version_tag",
                ModelVersionDao::readRow, f.params());
    }

    /**
     * Updates the version that is now called oldTag. If v has a different tag, the version is
     * renamed, and ON UPDATE CASCADE renames it in the tables that reference it. Returns false
     * if the version no longer exists.
     */
    public boolean update(String oldTag, ModelVersion v) throws SQLException {
        return execute("""
                UPDATE model_version
                   SET version_tag = ?, param_count_b = ?, weight_precision = ?, min_vram_gb = ?,
                       checkpoint_size_gb = ?, released_on = ?, is_deprecated = ?
                 WHERE model_id = ? AND version_tag = ?""",
                v.versionTag(), v.paramCountB(), v.weightPrecision(), v.minVramGb(),
                v.checkpointSizeGb(), v.releasedOn(), v.isDeprecated(),
                v.modelId(), oldTag) == 1;
    }

    /** Returns false if the version no longer exists. */
    public boolean delete(int modelId, String versionTag) throws SQLException {
        return execute("DELETE FROM model_version WHERE model_id = ? AND version_tag = ?",
                modelId, versionTag) == 1;
    }

    private static VersionRow readRow(ResultSet rs) throws SQLException {
        return new VersionRow(read(rs), rs.getString("model_name"), rs.getInt("node_count"), rs.getInt("env_count"));
    }

    private static ModelVersion read(ResultSet rs) throws SQLException {
        return new ModelVersion(
                rs.getInt("model_id"), rs.getString("version_tag"), rs.getBigDecimal("param_count_b"),
                rs.getString("weight_precision"), rs.getBigDecimal("min_vram_gb"),
                rs.getBigDecimal("checkpoint_size_gb"), rs.getObject("released_on", LocalDate.class),
                rs.getBoolean("is_deprecated"));
    }
}
