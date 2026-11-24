package com.librarymanagement.database.repositories;

import com.librarymanagement.database.SqlText;
import com.librarymanagement.security.Permission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SQL for users and roles, always called with a caller-supplied connection so services control transactions. */
public class UserRepository {

    public static final int ADMINISTRATOR_ROLE_ID = 1;

    /** One row of the users table joined with its role name. */
    public record UserRecord(long id, String username, String passwordHash, String fullName, int roleId,
                             String roleName, boolean active, int failedAttempts, Instant lockedUntil,
                             boolean mustChangePassword) {}

    /** A user as shown in lists. Never includes the password hash. */
    public record UserRow(long id, String username, String fullName, int roleId, String roleName, boolean active,
                          Instant lockedUntil, String lastLoginAt, boolean mustChangePassword) {}

    public record RoleRow(int id, String name) {}

    private static final String SELECT_USER = """
            SELECT u.id, u.username, u.password_hash, u.full_name, u.role_id, r.name,
                   u.is_active, u.failed_attempts, u.locked_until, u.must_change_password
            FROM users u JOIN roles r ON r.id = u.role_id
            """;

    public long count(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public long insert(Connection c, String username, String passwordHash, String fullName, int roleId)
            throws SQLException {
        return insert(c, username, passwordHash, fullName, roleId, false);
    }

    public long insert(Connection c, String username, String passwordHash, String fullName, int roleId,
                       boolean mustChangePassword) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(username, password_hash, full_name, role_id, must_change_password) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, fullName);
            ps.setInt(4, roleId);
            ps.setInt(5, mustChangePassword ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Username matching is case-insensitive (the column is declared COLLATE NOCASE). */
    public Optional<UserRecord> findByUsername(Connection c, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_USER + "WHERE u.username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<UserRecord> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_USER + "WHERE u.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static UserRecord map(ResultSet rs) throws SQLException {
        String locked = rs.getString(9);
        return new UserRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getInt(7) == 1, rs.getInt(8),
                locked == null ? null : Instant.parse(locked), rs.getInt(10) == 1);
    }

    // ------------------------------------------------------------------ listing

    static String likePattern(String search) {
        return SqlText.likeContains(search);
    }

    public List<UserRow> list(Connection c, String search, int offset, int limit) throws SQLException {
        String pattern = likePattern(search);
        List<UserRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT u.id, u.username, u.full_name, u.role_id, r.name, u.is_active,
                       u.locked_until, u.last_login_at, u.must_change_password
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.username LIKE ? ESCAPE '\\' OR u.full_name LIKE ? ESCAPE '\\'
                ORDER BY u.username COLLATE NOCASE
                LIMIT ? OFFSET ?""")) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String locked = rs.getString(7);
                    rows.add(new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getString(5), rs.getInt(6) == 1, locked == null ? null : Instant.parse(locked),
                            rs.getString(8), rs.getInt(9) == 1));
                }
            }
        }
        return rows;
    }

    public int countMatching(Connection c, String search) throws SQLException {
        String pattern = likePattern(search);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE username LIKE ? ESCAPE '\\' OR full_name LIKE ? ESCAPE '\\'")) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ roles

    public List<RoleRow> listRoles(Connection c) throws SQLException {
        List<RoleRow> roles = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name FROM roles ORDER BY id")) {
            while (rs.next()) roles.add(new RoleRow(rs.getInt(1), rs.getString(2)));
        }
        return roles;
    }

    public boolean roleExists(Connection c, int roleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM roles WHERE id = ?")) {
            ps.setInt(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Set<Permission> permissionsForRole(Connection c, int roleId) throws SQLException {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.code FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id WHERE rp.role_id = ?""")) {
            ps.setInt(1, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        result.add(Permission.valueOf(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // a permission code this version does not know about: grants nothing
                    }
                }
            }
        }
        return result;
    }

    /** Active administrators other than the given user (used to protect the last administrator). */
    public int countOtherActiveAdministrators(Connection c, long excludedUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE role_id = ? AND is_active = 1 AND id <> ?")) {
            ps.setInt(1, ADMINISTRATOR_ROLE_ID);
            ps.setLong(2, excludedUserId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ updates

    public void updateProfile(Connection c, long userId, String fullName, int roleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET full_name = ?, role_id = ? WHERE id = ?")) {
            ps.setString(1, fullName);
            ps.setInt(2, roleId);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    public void setActive(Connection c, long userId, boolean active) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET is_active = ? WHERE id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void setPassword(Connection c, long userId, String passwordHash, boolean mustChange) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?")) {
            ps.setString(1, passwordHash);
            ps.setInt(2, mustChange ? 1 : 0);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    public void clearLock(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET locked_until = NULL, failed_attempts = 0 WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ sign-in bookkeeping

    /** Atomically adds one failed attempt and returns the new count. */
    public int incrementFailedAttempts(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT failed_attempts FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void lock(Connection c, long userId, Instant until) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET locked_until = ?, failed_attempts = 0 WHERE id = ?")) {
            ps.setString(1, until.toString());
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public void recordSuccessfulLogin(Connection c, long userId, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login_at = ? WHERE id = ?")) {
            ps.setString(1, now.toString());
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }
}
