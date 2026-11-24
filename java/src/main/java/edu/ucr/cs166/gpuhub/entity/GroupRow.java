package edu.ucr.cs166.gpuhub.entity;

/** A research group as listed on screen, with data joined from other tables. */
public record GroupRow(ResearchGroup group, String piName, int memberCount) { }
