package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of model_version, identified by (modelId, versionTag). releasedOn may be null. */
public record ModelVersion(
        int modelId, String versionTag, BigDecimal paramCountB, String weightPrecision,
        BigDecimal minVramGb, BigDecimal checkpointSizeGb, LocalDate releasedOn, boolean isDeprecated) { }
