package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A row of gpu_type. Use gpuTypeId 0 for a new type. */
public record GpuType(
        int gpuTypeId, String typeName, String vendor, String architecture, int vramGb,
        BigDecimal computeCapability, BigDecimal fp16Tflops, boolean supportsBf16) { }
