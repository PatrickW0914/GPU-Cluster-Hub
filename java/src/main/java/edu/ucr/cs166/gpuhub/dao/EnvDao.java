package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.EnvRow;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
import edu.ucr.cs166.gpuhub.entity.VersionKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for software_env. */
public final class EnvDao extends Dao {

    private static final String SELECT = """
            SELECT e.env_id, e.env_name, e.python_version, e.cuda_version,
                   e.framework, e.framework_version, e.container_image, e.created_on
              FROM software_env e""";

    /** For lists: adds the number of model versions verified for the environment. */
    private static final String SELECT_ROW = """
            SELECT e.env_id, e.env_name, e.python_version, e.cuda_version,
                   e.framework, e.framework_version, e.container_image, e.created_on,
                   (SELECT COUNT(*) FROM env_supports_version s WHERE s.env_id = e.env_id) AS version_count
              FROM software_env e""";

    public EnvDao(Connection c) {
        super(c);
    }

    /** Inserts the environment and returns the generated id. */
    public int insert(SoftwareEnv e) throws SQLException {
        return insertReturningId("""
                INSERT INTO software_env (env_name, python_version, cuda_version,
                                          framework, framework_version, container_image)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING env_id""",
                e.envName(), e.pythonVersion(), e.cudaVersion(),
                e.framework(), e.frameworkVersion(), e.containerImage());
    }

    public Optional<SoftwareEnv> findById(int envId) throws SQLException {
        return queryOne(SELECT + " WHERE e.env_id = ?", EnvDao::read, envId);
    }

    /**
     * Every filter may be null. runsOnNodeId finds the environments whose CUDA version is
     * supported by that node's driver; supports finds the ones verified for that model version.
     */
    public List<EnvRow> search(String envName, String framework, Integer runsOnNodeId, VersionKey supports)
            throws SQLException {
        Filter f = new Filter()
                .andContains("e.env_name ILIKE ?", envName)
                .and("e.framework = ?", framework)
                .and("e.cuda_version <= (SELECT n.max_cuda_version FROM compute_node n WHERE n.node_id = ?)",
                        runsOnNodeId)
                .andPair("""
                        EXISTS (SELECT 1 FROM env_supports_version s
                                 WHERE s.env_id = e.env_id AND s.model_id = ? AND s.version_tag = ?)""",
                        supports == null ? null : supports.modelId(),
                        supports == null ? null : supports.versionTag());
        return queryAll(SELECT_ROW + f.where() + " ORDER BY e.env_name", EnvDao::readRow, f.params());
    }

    /** Returns false if the environment no longer exists. */
    public boolean update(SoftwareEnv e) throws SQLException {
        return execute("""
                UPDATE software_env
                   SET env_name = ?, python_version = ?, cuda_version = ?,
                       framework = ?, framework_version = ?, container_image = ?
                 WHERE env_id = ?""",
                e.envName(), e.pythonVersion(), e.cudaVersion(),
                e.framework(), e.frameworkVersion(), e.containerImage(),
                e.envId()) == 1;
    }

    /** Returns false if the environment no longer exists. */
    public boolean delete(int envId) throws SQLException {
        return execute("DELETE FROM software_env WHERE env_id = ?", envId) == 1;
    }

    private static EnvRow readRow(ResultSet rs) throws SQLException {
        return new EnvRow(read(rs), rs.getInt("version_count"));
    }

    private static SoftwareEnv read(ResultSet rs) throws SQLException {
        return new SoftwareEnv(
                rs.getInt("env_id"), rs.getString("env_name"), rs.getString("python_version"),
                rs.getBigDecimal("cuda_version"), rs.getString("framework"),
                rs.getString("framework_version"), rs.getString("container_image"),
                rs.getObject("created_on", LocalDate.class));
    }
}
