package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A group's booked GPU-hours in one week, and its weekly quota. */
public record WeekUsage(String groupName, BigDecimal booked, BigDecimal quota) { }
