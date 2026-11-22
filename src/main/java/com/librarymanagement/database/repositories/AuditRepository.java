package com.librarymanagement.database.repositories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

public class AuditRepository {

    /** Appends one entry. Callers must never pass passwords or other secrets in any field. */
    public void append(Connection c, Long userId, String action, String entityType, String entityId,
                       String details) throws SQLException {
        append(c, userId, action, entityType, entityId, details, null, null);
    }

    /** Same, with the previous and new values of what changed (never credentials). */
    public void append(Connection c, Long userId, String action, String entityType, String entityId,
                       String details, String oldValue, String newValue) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_logs(user_id, action, entity_type, entity_id, details, old_value, new_value) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            if (userId == null) ps.setNull(1, Types.INTEGER); else ps.setLong(1, userId);
            ps.setString(2, action);
            ps.setString(3, entityType);
            ps.setString(4, entityId);
            ps.setString(5, details);
            ps.setString(6, oldValue);
            ps.setString(7, newValue);
            ps.executeUpdate();
        }
    }
}
