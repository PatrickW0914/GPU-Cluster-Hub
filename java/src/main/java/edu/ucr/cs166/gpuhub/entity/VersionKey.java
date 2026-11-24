package edu.ucr.cs166.gpuhub.entity;

/** The composite primary key of model_version. */
public record VersionKey(int modelId, String versionTag) { }
