-- V001: initial schema
-- Statements are separated by a line containing only: -- @@
-- (needed because trigger bodies contain semicolons)

CREATE TABLE roles (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);
-- @@
CREATE TABLE permissions (
    id          INTEGER PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL
);
-- @@
CREATE TABLE role_permissions (
    role_id       INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id INTEGER NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
-- @@
CREATE TABLE users (
    id                   INTEGER PRIMARY KEY,
    username             TEXT NOT NULL UNIQUE COLLATE NOCASE
                         CHECK (length(username) BETWEEN 3 AND 50),
    password_hash        TEXT NOT NULL,
    full_name            TEXT NOT NULL CHECK (length(full_name) BETWEEN 1 AND 120),
    role_id              INTEGER NOT NULL REFERENCES roles(id),
    is_active            INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    failed_attempts      INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until         TEXT,
    must_change_password INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0, 1)),
    created_at           TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    last_login_at        TEXT
);
-- @@
CREATE TABLE system_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_by INTEGER REFERENCES users(id),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
CREATE TABLE categories (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(name) BETWEEN 1 AND 80)
);
-- @@
CREATE TABLE books (
    id               INTEGER PRIMARY KEY,
    isbn             TEXT UNIQUE,
    title            TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 255),
    author           TEXT NOT NULL CHECK (length(author) BETWEEN 1 AND 255),
    publisher        TEXT,
    publication_year INTEGER CHECK (publication_year IS NULL OR publication_year BETWEEN 1000 AND 2100),
    category_id      INTEGER REFERENCES categories(id),
    description      TEXT,
    cover_path       TEXT,
    is_archived      INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0, 1)),
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    updated_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
CREATE TABLE book_copies (
    id          INTEGER PRIMARY KEY,
    book_id     INTEGER NOT NULL REFERENCES books(id) ON DELETE RESTRICT,
    copy_code   TEXT NOT NULL UNIQUE,
    status      TEXT NOT NULL DEFAULT 'AVAILABLE'
                CHECK (status IN ('AVAILABLE', 'BORROWED', 'DAMAGED', 'LOST', 'MAINTENANCE')),
    acquired_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    notes       TEXT
);
-- @@
CREATE TABLE students (
    id             INTEGER PRIMARY KEY,
    student_no     TEXT NOT NULL UNIQUE,
    first_name     TEXT NOT NULL CHECK (length(first_name) BETWEEN 1 AND 80),
    middle_name    TEXT,
    last_name      TEXT NOT NULL CHECK (length(last_name) BETWEEN 1 AND 80),
    program_strand TEXT NOT NULL,
    grade_level    TEXT NOT NULL,
    section        TEXT NOT NULL,
    contact        TEXT,
    status         TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    registered_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
CREATE UNIQUE INDEX ux_students_identity
    ON students (lower(first_name), lower(last_name), grade_level, section);
-- @@
CREATE TABLE transactions (
    id          INTEGER PRIMARY KEY,
    student_id  INTEGER NOT NULL REFERENCES students(id) ON DELETE RESTRICT,
    issued_by   INTEGER NOT NULL REFERENCES users(id),
    issue_date  TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    status      TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED'))
);
-- @@
CREATE TABLE transaction_items (
    id             INTEGER PRIMARY KEY,
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    copy_id        INTEGER NOT NULL REFERENCES book_copies(id) ON DELETE RESTRICT,
    due_date       TEXT NOT NULL,
    renewal_count  INTEGER NOT NULL DEFAULT 0 CHECK (renewal_count >= 0),
    status         TEXT NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'RETURNED', 'LOST', 'REVERSED'))
);
-- @@
-- A physical copy can be on at most one active loan.
CREATE UNIQUE INDEX ux_items_one_active_per_copy
    ON transaction_items (copy_id) WHERE status = 'ACTIVE';
-- @@
CREATE TABLE returns (
    id           INTEGER PRIMARY KEY,
    item_id      INTEGER NOT NULL UNIQUE REFERENCES transaction_items(id) ON DELETE RESTRICT,
    return_date  TEXT NOT NULL,
    condition    TEXT NOT NULL CHECK (condition IN ('GOOD', 'MINOR_DAMAGE', 'DAMAGED', 'LOST')),
    processed_by INTEGER NOT NULL REFERENCES users(id),
    notes        TEXT
);
-- @@
CREATE TABLE transaction_corrections (
    id           INTEGER PRIMARY KEY,
    item_id      INTEGER NOT NULL REFERENCES transaction_items(id),
    action       TEXT NOT NULL,
    reason       TEXT NOT NULL CHECK (length(reason) >= 3),
    performed_by INTEGER NOT NULL REFERENCES users(id),
    performed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    before_json  TEXT,
    after_json   TEXT
);
-- @@
CREATE TABLE notifications (
    id           INTEGER PRIMARY KEY,
    type         TEXT NOT NULL,
    title        TEXT NOT NULL,
    message      TEXT NOT NULL,
    related_type TEXT,
    related_id   INTEGER,
    dedupe_key   TEXT UNIQUE,
    is_read      INTEGER NOT NULL DEFAULT 0 CHECK (is_read IN (0, 1)),
    is_archived  INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0, 1)),
    created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
CREATE TABLE audit_logs (
    id          INTEGER PRIMARY KEY,
    user_id     INTEGER REFERENCES users(id),
    action      TEXT NOT NULL,
    entity_type TEXT,
    entity_id   TEXT,
    details     TEXT,
    old_value   TEXT,
    new_value   TEXT,
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
CREATE TABLE backup_history (
    id         INTEGER PRIMARY KEY,
    file_path  TEXT NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    kind       TEXT NOT NULL CHECK (kind IN ('MANUAL', 'AUTOMATIC', 'SAFETY')),
    status     TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    verified   INTEGER NOT NULL DEFAULT 0 CHECK (verified IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
-- @@
-- Indexes
CREATE INDEX ix_books_title ON books (title COLLATE NOCASE);
-- @@
CREATE INDEX ix_books_author ON books (author COLLATE NOCASE);
-- @@
CREATE INDEX ix_books_category ON books (category_id);
-- @@
CREATE INDEX ix_copies_book_status ON book_copies (book_id, status);
-- @@
CREATE INDEX ix_students_name ON students (last_name COLLATE NOCASE, first_name COLLATE NOCASE);
-- @@
CREATE INDEX ix_items_status_due ON transaction_items (status, due_date);
-- @@
CREATE INDEX ix_items_transaction ON transaction_items (transaction_id);
-- @@
CREATE INDEX ix_items_copy ON transaction_items (copy_id);
-- @@
CREATE INDEX ix_transactions_student ON transactions (student_id);
-- @@
CREATE INDEX ix_audit_created ON audit_logs (created_at);
-- @@
CREATE INDEX ix_audit_user ON audit_logs (user_id);
-- @@
CREATE INDEX ix_notifications_state ON notifications (is_read, created_at);
-- @@

-- Audit log is append-only
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_logs
BEGIN
    SELECT RAISE(ABORT, 'audit_logs is append-only');
END;
-- @@
CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_logs
BEGIN
    SELECT RAISE(ABORT, 'audit_logs is append-only');
END;
-- @@

-- Borrowing history can never be deleted
CREATE TRIGGER trg_transactions_no_delete BEFORE DELETE ON transactions
BEGIN
    SELECT RAISE(ABORT, 'transaction history cannot be deleted');
END;
-- @@
CREATE TRIGGER trg_items_no_delete BEFORE DELETE ON transaction_items
BEGIN
    SELECT RAISE(ABORT, 'transaction history cannot be deleted');
END;
-- @@
CREATE TRIGGER trg_returns_no_delete BEFORE DELETE ON returns
BEGIN
    SELECT RAISE(ABORT, 'transaction history cannot be deleted');
END;
-- @@
CREATE TRIGGER trg_corrections_no_update BEFORE UPDATE ON transaction_corrections
BEGIN
    SELECT RAISE(ABORT, 'corrections are append-only');
END;
-- @@
CREATE TRIGGER trg_corrections_no_delete BEFORE DELETE ON transaction_corrections
BEGIN
    SELECT RAISE(ABORT, 'corrections are append-only');
END;
-- @@

-- Business invariants enforced at the database level
CREATE TRIGGER trg_transactions_student_active BEFORE INSERT ON transactions
WHEN (SELECT status FROM students WHERE id = NEW.student_id) <> 'ACTIVE'
BEGIN
    SELECT RAISE(ABORT, 'inactive students cannot borrow books');
END;
-- @@
CREATE TRIGGER trg_items_copy_must_be_available BEFORE INSERT ON transaction_items
WHEN NEW.status = 'ACTIVE'
     AND (SELECT status FROM book_copies WHERE id = NEW.copy_id) <> 'AVAILABLE'
BEGIN
    SELECT RAISE(ABORT, 'copy is not available');
END;
-- @@
CREATE TRIGGER trg_copies_no_available_while_loaned BEFORE UPDATE OF status ON book_copies
WHEN NEW.status = 'AVAILABLE'
     AND EXISTS (SELECT 1 FROM transaction_items WHERE copy_id = NEW.id AND status = 'ACTIVE')
BEGIN
    SELECT RAISE(ABORT, 'copy has an active loan');
END;
-- @@
CREATE TRIGGER trg_copies_borrowed_needs_loan BEFORE UPDATE OF status ON book_copies
WHEN NEW.status = 'BORROWED'
     AND NOT EXISTS (SELECT 1 FROM transaction_items WHERE copy_id = NEW.id AND status = 'ACTIVE')
BEGIN
    SELECT RAISE(ABORT, 'copy marked borrowed without an active loan');
END;
-- @@
CREATE TRIGGER trg_returns_item_must_be_active BEFORE INSERT ON returns
WHEN (SELECT status FROM transaction_items WHERE id = NEW.item_id) <> 'ACTIVE'
BEGIN
    SELECT RAISE(ABORT, 'loan is not active');
END;
-- @@

-- Seed: roles
INSERT INTO roles (id, name) VALUES (1, 'ADMINISTRATOR'), (2, 'LIBRARIAN'), (3, 'ASSISTANT');
-- @@
-- Seed: permissions
INSERT INTO permissions (code, description) VALUES
    ('DASHBOARD_VIEW',       'View the dashboard'),
    ('BOOK_VIEW',            'View and search books'),
    ('BOOK_MANAGE',          'Add, edit, archive books, copies and categories'),
    ('STUDENT_VIEW',         'View and search students'),
    ('STUDENT_MANAGE',       'Add, edit and deactivate students'),
    ('CIRCULATION_ISSUE',    'Issue books'),
    ('CIRCULATION_RETURN',   'Return books'),
    ('TRANSACTION_VIEW_ALL', 'View all transactions'),
    ('TRANSACTION_VIEW_OWN', 'View transactions the user processed'),
    ('TRANSACTION_CORRECT',  'Correct or reverse transactions'),
    ('REPORT_VIEW',          'Generate and export reports'),
    ('NOTIFICATION_VIEW',    'View notifications'),
    ('USER_MANAGE',          'Manage user accounts and roles'),
    ('AUDIT_VIEW',           'View audit logs'),
    ('BACKUP_MANAGE',        'Create and restore backups'),
    ('SETTINGS_MANAGE',      'Change system settings');
-- @@
-- Administrator: everything except the "own only" history permission (superseded by VIEW_ALL)
INSERT INTO role_permissions (role_id, permission_id)
SELECT 1, id FROM permissions WHERE code <> 'TRANSACTION_VIEW_OWN';
-- @@
INSERT INTO role_permissions (role_id, permission_id)
SELECT 2, id FROM permissions WHERE code IN (
    'DASHBOARD_VIEW', 'BOOK_VIEW', 'BOOK_MANAGE', 'STUDENT_VIEW', 'STUDENT_MANAGE',
    'CIRCULATION_ISSUE', 'CIRCULATION_RETURN', 'TRANSACTION_VIEW_ALL',
    'REPORT_VIEW', 'NOTIFICATION_VIEW');
-- @@
INSERT INTO role_permissions (role_id, permission_id)
SELECT 3, id FROM permissions WHERE code IN (
    'DASHBOARD_VIEW', 'BOOK_VIEW', 'STUDENT_VIEW',
    'CIRCULATION_ISSUE', 'CIRCULATION_RETURN', 'TRANSACTION_VIEW_OWN', 'NOTIFICATION_VIEW');
-- @@
-- Seed: default settings (administrators can change these)
INSERT INTO system_settings (key, value) VALUES
    ('library.name',                    'School Library'),
    ('library.info',                    ''),
    ('loan.max_active',                 '3'),
    ('loan.default_days',               '14'),
    ('loan.renewals_enabled',           'false'),
    ('loan.max_renewals',               '1'),
    ('loan.block_when_overdue',         'true'),
    ('return.damaged_to_maintenance',   'false'),
    ('student.id_format',               '{YEAR}-{SEQ:5}'),
    ('session.timeout_minutes',         '15'),
    ('login.max_attempts',              '5'),
    ('login.lockout_minutes',           '15'),
    ('app.date_format',                 'yyyy-MM-dd'),
    ('app.time_format',                 'HH:mm'),
    ('backup.auto_enabled',             'false'),
    ('backup.schedule',                 'DAILY'),
    ('backup.retention_count',          '14');
