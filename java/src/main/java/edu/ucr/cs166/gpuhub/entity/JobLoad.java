package edu.ucr.cs166.gpuhub.entity;

/** A row of job_loads_version: the job loads the model version, for example for inference. */
public record JobLoad(long jobId, int modelId, String versionTag, String usageRole) { }
