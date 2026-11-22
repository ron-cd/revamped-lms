package com.librarymanagement;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SetupServiceTest {

    @TempDir Path tmp;
    DatabaseManager db;
    SetupService service;
    PasswordHasher hasher = new PasswordHasher();

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        service = new SetupService(db, new UserRepository(), new AuditRepository(), hasher);
    }

    @Test
    void setupIsRequiredUntilAdminExists() {
        assertTrue(service.isSetupRequired());
        service.createInitialAdministrator("admin.user", "Admin User", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray());
        assertFalse(service.isSetupRequired());
    }

    @Test
    void passwordIsHashedNotStoredInPlaintext() throws Exception {
        service.createInitialAdministrator("admin", "Admin User", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray());
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT password_hash, role_id FROM users WHERE username='admin'")) {
            assertTrue(rs.next());
            String stored = rs.getString(1);
            assertNotEquals("Str0ngPassw0rd", stored);
            assertTrue(stored.startsWith("$2"), "expected a BCrypt hash");
            assertTrue(hasher.verify("Str0ngPassw0rd".toCharArray(), stored));
            assertFalse(hasher.verify("wrong".toCharArray(), stored));
            assertEquals(1, rs.getInt(2), "must be ADMINISTRATOR role");
        }
    }

    @Test
    void auditEntryWritten_withoutPassword() throws Exception {
        service.createInitialAdministrator("admin", "Admin User", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray());
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT action, details FROM audit_logs")) {
            assertTrue(rs.next());
            assertEquals("INITIAL_ADMIN_CREATED", rs.getString(1));
            assertFalse(rs.getString(2).contains("Str0ng"));
        }
    }

    @Test
    void weakOrMismatchedPasswordRejected() {
        assertThrows(ValidationException.class, () ->
                service.createInitialAdministrator("admin", "A", "short".toCharArray(), "short".toCharArray()));
        assertThrows(ValidationException.class, () ->
                service.createInitialAdministrator("admin", "A", "Str0ngPassw0rd".toCharArray(), "Different1Passw0rd".toCharArray()));
        assertTrue(service.isSetupRequired());
    }

    @Test
    void invalidUsernameRejected() {
        assertThrows(ValidationException.class, () ->
                service.createInitialAdministrator("a b;--", "A", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray()));
    }

    @Test
    void secondSetupIsRefused() {
        service.createInitialAdministrator("admin", "A", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray());
        assertThrows(BusinessRuleException.class, () ->
                service.createInitialAdministrator("other", "B", "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray()));
    }

    @Test
    void passwordArraysAreWipedAfterUse() {
        char[] pw = "Str0ngPassw0rd".toCharArray();
        char[] confirm = "Str0ngPassw0rd".toCharArray();
        service.createInitialAdministrator("admin", "A", pw, confirm);
        for (char ch : pw) assertEquals('\0', ch);
    }

    @Test
    void sqlInjectionInUsernameIsRejectedByValidation() {
        assertThrows(ValidationException.class, () ->
                service.createInitialAdministrator("x'); DROP TABLE users;--", "A",
                        "Str0ngPassw0rd".toCharArray(), "Str0ngPassw0rd".toCharArray()));
        assertTrue(service.isSetupRequired());
    }
}
