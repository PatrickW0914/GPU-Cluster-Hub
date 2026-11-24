package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A row of research_group. Use groupId 0 for a new group; createdOn is set by the database. */
public record ResearchGroup(
        int groupId, String groupName, String department, int piUserId,
        BigDecimal weeklyGpuHourQuota, LocalDate createdOn) { }
