package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDate;

/** A row of membership (user M:N group). joinedOn is set by the database. */
public record Membership(
        int userId, int groupId, String memberRole, LocalDate joinedOn) { }
