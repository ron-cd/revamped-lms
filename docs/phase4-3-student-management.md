# Phase 4.3 — Student Management

Students are library members, not login users. They are managed from **Students** in the sidebar.

## What it does
- **List** with search (student ID, first name, last name, "First Last", "Last, First"), status filter
  (All / Active / Inactive), grade filter, and paging. Search runs 300 ms after you stop typing.
- **Add / Edit** student: first, middle (optional), last name, program/strand, grade, section, contact (optional).
  Program, grade and section are editable drop-downs fed by values already in use, so staff can pick or type a new one.
- **Student ID** is generated when left blank, or can be typed (e.g. for existing school IDs).
  It can never be changed after creation. Format comes from setting `student.id_format`
  (`{YEAR}`, `{YY}`, `{SEQ:n}`; default `{YEAR}-{SEQ:5}` -> `2026-00001`). An invalid setting falls back to the default.
- **Deactivate / Activate** instead of delete. History is always kept. The database itself refuses
  new loans for inactive students.
- **Profile** (double-click a row or *View profile*): details, counts (current / overdue / total loans) and a paged
  borrowing history. Loans that were later corrected (reversed) are hidden. Fines do not exist; overdue is only a flag.

## Rules
- Duplicate student ID refused. Duplicate identity (same first + last name, grade and section, ignoring case) refused,
  and the message names the ID of the clashing record.
- Names allow letters, accents, spaces, `. ' - ’`; no digits. Contact max 100 characters, no control characters.
- Extra whitespace is trimmed and collapsed.

## Permissions (checked in `StudentService`, not only the UI)
| Role | View/search/profile | Contact info | Add / edit / (de)activate |
|---|---|---|---|
| Administrator | yes | yes | yes |
| Librarian | yes | yes | yes |
| Assistant | yes | **hidden** | no (buttons not shown, service refuses) |

## Audit
`STUDENT_CREATED`, `STUDENT_UPDATED`, `STUDENT_ACTIVATED`, `STUDENT_DEACTIVATED`.
Contact details are never written to the audit log; only `contact_changed=true`. Setting the same status again logs nothing.

## Files
- `validation/StudentRules`, `services/StudentIdFormat`, `services/StudentService`
- `database/SqlText` (LIKE escaping), `database/repositories/StudentRepository`
- `controllers/StudentsPage`, `ui/AppDialog` (width overload and `view()`), CSS additions in `theme.css`
- Tests: `StudentIdFormatTest` (9), `StudentServiceTest` (20)

## Verification status
Compiled and run by you on Windows: *pending*. In the build workspace only the JDK-only classes
(`StudentIdFormat`, `StudentRules`) and the SQL used by the tests were executed.

## Manual check list
1. Sign in as admin -> Students -> Add student with blank ID -> `2026-00001`; add another -> `2026-00002`.
2. Add the same name/grade/section again -> refused with the clashing ID.
3. Edit a student; the ID field is disabled. Deactivate, filter Inactive, Activate.
4. Search by ID, by `Reyes, Ana`, and try `%` (should find nothing).
5. Open a profile (counts show 0 until Borrowing exists).
6. Users -> add an Assistant, sign in as them: no Contact column, no Add/Edit/Deactivate.
