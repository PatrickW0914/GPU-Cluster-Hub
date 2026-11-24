package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.entity.NodeRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** SQL for compute_node. */
public final class NodeDao extends Dao {

    private static final String SELECT = """
            SELECT n.node_id, n.hostname, n.partition_name, n.cpu_cores, n.ram_gb,
                   n.local_nvme_tb, n.max_cuda_version, n.node_status, n.rack_location
              FROM compute_node n""";

    /** For lists: adds the node's GPUs and how much checkpoint storage it uses. */
    private static final String SELECT_ROW = """
            SELECT n.node_id, n.hostname, n.partition_name, n.cpu_cores, n.ram_gb,
                   n.local_nvme_tb, n.max_cuda_version, n.node_status, n.rack_location,
                   (SELECT COUNT(*) FROM gpu g WHERE g.node_id = n.node_id) AS gpu_count,
                   (SELECT STRING_AGG(DISTINCT t.type_name, ', ')
                      FROM gpu g JOIN gpu_type t ON t.gpu_type_id = g.gpu_type_id
                     WHERE g.node_id = n.node_id) AS gpu_types,
                   (SELECT COUNT(*) FROM node_hosts_version h WHERE h.node_id = n.node_id) AS staged_count,
                   (SELECT COALESCE(SUM(v.checkpoint_size_gb), 0)
                      FROM node_hosts_version h
                      JOIN model_version v ON (v.model_id, v.version_tag) = (h.model_id, h.version_tag)
                     WHERE h.node_id = n.node_id) AS staged_gb
              FROM compute_node n""";

    public NodeDao(Connection c) {
        super(c);
    }

    /** Inserts the node and returns the generated id. */
    public int insert(ComputeNode n) throws SQLException {
        return insertReturningId("""
                INSERT INTO compute_node (hostname, partition_name, cpu_cores, ram_gb,
                                          local_nvme_tb, max_cuda_version, node_status, rack_location)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING node_id""",
                n.hostname(), n.partitionName(), n.cpuCores(), n.ramGb(),
                n.localNvmeTb(), n.maxCudaVersion(), n.nodeStatus(), n.rackLocation());
    }

    public Optional<ComputeNode> findById(int nodeId) throws SQLException {
        return queryOne(SELECT + " WHERE n.node_id = ?", NodeDao::read, nodeId);
    }

    /**
     * Every filter may be null. gpuTypeId finds the nodes that contain that type of GPU;
     * hosted finds the nodes on which that model version is staged.
     */
    public List<NodeRow> search(String hostname, String partition, String status,
                                Integer gpuTypeId, VersionKey hosted) throws SQLException {
        Filter f = new Filter()
                .andContains("n.hostname ILIKE ?", hostname)
                .and("n.partition_name = ?", partition)
                .and("n.node_status = ?", status)
                .and("EXISTS (SELECT 1 FROM gpu g WHERE g.node_id = n.node_id AND g.gpu_type_id = ?)", gpuTypeId)
                .andPair("""
                        EXISTS (SELECT 1 FROM node_hosts_version h
                                 WHERE h.node_id = n.node_id AND h.model_id = ? AND h.version_tag = ?)""",
                        hosted == null ? null : hosted.modelId(),
                        hosted == null ? null : hosted.versionTag());
        return queryAll(SELECT_ROW + f.where() + " ORDER BY n.hostname", NodeDao::readRow, f.params());
    }

    /** Returns false if the node no longer exists. */
    public boolean update(ComputeNode n) throws SQLException {
        return execute("""
                UPDATE compute_node
                   SET hostname = ?, partition_name = ?, cpu_cores = ?, ram_gb = ?,
                       local_nvme_tb = ?, max_cuda_version = ?, node_status = ?, rack_location = ?
                 WHERE node_id = ?""",
                n.hostname(), n.partitionName(), n.cpuCores(), n.ramGb(),
                n.localNvmeTb(), n.maxCudaVersion(), n.nodeStatus(), n.rackLocation(),
                n.nodeId()) == 1;
    }

    /** Deletes the node and (by cascade) its staged copies. Returns false if it no longer exists. */
    public boolean delete(int nodeId) throws SQLException {
        return execute("DELETE FROM compute_node WHERE node_id = ?", nodeId) == 1;
    }

    private static NodeRow readRow(ResultSet rs) throws SQLException {
        return new NodeRow(read(rs), rs.getInt("gpu_count"), rs.getString("gpu_types"),
                rs.getInt("staged_count"), rs.getBigDecimal("staged_gb"));
    }

    private static ComputeNode read(ResultSet rs) throws SQLException {
        return new ComputeNode(
                rs.getInt("node_id"), rs.getString("hostname"), rs.getString("partition_name"),
                rs.getInt("cpu_cores"), rs.getInt("ram_gb"),
                rs.getBigDecimal("local_nvme_tb"), rs.getBigDecimal("max_cuda_version"),
                rs.getString("node_status"), rs.getString("rack_location"));
    }
}
