# Phase 4.2: User and role management

## For the administrator (User Management page)
- **Add user:** username, full name, role and a generated temporary password (Generate button). The user must replace it
  at their first sign-in; until then only the "choose a new password" screen is reachable.
- **Edit:** change full name and role. Roles: Administrator, Librarian, Assistant. The new role applies at the next sign-in.
- **Reset password:** sets a new temporary password and clears any lockout.
- **Activate / Deactivate:** deactivated users cannot sign in; their history is kept. Nothing is ever deleted.
- **Unlock:** clears a lockout immediately.
- **Search** by username or name, 20 users per page.
- **Change password** (any user): account menu, top right.

## Rules enforced in the service layer (not just the UI)
- Only `USER_MANAGE` (Administrator) may manage accounts.
- You cannot deactivate yourself, change your own role, or reset your own password from the admin screen.
- The last active administrator can never be deactivated or demoted.
- Usernames are unique, case-insensitively. Passwords follow the policy (10+ chars, upper, lower, number).
- Roles keep fixed permission sets in this version; custom per-role permissions are not offered.

## Audit actions
USER_CREATED, USER_UPDATED (old and new name/role), ROLE_CHANGED, USER_ACTIVATED, USER_DEACTIVATED,
PASSWORD_RESET, PASSWORD_CHANGED, USER_UNLOCKED. Passwords never appear in the log.

## Tests
`UserServiceTest` (26 tests): permissions for every operation, validation, duplicate usernames, search wildcards and paging,
self-protection and last-administrator rules, temporary passwords, lockout reset, own-password change, audit hygiene.
