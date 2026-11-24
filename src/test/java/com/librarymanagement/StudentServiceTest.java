package com.librarymanagement;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.StudentRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.AuthorizationException;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.services.PageResult;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.services.StudentService;
import com.librarymanagement.services.StudentService.LoanRow;
import com.librarymanagement.services.StudentService.StatusFilter;
import com.librarymanagement.services.StudentService.StudentInput;
import com.librarymanagement.services.StudentService.StudentView;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class StudentServiceTest {

    static final class FixedClock extends Clock {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.parse("2026-11-23T08:00:00Z"); }
    }

    static final String PW = "Str0ngPassw0rd";

    @TempDir Path tmp;
    DatabaseManager db;
    PasswordHasher hasher = new PasswordHasher();
    UserRepository users = new UserRepository();
    StudentService svc;
    Session admin;
    Session librarian;
    Session assistant;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(tmp.resolve("t.db"));
        new MigrationRunner(db).migrate();
        Clock clock = new FixedClock();
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
        svc = new StudentService(db, new StudentRepository(), new SettingsRepository(), audit, clock);
    }

    // ---- helpers
    static StudentInput input(String first, String last, String grade, String section) {
        return new StudentInput("", first, "", last, "STEM", grade, section, "");
    }

    long create(String first, String last, String grade, String section) {
        return svc.create(admin, input(first, last, grade, section));
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

    long insertCopy(String title, String code) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO books(title, author) VALUES('" + title + "', 'Author')");
            long bookId;
            try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) { rs.next(); bookId = rs.getLong(1); }
            st.execute("INSERT INTO book_copies(book_id, copy_code) VALUES(" + bookId + ", '" + code + "')");
            try (ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) { rs.next(); return rs.getLong(1); }
        }
    }

    /** Issues a copy through the same table rules the real circulation module will use. Returns the item id. */
    long issue(long studentId, long copyId, String dueDate) {
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
                ps.setString(3, dueDate);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { k.next(); itemId = k.getLong(1); }
            }
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE book_copies SET status = 'BORROWED' WHERE id = " + copyId);
            }
            return itemId;
        });
    }

    void giveBack(long itemId, long copyId, String condition) {
        db.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO returns(item_id, return_date, condition, processed_by) VALUES(" + itemId + ", '2026-11-15', '" + condition + "', 1)");
                st.execute("UPDATE transaction_items SET status = 'RETURNED' WHERE id = " + itemId);
                st.execute("UPDATE book_copies SET status = 'AVAILABLE' WHERE id = " + copyId);
            }
            return null;
        });
    }

    // ---- creating
    @Test
    void generatesSequentialStudentIdsFromTheFormat() throws Exception {
        long a = create("Ana", "Reyes", "Grade 11", "A");
        long b = create("Ben", "Cruz", "Grade 11", "A");
        assertEquals("2026-00001", text("SELECT student_no FROM students WHERE id = ?", a));
        assertEquals("2026-00002", text("SELECT student_no FROM students WHERE id = ?", b));
        assertEquals(2, audits("STUDENT_CREATED") );
    }

    @Test
    void manualIdsAreAcceptedAndMustBeUnique() throws Exception {
        svc.create(admin, new StudentInput("LEGACY-7", "Ana", "", "Reyes", "STEM", "11", "A", ""));
        ValidationException e = assertThrows(ValidationException.class, () ->
                svc.create(admin, new StudentInput("LEGACY-7", "Ben", "", "Cruz", "STEM", "11", "A", "")));
        assertTrue(e.getMessage().contains("already in use"));
        // a manual ID of another style, or another year, does not disturb automatic numbering
        svc.create(admin, new StudentInput("2025-00099", "Cy", "", "Lim", "STEM", "11", "A", ""));
        long next = create("Dan", "Uy", "11", "A");
        assertEquals("2026-00001", text("SELECT student_no FROM students WHERE id = ?", next));
    }

    @Test
    void idFormatComesFromSettingsAndFallsBackWhenInvalid() throws Exception {
        sql("UPDATE system_settings SET value = 'S{YY}-{SEQ:4}' WHERE key = 'student.id_format'");
        long a = create("Ana", "Reyes", "11", "A");
        assertEquals("S26-0001", text("SELECT student_no FROM students WHERE id = ?", a));

        sql("UPDATE system_settings SET value = 'broken' WHERE key = 'student.id_format'");
        long b = create("Ben", "Cruz", "11", "A");
        assertEquals("2026-00001", text("SELECT student_no FROM students WHERE id = ?", b));
    }

    @Test
    void duplicateStudentIsRejectedIgnoringCase() throws Exception {
        create("Ana", "Reyes", "Grade 11", "A");
        ValidationException e = assertThrows(ValidationException.class, () -> create("ANA", "reyes", "Grade 11", "A"));
        assertTrue(e.getMessage().contains("already exists"));
        assertTrue(e.getMessage().contains("2026-00001")); // tells staff which record clashes
        // same name in another section is a different student
        assertDoesNotThrow(() -> create("Ana", "Reyes", "Grade 11", "B"));
        assertEquals(2, count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        assertThrows(ValidationException.class, () -> svc.create(admin, input("An4", "Reyes", "11", "A")));
        assertThrows(ValidationException.class, () -> svc.create(admin, input("", "Reyes", "11", "A")));
        assertThrows(ValidationException.class, () -> svc.create(admin, input("Ana", "Reyes", "", "A")));
        assertThrows(ValidationException.class, () -> svc.create(admin, input("Ana'); DROP TABLE students;--", "Reyes", "11", "A")));
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new StudentInput("bad id!", "Ana", "", "Reyes", "STEM", "11", "A", "")));
        assertThrows(ValidationException.class, () -> svc.create(admin,
                new StudentInput("", "Ana", "", "Reyes", "STEM", "11", "A", "x".repeat(101))));
        assertEquals(0, count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void namesAreTrimmedAndSpacesCollapsed() throws Exception {
        long id = svc.create(admin, new StudentInput("", "  Ana   Marie ", " d ", " De  la  Cruz ", " STEM ", " Grade  11", " A ", ""));
        assertEquals("Ana Marie", text("SELECT first_name FROM students WHERE id = ?", id));
        assertEquals("De la Cruz", text("SELECT last_name FROM students WHERE id = ?", id));
        assertEquals("Grade 11", text("SELECT grade_level FROM students WHERE id = ?", id));
    }

    // ---- permissions
    @Test
    void assistantCanLookUpButNotChangeStudents() throws Exception {
        long id = svc.create(admin, new StudentInput("", "Ana", "", "Reyes", "STEM", "11", "A", "0917 000 0000"));
        assertThrows(AuthorizationException.class, () -> svc.create(assistant, input("Ben", "Cruz", "11", "A")));
        assertThrows(AuthorizationException.class, () -> svc.update(assistant, id, input("Ana", "Reyes", "11", "B")));
        assertThrows(AuthorizationException.class, () -> svc.setActive(assistant, id, false));

        PageResult<StudentView> seen = svc.list(assistant, "", StatusFilter.ALL, null, 0, 10);
        assertEquals(1, seen.total());
        assertNull(seen.items().get(0).contact(), "assistants must not receive contact details");
        assertNull(svc.profile(assistant, id).student().contact());
        assertNotNull(svc.options(assistant));
    }

    @Test
    void librarianAndAdministratorSeeAndChangeEverything() throws Exception {
        long id = svc.create(librarian, new StudentInput("", "Ana", "", "Reyes", "STEM", "11", "A", "0917 000 0000"));
        assertEquals("0917 000 0000", svc.list(librarian, "", StatusFilter.ALL, null, 0, 10).items().get(0).contact());
        assertEquals("0917 000 0000", svc.profile(admin, id).student().contact());
    }

    @Test
    void endedSessionIsRefused() throws Exception {
        Session ended = new Session(1, "x", "X", "ADMINISTRATOR",
                java.util.EnumSet.allOf(com.librarymanagement.security.Permission.class),
                java.time.Duration.ofMinutes(5), Instant.now(), false);
        ended.end();
        assertThrows(AuthorizationException.class, () -> svc.list(ended, "", StatusFilter.ALL, null, 0, 10));
    }

    // ---- updating
    @Test
    void updateChangesDetailsButNeverTheStudentId() throws Exception {
        long id = svc.create(admin, new StudentInput("", "Ana", "", "Reyes", "STEM", "11", "A", "secret-contact-123"));
        svc.update(admin, id, new StudentInput("IGNORED-ID", "Anna", "M", "Reyes", "ABM", "12", "B", "new-contact-456"));

        assertEquals("2026-00001", text("SELECT student_no FROM students WHERE id = ?", id));
        assertEquals("Anna", text("SELECT first_name FROM students WHERE id = ?", id));
        assertEquals("ABM", text("SELECT program_strand FROM students WHERE id = ?", id));
        assertEquals("new-contact-456", text("SELECT contact FROM students WHERE id = ?", id));
        assertEquals(1, audits("STUDENT_UPDATED"));
        // contact details must not be copied into the audit log
        assertEquals(0, count("SELECT COUNT(*) FROM audit_logs WHERE COALESCE(details,'')||COALESCE(old_value,'')||COALESCE(new_value,'') LIKE '%contact-%'"));
        assertEquals("contact_changed=true", text("SELECT substr(details, instr(details, ' ') + 1) FROM audit_logs WHERE action = 'STUDENT_UPDATED'"));
    }

    @Test
    void updateCannotMakeTwoStudentsIdentical() throws Exception {
        create("Ana", "Reyes", "11", "A");
        long other = create("Ana", "Reyes", "11", "B");
        assertThrows(ValidationException.class, () -> svc.update(admin, other, input("Ana", "Reyes", "11", "A")));
        // saving a student without changing identity is fine
        assertDoesNotThrow(() -> svc.update(admin, other, input("Ana", "Reyes", "11", "B")));
    }

    @Test
    void unknownStudentsAreRejected() throws Exception {
        assertThrows(ValidationException.class, () -> svc.update(admin, 9999, input("Ana", "Reyes", "11", "A")));
        assertThrows(ValidationException.class, () -> svc.setActive(admin, 9999, false));
        assertThrows(ValidationException.class, () -> svc.profile(admin, 9999));
        assertThrows(ValidationException.class, () -> svc.loans(admin, 9999, 0, 10));
    }

    // ---- activate / deactivate
    @Test
    void deactivatedStudentCannotBorrowButHistoryIsKept() throws Exception {
        long id = create("Ana", "Reyes", "11", "A");
        long copy = insertCopy("Dune", "B000001-01");
        svc.setActive(admin, id, false);
        assertEquals("INACTIVE", text("SELECT status FROM students WHERE id = ?", id));
        assertEquals(1, audits("STUDENT_DEACTIVATED"));
        // the database refuses a loan for an inactive student
        assertThrows(RuntimeException.class, () -> issue(id, copy, "2026-12-01"));

        svc.setActive(admin, id, true);
        assertEquals(1, audits("STUDENT_ACTIVATED"));
        assertDoesNotThrow(() -> issue(id, copy, "2026-12-01"));
    }

    @Test
    void settingTheSameStatusAddsNoAuditEntry() throws Exception {
        long id = create("Ana", "Reyes", "11", "A");
        svc.setActive(admin, id, true);
        assertEquals(0, audits("STUDENT_ACTIVATED"));
    }

    // ---- searching, filtering, paging
    @Test
    void searchFilterAndPaging() throws Exception {
        for (int i = 1; i <= 7; i++) create("Student" + "abcdefg".charAt(i - 1), "Lastname", i <= 4 ? "Grade 11" : "Grade 12", "A");
        svc.setActive(admin, create("Zed", "Zulu", "Grade 12", "B"), false);

        assertEquals(8, svc.list(admin, "", StatusFilter.ALL, null, 0, 5).total());
        assertEquals(5, svc.list(admin, "", StatusFilter.ALL, null, 0, 5).items().size());
        assertEquals(3, svc.list(admin, "", StatusFilter.ALL, null, 5, 5).items().size());
        assertEquals(7, svc.list(admin, "", StatusFilter.ACTIVE, null, 0, 20).total());
        assertEquals(1, svc.list(admin, "", StatusFilter.INACTIVE, null, 0, 20).total());
        assertEquals(4, svc.list(admin, "", StatusFilter.ALL, "Grade 11", 0, 20).total());
        assertEquals(1, svc.list(admin, "2026-00003", StatusFilter.ALL, null, 0, 20).total());   // by ID
        assertEquals(1, svc.list(admin, "Zulu, Zed", StatusFilter.ALL, null, 0, 20).total());    // "Last, First"
        assertEquals(1, svc.list(admin, "zed zulu", StatusFilter.ALL, null, 0, 20).total());     // "First Last"
        assertEquals(1, svc.list(admin, "ZULU", StatusFilter.ALL, "Grade 12", 0, 20).total());   // filters combine
    }

    @Test
    void searchTreatsWildcardsAndSqlLiterally() throws Exception {
        create("Ana", "Reyes", "11", "A");
        assertEquals(0, svc.list(admin, "%", StatusFilter.ALL, null, 0, 10).total());
        assertEquals(0, svc.list(admin, "_", StatusFilter.ALL, null, 0, 10).total());
        assertEquals(0, svc.list(admin, "' OR 1=1 --", StatusFilter.ALL, null, 0, 10).total());
        assertEquals(1, count("SELECT COUNT(*) FROM students"));
    }

    @Test
    void displayNameShowsLastNameFirstWithMiddleInitial() throws Exception {
        svc.create(admin, new StudentInput("", "Ana", "Marie", "Reyes", "STEM", "11", "A", ""));
        assertEquals("Reyes, Ana M.", svc.list(admin, "", StatusFilter.ALL, null, 0, 10).items().get(0).displayName());
    }

    @Test
    void optionsListDistinctValuesSorted() throws Exception {
        svc.create(admin, new StudentInput("", "Ana", "", "Reyes", "STEM", "Grade 12", "B", ""));
        svc.create(admin, new StudentInput("", "Ben", "", "Cruz", "ABM", "Grade 11", "A", ""));
        svc.create(admin, new StudentInput("", "Cy", "", "Lim", "STEM", "Grade 11", "A", ""));
        var o = svc.options(admin);
        assertEquals(java.util.List.of("ABM", "STEM"), o.programs());
        assertEquals(java.util.List.of("Grade 11", "Grade 12"), o.grades());
        assertEquals(java.util.List.of("A", "B"), o.sections());
    }

    // ---- profile and borrowing history
    @Test
    void profileCountsAndHistoryReflectLoans() throws Exception {
        long ana = create("Ana", "Reyes", "11", "A");
        long ben = create("Ben", "Cruz", "11", "A");
        long c1 = insertCopy("Book One", "B1-01");
        long c2 = insertCopy("Book Two", "B2-01");
        long c3 = insertCopy("Book Three", "B3-01");
        long c4 = insertCopy("Book Four", "B4-01");
        long c5 = insertCopy("Book Five", "B5-01");

        issue(ana, c1, "2026-11-10");                  // overdue: due before 2026-11-23, not returned
        issue(ana, c2, "2026-12-01");                  // borrowed, not yet due
        long returned = issue(ana, c3, "2026-11-12");
        giveBack(returned, c3, "GOOD");                // returned
        long reversed = issue(ana, c4, "2026-12-05");
        sql("UPDATE transaction_items SET status = 'REVERSED' WHERE id = ?", reversed); // corrected entry: hidden
        issue(ben, c5, "2026-11-01");                  // someone else's overdue loan

        var profile = svc.profile(admin, ana);
        assertEquals(2, profile.currentLoans());
        assertEquals(1, profile.overdueLoans());
        assertEquals(3, profile.totalLoans());

        PageResult<LoanRow> history = svc.loans(admin, ana, 0, 10);
        assertEquals(3, history.total());
        assertEquals("ACTIVE", history.items().get(0).state());    // active loans first
        assertEquals("OVERDUE", history.items().get(1).state());
        assertEquals("RETURNED", history.items().get(2).state());
        assertEquals("Book Three", history.items().get(2).bookTitle());
        assertEquals("2026-11-15", history.items().get(2).returnedOn());
        assertEquals("GOOD", history.items().get(2).condition());
        assertTrue(history.items().stream().noneMatch(r -> r.bookTitle().equals("Book Five")));

        assertEquals(1, svc.profile(admin, ben).overdueLoans());
        assertEquals(2, svc.loans(admin, ana, 0, 2).items().size()); // paging
        assertEquals(1, svc.loans(admin, ana, 2, 2).items().size());
    }

    @Test
    void studentWithoutLoansHasEmptyHistory() throws Exception {
        long id = create("Ana", "Reyes", "11", "A");
        var profile = svc.profile(admin, id);
        assertEquals(0, profile.totalLoans());
        assertTrue(svc.loans(admin, id, 0, 10).items().isEmpty());
    }
}
