package com.librarymanagement.database.repositories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SettingsRepository {

    public String get(Connection c, String key, String defaultValue) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM system_settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : defaultValue;
            }
        }
    }

    /** Reads an integer setting; a missing or unparsable value falls back to the default. */
    public int getInt(Connection c, String key, int defaultValue) throws SQLException {
        String raw = get(c, key, null);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
