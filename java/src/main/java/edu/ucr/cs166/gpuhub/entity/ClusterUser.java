package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDateTime;

/** A row of cluster_user. Use userId 0 for a user that is not inserted yet; createdAt is set by the database. */
public record ClusterUser(
        int userId, String netId, String fullName, String email, String userRole,
        boolean isAdmin, boolean isActive, LocalDateTime createdAt) { }
