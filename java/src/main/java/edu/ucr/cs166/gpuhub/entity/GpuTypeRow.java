package edu.ucr.cs166.gpuhub.entity;

/** A GPU type as listed on screen, with the number of GPUs of that type. */
public record GpuTypeRow(GpuType type, int gpuCount) { }
