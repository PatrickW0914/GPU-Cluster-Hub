package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A row of compute_node. Use nodeId 0 for a new node; rackLocation may be null. */
public record ComputeNode(
        int nodeId, String hostname, String partitionName, int cpuCores, int ramGb,
        BigDecimal localNvmeTb, BigDecimal maxCudaVersion, String nodeStatus, String rackLocation) { }
