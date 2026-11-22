package com.librarymanagement.services;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.database.repositories.UserRepository.UserRecord;
import com.librarymanagement.security.AuthenticationException;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.PasswordPolicy;
import com.librarymanagement.security.Session;
import com.librarymanagement.validation.ValidationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Sign in / sign out.
 * - One generic failure message for every cause (unknown user, wrong password, locked, disabled).
 * - Repeated failures lock the account for a configurable time (settings login.max_attempts / login.lockout_minutes).
 * - Every outcome is written to the audit log. Passwords and attempted usernames of unknown users are never logged.
 * - The sign-in time check uses an injected Clock so lockouts and expiry are testable.
 */
public class AuthService {

    public static final String GENERIC_FAILURE =
            "Invalid username or password, or the account is temporarily locked.";

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_LOCKOUT_MINUTES = 15;
    private static final int DEFAULT_TIMEOUT_MINUTES = 15;

    private final DatabaseManager db;
    private final UserRepository users;
    private final SettingsRepository settings;
    private final AuditRepository audit;
    private final PasswordHasher hasher;
    private final Clock clock;

    private volatile String dummyHash; // verified against when the user does not exist, so timing looks the same

    public AuthService(DatabaseManager db, UserRepository users, SettingsRepository settings,
                       AuditRepository audit, PasswordHasher hasher, Clock clock) {
        this.db = db;
        this.users = users;
        this.settings = settings;
        this.audit = audit;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** The password array is wiped before this method returns. */
    public Session login(String username, char[] password) {
        try {
            String name = username == null ? "" : username.trim();
            if (name.isEmpty() || password == null || password.length == 0) {
                throw new ValidationException("Enter your username and password.");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

            UserRecord user = name.length() > 50 ? null
                    : db.inTransactionless(c -> users.findByUsername(c, name)).orElse(null);

            // Always run one BCrypt verification, whatever the outcome, so response time does not hint at the cause.
            boolean tooLong = password.length > PasswordPolicy.MAX_LENGTH;
            boolean passwordOk = !tooLong && hasher.verify(password, user != null ? user.passwordHash() : dummyHash());

            if (user == null) {
                recordFailure(null, "reason=unknown_user");
                throw new AuthenticationException(GENERIC_FAILURE);
            }
            if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
                recordFailure(user.id(), "reason=account_locked");
                throw new AuthenticationException(GENERIC_FAILURE);
            }
            if (!user.active()) {
                recordFailure(user.id(), "reason=account_disabled");
                throw new AuthenticationException(GENERIC_FAILURE);
            }
            if (!passwordOk) {
                registerBadPassword(user, now);
                throw new AuthenticationException(GENERIC_FAILURE);
            }
            return db.inTransaction(c -> {
                users.recordSuccessfulLogin(c, user.id(), now);
                var permissions = users.permissionsForRole(c, user.roleId());
                int minutes = clamp(settings.getInt(c, "session.timeout_minutes", DEFAULT_TIMEOUT_MINUTES), 1, 480);
                audit.append(c, user.id(), "LOGIN_SUCCESS", "user", String.valueOf(user.id()), null);
                return new Session(user.id(), user.username(), user.fullName(), user.roleName(),
                        permissions, Duration.ofMinutes(minutes), now, user.mustChangePassword());
            });
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public void logout(Session session) {
        end(session, "LOGOUT");
    }

    /** Called when the inactivity timeout ended the session. */
    public void expire(Session session) {
        end(session, "SESSION_EXPIRED");
    }

    public Clock clock() {
        return clock;
    }

    // ------------------------------------------------------------------ internals

    private void end(Session session, String action) {
        if (!session.isActive()) return; // already ended: do not log twice
        session.end();
        db.inTransaction(c -> {
            audit.append(c, session.userId(), action, "user", String.valueOf(session.userId()), null);
            return null;
        });
    }

    private void registerBadPassword(UserRecord user, Instant now) {
        db.inTransaction(c -> {
            int max = Math.max(1, settings.getInt(c, "login.max_attempts", DEFAULT_MAX_ATTEMPTS));
            int minutes = Math.max(1, settings.getInt(c, "login.lockout_minutes", DEFAULT_LOCKOUT_MINUTES));
            int attempts = users.incrementFailedAttempts(c, user.id());
            audit.append(c, user.id(), "LOGIN_FAILED", "user", String.valueOf(user.id()),
                    "reason=bad_password attempts=" + attempts);
            if (attempts >= max) {
                users.lock(c, user.id(), now.plus(Duration.ofMinutes(minutes)));
                audit.append(c, user.id(), "ACCOUNT_LOCKED", "user", String.valueOf(user.id()),
                        "minutes=" + minutes);
            }
            return null;
        });
    }

    private void recordFailure(Long userId, String details) {
        db.inTransaction(c -> {
            audit.append(c, userId, "LOGIN_FAILED", userId == null ? null : "user",
                    userId == null ? null : String.valueOf(userId), details);
            return null;
        });
    }

    private String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            h = hasher.hash("not-a-real-password-0Aa".toCharArray());
            dummyHash = h;
        }
        return h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
