package edu.ucr.cs166.gpuhub.entity;

import java.util.List;

/**
 * One of the queries Q1-Q8.
 *
 * @param id            names its SQL file, for example Q1 for sql/queries/Q1.sql
 * @param answers       the question it answers
 * @param concept       the SQL concept it demonstrates
 * @param needsModel    whether it has the parameters model and version
 * @param needsHours    whether it has the parameter hours
 * @param columns       the result's columns, in the order of the SELECT list
 */
public record Report(String id, String title, String answers, String concept,
                     boolean needsModel, boolean needsHours, List<ReportColumn> columns) {

    @Override
    public String toString() { // the list of queries displays this
        return id + "  " + title;
    }
}
