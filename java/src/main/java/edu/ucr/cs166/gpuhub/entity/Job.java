package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A row of job. Use jobId 0 for a new job; fields that a job does not have yet (such as endTime) are null. */
public record Job(
        long jobId, int reservationId, int envId, String jobName, LocalDateTime submitTime,
        LocalDateTime startTime, LocalDateTime endTime, String jobState, Integer exitCode,
        BigDecimal peakVramGb, BigDecimal avgGpuUtilPct) { }
