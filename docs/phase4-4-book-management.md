# Phase 4.4 — Book and Copy Management

Opens from **Book Catalog** in the sidebar.

## What it does
- **Catalog list** with search (title, author, publisher, ISBN, or a copy code), filters for category, availability
  (any / has available copies / none available) and archive state (active / archived / both), and paging.
  ISBNs can be searched with or without hyphens.
- **Add / edit book**: title, author, ISBN (optional), publisher, publication year, category, description.
  When adding, you also choose how many copies to create (0 to 50).
- **Details and copies** (double-click a row): book info plus every physical copy with status, who has it and the due
  date, and notes. A copy's borrower is only shown to users who may view students.
- **Copies**: add copies (codes continue `B000012-01`, `-02`, ...; numbers are never reused), set a copy to
  Available / In maintenance / Damaged / Lost, edit notes, remove a copy that was added by mistake.
- **Archive / Restore** instead of delete. Archived books leave the default catalog and cannot get new copies.
- **Categories** dialog: add, rename, delete (only when no book uses the category).

## Rules
- ISBN is validated (10 or 13 digits, check digit). A valid ISBN-10 is stored as its ISBN-13 form, so the same
  book cannot be entered twice in two forms. ISBN is optional; when given it must be unique.
- Same title + author + publisher + year (ignoring case) is the same edition and is refused; add a copy instead.
- Publication year: 1000 up to next year. Max 50 copies added at a time, 200 per book.
- **BORROWED belongs to circulation.** Staff can never set a copy to Borrowed, and a copy that is on loan cannot be
  changed, removed or archived around; return it first (notes can still be edited).
- A copy with any loan history cannot be removed (mark it Lost or Damaged). The database also refuses deleting it.
- A book with copies on loan cannot be archived.

## Permissions (checked in `BookService`, not only the UI)
| Role | Browse, search, view copies | Add/edit/archive books, copies, categories |
|---|---|---|
| Administrator | yes | yes |
| Librarian | yes | yes |
| Assistant | yes | no (buttons hidden, service refuses) |

## Audit
`BOOK_CREATED`, `BOOK_UPDATED`, `BOOK_ARCHIVED`, `BOOK_RESTORED`, `COPY_ADDED`, `COPY_STATUS_CHANGED`,
`COPY_UPDATED` (notes only), `COPY_REMOVED`, `CATEGORY_CREATED`, `CATEGORY_RENAMED`, `CATEGORY_DELETED`.
Setting the same value again logs nothing. Book descriptions are not copied into the audit log.

## Not in this step
- **Cover images**: the `cover_path` column exists but there is no upload yet. It is a small, self-contained addition
  (copy the chosen file into the `covers` data folder), planned for the polish pass in Phase 5/6 so no button exists
  that does nothing.
- Issuing archived books is blocked at the Issue Book screen (next step); archived state is already stored.

## Files
- `validation/Isbn`, `validation/BookRules`
- `database/repositories/BookRepository`, `services/BookService`
- `controllers/BooksPage`, `BookDetailsDialog`, `CategoriesDialog`, `ui/BadgeColumn`
- Tests: `BookRulesTest` (9), `BookServiceTest` (27)

## Verification status
Compiled and run by you on Windows: *pending*. In the build workspace, `BookService`/`BookRepository` compile against the
JDK, the ISBN and field rules were run in plain Java, and the SQL (including triggers) was replayed against the schema.
The JavaFX screens and the JUnit tests have not been compiled here.

## Manual check list
1. Book Catalog -> Categories -> add Fiction and Science; rename one; try a duplicate name; try deleting one later.
2. Add book "Dune", ISBN `0-441-01359-7`, 3 copies -> list shows `3 of 3`. Add it again with ISBN `978-0441013593` -> refused.
3. Double-click Dune: copies `B000001-01..03`. Add 2 copies (`-04`, `-05`). Mark `-02` Damaged: the list shows `4 of 5`. Set it back to Available: `5 of 5`.
4. Remove a copy; add one more: the number is not reused.
5. Archive the book; filter "Archived books"; Restore it.
6. Search by title, author, ISBN with hyphens, and a copy code like `B000001-01`; search `%`.
7. Sign in as an Assistant: you can search and open details, but there are no Add, Edit, Archive, Categories or copy buttons.
