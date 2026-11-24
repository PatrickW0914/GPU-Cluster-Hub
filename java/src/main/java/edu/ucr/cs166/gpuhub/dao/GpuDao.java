package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Gpu;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for gpu. */
public final class GpuDao extends Dao {

    private static final String SELECT = """
            SELECT g.gpu_id, g.node_id, g.slot_index, g.gpu_type_id,
                   g.serial_number, g.gpu_status, g.installed_on
              FROM gpu g""";

    public GpuDao(Connection c) {
        super(c);
    }

    /** Inserts the GPU and returns the generated id. */
    public int insert(Gpu g) throws SQLException {
        return insertReturningId("""
                INSERT INTO gpu (node_id, slot_index, gpu_type_id, serial_number, gpu_status, installed_on)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING gpu_id""",
                g.nodeId(), g.slotIndex(), g.gpuTypeId(), g.serialNumber(), g.gpuStatus(), g.installedOn());
    }

    public Optional<Gpu> findById(int gpuId) throws SQLException {
        return queryOne(SELECT + " WHERE g.gpu_id = ?", GpuDao::read, gpuId);
    }

    /** Every filter may be null. */
    public List<Gpu> search(Integer nodeId, Integer gpuTypeId, String status, String serialNumber)
            throws SQLException {
        Filter f = new Filter()
                .and("g.node_id = ?", nodeId)
                .and("g.gpu_type_id = ?", gpuTypeId)
                .and("g.gpu_status = ?", status)
                .andContains("g.serial_number ILIKE ?", serialNumber);
        return queryAll(SELECT + f.where() + " ORDER BY g.node_id, g.slot_index", GpuDao::read, f.params());
    }

    /** Returns false if the GPU no longer exists. */
    public boolean update(Gpu g) throws SQLException {
        return execute("""
                UPDATE gpu
                   SET node_id = ?, slot_index = ?, gpu_type_id = ?, serial_number = ?,
                       gpu_status = ?, installed_on = ?
                 WHERE gpu_id = ?""",
                g.nodeId(), g.slotIndex(), g.gpuTypeId(), g.serialNumber(), g.gpuStatus(), g.installedOn(),
                g.gpuId()) == 1;
    }

    /** Returns false if the GPU no longer exists. */
    public boolean delete(int gpuId) throws SQLException {
        return execute("DELETE FROM gpu WHERE gpu_id = ?", gpuId) == 1;
    }

    private static Gpu read(ResultSet rs) throws SQLException {
        return new Gpu(
                rs.getInt("gpu_id"), rs.getInt("node_id"), rs.getInt("slot_index"),
                rs.getInt("gpu_type_id"), rs.getString("serial_number"), rs.getString("gpu_status"),
                rs.getObject("installed_on", LocalDate.class));
    }
}
