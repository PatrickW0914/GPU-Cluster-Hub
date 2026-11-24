package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDate;

/** A row of gpu. Use gpuId 0 for a new GPU. */
public record Gpu(
        int gpuId, int nodeId, int slotIndex, int gpuTypeId,
        String serialNumber, String gpuStatus, LocalDate installedOn) { }
