package edu.ucr.cs166.gpuhub.entity;

/** A GPU as listed on screen, with data from its node and type. */
public record GpuRow(Gpu gpu, String hostname, String nodeStatus, String typeName, int vramGb) { }
