package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A compatibility record as listed on screen, with data from the environment and the model. */
public record CompatibilityRow(
        Compatibility compatibility, String envName, BigDecimal cudaVersion, String modelName) { }
