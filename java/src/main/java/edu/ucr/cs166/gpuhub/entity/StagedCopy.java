package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDate;

/** A row of node_hosts_version: the model version's checkpoint is stored on the node. */
public record StagedCopy(int nodeId, int modelId, String versionTag, String localPath, LocalDate stagedOn) { }
