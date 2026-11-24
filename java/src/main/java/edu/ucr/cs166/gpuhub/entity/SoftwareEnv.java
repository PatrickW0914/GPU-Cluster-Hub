package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of software_env. Use envId 0 for a new environment; containerImage may be null; createdOn is set by the database. */
public record SoftwareEnv(
        int envId, String envName, String pythonVersion, BigDecimal cudaVersion,
        String framework, String frameworkVersion, String containerImage, LocalDate createdOn) { }
