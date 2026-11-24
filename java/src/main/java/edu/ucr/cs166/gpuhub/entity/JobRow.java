package edu.ucr.cs166.gpuhub.entity;

/** A job as listed on screen, with its user, its environment and what it loads. loads may be null. */
public record JobRow(Job job, String netId, String envName, int loadCount, String loads) { }
