package com.librarymanagement;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.AuthenticationException;
import com.librarymanagement.security.AuthorizationException;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-21T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    static final String PW = "Str0ngPassw0rd";

    @TempDir Path tmp;
    DatabaseManager db;
    MutableClock clock;
    PasswordHasher hasher = new PasswordHasher();
    UserRepository users = new UserRepository();
    AuthService auth;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        clock = new MutableClock();
        AuditRepository audit = new AuditRepository();
        new SetupService(db, users, audit, hasher)
                .createInitialAdministrator("admin", "Admin User", PW.toCharArray(), PW.toCharArray());
        auth = new AuthService(db, users, new SettingsRepository(), audit, hasher, clock);
    }

    // ---- helpers
    long addUser(String username, int roleId) {
        return db.inTransaction(c -> users.insert(c, username, hasher.hash(PW.toCharArray()), "Test " + username, roleId));
    }

    void sql(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    int count(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    int audits(String action) throws Exception {
        return count("SELECT COUNT(*) FROM audit_logs WHERE action = ?", action);
    }

    char[] pw(String s) { return s.toCharArray(); }

    // ---- tests
    @Test
    void successfulLoginReturnsSessionWithRolePermissions() throws Exception {
        Session s = auth.login("admin", pw(PW));
        assertEquals("admin", s.username());
        assertEquals("Administrator", s.roleLabel());
        assertTrue(s.has(Permission.USER_MANAGE));
        assertTrue(s.has(Permission.BACKUP_MANAGE));
        assertEquals(Duration.ofMinutes(15), s.timeout());
        assertEquals(1, audits("LOGIN_SUCCESS"));
    }

    @Test
    void usernameIsCaseInsensitive() {
        assertNotNull(auth.login("ADMIN", pw(PW)));
    }

    @Test
    void unknownUserAndWrongPasswordGiveIdenticalMessage() {
        AuthenticationException unknown = assertThrows(AuthenticationException.class,
                () -> auth.login("nobody", pw(PW)));
        AuthenticationException wrong = assertThrows(AuthenticationException.class,
                () -> auth.login("admin", pw("WrongPassw0rd")));
        assertEquals(unknown.getMessage(), wrong.getMessage());
        assertEquals(AuthService.GENERIC_FAILURE, wrong.getMessage());
    }

    @Test
    void emptyCredentialsAreRejectedAsValidationErrors() {
        assertThrows(ValidationException.class, () -> auth.login("", pw(PW)));
        assertThrows(ValidationException.class, () -> auth.login("admin", pw("")));
        assertThrows(ValidationException.class, () -> auth.login(null, pw(PW)));
    }

    @Test
    void accountLocksAfterFiveFailuresEvenForCorrectPassword_thenUnlocksAfterTimeout() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("WrongPassw0rd")));
        }
        assertEquals(1, audits("ACCOUNT_LOCKED"));
        // correct password is refused while locked, with the same generic message
        AuthenticationException e = assertThrows(AuthenticationException.class, () -> auth.login("admin", pw(PW)));
        assertEquals(AuthService.GENERIC_FAILURE, e.getMessage());

        clock.advance(Duration.ofMinutes(14));
        assertThrows(AuthenticationException.class, () -> auth.login("admin", pw(PW)));
        clock.advance(Duration.ofMinutes(2)); // 16 minutes total
        assertNotNull(auth.login("admin", pw(PW)));
    }

    @Test
    void lockoutThresholdComesFromSettings() throws Exception {
        sql("UPDATE system_settings SET value = '2' WHERE key = 'login.max_attempts'");
        assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("WrongPassw0rd")));
        assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("WrongPassw0rd")));
        assertEquals(1, audits("ACCOUNT_LOCKED"));
    }

    @Test
    void successfulLoginResetsFailedAttempts() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("WrongPassw0rd")));
        }
        assertNotNull(auth.login("admin", pw(PW)));
        assertEquals(0, count("SELECT failed_attempts FROM users WHERE username = 'admin'"));
        // four more failures must not lock (counter restarted)
        for (int i = 0; i < 4; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("WrongPassw0rd")));
        }
        assertEquals(0, audits("ACCOUNT_LOCKED"));
    }

    @Test
    void disabledAccountCannotSignIn() throws Exception {
        sql("UPDATE users SET is_active = 0 WHERE username = 'admin'");
        assertThrows(AuthenticationException.class, () -> auth.login("admin", pw(PW)));
    }

    @Test
    void sqlInjectionInUsernameFails() throws Exception {
        assertThrows(AuthenticationException.class, () -> auth.login("' OR '1'='1", pw(PW)));
        assertThrows(AuthenticationException.class, () -> auth.login("admin' --", pw(PW)));
        assertEquals(1, count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void extremelyLongInputsAreRejectedSafely() {
        assertThrows(AuthenticationException.class, () -> auth.login("a".repeat(500), pw(PW)));
        assertThrows(AuthenticationException.class, () -> auth.login("admin", pw("Aa1".repeat(100))));
    }

    @Test
    void passwordArrayIsWipedAndNeverAudited() throws Exception {
        char[] p = pw(PW);
        auth.login("admin", p);
        for (char ch : p) assertEquals('\0', ch);
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(details,'') || COALESCE(old_value,'') || COALESCE(new_value,'') FROM audit_logs")) {
            while (rs.next()) assertFalse(rs.getString(1).contains("Str0ng"));
        }
    }

    @Test
    void failedAttemptsAreAuditedWithoutLeakingUnknownUsernames() throws Exception {
        assertThrows(AuthenticationException.class, () -> auth.login("secret-looking-name", pw(PW)));
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT details FROM audit_logs WHERE action = 'LOGIN_FAILED'")) {
            assertTrue(rs.next());
            assertEquals("reason=unknown_user", rs.getString(1));
        }
    }

    @Test
    void logoutEndsSessionAndIsAuditedOnce() throws Exception {
        Session s = auth.login("admin", pw(PW));
        auth.logout(s);
        auth.logout(s); // second call must not add another entry
        assertFalse(s.isActive());
        assertEquals(1, audits("LOGOUT"));
        assertThrows(AuthorizationException.class, () -> s.require(Permission.DASHBOARD_VIEW));
    }

    @Test
    void sessionExpiresAfterInactivityAndActivityKeepsItAlive() {
        Session s = auth.login("admin", pw(PW));
        clock.advance(Duration.ofMinutes(10));
        assertFalse(s.isExpired(clock.instant()));
        s.touch(clock.instant());                  // user activity
        clock.advance(Duration.ofMinutes(10));
        assertFalse(s.isExpired(clock.instant())); // 10 min since last activity
        clock.advance(Duration.ofMinutes(5));
        assertTrue(s.isExpired(clock.instant()));  // 15 min since last activity
    }

    @Test
    void expiryIsAuditedAndDisablesTheSession() throws Exception {
        Session s = auth.login("admin", pw(PW));
        auth.expire(s);
        assertEquals(1, audits("SESSION_EXPIRED"));
        assertFalse(s.has(Permission.DASHBOARD_VIEW));
    }

    @Test
    void sessionTimeoutComesFromSettings() throws Exception {
        sql("UPDATE system_settings SET value = '5' WHERE key = 'session.timeout_minutes'");
        assertEquals(Duration.ofMinutes(5), auth.login("admin", pw(PW)).timeout());
    }

    @Test
    void rolesGetTheirOwnPermissions() {
        addUser("lib", 2);
        addUser("asst", 3);
        Session librarian = auth.login("lib", pw(PW));
        Session assistant = auth.login("asst", pw(PW));

        assertTrue(librarian.has(Permission.BOOK_MANAGE));
        assertTrue(librarian.has(Permission.REPORT_VIEW));
        assertFalse(librarian.has(Permission.USER_MANAGE));
        assertFalse(librarian.has(Permission.BACKUP_MANAGE));
        assertFalse(librarian.has(Permission.AUDIT_VIEW));
        assertFalse(librarian.has(Permission.TRANSACTION_CORRECT));

        assertTrue(assistant.has(Permission.CIRCULATION_ISSUE));
        assertTrue(assistant.has(Permission.BOOK_VIEW));
        assertFalse(assistant.has(Permission.BOOK_MANAGE));
        assertFalse(assistant.has(Permission.STUDENT_MANAGE));
        assertFalse(assistant.has(Permission.REPORT_VIEW));
        assertFalse(assistant.has(Permission.SETTINGS_MANAGE));
    }

    @Test
    void requireThrowsForMissingPermission() {
        addUser("asst", 3);
        Session assistant = auth.login("asst", pw(PW));
        assertDoesNotThrow(() -> assistant.require(Permission.CIRCULATION_RETURN));
        assertThrows(AuthorizationException.class, () -> assistant.require(Permission.USER_MANAGE));
        assertThrows(AuthorizationException.class,
                () -> assistant.requireAny(Permission.USER_MANAGE, Permission.AUDIT_VIEW));
        assertDoesNotThrow(() -> assistant.requireAny(Permission.USER_MANAGE, Permission.BOOK_VIEW));
    }
}
