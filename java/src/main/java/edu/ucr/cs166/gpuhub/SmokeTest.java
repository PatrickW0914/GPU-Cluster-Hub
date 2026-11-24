package edu.ucr.cs166.gpuhub;

import edu.ucr.cs166.gpuhub.db.Database;
import edu.ucr.cs166.gpuhub.db.SqlErrors;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Checks the setup: connects and prints the number of ai_model rows (28 in the small dataset). */
public final class SmokeTest {

    private SmokeTest() { }

    public static void main(String[] args) {
        try (Connection c = Database.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ai_model");
             ResultSet rs = ps.executeQuery()) {
            rs.next(); // COUNT(*) always returns exactly one row
            System.out.println("Connected. ai_model rows: " + rs.getLong(1));
        } catch (SQLException e) {
            System.err.println(SqlErrors.message(e));
            System.err.println("Detail: " + SqlErrors.detail(e));
            System.exit(1);
        }
    }
}
