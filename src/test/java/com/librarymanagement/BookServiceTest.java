package com.librarymanagement;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.BookRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.AuthorizationException;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.services.BookService;
import com.librarymanagement.services.BookService.ArchiveFilter;
import com.librarymanagement.services.BookService.AvailabilityFilter;
import com.librarymanagement.services.BookService.BookInput;
import com.librarymanagement.services.BookService.BookView;
import com.librarymanagement.services.BookService.CopyView;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BookServiceTest {

    static final class FixedClock extends Clock {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.parse("2026-11-23T08:00:00Z"); }
    }

    static final String PW = "Str0ngPassw0rd";

    @TempDir Path tmp;
    DatabaseManager db;
    BookService svc;
    Session admin;
    Session librarian;
    Session assistant;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        Clock clock = new FixedClock();
        PasswordHasher hasher = new PasswordHasher();
        UserRepository users = new UserRepository();
        AuditRepository audit = new AuditRepository();
        new SetupService(db, users, audit, hasher).createInitialAdministrator("admin", "Admin", PW.toCharArray(), PW.toCharArray());
        db.inTransaction(c -> {
            users.insert(c, "lib", hasher.hash(PW.toCharArray()), "Lib", 2);
            users.insert(c, "asst", hasher.hash(PW.toCharArray()), "Asst", 3);
            return null;
        });
        AuthService auth = new AuthService(db, users, new SettingsRepository(), audit, hasher, clock);
        admin = auth.login("admin", PW.toCharArray());
        librarian = auth.login("lib", PW.toCharArray());
        assistant = auth.login("asst", PW.toCharArray());
        svc = new BookService(db, new BookRepository(), audit, clock);
    }

    // ---- helpers
    static BookInput book(String title, String author) {
        return new BookInput("", title, author, "", null, null, "");
    }

    long create(String title, String author) {
        return svc.create(admin, book(title, author), 0);
    }

    int count(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    String text(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    void sql(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    int audits(String action) throws Exception {
        return count("SELECT COUNT(*) FROM audit_logs WHERE action = ?", action);
    }

    List<CopyView> copies(long bookId) {
        return svc.copies(admin, bookId);
    }

    /** Lends a copy to a new student, the way circulation will. Returns the loan item id. */
    long lend(long copyId, String due) throws Exception {
        sql("INSERT INTO students(student_no, first_name, last_name, program_strand, grade_level, section)"
                + " VALUES('S-' || ?, 'Ana', 'Reyes', 'STEM', '11', ?)", copyId, "A" + copyId);
        long studentId = Long.parseLong(text("SELECT id FROM students WHERE student_no = 'S-' || ?", copyId));
        return db.inTransaction(c -> {
            long txId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transactions(student_id, issued_by, issue_date) VALUES(?, 1, '2026-11-01')", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, studentId);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); txId = k.getLong(1); }
            }
            long itemId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO transaction_items(transaction_id, copy_id, due_date) VALUES(?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, txId);
                ps.setLong(2, copyId);
                ps.setString(3, due);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); itemId = k.getLong(1); }
            }
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE book_copies SET status = 'BORROWED' WHERE id = " + copyId);
            }
            return itemId;
        });
    }

    void giveBack(long itemId, long copyId) {
        db.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO returns(item_id, return_date, condition, processed_by) VALUES(" + itemId + ", '2026-11-15', 'GOOD', 1)");
                st.execute("UPDATE transaction_items SET status = 'RETURNED' WHERE id = " + itemId);
                st.execute("UPDATE book_copies SET status = 'AVAILABLE' WHERE id = " + copyId);
            }
            return null;
        });
    }

    // ---- creating books
    @Test
    void createWithCopiesGeneratesSequentialCopyCodes() throws Exception {
        long id = svc.create(admin, new BookInput("", "Dune", "Frank Herbert", "Chilton", 1965, null, "A desert planet."), 3);
        assertEquals(List.of("B000001-01", "B000001-02", "B000001-03"),
                copies(id).stream().map(CopyView::code).toList());
        BookView view = svc.get(admin, id);
        assertEquals(3, view.totalCopies());
        assertEquals(3, view.availableCopies());
        assertEquals(1, audits("BOOK_CREATED"));
        assertEquals(1, audits("COPY_ADDED"));
        // the long description is not copied into the audit log
        assertEquals(0, count("SELECT COUNT(*) FROM audit_logs WHERE COALESCE(new_value,'') LIKE '%desert planet%'"));
    }

    @Test
    void invalidBooksAreRejected() throws Exception {
        assertThrows(ValidationException.class, () -> svc.create(admin, book("", "Herbert"), 0));
        assertThrows(ValidationException.class, () -> svc.create(admin, book("Dune", ""), 0));
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("12345", "Dune", "Herbert", "", null, null, ""), 0));
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("", "Dune", "Herbert", "", 2028, null, ""), 0));   // after next year
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("", "Dune", "Herbert", "", 999, null, ""), 0));
        assertThrows(ValidationException.class, () -> svc.create(admin, book("Dune", "Herbert"), -1));
        assertThrows(ValidationException.class, () -> svc.create(admin, book("Dune", "Herbert"), 51));
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("", "Dune", "Herbert", "", null, 999L, ""), 0));   // unknown category
        assertEquals(0, count("SELECT COUNT(*) FROM books"));
    }

    @Test
    void titlesAreStoredLiterallyAndWhitespaceIsCollapsed() throws Exception {
        long id = svc.create(admin, new BookInput("", "  Dune'); DROP TABLE books;--  ", "  Frank   Herbert ", "", null, null, ""), 0);
        assertEquals("Dune'); DROP TABLE books;--", text("SELECT title FROM books WHERE id = ?", id));
        assertEquals("Frank Herbert", text("SELECT author FROM books WHERE id = ?", id));
    }

    @Test
    void isbnIsStoredAsThirteenDigitsAndIsUnique() throws Exception {
        long id = svc.create(admin, new BookInput("0-441-01359-7", "Dune", "Herbert", "", null, null, ""), 0);
        assertEquals("9780441013593", text("SELECT isbn FROM books WHERE id = ?", id));
        // the same book typed as ISBN-13 with hyphens is a duplicate
        ValidationException e = assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("978-0-441-01359-3", "Dune (reprint)", "Herbert", "", null, null, ""), 0));
        assertTrue(e.getMessage().contains("ISBN"));
        assertTrue(e.getMessage().contains("Dune"));
    }

    @Test
    void sameEditionIsRefusedButOtherYearIsAllowed() throws Exception {
        svc.create(admin, new BookInput("", "Dune", "Herbert", "Chilton", 1965, null, ""), 0);
        ValidationException e = assertThrows(ValidationException.class, () -> svc.create(admin,
                new BookInput("", "DUNE", "herbert", "chilton", 1965, null, ""), 0));
        assertTrue(e.getMessage().contains("already in the catalog"));
        assertDoesNotThrow(() -> svc.create(admin, new BookInput("", "Dune", "Herbert", "Chilton", 1984, null, ""), 0));
        assertEquals(2, count("SELECT COUNT(*) FROM books"));
    }

    // ---- permissions
    @Test
    void assistantCanBrowseButNotChange() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        long copy = copies(id).get(0).id();
        long cat = svc.createCategory(admin, "Fiction");

        assertEquals(1, svc.list(assistant, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 10).total());
        assertEquals("Dune", svc.get(assistant, id).title());
        assertEquals(1, svc.copies(assistant, id).size());
        assertEquals(1, svc.categories(assistant).size());

        assertThrows(AuthorizationException.class, () -> svc.create(assistant, book("X", "Y"), 0));
        assertThrows(AuthorizationException.class, () -> svc.update(assistant, id, book("Dune", "Herbert")));
        assertThrows(AuthorizationException.class, () -> svc.setArchived(assistant, id, true));
        assertThrows(AuthorizationException.class, () -> svc.addCopies(assistant, id, 1, ""));
        assertThrows(AuthorizationException.class, () -> svc.updateCopy(assistant, copy, "LOST", ""));
        assertThrows(AuthorizationException.class, () -> svc.removeCopy(assistant, copy));
        assertThrows(AuthorizationException.class, () -> svc.createCategory(assistant, "New"));
        assertThrows(AuthorizationException.class, () -> svc.renameCategory(assistant, cat, "New"));
        assertThrows(AuthorizationException.class, () -> svc.deleteCategory(assistant, cat));
    }

    @Test
    void librarianCanManageBooks() throws Exception {
        long id = svc.create(librarian, book("Dune", "Herbert"), 2);
        svc.updateCopy(librarian, copies(id).get(0).id(), "MAINTENANCE", "rebinding");
        svc.setArchived(librarian, id, false);
        assertEquals(2, svc.get(librarian, id).totalCopies());
    }

    @Test
    void sessionWithoutBookViewCannotList() throws Exception {
        Session none = new Session(1, "x", "X", "NONE", EnumSet.noneOf(Permission.class),
                Duration.ofMinutes(5), Instant.now(), false);
        assertThrows(AuthorizationException.class, () -> svc.list(none, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 10));
    }

    // ---- updating and archiving
    @Test
    void updateChangesDetailsAndKeepsCopies() throws Exception {
        long cat = svc.createCategory(admin, "Science Fiction");
        long id = svc.create(admin, book("Dune", "Herbert"), 2);
        svc.update(admin, id, new BookInput("", "Dune Messiah", "Frank Herbert", "Putnam", 1969, cat, "Sequel"));
        BookView v = svc.get(admin, id);
        assertEquals("Dune Messiah", v.title());
        assertEquals("Science Fiction", v.category());
        assertEquals(Integer.valueOf(1969), v.year());
        assertEquals(2, v.totalCopies());
        assertEquals(1, audits("BOOK_UPDATED"));
    }

    @Test
    void updateCannotCreateADuplicate() throws Exception {
        svc.create(admin, new BookInput("9780441013593", "Dune", "Herbert", "", null, null, ""), 0);
        long other = create("Emma", "Austen");
        assertThrows(ValidationException.class, () -> svc.update(admin, other,
                new BookInput("9780441013593", "Emma", "Austen", "", null, null, "")));
        // saving a book without changing its identity is fine
        assertDoesNotThrow(() -> svc.update(admin, other, book("Emma", "Jane Austen")));
        assertThrows(ValidationException.class, () -> svc.update(admin, 9999, book("A", "B")));
    }

    @Test
    void archivedBooksAreHiddenByDefaultAndCanBeRestored() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        svc.setArchived(admin, id, true);
        assertEquals(0, svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 10).total());
        assertEquals(1, svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ARCHIVED, 0, 10).total());
        assertEquals(1, svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ALL, 0, 10).total());
        assertTrue(svc.get(admin, id).archived());
        assertEquals(1, audits("BOOK_ARCHIVED"));

        svc.setArchived(admin, id, false);
        assertEquals(1, svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 10).total());
        assertEquals(1, audits("BOOK_RESTORED"));
        assertEquals(1, count("SELECT COUNT(*) FROM books")); // never deleted
    }

    @Test
    void archivingTwiceAddsNoAuditNoise() throws Exception {
        long id = create("Dune", "Herbert");
        svc.setArchived(admin, id, false);
        assertEquals(0, audits("BOOK_RESTORED"));
    }

    @Test
    void bookWithCopiesOnLoanCannotBeArchived() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 2);
        long itemId = lend(copies(id).get(0).id(), "2026-12-01");
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> svc.setArchived(admin, id, true));
        assertTrue(e.getMessage().contains("on loan"));
        assertFalse(svc.get(admin, id).archived());

        giveBack(itemId, copies(id).get(0).id());
        assertDoesNotThrow(() -> svc.setArchived(admin, id, true));
    }

    @Test
    void copiesCannotBeAddedToAnArchivedBook() throws Exception {
        long id = create("Dune", "Herbert");
        svc.setArchived(admin, id, true);
        assertThrows(BusinessRuleException.class, () -> svc.addCopies(admin, id, 1, ""));
        svc.setArchived(admin, id, false);
        assertEquals(1, svc.addCopies(admin, id, 1, "").size());
    }

    // ---- copies
    @Test
    void addedCopiesContinueTheNumberingAndNumbersAreNeverReused() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 3);
        assertEquals(List.of("B000001-04", "B000001-05"), svc.addCopies(admin, id, 2, "gift"));
        assertEquals("gift", copies(id).get(3).notes());

        // removing a copy in the middle does not free its number
        long second = copies(id).get(1).id();
        svc.removeCopy(admin, second);
        assertEquals(List.of("B000001-06"), svc.addCopies(admin, id, 1, ""));
        assertEquals(5, copies(id).size());
    }

    @Test
    void copyCountsAreLimited() throws Exception {
        long id = create("Dune", "Herbert");
        assertThrows(ValidationException.class, () -> svc.addCopies(admin, id, 0, ""));
        assertThrows(ValidationException.class, () -> svc.addCopies(admin, id, 51, ""));
        assertThrows(ValidationException.class, () -> svc.addCopies(admin, id, 1, "x".repeat(256)));
        for (int i = 0; i < 4; i++) svc.addCopies(admin, id, 50, "");       // 200 copies
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> svc.addCopies(admin, id, 1, ""));
        assertTrue(e.getMessage().contains("200"));
        assertEquals(200, svc.get(admin, id).totalCopies());
    }

    @Test
    void copyStatusCanBeChangedByHand() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        long copy = copies(id).get(0).id();

        svc.updateCopy(admin, copy, "DAMAGED", "torn cover");
        assertEquals("DAMAGED", copies(id).get(0).status());
        assertEquals(0, svc.get(admin, id).availableCopies());
        svc.updateCopy(admin, copy, "MAINTENANCE", "torn cover");
        svc.updateCopy(admin, copy, "LOST", "torn cover");
        svc.updateCopy(admin, copy, "AVAILABLE", "found it");
        assertEquals("AVAILABLE", copies(id).get(0).status());
        assertEquals(1, svc.get(admin, id).availableCopies());
        assertEquals(4, audits("COPY_STATUS_CHANGED"));
    }

    @Test
    void notesOnlyChangeIsAuditedSeparatelyAndNoChangeIsSilent() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        long copy = copies(id).get(0).id();
        svc.updateCopy(admin, copy, "AVAILABLE", "shelf 3");
        assertEquals(1, audits("COPY_UPDATED"));
        svc.updateCopy(admin, copy, "AVAILABLE", "shelf 3");   // nothing changed
        assertEquals(1, audits("COPY_UPDATED"));
        assertEquals(0, audits("COPY_STATUS_CHANGED"));
    }

    @Test
    void invalidCopyStatusIsRefused() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        long copy = copies(id).get(0).id();
        assertThrows(ValidationException.class, () -> svc.updateCopy(admin, copy, "STOLEN", ""));
        assertThrows(ValidationException.class, () -> svc.updateCopy(admin, copy, "BORROWED", "")); // only circulation does that
        assertThrows(ValidationException.class, () -> svc.updateCopy(admin, copy, null, ""));
        assertThrows(ValidationException.class, () -> svc.updateCopy(admin, 9999, "LOST", ""));
        assertEquals("AVAILABLE", copies(id).get(0).status());
    }

    @Test
    void copyOnLoanKeepsItsStatusButNotesCanChange() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 1);
        long copy = copies(id).get(0).id();
        lend(copy, "2026-12-01");

        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> svc.updateCopy(admin, copy, "AVAILABLE", ""));
        assertTrue(e.getMessage().contains("on loan"));
        assertThrows(BusinessRuleException.class, () -> svc.updateCopy(admin, copy, "LOST", ""));
        assertDoesNotThrow(() -> svc.updateCopy(admin, copy, "BORROWED", "water stain"));
        assertEquals("water stain", copies(id).get(0).notes());
        assertEquals("BORROWED", copies(id).get(0).status());
    }

    @Test
    void removingCopiesIsOnlyForCopiesWithoutHistory() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 3);
        long clean = copies(id).get(2).id();
        long onLoan = copies(id).get(0).id();
        long returned = copies(id).get(1).id();
        lend(onLoan, "2026-12-01");
        giveBack(lend(returned, "2026-11-20"), returned);

        assertThrows(BusinessRuleException.class, () -> svc.removeCopy(admin, onLoan));     // borrowed now
        BusinessRuleException history = assertThrows(BusinessRuleException.class, () -> svc.removeCopy(admin, returned));
        assertTrue(history.getMessage().contains("history"));
        svc.removeCopy(admin, clean);
        assertEquals(2, copies(id).size());
        assertEquals(1, audits("COPY_REMOVED"));
        assertThrows(ValidationException.class, () -> svc.removeCopy(admin, clean));         // already gone
    }

    @Test
    void borrowerIsShownOnlyToUsersWhoMayViewStudents() throws Exception {
        long id = svc.create(admin, book("Dune", "Herbert"), 2);
        lend(copies(id).get(0).id(), "2026-12-01");

        CopyView withName = svc.copies(admin, id).get(0);
        assertEquals("BORROWED", withName.status());
        assertEquals("Reyes, Ana", withName.borrower());
        assertEquals("2026-12-01", withName.dueDate());
        assertNull(svc.copies(admin, id).get(1).borrower());

        Session booksOnly = new Session(5, "b", "B", "CUSTOM", EnumSet.of(Permission.BOOK_VIEW),
                Duration.ofMinutes(5), Instant.now(), false);
        CopyView hidden = svc.copies(booksOnly, id).get(0);
        assertNull(hidden.borrower());
        assertEquals("2026-12-01", hidden.dueDate());
    }

    // ---- searching and filtering
    @Test
    void searchFindsTitleAuthorIsbnAndCopyCode() throws Exception {
        long dune = svc.create(admin, new BookInput("978-0-441-01359-3", "Dune", "Frank Herbert", "Chilton", null, null, ""), 1);
        svc.create(admin, book("Emma", "Jane Austen"), 1);

        assertEquals(1, search("dune").total());
        assertEquals(1, search("HERBERT").total());
        assertEquals(1, search("Chilton").total());
        assertEquals(1, search("978-0-441-01359-3").total());     // typed with hyphens
        assertEquals(1, search("9780441013593").total());
        assertEquals(1, search("0441").total());
        assertEquals(1, search("B000001-01").total());            // by copy code
        assertEquals(dune, search("B000001-01").items().get(0).id());
        assertEquals(2, search("").total());
        assertEquals(0, search("nothing like this").total());
    }

    @Test
    void searchTreatsWildcardsAndSqlLiterally() throws Exception {
        create("100% Pure", "Someone");
        create("Dune", "Herbert");
        assertEquals(1, search("%").total());                      // matches only the real percent sign
        assertEquals(0, search("_").total());
        assertEquals(0, search("' OR 1=1 --").total());
        assertEquals(2, count("SELECT COUNT(*) FROM books"));
    }

    @Test
    void filtersByCategoryAndAvailabilityAndPages() throws Exception {
        long scifi = svc.createCategory(admin, "Sci-Fi");
        long dune = svc.create(admin, new BookInput("", "Dune", "Herbert", "", null, scifi, ""), 1);
        long emma = svc.create(admin, book("Emma", "Austen"), 1);
        create("Hamlet", "Shakespeare");                           // no copies
        create("100% Pure", "Someone");                            // no copies
        lend(copies(emma).get(0).id(), "2026-12-01");              // Emma: all copies out

        assertEquals(1, svc.list(admin, "", scifi, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 10).total());
        assertEquals(1, svc.list(admin, "", null, AvailabilityFilter.AVAILABLE, ArchiveFilter.ACTIVE, 0, 10).total());
        assertEquals(dune, svc.list(admin, "", null, AvailabilityFilter.AVAILABLE, ArchiveFilter.ACTIVE, 0, 10).items().get(0).id());
        assertEquals(3, svc.list(admin, "", null, AvailabilityFilter.UNAVAILABLE, ArchiveFilter.ACTIVE, 0, 10).total());

        var first = svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 3);
        assertEquals(4, first.total());
        assertEquals(List.of("100% Pure", "Dune", "Emma"), first.items().stream().map(BookView::title).toList());
        assertEquals(List.of("Hamlet"), svc.list(admin, "", null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 3, 3)
                .items().stream().map(BookView::title).toList());
    }

    PageResultHolder search(String term) {
        var r = svc.list(admin, term, null, AvailabilityFilter.ALL, ArchiveFilter.ACTIVE, 0, 20);
        return new PageResultHolder(r.total(), r.items());
    }

    record PageResultHolder(int total, List<BookView> items) {}

    // ---- categories
    @Test
    void categoriesCanBeAddedRenamedAndDeleted() throws Exception {
        long id = svc.createCategory(admin, "  Science   Fiction ");
        assertEquals("Science Fiction", svc.categories(admin).get(0).name());
        assertThrows(ValidationException.class, () -> svc.createCategory(admin, "science fiction"));   // case-insensitive
        assertThrows(ValidationException.class, () -> svc.createCategory(admin, ""));
        assertThrows(ValidationException.class, () -> svc.createCategory(admin, "<b>x</b>"));

        svc.renameCategory(admin, id, "Sci-Fi");
        assertEquals("Sci-Fi", svc.categories(admin).get(0).name());
        svc.renameCategory(admin, id, "sci-fi");                       // changing only the case of its own name is fine
        long other = svc.createCategory(admin, "History");
        assertThrows(ValidationException.class, () -> svc.renameCategory(admin, other, "SCI-FI"));

        svc.deleteCategory(admin, other);
        assertEquals(1, svc.categories(admin).size());
        assertEquals(1, audits("CATEGORY_DELETED"));
        assertEquals(2, audits("CATEGORY_CREATED"));
        assertEquals(2, audits("CATEGORY_RENAMED"));
        assertThrows(ValidationException.class, () -> svc.deleteCategory(admin, other));               // already gone
    }

    @Test
    void categoryInUseCannotBeDeleted() throws Exception {
        long cat = svc.createCategory(admin, "Fiction");
        svc.create(admin, new BookInput("", "Dune", "Herbert", "", null, cat, ""), 0);
        assertEquals(1, svc.categories(admin).get(0).bookCount());
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> svc.deleteCategory(admin, cat));
        assertTrue(e.getMessage().contains("One book"));
        assertEquals(1, svc.categories(admin).size());
    }
}
