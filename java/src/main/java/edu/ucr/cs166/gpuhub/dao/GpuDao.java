package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.entity.GpuRow;
import edu.ucr.cs166.gpuhub.entity.GpuSlot;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for gpu. */
public final class GpuDao extends Dao {

    private static final String SELECT = """
            SELECT g.gpu_id, g.node_id, g.slot_index, g.gpu_type_id,
                   g.serial_number, g.gpu_status, g.installed_on
              FROM gpu g""";

    /** For lists: adds the node's name and status, and the type's name and VRAM. */
    private static final String SELECT_ROW = """
            SELECT g.gpu_id, g.node_id, g.slot_index, g.gpu_type_id,
                   g.serial_number, g.gpu_status, g.installed_on,
                   n.hostname, n.node_status, t.type_name, t.vram_gb
              FROM gpu g
              JOIN compute_node n ON n.node_id = g.node_id
              JOIN gpu_type t     ON t.gpu_type_id = g.gpu_type_id""";

    /** A reservation that is not cancelled covers the given time. */
    private static final String BOOKED_AT = """
            EXISTS (SELECT 1 FROM reservation_gpu rg
                      JOIN reservation r ON r.reservation_id = rg.reservation_id
                     WHERE rg.gpu_id = g.gpu_id AND r.res_status <> 'CANCELLED'
                       AND tsrange(r.start_time, r.end_time) @> ?::timestamp)""";

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

    /**
     * Every filter except now may be null. booked finds the GPUs that are (true) or are not
     * (false) in a reservation at the time now.
     */
    public List<GpuRow> search(Integer nodeId, Integer gpuTypeId, String status, String serialNumber,
                               Boolean booked, LocalDateTime now) throws SQLException {
        Filter f = new Filter()
                .and("g.node_id = ?", nodeId)
                .and("g.gpu_type_id = ?", gpuTypeId)
                .and("g.gpu_status = ?", status)
                .andContains("g.serial_number ILIKE ?", serialNumber);
        if (booked != null) {
            f.and(booked ? BOOKED_AT : "NOT " + BOOKED_AT, now);
        }
        return queryAll(SELECT_ROW + f.where() + " ORDER BY n.hostname, g.slot_index",
                GpuDao::readRow, f.params());
    }

    /**
     * All GPUs of a node, each with the reservation (if any) that already has it at some time
     * between start and end. Uses the same overlap test as the GH001 trigger.
     */
    public List<GpuSlot> slots(int nodeId, LocalDateTime start, LocalDateTime end) throws SQLException {
        return queryAll("""
                SELECT g.gpu_id, g.node_id, g.slot_index, g.gpu_type_id,
                       g.serial_number, g.gpu_status, g.installed_on,
                       n.hostname, n.node_status, t.type_name, t.vram_gb,
                       (SELECT MIN(r.reservation_id)
                          FROM reservation_gpu rg
                          JOIN reservation r ON r.reservation_id = rg.reservation_id
                         WHERE rg.gpu_id = g.gpu_id AND r.res_status <> 'CANCELLED'
                           AND tsrange(r.start_time, r.end_time) && tsrange(?::timestamp, ?::timestamp)
                       ) AS booked_by
                  FROM gpu g
                  JOIN compute_node n ON n.node_id = g.node_id
                  JOIN gpu_type t     ON t.gpu_type_id = g.gpu_type_id
                 WHERE g.node_id = ?
                 ORDER BY g.slot_index""",
                rs -> new GpuSlot(readRow(rs), rs.getObject("booked_by", Integer.class)),
                start, end, nodeId);
    }

    /** Makes other transactions that want to lock this GPU wait until this transaction ends. */
    public void lock(int gpuId) throws SQLException {
        queryOne("SELECT gpu_id FROM gpu WHERE gpu_id = ? FOR UPDATE", rs -> rs.getInt(1), gpuId);
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

    private static GpuRow readRow(ResultSet rs) throws SQLException {
        return new GpuRow(read(rs), rs.getString("hostname"), rs.getString("node_status"),
                rs.getString("type_name"), rs.getInt("vram_gb"));
    }

    private static Gpu read(ResultSet rs) throws SQLException {
        return new Gpu(
                rs.getInt("gpu_id"), rs.getInt("node_id"), rs.getInt("slot_index"),
                rs.getInt("gpu_type_id"), rs.getString("serial_number"), rs.getString("gpu_status"),
                rs.getObject("installed_on", LocalDate.class));
    }
}
