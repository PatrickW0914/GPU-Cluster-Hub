package edu.ucr.cs166.gpuhub.entity;

/** A column of a query's result: its readable title, and the Java type of its values. */
public record ReportColumn(String title, Class<?> type) { }
