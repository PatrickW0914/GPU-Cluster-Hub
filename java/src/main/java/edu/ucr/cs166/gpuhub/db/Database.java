package edu.ucr.cs166.gpuhub.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens connections to PostgreSQL. Each operation opens its own connection in
 * try-with-resources, so it is always closed, and one operation's transaction
 * can never leak into another:
 *
 * <pre>
 *   try (Connection c = Database.open()) { ... }
 * </pre>
 */
public final class Database {

    private static final String SCHEMA = "gpuhub";

    private Database() { }

    /** Opens a new connection (autocommit on) whose search path is the gpuhub schema. */
    public static Connection open() throws SQLException {
        DbConfig config;
        try {
            config = DbConfig.load();
        } catch (IOException e) {
            // Report bad settings as a connection failure (class 08), so callers
            // only have to handle SQLException.
            throw new SQLException(e.getMessage(), "08001", e);
        }

        Properties props = new Properties();
        props.setProperty("user", config.user());
        props.setProperty("password", config.password());
        // Set here too, so it still applies if the configured URL leaves it out.
        props.setProperty("currentSchema", SCHEMA);
        return DriverManager.getConnection(config.url(), props);
    }
}
