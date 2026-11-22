package com.librarymanagement;

import com.librarymanagement.database.DatabaseException;
import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class MigrationRunnerTest {

    @TempDir Path tmp;

    @Test
    void createsSchemaAndSeedsRolesOnFreshDatabase() throws Exception {
        DatabaseManager db = new DatabaseManager(tmp.resolve("t.db"));
        MigrationRunner runner = new MigrationRunner(db);
        assertEquals(1, runner.migrate());
        assertEquals(1, runner.currentVersion());
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM roles")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    void secondRunAppliesNothingAndKeepsData() throws Exception {
        DatabaseManager db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO categories(name) VALUES('Science')");
        }
        assertEquals(0, new MigrationRunner(db).migrate());
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM categories")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "existing data must survive a re-run");
        }
    }

    @Test
    void foreignKeysAreEnforcedOnEveryConnection() throws Exception {
        DatabaseManager db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> st.execute(
                    "INSERT INTO transactions(student_id, issued_by, issue_date) VALUES(999, 999, '2026-01-01')"));
        }
    }

    @Test
    void auditLogIsAppendOnly() throws Exception {
        DatabaseManager db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO audit_logs(action) VALUES('TEST')");
            assertThrows(java.sql.SQLException.class, () -> st.execute("DELETE FROM audit_logs"));
            assertThrows(java.sql.SQLException.class, () -> st.execute("UPDATE audit_logs SET action='X'"));
        }
    }

    @Test
    void failedTransactionRollsBack() throws Exception {
        DatabaseManager db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        assertThrows(DatabaseException.class, () -> db.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO categories(name) VALUES('A')");
                st.execute("INSERT INTO categories(name) VALUES('A')"); // duplicate -> fails
            }
            return null;
        }));
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM categories")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "first insert must have been rolled back");
        }
    }
}
