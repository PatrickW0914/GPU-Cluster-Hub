package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A staged copy as listed on screen, with data from the node and the model version. */
public record StagedCopyRow(
        StagedCopy copy, String hostname, String modelName, String taskCategory, BigDecimal sizeGb) { }
