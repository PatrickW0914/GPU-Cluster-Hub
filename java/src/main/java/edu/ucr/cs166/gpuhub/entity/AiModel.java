package edu.ucr.cs166.gpuhub.entity;

import java.time.LocalDate;

/** A row of ai_model. Use modelId 0 for a new model; sourceUrl and description may be null; addedOn is set by the database. */
public record AiModel(
        int modelId, String modelName, String taskCategory, String framework, String license,
        String sourceUrl, int maintainerId, String description, LocalDate addedOn) { }
