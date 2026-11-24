package edu.ucr.cs166.gpuhub.entity;

/** A model version as listed on screen: its model's name, and how many nodes and environments have it. */
public record VersionRow(ModelVersion version, String modelName, int nodeCount, int envCount) { }
