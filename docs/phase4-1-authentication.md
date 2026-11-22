# Phase 4.1: Authentication

## Behaviour
- **Sign in:** username (case-insensitive) + password. Every failure shows the same message
  ("Invalid username or password, or the account is temporarily locked."), whether the user is unknown, the password is wrong,
  the account is locked or disabled. A BCrypt check always runs so timing does not reveal which case it was.
- **Lockout:** after `login.max_attempts` (default 5) wrong passwords the account is locked for `login.lockout_minutes`
  (default 15). While locked, even the correct password is refused. A successful sign-in resets the counter.
- **Session timeout:** `session.timeout_minutes` (default 15) of no mouse/keyboard activity signs the user out and returns
  to the login screen with a notice.
- **Sign out:** account menu (top right). Ends the session and is audited.
- **First run:** admin setup, then the login screen (no automatic sign-in).
- **Sidebar:** only pages the role may open are shown. The permission check is repeated in the service layer
  (`Session.require(Permission)`), which later services must call first.

## Audit entries written
LOGIN_SUCCESS, LOGIN_FAILED (reason: unknown_user / bad_password / account_locked / account_disabled),
ACCOUNT_LOCKED, LOGOUT, SESSION_EXPIRED. Passwords are never logged; attempted usernames of unknown users are not stored.

## Files
security: Permission, Session, AuthenticationException, AuthorizationException
services: AuthService  |  repositories: UserRepository (extended), SettingsRepository
navigation: SessionWatcher, Page (permissions per page)  |  controllers/fxml: LoginController, login.fxml
tests: AuthServiceTest (19 tests: lockout, generic errors, expiry, roles, injection, audit)

## Known limitations
- Lockout and session settings are edited in System Settings (Phase 5); until then change them in the database.
- A modal dialog left open when the session expires stays open, but any action it triggers is refused
  because the ended session fails every `require` check.
- "Must change password" and password changes arrive with User Management (next step).
