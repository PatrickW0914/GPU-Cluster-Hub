package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/**
 * A reservation as listed on screen. gpus names the nodes and GPU ids; openJobs counts the
 * jobs that are queued or running.
 */
public record ReservationRow(
        Reservation reservation, String netId, String groupName,
        int gpuCount, BigDecimal gpuHours, String gpus, int openJobs) { }
