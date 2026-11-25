package com.librarymanagement.services;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.BookRepository;
import com.librarymanagement.database.repositories.BookRepository.BookRecord;
import com.librarymanagement.database.repositories.BookRepository.CategoryRecord;
import com.librarymanagement.database.repositories.BookRepository.CopyRecord;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.validation.BookRules;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.Isbn;
import com.librarymanagement.validation.ValidationException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The book catalog: titles, physical copies and categories.
 * - viewing needs BOOK_VIEW; every change needs BOOK_MANAGE;
 * - books are archived, never deleted (history keeps pointing at them);
 * - a copy's BORROWED status belongs to circulation: it is never set or cleared here;
 * - a copy can only be removed if it has no loan history, otherwise mark it Lost or Damaged.
 */
public class BookService {

    public enum AvailabilityFilter { ALL, AVAILABLE, UNAVAILABLE }

    public enum ArchiveFilter { ACTIVE, ARCHIVED, ALL }

    /** Form input. Empty strings mean "not provided"; year and categoryId are null when not provided. */
    public record BookInput(String isbn, String title, String author, String publisher, Integer year,
                            Long categoryId, String description) {}

    public record BookView(long id, String isbn, String title, String author, String publisher, Integer year,
                           Long categoryId, String category, String description, boolean archived,
                           int totalCopies, int availableCopies) {}

    /** borrower and dueDate are only filled for copies on loan; borrower only for users who may view students. */
    public record CopyView(long id, String code, String status, String acquiredAt, String notes,
                           String dueDate, String borrower) {}

    public record CategoryView(long id, String name, int bookCount) {}

    /** Statuses staff may set by hand. BORROWED is only ever set by circulation. */
    public static final Set<String> MANUAL_STATUSES = Set.of("AVAILABLE", "DAMAGED", "LOST", "MAINTENANCE");

    private final DatabaseManager db;
    private final BookRepository books;
    private final AuditRepository audit;
    private final Clock clock;

    public BookService(DatabaseManager db, BookRepository books, AuditRepository audit, Clock clock) {
        this.db = db;
        this.books = books;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** @param categoryId null for every category */
    public PageResult<BookView> list(Session session, String search, Long categoryId, AvailabilityFilter availability,
                                     ArchiveFilter archive, int offset, int limit) {
        session.require(Permission.BOOK_VIEW);
        String availabilityValue = availability == null || availability == AvailabilityFilter.ALL ? null : availability.name();
        // null (no choice) behaves like the default: active books only
        Integer archivedFinal = archive == ArchiveFilter.ALL ? null : (archive == ArchiveFilter.ARCHIVED ? 1 : 0);
        return db.inTransactionless(c -> {
            List<BookView> items = new ArrayList<>();
            for (BookRecord r : books.list(c, search, categoryId, availabilityValue, archivedFinal,
                    Math.max(0, offset), Math.max(1, limit))) {
                items.add(view(r));
            }
            return new PageResult<>(items, books.count(c, search, categoryId, availabilityValue, archivedFinal));
        });
    }

    public BookView get(Session session, long bookId) {
        session.require(Permission.BOOK_VIEW);
        return db.inTransactionless(c -> view(find(c, bookId)));
    }

    public List<CopyView> copies(Session session, long bookId) {
        session.require(Permission.BOOK_VIEW);
        boolean showBorrower = session.has(Permission.STUDENT_VIEW);
        return db.inTransactionless(c -> {
            find(c, bookId);
            List<CopyView> rows = new ArrayList<>();
            for (CopyRecord r : books.copiesOf(c, bookId)) rows.add(copyView(r, showBorrower));
            return rows;
        });
    }

    public List<CategoryView> categories(Session session) {
        session.require(Permission.BOOK_VIEW);
        return db.inTransactionless(c -> {
            List<CategoryView> rows = new ArrayList<>();
            for (CategoryRecord r : books.categories(c)) rows.add(new CategoryView(r.id(), r.name(), r.bookCount()));
            return rows;
        });
    }

    // ------------------------------------------------------------------ books

    /** @param initialCopies 0..50 copies created together with the book */
    public long create(Session session, BookInput input, int initialCopies) {
        session.require(Permission.BOOK_MANAGE);
        BookInput in = normalise(input);
        validate(in);
        if (initialCopies < 0 || initialCopies > BookRules.MAX_COPIES_PER_ADD) {
            throw new ValidationException("Number of copies must be between 0 and " + BookRules.MAX_COPIES_PER_ADD + ".");
        }
        String isbn = canonicalIsbn(in);

        return db.inTransaction(c -> {
            checkCategory(c, in.categoryId());
            rejectDuplicates(c, in, isbn, -1);
            long id = books.insert(c, isbn, in.title(), in.author(), in.publisher(), in.year(), in.categoryId(),
                    in.description());
            audit.append(c, session.userId(), "BOOK_CREATED", "book", String.valueOf(id),
                    "title=" + in.title(), null, describe(c, in, isbn));
            if (initialCopies > 0) insertCopies(c, session, id, initialCopies, "");
            return id;
        });
    }

    public void update(Session session, long bookId, BookInput input) {
        session.require(Permission.BOOK_MANAGE);
        BookInput in = normalise(input);
        validate(in);
        String isbn = canonicalIsbn(in);

        db.inTransaction(c -> {
            BookRecord old = find(c, bookId);
            checkCategory(c, in.categoryId());
            rejectDuplicates(c, in, isbn, bookId);
            books.update(c, bookId, isbn, in.title(), in.author(), in.publisher(), in.year(), in.categoryId(),
                    in.description());
            audit.append(c, session.userId(), "BOOK_UPDATED", "book", String.valueOf(bookId),
                    "title=" + in.title(), describe(old), describe(c, in, isbn));
            return null;
        });
    }

    /** Archived books disappear from the default catalog and cannot be issued, but keep their history. */
    public void setArchived(Session session, long bookId, boolean archived) {
        session.require(Permission.BOOK_MANAGE);
        db.inTransaction(c -> {
            BookRecord book = find(c, bookId);
            if (book.archived() == archived) return null;
            if (archived) {
                int onLoan = books.countBorrowedCopies(c, bookId);
                if (onLoan > 0) {
                    throw new BusinessRuleException(onLoan == 1
                            ? "One copy of this book is on loan. Return it before archiving the book."
                            : onLoan + " copies of this book are on loan. Return them before archiving the book.");
                }
            }
            books.setArchived(c, bookId, archived);
            audit.append(c, session.userId(), archived ? "BOOK_ARCHIVED" : "BOOK_RESTORED", "book",
                    String.valueOf(bookId), "title=" + book.title(),
                    archived ? "active" : "archived", archived ? "archived" : "active");
            return null;
        });
    }

    // ------------------------------------------------------------------ copies

    /** @return the codes of the new copies */
    public List<String> addCopies(Session session, long bookId, int count, String notes) {
        session.require(Permission.BOOK_MANAGE);
        if (count < 1 || count > BookRules.MAX_COPIES_PER_ADD) {
            throw new ValidationException("Number of copies must be between 1 and " + BookRules.MAX_COPIES_PER_ADD + ".");
        }
        String cleanNotes = BookRules.normalize(notes);
        List<String> problems = BookRules.checkNotes(cleanNotes);
        if (!problems.isEmpty()) throw new ValidationException(problems);

        return db.inTransaction(c -> {
            BookRecord book = find(c, bookId);
            if (book.archived()) throw new BusinessRuleException("This book is archived. Restore it before adding copies.");
            return insertCopies(c, session, bookId, count, cleanNotes);
        });
    }

    /** Changes a copy's status and notes. A copy on loan keeps its status until it is returned. */
    public void updateCopy(Session session, long copyId, String status, String notes) {
        session.require(Permission.BOOK_MANAGE);
        String cleanNotes = BookRules.normalize(notes);
        List<String> problems = BookRules.checkNotes(cleanNotes);
        if (!problems.isEmpty()) throw new ValidationException(problems);
        String target = status == null ? "" : status.trim().toUpperCase();

        db.inTransaction(c -> {
            CopyRecord copy = findCopy(c, copyId);
            if ("BORROWED".equals(copy.status())) {
                if (!"BORROWED".equals(target)) {
                    throw new BusinessRuleException("This copy is on loan. Return it first, then change its status.");
                }
            } else if (!MANUAL_STATUSES.contains(target)) {
                throw new ValidationException("Choose a valid status for the copy.");
            }
            boolean statusChanged = !target.equals(copy.status());
            boolean notesChanged = !nullToEmpty(copy.notes()).equals(cleanNotes);
            if (!statusChanged && !notesChanged) return null;

            books.updateCopy(c, copyId, target, cleanNotes);
            audit.append(c, session.userId(), statusChanged ? "COPY_STATUS_CHANGED" : "COPY_UPDATED", "book_copy",
                    String.valueOf(copyId), "copy=" + copy.code(),
                    "status=" + copy.status() + "; notes=" + nullToEmpty(copy.notes()),
                    "status=" + target + "; notes=" + cleanNotes);
            return null;
        });
    }

    /** Removes a copy that was added by mistake. Copies with any loan history are kept (mark them Lost or Damaged). */
    public void removeCopy(Session session, long copyId) {
        session.require(Permission.BOOK_MANAGE);
        db.inTransaction(c -> {
            CopyRecord copy = findCopy(c, copyId);
            if ("BORROWED".equals(copy.status())) {
                throw new BusinessRuleException("This copy is on loan and cannot be removed.");
            }
            if (books.copyHasLoanHistory(c, copyId)) {
                throw new BusinessRuleException("This copy has borrowing history, so it cannot be removed. "
                        + "Mark it Lost or Damaged instead.");
            }
            books.deleteCopy(c, copyId);
            audit.append(c, session.userId(), "COPY_REMOVED", "book_copy", String.valueOf(copyId),
                    "copy=" + copy.code(), "status=" + copy.status(), null);
            return null;
        });
    }

    // ------------------------------------------------------------------ categories

    public long createCategory(Session session, String name) {
        session.require(Permission.BOOK_MANAGE);
        String clean = BookRules.normalize(name);
        requireValidCategory(clean);
        return db.inTransaction(c -> {
            if (books.categoryNameExists(c, clean, -1)) {
                throw new ValidationException("A category with that name already exists.");
            }
            long id = books.insertCategory(c, clean);
            audit.append(c, session.userId(), "CATEGORY_CREATED", "category", String.valueOf(id),
                    "name=" + clean, null, null);
            return id;
        });
    }

    public void renameCategory(Session session, long categoryId, String name) {
        session.require(Permission.BOOK_MANAGE);
        String clean = BookRules.normalize(name);
        requireValidCategory(clean);
        db.inTransaction(c -> {
            CategoryRecord old = books.findCategory(c, categoryId)
                    .orElseThrow(() -> new ValidationException("That category no longer exists."));
            if (books.categoryNameExists(c, clean, categoryId)) {
                throw new ValidationException("A category with that name already exists.");
            }
            if (old.name().equals(clean)) return null;
            books.renameCategory(c, categoryId, clean);
            audit.append(c, session.userId(), "CATEGORY_RENAMED", "category", String.valueOf(categoryId),
                    "name=" + clean, old.name(), clean);
            return null;
        });
    }

    /** Only categories no book uses can be deleted. */
    public void deleteCategory(Session session, long categoryId) {
        session.require(Permission.BOOK_MANAGE);
        db.inTransaction(c -> {
            CategoryRecord category = books.findCategory(c, categoryId)
                    .orElseThrow(() -> new ValidationException("That category no longer exists."));
            if (category.bookCount() > 0) {
                throw new BusinessRuleException(category.bookCount() == 1
                        ? "One book uses this category. Move it to another category first."
                        : category.bookCount() + " books use this category. Move them to another category first.");
            }
            books.deleteCategory(c, categoryId);
            audit.append(c, session.userId(), "CATEGORY_DELETED", "category", String.valueOf(categoryId),
                    "name=" + category.name(), category.name(), null);
            return null;
        });
    }

    // ------------------------------------------------------------------ internals

    private List<String> insertCopies(Connection c, Session session, long bookId, int count, String notes)
            throws SQLException {
        int existing = books.countCopies(c, bookId);
        if (existing + count > BookRules.MAX_COPIES_PER_BOOK) {
            throw new BusinessRuleException("A book can have at most " + BookRules.MAX_COPIES_PER_BOOK + " copies.");
        }
        int next = highestCopyNumber(books.copyCodesOf(c, bookId)) + 1;
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = copyCode(bookId, next + i);
            if (books.copyCodeExists(c, code)) throw new BusinessRuleException("Copy code " + code + " already exists.");
            books.insertCopy(c, bookId, code, notes);
            codes.add(code);
        }
        String range = count == 1 ? codes.get(0) : codes.get(0) + " to " + codes.get(count - 1);
        audit.append(c, session.userId(), "COPY_ADDED", "book", String.valueOf(bookId),
                "copies=" + count + " (" + range + ")", null, null);
        return codes;
    }

    /** B + 6-digit book number + dash + copy number, e.g. B000012-03. */
    static String copyCode(long bookId, int number) {
        return String.format("B%06d-%02d", bookId, number);
    }

    /** Highest copy number in the codes; numbers are never reused, even if a copy was removed. */
    static int highestCopyNumber(List<String> codes) {
        int highest = 0;
        for (String code : codes) {
            int dash = code.lastIndexOf('-');
            if (dash < 0) continue;
            try {
                highest = Math.max(highest, Integer.parseInt(code.substring(dash + 1)));
            } catch (NumberFormatException ignored) {
                // a code of another style; does not affect numbering
            }
        }
        return highest;
    }

    private void rejectDuplicates(Connection c, BookInput in, String isbn, long excludedId) throws SQLException {
        if (!isbn.isEmpty()) {
            var other = books.findTitleByIsbn(c, isbn, excludedId);
            if (other.isPresent()) {
                throw new ValidationException("Another book already uses this ISBN (\"" + other.get() + "\").");
            }
        }
        if (books.sameEditionExists(c, in.title(), in.author(), in.publisher(), in.year(), excludedId)) {
            throw new ValidationException("This book (same title, author, publisher and year) is already in the catalog. "
                    + "Add a copy to the existing entry instead.");
        }
    }

    private void checkCategory(Connection c, Long categoryId) throws SQLException {
        if (categoryId != null && books.findCategory(c, categoryId).isEmpty()) {
            throw new ValidationException("That category no longer exists.");
        }
    }

    private void validate(BookInput in) {
        List<String> problems = BookRules.checkBook(in.isbn(), in.title(), in.author(), in.publisher(), in.year(),
                in.description(), today().getYear() + 1);
        if (!problems.isEmpty()) throw new ValidationException(problems);
    }

    private static String canonicalIsbn(BookInput in) {
        return in.isbn().isEmpty() ? "" : Isbn.canonical(in.isbn()).orElse("");
    }

    private static void requireValidCategory(String name) {
        List<String> problems = BookRules.checkCategory(name);
        if (!problems.isEmpty()) throw new ValidationException(problems);
    }

    private static BookInput normalise(BookInput in) {
        return new BookInput(BookRules.normalize(in.isbn()), BookRules.normalize(in.title()),
                BookRules.normalize(in.author()), BookRules.normalize(in.publisher()), in.year(), in.categoryId(),
                BookRules.normalizeMultiline(in.description()));
    }

    private BookRecord find(Connection c, long id) throws SQLException {
        return books.findById(c, id).orElseThrow(() -> new ValidationException("That book no longer exists."));
    }

    private CopyRecord findCopy(Connection c, long id) throws SQLException {
        return books.findCopy(c, id).orElseThrow(() -> new ValidationException("That copy no longer exists."));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private static BookView view(BookRecord r) {
        return new BookView(r.id(), nullToEmpty(r.isbn()), r.title(), r.author(), nullToEmpty(r.publisher()), r.year(),
                r.categoryId(), nullToEmpty(r.category()), nullToEmpty(r.description()), r.archived(),
                r.totalCopies(), r.availableCopies());
    }

    private static CopyView copyView(CopyRecord r, boolean showBorrower) {
        String borrower = null;
        if (showBorrower && r.borrowerLast() != null) borrower = r.borrowerLast() + ", " + r.borrowerFirst();
        return new CopyView(r.id(), r.code(), r.status(), r.acquiredAt(), nullToEmpty(r.notes()), r.dueDate(), borrower);
    }

    /** Audit text; the long description is left out. */
    private String describe(Connection c, BookInput in, String isbn) throws SQLException {
        String category = in.categoryId() == null ? "" : books.findCategory(c, in.categoryId()).map(CategoryRecord::name).orElse("");
        return "title=" + in.title() + "; author=" + in.author() + "; isbn=" + isbn + "; publisher=" + in.publisher()
                + "; year=" + (in.year() == null ? "" : in.year()) + "; category=" + category;
    }

    private static String describe(BookRecord r) {
        return "title=" + r.title() + "; author=" + r.author() + "; isbn=" + nullToEmpty(r.isbn()) + "; publisher="
                + nullToEmpty(r.publisher()) + "; year=" + (r.year() == null ? "" : r.year()) + "; category="
                + nullToEmpty(r.category());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
