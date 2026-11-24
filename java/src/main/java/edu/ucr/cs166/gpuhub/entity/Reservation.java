package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDateTime;

/** A row of reservation. Use reservationId 0 for a new one; createdAt is the app's current time (see ReservationDao). */
public record Reservation(
        int reservationId, int userId, int groupId, LocalDateTime startTime, LocalDateTime endTime,
        String purpose, String resStatus, LocalDateTime createdAt) { }
