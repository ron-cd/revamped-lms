# Library Management System

Offline desktop app (Java 21, JavaFX, SQLite) for a student library. Windows target.
**Current phase: 4 (core modules), steps done: authentication, user management.** See `docs/`.

## Requirements to build
- JDK 21
- Maven 3.9+ (internet needed once to download dependencies)

## Run
```
mvn javafx:run
```
Data is stored in `%LOCALAPPDATA%\LibraryManagementSystem\` (database, logs, backups, reports, covers).
Override for testing: `mvn javafx:run -Dlms.data.dir=C:\temp\lms-test`
(on first run you are asked to create the administrator; there is no default password).

## Test
```
mvn test
python scripts/test_schema.py      # schema rules checked directly against SQLite
```
