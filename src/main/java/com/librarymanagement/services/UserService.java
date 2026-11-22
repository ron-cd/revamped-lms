package com.librarymanagement.services;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.database.repositories.UserRepository.UserRecord;
import com.librarymanagement.database.repositories.UserRepository.UserRow;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.PasswordPolicy;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.UserRules;
import com.librarymanagement.validation.ValidationException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * User account management (administrators only) plus every user's own password change.
 * Rules enforced here, not in the UI:
 * - only users with USER_MANAGE may manage accounts;
 * - you cannot deactivate yourself, change your own role, or reset your own password from the admin screen;
 * - the last active administrator can never be deactivated or demoted;
 * - new and reset accounts get a temporary password that must be changed at the next sign-in;
 * - everything is audited; passwords never appear in the audit log.
 */
public class UserService {

    public record RoleOption(int id, String name, String label) {}

    public record UserSummary(long id, String username, String fullName, int roleId, String roleName,
                              boolean active, boolean locked, String lastLoginAt, boolean mustChangePassword) {}

    private static final String LAST_ADMIN_MESSAGE = "At least one active administrator is required.";

    private final DatabaseManager db;
    private final UserRepository users;
    private final AuditRepository audit;
    private final PasswordHasher hasher;
    private final Clock clock;

    public UserService(DatabaseManager db, UserRepository users, AuditRepository audit,
                       PasswordHasher hasher, Clock clock) {
        this.db = db;
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    public PageResult<UserSummary> list(Session session, String search, int offset, int limit) {
        session.require(Permission.USER_MANAGE);
        Instant now = clock.instant();
        return db.inTransactionless(c -> {
            List<UserSummary> items = new ArrayList<>();
            for (UserRow r : users.list(c, search, Math.max(0, offset), Math.max(1, limit))) {
                boolean locked = r.lockedUntil() != null && r.lockedUntil().isAfter(now);
                items.add(new UserSummary(r.id(), r.username(), r.fullName(), r.roleId(), r.roleName(),
                        r.active(), locked, r.lastLoginAt(), r.mustChangePassword()));
            }
            return new PageResult<>(items, users.countMatching(c, search));
        });
    }

    public List<RoleOption> roles(Session session) {
        session.require(Permission.USER_MANAGE);
        return db.inTransactionless(c -> {
            List<RoleOption> options = new ArrayList<>();
            for (var r : users.listRoles(c)) options.add(new RoleOption(r.id(), r.name(), label(r.name())));
            return options;
        });
    }

    // ------------------------------------------------------------------ account management

    /** Creates an account with a temporary password the user must change at first sign-in. */
    public long create(Session session, String username, String fullName, int roleId, char[] temporaryPassword) {
        try {
            session.require(Permission.USER_MANAGE);
            String u = trim(username);
            String name = trim(fullName);

            List<String> problems = new ArrayList<>(UserRules.check(u, name));
            problems.addAll(PasswordPolicy.check(temporaryPassword, u));
            if (!problems.isEmpty()) throw new ValidationException(problems);

            String hash = hasher.hash(temporaryPassword);
            return db.inTransaction(c -> {
                if (!users.roleExists(c, roleId)) throw new ValidationException("Select a valid role.");
                if (users.findByUsername(c, u).isPresent()) throw new ValidationException("That username is already taken.");
                long id = users.insert(c, u, hash, name, roleId, true);
                String role = roleName(c, roleId);
                audit.append(c, session.userId(), "USER_CREATED", "user", String.valueOf(id),
                        "username=" + u + " role=" + role, null, role);
                return id;
            });
        } finally {
            wipe(temporaryPassword);
        }
    }

    /** Changes the display name and role. */
    public void update(Session session, long userId, String fullName, int roleId) {
        session.require(Permission.USER_MANAGE);
        String name = trim(fullName);
        List<String> problems = UserRules.checkFullName(name);
        if (!problems.isEmpty()) throw new ValidationException(problems);

        db.inTransaction(c -> {
            UserRecord target = find(c, userId);
            boolean roleChanged = roleId != target.roleId();
            if (roleChanged) {
                if (target.id() == session.userId()) throw new BusinessRuleException("You cannot change your own role.");
                if (!users.roleExists(c, roleId)) throw new ValidationException("Select a valid role.");
                if (target.active() && target.roleId() == UserRepository.ADMINISTRATOR_ROLE_ID
                        && users.countOtherActiveAdministrators(c, target.id()) == 0) {
                    throw new BusinessRuleException(LAST_ADMIN_MESSAGE);
                }
            }
            users.updateProfile(c, userId, name, roleId);

            String oldRole = target.roleName();
            String newRole = roleChanged ? roleName(c, roleId) : oldRole;
            audit.append(c, session.userId(), "USER_UPDATED", "user", String.valueOf(userId),
                    "username=" + target.username(),
                    "name=" + target.fullName() + "; role=" + oldRole,
                    "name=" + name + "; role=" + newRole);
            if (roleChanged) {
                audit.append(c, session.userId(), "ROLE_CHANGED", "user", String.valueOf(userId),
                        "username=" + target.username(), oldRole, newRole);
            }
            return null;
        });
    }

    public void setActive(Session session, long userId, boolean active) {
        session.require(Permission.USER_MANAGE);
        db.inTransaction(c -> {
            UserRecord target = find(c, userId);
            if (target.active() == active) return null; // nothing to do
            if (!active) {
                if (target.id() == session.userId()) throw new BusinessRuleException("You cannot deactivate your own account.");
                if (target.roleId() == UserRepository.ADMINISTRATOR_ROLE_ID
                        && users.countOtherActiveAdministrators(c, target.id()) == 0) {
                    throw new BusinessRuleException(LAST_ADMIN_MESSAGE);
                }
            }
            users.setActive(c, userId, active);
            audit.append(c, session.userId(), active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "user",
                    String.valueOf(userId), "username=" + target.username(),
                    active ? "inactive" : "active", active ? "active" : "inactive");
            return null;
        });
    }

    /** Sets a new temporary password (must be changed at next sign-in) and clears any lockout. */
    public void resetPassword(Session session, long userId, char[] temporaryPassword) {
        try {
            session.require(Permission.USER_MANAGE);
            if (userId == session.userId()) {
                throw new BusinessRuleException("Use \"Change password\" in the account menu for your own account.");
            }
            UserRecord target = db.inTransactionless(c -> find(c, userId));
            List<String> problems = PasswordPolicy.check(temporaryPassword, target.username());
            if (!problems.isEmpty()) throw new ValidationException(problems);

            String hash = hasher.hash(temporaryPassword);
            db.inTransaction(c -> {
                users.setPassword(c, userId, hash, true);
                users.clearLock(c, userId);
                audit.append(c, session.userId(), "PASSWORD_RESET", "user", String.valueOf(userId),
                        "username=" + target.username());
                return null;
            });
        } finally {
            wipe(temporaryPassword);
        }
    }

    public void unlock(Session session, long userId) {
        session.require(Permission.USER_MANAGE);
        db.inTransaction(c -> {
            UserRecord target = find(c, userId);
            users.clearLock(c, userId);
            audit.append(c, session.userId(), "USER_UNLOCKED", "user", String.valueOf(userId),
                    "username=" + target.username());
            return null;
        });
    }

    // ------------------------------------------------------------------ own password

    /** Any signed-in user, including one who must replace a temporary password. */
    public void changeOwnPassword(Session session, char[] current, char[] newPassword, char[] confirm) {
        try {
            session.requireActive();
            UserRecord me = db.inTransactionless(c -> find(c, session.userId()));

            List<String> problems = new ArrayList<>();
            if (current == null || current.length == 0 || current.length > PasswordPolicy.MAX_LENGTH
                    || !hasher.verify(current, me.passwordHash())) {
                throw new ValidationException("Your current password is not correct.");
            }
            problems.addAll(PasswordPolicy.check(newPassword, me.username()));
            if (confirm == null || !Arrays.equals(newPassword, confirm)) problems.add("New passwords do not match.");
            if (newPassword != null && Arrays.equals(newPassword, current)) {
                problems.add("The new password must be different from the current one.");
            }
            if (!problems.isEmpty()) throw new ValidationException(problems);

            String hash = hasher.hash(newPassword);
            db.inTransaction(c -> {
                users.setPassword(c, me.id(), hash, false);
                audit.append(c, me.id(), "PASSWORD_CHANGED", "user", String.valueOf(me.id()), null);
                return null;
            });
            session.markPasswordChanged();
        } finally {
            wipe(current);
            wipe(newPassword);
            wipe(confirm);
        }
    }

    // ------------------------------------------------------------------ helpers

    private UserRecord find(java.sql.Connection c, long userId) throws java.sql.SQLException {
        return users.findById(c, userId).orElseThrow(() -> new ValidationException("That user no longer exists."));
    }

    private String roleName(java.sql.Connection c, int roleId) throws java.sql.SQLException {
        for (var r : users.listRoles(c)) if (r.id() == roleId) return r.name();
        throw new ValidationException("Select a valid role.");
    }

    static String label(String roleName) {
        String lower = roleName.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static void wipe(char[] chars) {
        if (chars != null) Arrays.fill(chars, '\0');
    }
}
