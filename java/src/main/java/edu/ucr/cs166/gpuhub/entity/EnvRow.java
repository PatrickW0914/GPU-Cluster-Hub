package edu.ucr.cs166.gpuhub.entity;

/** An environment as listed on screen, with the number of model versions verified for it. */
public record EnvRow(SoftwareEnv env, int versionCount) { }
