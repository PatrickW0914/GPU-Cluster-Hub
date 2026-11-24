package edu.ucr.cs166.gpuhub.dao;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A query from sql/queries (Maven packs that folder into the jar). The same file runs in psql,
 * where :'now' is a psql variable. Here every :'name' becomes a ? placeholder, and its value is
 * bound by a PreparedStatement; values never become part of the SQL text.
 */
final class SqlFile {

    private static final Pattern PARAMETER = Pattern.compile(":'(\\w+)'");

    private final String sql;                                 // with ? placeholders
    private final List<String> names = new ArrayList<>();     // the parameter of each ?, in order

    private SqlFile(String psql) {
        Matcher m = PARAMETER.matcher(psql);
        while (m.find()) {
            names.add(m.group(1));
        }
        sql = m.replaceAll("?");
    }

    /** Loads a query, for example "Q1". */
    static SqlFile load(String id) {
        return new SqlFile(readQuery(id));
    }

    /** The query as it is in the file, without the lines that are only for psql. */
    static String readQuery(String id) {
        try (InputStream in = SqlFile.class.getResourceAsStream("/queries/" + id + ".sql")) {
            if (in == null) {
                throw new IllegalStateException("The query file " + id + ".sql is not in the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !isForPsqlOnly(line))
                    .collect(Collectors.joining("\n"))
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Comments, psql commands such as \if, and SET (the app's connection already has its schema). */
    private static boolean isForPsqlOnly(String line) {
        String s = line.strip();
        return s.startsWith("--") || s.startsWith("\\") || s.startsWith("SET ");
    }

    String sql() {
        return sql;
    }

    /** The values in the order of the ? placeholders; a name can occur several times. */
    Object[] values(Map<String, Object> byName) {
        return names.stream().map(name -> {
            Object value = byName.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Missing parameter " + name);
            }
            return value;
        }).toArray();
    }
}
