package edu.ucr.cs166.gpuhub.entity;

/** A GPU and, for some time window, the reservation that already has it (null: it is free). */
public record GpuSlot(GpuRow row, Integer bookedBy) { }
