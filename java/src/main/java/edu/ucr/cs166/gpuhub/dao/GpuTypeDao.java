package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.GpuType;
import edu.ucr.cs166.gpuhub.entity.GpuTypeRow;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** SQL for gpu_type. */
public final class GpuTypeDao extends Dao {

    private static final String SELECT = """
            SELECT t.gpu_type_id, t.type_name, t.vendor, t.architecture, t.vram_gb,
                   t.compute_capability, t.fp16_tflops, t.supports_bf16
              FROM gpu_type t""";

    /** For lists: adds the number of GPUs of this type. */
    private static final String SELECT_ROW = """
            SELECT t.gpu_type_id, t.type_name, t.vendor, t.architecture, t.vram_gb,
                   t.compute_capability, t.fp16_tflops, t.supports_bf16,
                   (SELECT COUNT(*) FROM gpu g WHERE g.gpu_type_id = t.gpu_type_id) AS gpu_count
              FROM gpu_type t""";

    public GpuTypeDao(Connection c) {
        super(c);
    }

    /** Inserts the GPU type and returns the generated id. */
    public int insert(GpuType t) throws SQLException {
        return insertReturningId("""
                INSERT INTO gpu_type (type_name, vendor, architecture, vram_gb,
                                      compute_capability, fp16_tflops, supports_bf16)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING gpu_type_id""",
                t.typeName(), t.vendor(), t.architecture(), t.vramGb(),
                t.computeCapability(), t.fp16Tflops(), t.supportsBf16());
    }

    public Optional<GpuType> findById(int gpuTypeId) throws SQLException {
        return queryOne(SELECT + " WHERE t.gpu_type_id = ?", GpuTypeDao::read, gpuTypeId);
    }

    /** Every filter may be null. nodeId finds the types installed in that node. */
    public List<GpuTypeRow> search(String typeName, String vendor, Integer minVramGb,
                                   Boolean supportsBf16, Integer nodeId) throws SQLException {
        Filter f = new Filter()
                .andContains("t.type_name ILIKE ?", typeName)
                .and("t.vendor = ?", vendor)
                .and("t.vram_gb >= ?", minVramGb)
                .and("t.supports_bf16 = ?", supportsBf16)
                .and("EXISTS (SELECT 1 FROM gpu g WHERE g.gpu_type_id = t.gpu_type_id AND g.node_id = ?)", nodeId);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY t.type_name", GpuTypeDao::readRow, f.params());
    }

    /** Returns false if the type no longer exists. */
    public boolean update(GpuType t) throws SQLException {
        return execute("""
                UPDATE gpu_type
                   SET type_name = ?, vendor = ?, architecture = ?, vram_gb = ?,
                       compute_capability = ?, fp16_tflops = ?, supports_bf16 = ?
                 WHERE gpu_type_id = ?""",
                t.typeName(), t.vendor(), t.architecture(), t.vramGb(),
                t.computeCapability(), t.fp16Tflops(), t.supportsBf16(),
                t.gpuTypeId()) == 1;
    }

    /** Returns false if the type no longer exists. */
    public boolean delete(int gpuTypeId) throws SQLException {
        return execute("DELETE FROM gpu_type WHERE gpu_type_id = ?", gpuTypeId) == 1;
    }

    private static GpuTypeRow readRow(ResultSet rs) throws SQLException {
        return new GpuTypeRow(read(rs), rs.getInt("gpu_count"));
    }

    private static GpuType read(ResultSet rs) throws SQLException {
        return new GpuType(
                rs.getInt("gpu_type_id"), rs.getString("type_name"), rs.getString("vendor"),
                rs.getString("architecture"), rs.getInt("vram_gb"),
                rs.getBigDecimal("compute_capability"), rs.getBigDecimal("fp16_tflops"),
                rs.getBoolean("supports_bf16"));
    }
}
