package edu.ucr.cs166.gpuhub.dao;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the WHERE clause of a search whose filters are all optional. A filter
 * whose value is null or blank is skipped.
 *
 * Only conditions written in the DAO source become SQL text. The user's values go
 * into params and are bound to the ? placeholders, so they are never part of the SQL.
 */
final class Filter {

    private final StringBuilder where = new StringBuilder();
    private final List<Object> params = new ArrayList<>();

    /** Adds "AND condition" if there is a value; every ? in the condition gets that value. */
    Filter and(String condition, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return this;
        }
        where.append(where.isEmpty() ? " WHERE " : " AND ").append(condition);
        for (char ch : condition.toCharArray()) {
            if (ch == '?') {
                params.add(value);
            }
        }
        return this;
    }

    /** Like {@link #and}, for conditions of the form "column ILIKE ?" (text contained, any case). */
    Filter andContains(String condition, String text) {
        if (text == null || text.isBlank()) {
            return this;
        }
        // Escape the LIKE wildcards so that "_" or "%" typed by the user match literally.
        String escaped = text.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return and(condition, "%" + escaped + "%");
    }

    String where() {
        return where.toString();
    }

    Object[] params() {
        return params.toArray();
    }
}
