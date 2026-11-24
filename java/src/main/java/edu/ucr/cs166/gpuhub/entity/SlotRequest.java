package edu.ucr.cs166.gpuhub.entity;

/**
 * What the user asks the slot finder for.
 *
 * @param minGpuType name of the weakest acceptable GPU type, or "" for any
 */
public record SlotRequest(ModelName model, int hours, int days, String minGpuType) { }
