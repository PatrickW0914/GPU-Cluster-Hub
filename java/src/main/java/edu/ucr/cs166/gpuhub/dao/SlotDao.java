package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.SlotCandidate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Runs the slot finder query, sql/queries/slot_finder.sql. */
public final class SlotDao extends Dao {

    public SlotDao(Connection c) {
        super(c);
    }

    /** Returns at most 3 slots per node and GPU type, the earliest first. */
    public List<SlotCandidate> find(Map<String, Object> parameters) throws SQLException {
        SqlFile file = SqlFile.load("slot_finder");
        return queryAll(file.sql(), SlotDao::read, file.values(parameters));
    }

    private static SlotCandidate read(ResultSet rs) throws SQLException {
        Integer[] gpuIds = (Integer[]) rs.getArray("gpu_ids").getArray();
        return new SlotCandidate(
                rs.getString("hostname"), rs.getInt("option_no"),
                rs.getObject("slot_start", LocalDateTime.class),
                rs.getObject("slot_end", LocalDateTime.class),
                rs.getString("type_name"), rs.getInt("vram_gb"), rs.getBigDecimal("min_vram_gb"),
                rs.getInt("gpus_needed"), rs.getInt("free_gpus"),
                List.of(gpuIds), rs.getString("envs"));
    }
}
