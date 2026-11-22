# Phase 2: Foundation notes

## Implemented
- Maven project (JDK 21, JavaFX 21, sqlite-jdbc, BCrypt, SLF4J/Logback, JUnit 5)
- `AppPaths`: data folders under %LOCALAPPDATA%\LibraryManagementSystem (created, never deleted)
- `DatabaseManager`: connections with foreign_keys=ON, busy_timeout, WAL; `inTransaction` commits or rolls back
- `MigrationRunner`: versioned SQL (`db/migration/V001__...sql`, listed in `index.txt`), per-migration transaction,
  SHA-256 checksum guard, never drops existing data
- `V001__initial_schema.sql`: full schema, indexes, seeded roles/permissions/default settings, and database-level rules
  (append-only audit log, undeletable history, one active loan per copy, inactive students cannot borrow, ...)
- `SetupService` + first-run setup screen: no default credentials, BCrypt cost 12, password policy, audit entry
- Global exception handler (details to log, safe message to user), logging to rolling files
- Minimal navigation (`Navigator`) and placeholder status page

## Adding a migration later
Create `V002__name.sql`, add its filename on a new line in `index.txt`. Never edit an applied migration.

## Known limitations / not yet done
- Login, sessions, lockout: Phase 4 (step 1)
- Design system (sidebar, components): Phase 3
- Windows packaging: Phase 7 (a packaging spike is still recommended early)
