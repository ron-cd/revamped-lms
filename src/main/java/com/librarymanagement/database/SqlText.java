package com.librarymanagement.database;

/** Helpers for LIKE searches. Queries must use "ESCAPE '\'" so user-typed % and _ match literally. */
public final class SqlText {

    private SqlText() {}

    /** "%text%" with wildcards in the text escaped. */
    public static String likeContains(String search) {
        return "%" + escape(search == null ? "" : search.trim()) + "%";
    }

    /** "text%" with wildcards in the text escaped. */
    public static String likePrefix(String prefix) {
        return escape(prefix == null ? "" : prefix) + "%";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
