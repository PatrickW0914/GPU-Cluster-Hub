package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDate;

/** A row of env_supports_version: the model version was verified in the environment. notes may be null. */
public record Compatibility(
        int envId, int modelId, String versionTag, LocalDate verifiedOn, String notes) { }
