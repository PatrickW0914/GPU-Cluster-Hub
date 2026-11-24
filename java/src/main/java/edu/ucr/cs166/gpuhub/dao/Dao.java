package edu.ucr.cs166.gpuhub.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC code, so each DAO contains only its SQL. Every statement in the
 * application is a PreparedStatement created here, and values are always bound to ?.
 *
 * A DAO does not open, commit or close its connection; the caller does. That lets
 * several DAOs work in the same transaction.
 */
abstract class Dao {

    private final Connection c;

    Dao(Connection c) {
        this.c = c;
    }

    /** Turns the current row of a result into an object. */
    interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** Runs a query and returns all rows. */
    <T> List<T> queryAll(String sql, RowReader<T> reader, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(reader.read(rs));
            }
            return rows;
        }
    }

    /** Runs a query that returns at most one row (a lookup by key). */
    <T> Optional<T> queryOne(String sql, RowReader<T> reader, Object... params) throws SQLException {
        return queryAll(sql, reader, params).stream().findFirst();
    }

    /** Runs "INSERT ... RETURNING id" and returns the generated id. */
    int insertReturningId(String sql, Object... params) throws SQLException {
        return queryOne(sql, rs -> rs.getInt(1), params).orElseThrow();
    }

    /** Runs an INSERT, UPDATE or DELETE and returns how many rows it changed. */
    int execute(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]); // null becomes SQL NULL
            }
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }
}
