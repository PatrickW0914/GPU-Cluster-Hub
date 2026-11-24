package edu.ucr.cs166.gpuhub.ui;

import java.util.function.Function;

/** One column of a results table: its title, value type (used for sorting), and how to get the value. */
public record Column<T>(String title, Class<?> type, Function<T, Object> value) { }
