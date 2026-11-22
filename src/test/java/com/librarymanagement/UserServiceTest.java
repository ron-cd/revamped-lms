package com.librarymanagement;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.AuthenticationException;
import com.librarymanagement.security.AuthorizationException;
import com.librarymanagement.security.PasswordGenerator;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.PasswordPolicy;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.services.PageResult;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.services.UserService;
import com.librarymanagement.services.UserService.UserSummary;
import com.librarymanagement.validation.BusinessRuleException;
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

class UserServiceTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-22T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    static final String PW = "Str0ngPassw0rd";
    static final String TEMP = "TempPassw0rd9";
    static final int ADMIN = 1, LIBRARIAN = 2, ASSISTANT = 3;

    @TempDir Path tmp;
    DatabaseManager db;
    MutableClock clock;
    PasswordHasher hasher = new PasswordHasher();
    UserRepository repo = new UserRepository();
    AuthService auth;
    UserService svc;
    Session admin;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        clock = new MutableClock();
        AuditRepository audit = new AuditRepository();
        new SetupService(db, repo, audit, hasher)
                .createInitialAdministrator("admin", "Admin User", PW.toCharArray(), PW.toCharArray());
        auth = new AuthService(db, repo, new SettingsRepository(), audit, hasher, clock);
        svc = new UserService(db, repo, audit, hasher, clock);
        admin = auth.login("admin", PW.toCharArray());
    }

    // ---- helpers
    char[] pw(String s) { return s.toCharArray(); }

    /** Adds a user whose password is already permanent (no forced change). */
    long addPermanent(String username, int roleId) {
        return db.inTransaction(c -> repo.insert(c, username, hasher.hash(pw(PW)), "Test " + username, roleId));
    }

    long idOf(String username) throws Exception {
        return count("SELECT id FROM users WHERE username = ?", username);
    }

    int count(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    void sql(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    int audits(String action) throws Exception {
        return count("SELECT COUNT(*) FROM audit_logs WHERE action = ?", action);
    }

    // ---- creating users
    @Test
    void adminCreatesUserWithTemporaryPasswordThatMustBeChanged() throws Exception {
        long id = svc.create(admin, "maria", "Maria Santos", LIBRARIAN, pw(TEMP));
        assertTrue(id > 0);
        assertEquals(1, count("SELECT must_change_password FROM users WHERE username = 'maria'"));
        assertEquals(1, audits("USER_CREATED"));

        Session first = auth.login("maria", pw(TEMP));
        assertTrue(first.mustChangePassword());
        // until the password is changed nothing else is allowed
        assertThrows(AuthorizationException.class, () -> first.require(com.librarymanagement.security.Permission.BOOK_VIEW));
        assertFalse(first.has(com.librarymanagement.security.Permission.BOOK_VIEW));
    }

    @Test
    void createRejectsDuplicateUsernameCaseInsensitively() throws Exception {
        svc.create(admin, "maria", "Maria Santos", LIBRARIAN, pw(TEMP));
        ValidationException e = assertThrows(ValidationException.class,
                () -> svc.create(admin, "MARIA", "Other", ASSISTANT, pw(TEMP)));
        assertTrue(e.getMessage().contains("already taken"));
    }

    @Test
    void createValidatesUsernameNameRoleAndPassword() throws Exception {
        assertThrows(ValidationException.class, () -> svc.create(admin, "a b", "X", LIBRARIAN, pw(TEMP)));
        assertThrows(ValidationException.class, () -> svc.create(admin, "ok.user", "", LIBRARIAN, pw(TEMP)));
        assertThrows(ValidationException.class, () -> svc.create(admin, "ok.user", "X", 99, pw(TEMP)));
        assertThrows(ValidationException.class, () -> svc.create(admin, "ok.user", "X", LIBRARIAN, pw("weak")));
        assertThrows(ValidationException.class,
                () -> svc.create(admin, "x'); DROP TABLE users;--", "X", LIBRARIAN, pw(TEMP)));
        assertEquals(1, count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void createWipesPasswordArray() throws Exception {
        char[] p = pw(TEMP);
        svc.create(admin, "maria", "Maria Santos", LIBRARIAN, p);
        for (char ch : p) assertEquals('\0', ch);
    }

    // ---- permissions
    @Test
    void onlyAdministratorsMayManageUsers() throws Exception {
        addPermanent("lib", LIBRARIAN);
        addPermanent("asst", ASSISTANT);
        Session lib = auth.login("lib", pw(PW));
        Session asst = auth.login("asst", pw(PW));

        for (Session s : new Session[]{lib, asst}) {
            assertThrows(AuthorizationException.class, () -> svc.list(s, "", 0, 10));
            assertThrows(AuthorizationException.class, () -> svc.roles(s));
            assertThrows(AuthorizationException.class, () -> svc.create(s, "x.user", "X", ASSISTANT, pw(TEMP)));
            assertThrows(AuthorizationException.class, () -> svc.update(s, 1, "Hacked", ADMIN));
            assertThrows(AuthorizationException.class, () -> svc.setActive(s, 1, false));
            assertThrows(AuthorizationException.class, () -> svc.resetPassword(s, 1, pw(TEMP)));
            assertThrows(AuthorizationException.class, () -> svc.unlock(s, 1));
        }
    }

    // ---- listing / search
    @Test
    void listSearchesAndPages() throws Exception {
        for (int i = 1; i <= 7; i++) svc.create(admin, "user" + i, "Person " + i, ASSISTANT, pw(TEMP));
        PageResult<UserSummary> first = svc.list(admin, "", 0, 5);
        assertEquals(8, first.total());
        assertEquals(5, first.items().size());
        assertEquals(3, svc.list(admin, "", 5, 5).items().size());
        assertEquals(1, svc.list(admin, "Person 3", 0, 10).total());
        assertEquals(1, svc.list(admin, "USER3", 0, 10).total()); // case-insensitive
    }

    @Test
    void searchWildcardsAreTreatedLiterally() throws Exception {
        svc.create(admin, "user1", "Person 1", ASSISTANT, pw(TEMP));
        assertEquals(0, svc.list(admin, "%", 0, 10).total());
        assertEquals(0, svc.list(admin, "_", 0, 10).total());
        assertEquals(0, svc.list(admin, "' OR 1=1 --", 0, 10).total());
    }

    @Test
    void listNeverExposesPasswordHash() throws Exception {
        PageResult<UserSummary> r = svc.list(admin, "", 0, 10);
        String text = r.items().toString();
        assertFalse(text.contains("$2"), "bcrypt hash must not appear in list rows");
    }

    // ---- editing and the role rules
    @Test
    void adminChangesRoleAndNameWithAuditTrail() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        svc.update(admin, id, "New Name", ASSISTANT);
        assertEquals(3, count("SELECT role_id FROM users WHERE id = ?", id));
        assertEquals(1, audits("USER_UPDATED"));
        assertEquals(1, audits("ROLE_CHANGED"));
        // new role takes effect at next sign-in
        Session s = auth.login("lib", pw(PW));
        assertFalse(s.has(com.librarymanagement.security.Permission.BOOK_MANAGE));
    }

    @Test
    void nameOnlyChangeDoesNotLogRoleChange() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        svc.update(admin, id, "Renamed", LIBRARIAN);
        assertEquals(1, audits("USER_UPDATED"));
        assertEquals(0, audits("ROLE_CHANGED"));
    }

    @Test
    void cannotChangeOwnRole() throws Exception {
        long me = idOf("admin");
        assertThrows(BusinessRuleException.class, () -> svc.update(admin, me, "Admin User", LIBRARIAN));
        assertEquals(ADMIN, count("SELECT role_id FROM users WHERE id = ?", me));
        // renaming yourself is fine
        assertDoesNotThrow(() -> svc.update(admin, me, "Admin Renamed", ADMIN));
    }

    @Test
    void updateRejectsBadInput() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        assertThrows(ValidationException.class, () -> svc.update(admin, id, "", LIBRARIAN));
        assertThrows(ValidationException.class, () -> svc.update(admin, id, "Ok", 99));
        assertThrows(ValidationException.class, () -> svc.update(admin, 9999, "Ok", LIBRARIAN));
    }

    // ---- activate / deactivate
    @Test
    void deactivatedUserCannotSignInUntilReactivated() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        svc.setActive(admin, id, false);
        assertThrows(AuthenticationException.class, () -> auth.login("lib", pw(PW)));
        assertEquals(1, audits("USER_DEACTIVATED"));

        svc.setActive(admin, id, true);
        assertNotNull(auth.login("lib", pw(PW)));
        assertEquals(1, audits("USER_ACTIVATED"));
    }

    @Test
    void settingSameStateAddsNoAuditNoise() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        svc.setActive(admin, id, true); // already active
        assertEquals(0, audits("USER_ACTIVATED"));
    }

    @Test
    void cannotDeactivateYourself() throws Exception {
        long me = idOf("admin");
        assertThrows(BusinessRuleException.class, () -> svc.setActive(admin, me, false));
        assertEquals(1, count("SELECT is_active FROM users WHERE id = ?", me));
    }

    @Test
    void lastActiveAdministratorIsProtected() throws Exception {
        long adminId = idOf("admin");
        long second = addPermanent("admin2", ADMIN);
        Session s2 = auth.login("admin2", pw(PW));

        // the original admin becomes inactive while admin2's session is still running
        svc.setActive(s2, adminId, false);
        // admin2 is now the only active administrator: even an attempt to demote them is refused
        assertThrows(BusinessRuleException.class, () -> svc.setActive(admin, second, false));
        assertEquals(1, count("SELECT is_active FROM users WHERE id = ?", second));
        // (admin's own session is still valid here only because sessions are in memory; the guard is what matters)
    }

    // ---- reset password / unlock
    @Test
    void resetPasswordSetsTemporaryPasswordAndClearsLockout() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login("lib", pw("WrongPassw0rd")));
        }
        assertThrows(AuthenticationException.class, () -> auth.login("lib", pw(PW))); // locked

        svc.resetPassword(admin, id, pw(TEMP));
        assertEquals(1, audits("PASSWORD_RESET"));
        assertThrows(AuthenticationException.class, () -> auth.login("lib", pw(PW))); // old password gone
        Session s = auth.login("lib", pw(TEMP)); // lock cleared, new password works
        assertTrue(s.mustChangePassword());
    }

    @Test
    void adminCannotResetOwnPasswordFromTheAdminScreen() throws Exception {
        long me = idOf("admin");
        assertThrows(BusinessRuleException.class, () -> svc.resetPassword(admin, me, pw(TEMP)));
    }

    @Test
    void resetRejectsWeakPassword() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        assertThrows(ValidationException.class, () -> svc.resetPassword(admin, id, pw("weak")));
    }

    @Test
    void unlockClearsLockoutImmediately() throws Exception {
        long id = addPermanent("lib", LIBRARIAN);
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationException.class, () -> auth.login("lib", pw("WrongPassw0rd")));
        }
        assertTrue(svc.list(admin, "lib", 0, 10).items().get(0).locked());
        svc.unlock(admin, id);
        assertFalse(svc.list(admin, "lib", 0, 10).items().get(0).locked());
        assertNotNull(auth.login("lib", pw(PW)));
        assertEquals(1, audits("USER_UNLOCKED"));
    }

    // ---- changing your own password
    @Test
    void changingTemporaryPasswordLiftsRestrictions() throws Exception {
        svc.create(admin, "maria", "Maria Santos", LIBRARIAN, pw(TEMP));
        Session s = auth.login("maria", pw(TEMP));
        svc.changeOwnPassword(s, pw(TEMP), pw("BrandNewPassw0rd"), pw("BrandNewPassw0rd"));

        assertFalse(s.mustChangePassword());
        assertTrue(s.has(com.librarymanagement.security.Permission.BOOK_MANAGE));
        assertEquals(1, audits("PASSWORD_CHANGED"));
        assertThrows(AuthenticationException.class, () -> auth.login("maria", pw(TEMP)));
        assertNotNull(auth.login("maria", pw("BrandNewPassw0rd")));
    }

    @Test
    void changeOwnPasswordChecksCurrentPolicyMatchAndDifference() throws Exception {
        addPermanent("lib", LIBRARIAN);
        Session s = auth.login("lib", pw(PW));
        assertThrows(ValidationException.class,
                () -> svc.changeOwnPassword(s, pw("WrongCurrent1"), pw("BrandNewPassw0rd"), pw("BrandNewPassw0rd")));
        assertThrows(ValidationException.class,
                () -> svc.changeOwnPassword(s, pw(PW), pw("weak"), pw("weak")));
        assertThrows(ValidationException.class,
                () -> svc.changeOwnPassword(s, pw(PW), pw("BrandNewPassw0rd"), pw("DifferentPassw0rd")));
        assertThrows(ValidationException.class,
                () -> svc.changeOwnPassword(s, pw(PW), pw(PW), pw(PW)));
        // still the old password
        assertNotNull(auth.login("lib", pw(PW)));
    }

    @Test
    void endedSessionCannotChangePassword() throws Exception {
        addPermanent("lib", LIBRARIAN);
        Session s = auth.login("lib", pw(PW));
        auth.logout(s);
        assertThrows(AuthorizationException.class,
                () -> svc.changeOwnPassword(s, pw(PW), pw("BrandNewPassw0rd"), pw("BrandNewPassw0rd")));
    }

    // ---- audit hygiene and generator
    @Test
    void auditLogNeverContainsPasswords() throws Exception {
        long id = svc.create(admin, "maria", "Maria Santos", LIBRARIAN, pw(TEMP));
        svc.resetPassword(admin, id, pw("AnotherTemp8x"));
        Session s = auth.login("maria", pw("AnotherTemp8x"));
        svc.changeOwnPassword(s, pw("AnotherTemp8x"), pw("BrandNewPassw0rd"), pw("BrandNewPassw0rd"));
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(details,'')||COALESCE(old_value,'')||COALESCE(new_value,'') FROM audit_logs")) {
            while (rs.next()) {
                String row = rs.getString(1);
                for (String secret : new String[]{TEMP, "AnotherTemp8x", "BrandNewPassw0rd", PW}) {
                    assertFalse(row.contains(secret), "audit row leaks a password: " + row);
                }
            }
        }
    }

    @Test
    void generatedPasswordsSatisfyThePolicyAndDiffer() throws Exception {
        String a = PasswordGenerator.generate();
        String b = PasswordGenerator.generate();
        assertEquals(12, a.length());
        assertNotEquals(a, b);
        for (int i = 0; i < 200; i++) {
            assertTrue(PasswordPolicy.check(PasswordGenerator.generate().toCharArray(), "someone").isEmpty());
        }
    }

    @Test
    void rolesListContainsTheThreeRoles() throws Exception {
        var roles = svc.roles(admin);
        assertEquals(3, roles.size());
        assertEquals("Administrator", roles.get(0).label());
    }
}
