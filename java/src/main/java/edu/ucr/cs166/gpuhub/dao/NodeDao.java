package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.ComputeNode;

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

    /** Every filter may be null. gpuTypeId finds the nodes that contain that type of GPU. */
    public List<ComputeNode> search(String hostname, String partition, String status, Integer gpuTypeId)
            throws SQLException {
        Filter f = new Filter()
                .andContains("n.hostname ILIKE ?", hostname)
                .and("n.partition_name = ?", partition)
                .and("n.node_status = ?", status)
                .and("EXISTS (SELECT 1 FROM gpu g WHERE g.node_id = n.node_id AND g.gpu_type_id = ?)", gpuTypeId);
        return queryAll(SELECT + f.where() + " ORDER BY n.hostname", NodeDao::read, f.params());
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

    /** Returns false if the node no longer exists. */
    public boolean delete(int nodeId) throws SQLException {
        return execute("DELETE FROM compute_node WHERE node_id = ?", nodeId) == 1;
    }

    private static ComputeNode read(ResultSet rs) throws SQLException {
        return new ComputeNode(
                rs.getInt("node_id"), rs.getString("hostname"), rs.getString("partition_name"),
                rs.getInt("cpu_cores"), rs.getInt("ram_gb"),
                rs.getBigDecimal("local_nvme_tb"), rs.getBigDecimal("max_cuda_version"),
                rs.getString("node_status"), rs.getString("rack_location"));
    }
}
