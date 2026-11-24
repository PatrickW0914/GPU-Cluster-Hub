package edu.ucr.cs166.gpuhub.db;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Connection settings. No credentials live in the source code: they come from
 * config/db.properties (gitignored), and each one can be overridden by an
 * environment variable.
 */
public record DbConfig(String url, String user, String password) {

    /** The file is found whether the app is started from java/ or from the repo root. */
    private static final List<Path> LOCATIONS = List.of(
            Path.of("config", "db.properties"),
            Path.of("java", "config", "db.properties"));

    /**
     * Reads the settings.
     *
     * @throws IOException if the file cannot be read, or the URL or user is missing
     */
    public static DbConfig load() throws IOException {
        Properties file = new Properties();
        for (Path path : LOCATIONS) {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    file.load(reader);
                }
                break;
            }
        }
        // A missing file is fine as long as the environment variables are set.
        return new DbConfig(
                required(file, "db.url", "GPUHUB_DB_URL"),
                required(file, "db.user", "GPUHUB_DB_USER"),
                setting(file, "db.password", "GPUHUB_DB_PASSWORD")); // may be blank
    }

    /** Environment variable first, then the file, then blank. */
    private static String setting(Properties file, String key, String envVar) {
        String fromEnv = System.getenv(envVar);
        return fromEnv != null ? fromEnv : file.getProperty(key, "");
    }

    private static String required(Properties file, String key, String envVar) throws IOException {
        String value = setting(file, key, envVar).strip();
        if (value.isEmpty()) {
            throw new IOException("Missing database setting " + key
                    + ": copy config/db.properties.example to config/db.properties and fill it in,"
                    + " or set " + envVar + ".");
        }
        return value;
    }

    /** A record's default toString would print the password, so leave it out. */
    @Override
    public String toString() {
        return "DbConfig[url=" + url + ", user=" + user + "]";
    }
}
