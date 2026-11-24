package edu.ucr.cs166.gpuhub.entity;

/** A model as listed on screen, with its maintainer's name and number of versions. */
public record ModelRow(AiModel model, String maintainerName, int versionCount) { }
