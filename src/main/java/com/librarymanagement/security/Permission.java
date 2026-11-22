package com.librarymanagement.security;

/** Every permission in the system. Names match the codes stored in the permissions table. */
public enum Permission {
    DASHBOARD_VIEW,
    BOOK_VIEW,
    BOOK_MANAGE,
    STUDENT_VIEW,
    STUDENT_MANAGE,
    CIRCULATION_ISSUE,
    CIRCULATION_RETURN,
    TRANSACTION_VIEW_ALL,
    TRANSACTION_VIEW_OWN,
    TRANSACTION_CORRECT,
    REPORT_VIEW,
    NOTIFICATION_VIEW,
    USER_MANAGE,
    AUDIT_VIEW,
    BACKUP_MANAGE,
    SETTINGS_MANAGE
}
