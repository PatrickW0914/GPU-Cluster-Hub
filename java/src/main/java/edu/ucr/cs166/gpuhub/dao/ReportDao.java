package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Report;
import edu.ucr.cs166.gpuhub.entity.ReportColumn;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs the queries Q1-Q8. */
public final class ReportDao extends Dao {

    public ReportDao(Connection c) {
        super(c);
    }

    /** Runs the query and returns its rows, each with one value per column. */
    public List<List<Object>> run(Report report, Map<String, Object> parameters) throws SQLException {
        SqlFile file = SqlFile.load(report.id());
        return queryAll(file.sql(), rs -> readRow(rs, report.columns()), file.values(parameters));
    }

    /** The query's SQL, to show to the user. */
    public static String sqlOf(Report report) {
        return SqlFile.readQuery(report.id());
    }

    private static List<Object> readRow(ResultSet rs, List<ReportColumn> columns) throws SQLException {
        if (rs.getMetaData().getColumnCount() != columns.size()) {
            throw new IllegalStateException("The query returns a different number of columns than described");
        }
        List<Object> row = new ArrayList<>(); // not List.of: values can be null
        for (int i = 0; i < columns.size(); i++) {
            row.add(rs.getObject(i + 1, columns.get(i).type()));
        }
        return row;
    }
}
