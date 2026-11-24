package edu.ucr.cs166.gpuhub.entity;

/** A model load as listed on screen, with data from the job and the model. */
public record JobLoadRow(JobLoad load, String jobName, String netId, String envName, String modelName) { }
