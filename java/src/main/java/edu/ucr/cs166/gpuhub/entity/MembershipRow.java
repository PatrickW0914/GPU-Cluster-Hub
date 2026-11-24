package edu.ucr.cs166.gpuhub.entity;

/** A membership as listed on screen, with data joined from other tables. */
public record MembershipRow(
        Membership membership, String netId, String fullName, String groupName, boolean isPi) { }
