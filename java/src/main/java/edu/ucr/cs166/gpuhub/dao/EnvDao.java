package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;

import java.math.BigDecimal;
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

    /** Every filter may be null. maxCuda finds the environments a node's driver can run. */
    public List<SoftwareEnv> search(String envName, String framework, BigDecimal maxCuda)
            throws SQLException {
        Filter f = new Filter()
                .andContains("e.env_name ILIKE ?", envName)
                .and("e.framework = ?", framework)
                .and("e.cuda_version <= ?", maxCuda);
        return queryAll(SELECT + f.where() + " ORDER BY e.env_name", EnvDao::read, f.params());
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

    private static SoftwareEnv read(ResultSet rs) throws SQLException {
        return new SoftwareEnv(
                rs.getInt("env_id"), rs.getString("env_name"), rs.getString("python_version"),
                rs.getBigDecimal("cuda_version"), rs.getString("framework"),
                rs.getString("framework_version"), rs.getString("container_image"),
                rs.getObject("created_on", LocalDate.class));
    }
}
