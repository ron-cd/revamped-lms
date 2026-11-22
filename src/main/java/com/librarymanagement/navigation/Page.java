package com.librarymanagement.navigation;

import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.ui.Icon;

/** Every screen reachable from the sidebar, in display order. The section groups items under a heading. */
public enum Page {
    DASHBOARD("Dashboard", Icon.DASHBOARD, null, Permission.DASHBOARD_VIEW),
    BOOKS("Book Catalog", Icon.BOOK, "Library", Permission.BOOK_VIEW),
    STUDENTS("Student Management", Icon.PEOPLE, "Library", Permission.STUDENT_VIEW),
    ISSUE("Issue Book", Icon.ISSUE, "Circulation", Permission.CIRCULATION_ISSUE),
    RETURN("Return Book", Icon.RETURN, "Circulation", Permission.CIRCULATION_RETURN),
    HISTORY("Transaction History", Icon.HISTORY, "Circulation", Permission.TRANSACTION_VIEW_ALL, Permission.TRANSACTION_VIEW_OWN),
    REPORTS("Reports and Analytics", Icon.CHART, "Insights", Permission.REPORT_VIEW),
    NOTIFICATIONS("Notifications", Icon.BELL, "Insights", Permission.NOTIFICATION_VIEW),
    USERS("User Management", Icon.USER, "Administration", Permission.USER_MANAGE),
    AUDIT("Audit Logs", Icon.SHIELD, "Administration", Permission.AUDIT_VIEW),
    BACKUP("Backup and Restore", Icon.BACKUP, "Administration", Permission.BACKUP_MANAGE),
    SETTINGS("System Settings", Icon.SETTINGS, "Administration", Permission.SETTINGS_MANAGE),
    /** Developer page; removed before release (Phase 7). */
    GALLERY("UI Gallery", Icon.INFO, "Development", Permission.SETTINGS_MANAGE);

    private final String title;
    private final Icon icon;
    private final String section;
    private final Permission[] anyOf;

    Page(String title, Icon icon, String section, Permission... anyOf) {
        this.title = title;
        this.icon = icon;
        this.section = section;
        this.anyOf = anyOf;
    }

    /** True if the session holds at least one of the permissions that open this page. */
    public boolean isAllowedFor(Session session) {
        return session.hasAny(anyOf);
    }

    public String title()   { return title; }
    public Icon icon()      { return icon; }
    /** null for items that sit above all sections */
    public String section() { return section; }
}
