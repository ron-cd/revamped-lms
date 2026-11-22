package com.librarymanagement.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;

/**
 * Opens SQLite connections with the pragmas this application depends on, and offers a
 * helper that runs a block inside one transaction (commit on success, rollback on any failure).
 *
 * Connections are short-lived on purpose: SQLite is a local file, opening is cheap, and
 * this avoids a pool for a single-PC application.
 */
public class DatabaseManager {

    private final String url;
    private final boolean inMemoryShared;

    public DatabaseManager(Path dbFile) {
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        this.inMemoryShared = false;
    }

    /** For tests: a named shared in-memory database that lives while at least one connection is open. */
    public static DatabaseManager inMemory(String name) {
        return new DatabaseManager("jdbc:sqlite:file:" + name + "?mode=memory&cache=shared", true);
    }

    private DatabaseManager(String url, boolean inMemoryShared) {
        this.url = url;
        this.inMemoryShared = inMemoryShared;
    }

    public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            if (!inMemoryShared) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA synchronous = NORMAL");
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Runs work on a plain auto-commit connection (the caller manages its own transactions, e.g. migrations). */
    public <T> T inTransactionless(TxWork<T> work) {
        try (Connection c = getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DatabaseException("Database operation failed", e);
        }
    }

    /** Runs the work in a single transaction. Any exception rolls everything back. */
    public <T> T inTransaction(TxWork<T> work) {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { /* original error matters more */ }
                throw e instanceof RuntimeException re ? re : new DatabaseException("Database operation failed", e);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Could not open the database", e);
        }
    }
}
