package edu.ucr.cs166.gpuhub.db;

import java.sql.Connection;
import java.sql.SQLException;

/** Runs several statements as one transaction: all of them are saved, or none. */
public final class Transactions {

    private Transactions() { }

    /** The statements to run. They must all use the given connection. */
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs the work, commits, and returns its result. On any error, rolls back and rethrows. */
    public static <T> T run(Work<T> work) throws SQLException {
        try (Connection c = Database.open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                // Deferred constraints (GH004, group-PI foreign key) are checked here,
                // so commit() has to be inside the try block too.
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(c, e);
                throw e;
            }
        }
    }

    /** Rolls back and restores autocommit. If that fails too, the original error stays the main one. */
    private static void rollback(Connection c, Exception original) {
        try {
            c.rollback();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            original.addSuppressed(e);
        }
    }
}
