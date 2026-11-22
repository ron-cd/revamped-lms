package com.librarymanagement.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Applies versioned SQL migrations (classpath: db/migration/V001__name.sql, V002__...).
 * - Already-applied versions are skipped.
 * - Each migration runs in its own transaction.
 * - A checksum of every applied migration is stored; if a shipped file is later edited the
 *   app refuses to start rather than guessing. Existing data is never dropped or recreated.
 * Statements inside a file are separated by a line containing only "-- @@".
 */
public class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^-- @@\\s*$");
    private static final String LOCATION = "db/migration/";

    private final DatabaseManager db;
    private final String location;

    public MigrationRunner(DatabaseManager db) {
        this(db, LOCATION);
    }

    public MigrationRunner(DatabaseManager db, String location) {
        this.db = db;
        this.location = location.endsWith("/") ? location : location + "/";
    }

    /** @return number of migrations applied in this run */
    public int migrate() {
        return db.inTransactionless(this::migrateWith);
    }

    private int migrateWith(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version     INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    checksum    TEXT NOT NULL,
                    applied_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
                )""");
        }
        Map<Integer, String> applied = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM schema_migrations")) {
            while (rs.next()) applied.put(rs.getInt(1), rs.getString(2));
        }

        int count = 0;
        for (int version = 1; ; version++) {
            Resource res = findResource(version);
            if (res == null) break;

            String checksum = sha256(res.sql());
            String previous = applied.get(version);
            if (previous != null) {
                if (!previous.equals(checksum)) {
                    throw new DatabaseException("Migration V" + version + " was modified after it was applied. "
                            + "Create a new migration instead of editing an old one.", null);
                }
                continue;
            }
            apply(c, version, res, checksum);
            count++;
        }
        return count;
    }

    private void apply(Connection c, int version, Resource res, String checksum) throws SQLException {
        log.info("Applying migration V{} ({})", version, res.description());
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            for (String stmt : SEPARATOR.split(res.sql())) {
                if (!stripComments(stmt).isBlank()) st.execute(stmt);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schema_migrations(version, description, checksum) VALUES(?,?,?)")) {
                ps.setInt(1, version);
                ps.setString(2, res.description());
                ps.setString(3, checksum);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(previousAutoCommit);
        }
    }

    public int currentVersion() {
        return db.inTransactionless(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_migrations")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0; // table does not exist yet
            }
        });
    }

    private record Resource(String description, String sql) {}

    private Resource findResource(int version) {
        String prefix = String.format("V%03d__", version);
        // Known names are listed in index.txt so this also works from inside a packaged jar.
        try (InputStream idx = getClass().getClassLoader().getResourceAsStream(location + "index.txt")) {
            if (idx == null) throw new DatabaseException("Missing " + location + "index.txt", null);
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim();
                if (name.startsWith(prefix) && name.endsWith(".sql")) {
                    try (InputStream in = getClass().getClassLoader().getResourceAsStream(location + name)) {
                        if (in == null) throw new DatabaseException("Missing migration file " + name, null);
                        String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                        String desc = name.substring(prefix.length(), name.length() - 4).replace('_', ' ');
                        return new Resource(desc, sql);
                    }
                }
            }
            return null;
        } catch (IOException e) {
            throw new DatabaseException("Could not read migrations", e);
        }
    }

    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            if (!line.trim().startsWith("--")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
