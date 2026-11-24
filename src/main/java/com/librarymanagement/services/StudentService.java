package com.librarymanagement.services;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.StudentRepository;
import com.librarymanagement.database.repositories.StudentRepository.LoanRecord;
import com.librarymanagement.database.repositories.StudentRepository.StudentRecord;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.StudentRules;
import com.librarymanagement.validation.ValidationException;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Student records. Students are library members, not application users, and are never deleted:
 * they are deactivated (an inactive student cannot borrow, but can still return books and keeps their history).
 * - viewing needs STUDENT_VIEW; changing needs STUDENT_MANAGE;
 * - contact information is only returned to users who may manage students;
 * - duplicates (same name, grade and section) and duplicate student IDs are refused;
 * - IDs are generated from the configurable format when left blank.
 */
public class StudentService {

    public enum StatusFilter { ALL, ACTIVE, INACTIVE }

    /** Form input; empty strings mean "not provided". studentNo blank = generate automatically (create only). */
    public record StudentInput(String studentNo, String firstName, String middleName, String lastName,
                               String program, String grade, String section, String contact) {}

    public record StudentView(long id, String studentNo, String firstName, String middleName, String lastName,
                              String displayName, String program, String grade, String section, String contact,
                              boolean active, String registeredAt) {}

    public record StudentProfile(StudentView student, int currentLoans, int overdueLoans, int totalLoans) {}

    /** state is one of ACTIVE, OVERDUE, RETURNED, LOST */
    public record LoanRow(String bookTitle, String copyCode, String issueDate, String dueDate, String returnedOn,
                          String state, String condition) {}

    public record Options(List<String> programs, List<String> grades, List<String> sections) {}

    private static final int STUDENT_NO_MAX = 30;

    private final DatabaseManager db;
    private final StudentRepository students;
    private final SettingsRepository settings;
    private final AuditRepository audit;
    private final Clock clock;

    public StudentService(DatabaseManager db, StudentRepository students, SettingsRepository settings,
                          AuditRepository audit, Clock clock) {
        this.db = db;
        this.students = students;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** @param grade null for every grade */
    public PageResult<StudentView> list(Session session, String search, StatusFilter status, String grade,
                                        int offset, int limit) {
        session.require(Permission.STUDENT_VIEW);
        boolean showContact = session.has(Permission.STUDENT_MANAGE);
        String statusValue = status == null || status == StatusFilter.ALL ? null : status.name();
        String gradeValue = grade == null || grade.isBlank() ? null : grade;
        return db.inTransactionless(c -> {
            List<StudentView> items = new ArrayList<>();
            for (StudentRecord r : students.list(c, search, statusValue, gradeValue, Math.max(0, offset), Math.max(1, limit))) {
                items.add(view(r, showContact));
            }
            return new PageResult<>(items, students.count(c, search, statusValue, gradeValue));
        });
    }

    public StudentProfile profile(Session session, long studentId) {
        session.require(Permission.STUDENT_VIEW);
        boolean showContact = session.has(Permission.STUDENT_MANAGE);
        String today = today().toString();
        return db.inTransactionless(c -> {
            StudentRecord record = find(c, studentId);
            return new StudentProfile(view(record, showContact),
                    students.countActiveLoans(c, studentId),
                    students.countOverdueLoans(c, studentId, today),
                    students.countLoans(c, studentId));
        });
    }

    /** Borrowing history, active loans first. */
    public PageResult<LoanRow> loans(Session session, long studentId, int offset, int limit) {
        session.require(Permission.STUDENT_VIEW);
        String today = today().toString();
        return db.inTransactionless(c -> {
            find(c, studentId);
            List<LoanRow> rows = new ArrayList<>();
            for (LoanRecord r : students.loans(c, studentId, Math.max(0, offset), Math.max(1, limit))) {
                rows.add(new LoanRow(r.bookTitle(), r.copyCode(), r.issueDate(), r.dueDate(), r.returnDate(),
                        state(r, today), r.condition()));
            }
            return new PageResult<>(rows, students.countLoans(c, studentId));
        });
    }

    /** Existing values for program, grade and section, so forms can suggest consistent spelling. */
    public Options options(Session session) {
        session.require(Permission.STUDENT_VIEW);
        return db.inTransactionless(c -> new Options(
                students.distinctProgram(c), students.distinctGrade(c), students.distinctSection(c)));
    }

    // ------------------------------------------------------------------ changes

    public long create(Session session, StudentInput input) {
        session.require(Permission.STUDENT_MANAGE);
        StudentInput in = normalise(input);
        validate(in);

        return db.inTransaction(c -> {
            rejectDuplicateIdentity(c, in, -1);
            String studentNo = in.studentNo();
            if (studentNo.isEmpty()) {
                studentNo = nextStudentNo(c);
            } else if (students.studentNoExists(c, studentNo)) {
                throw new ValidationException("That student ID is already in use.");
            }
            long id = students.insert(c, studentNo, in.firstName(), in.middleName(), in.lastName(),
                    in.program(), in.grade(), in.section(), in.contact());
            audit.append(c, session.userId(), "STUDENT_CREATED", "student", String.valueOf(id),
                    "student_no=" + studentNo, null, describe(in));
            return id;
        });
    }

    /** Updates everything except the student ID, which never changes. */
    public void update(Session session, long studentId, StudentInput input) {
        session.require(Permission.STUDENT_MANAGE);
        StudentInput in = normalise(input);
        validate(in);

        db.inTransaction(c -> {
            StudentRecord old = find(c, studentId);
            rejectDuplicateIdentity(c, in, studentId);
            students.update(c, studentId, in.firstName(), in.middleName(), in.lastName(),
                    in.program(), in.grade(), in.section(), in.contact());
            boolean contactChanged = !nullToEmpty(old.contact()).equals(in.contact());
            audit.append(c, session.userId(), "STUDENT_UPDATED", "student", String.valueOf(studentId),
                    "student_no=" + old.studentNo() + (contactChanged ? " contact_changed=true" : ""),
                    describe(old), describe(in));
            return null;
        });
    }

    public void setActive(Session session, long studentId, boolean active) {
        session.require(Permission.STUDENT_MANAGE);
        db.inTransaction(c -> {
            StudentRecord target = find(c, studentId);
            if (target.active() == active) return null;
            students.setActive(c, studentId, active);
            audit.append(c, session.userId(), active ? "STUDENT_ACTIVATED" : "STUDENT_DEACTIVATED", "student",
                    String.valueOf(studentId), "student_no=" + target.studentNo(),
                    active ? "inactive" : "active", active ? "active" : "inactive");
            return null;
        });
    }

    // ------------------------------------------------------------------ internals

    private String nextStudentNo(Connection c) throws SQLException {
        int year = today().getYear();
        StudentIdFormat format = StudentIdFormat.parseOrDefault(
                settings.get(c, "student.id_format", StudentIdFormat.DEFAULT));
        int highest = 0;
        for (String existing : students.studentNosStartingWith(c, format.prefix(year))) {
            OptionalInt seq = format.extractSequence(year, existing);
            if (seq.isPresent() && seq.getAsInt() > highest) highest = seq.getAsInt();
        }
        String next = format.format(year, (long) highest + 1);
        if (next.length() > STUDENT_NO_MAX) {
            throw new BusinessRuleException("The generated student ID is too long. Shorten the student ID format in settings.");
        }
        return next;
    }

    private void rejectDuplicateIdentity(Connection c, StudentInput in, long excludedId) throws SQLException {
        var duplicate = students.findDuplicateIdentity(c, in.firstName(), in.lastName(), in.grade(), in.section(), excludedId);
        if (duplicate.isPresent()) {
            throw new ValidationException("A student with the same name, grade and section already exists (ID "
                    + duplicate.get() + ").");
        }
    }

    private static void validate(StudentInput in) {
        List<String> problems = StudentRules.check(in.studentNo(), in.firstName(), in.middleName(), in.lastName(),
                in.program(), in.grade(), in.section(), in.contact());
        if (!problems.isEmpty()) throw new ValidationException(problems);
    }

    private static StudentInput normalise(StudentInput in) {
        return new StudentInput(StudentRules.normalize(in.studentNo()), StudentRules.normalize(in.firstName()),
                StudentRules.normalize(in.middleName()), StudentRules.normalize(in.lastName()),
                StudentRules.normalize(in.program()), StudentRules.normalize(in.grade()),
                StudentRules.normalize(in.section()), StudentRules.normalize(in.contact()));
    }

    private StudentRecord find(Connection c, long id) throws SQLException {
        return students.findById(c, id).orElseThrow(() -> new ValidationException("That student no longer exists."));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    private static StudentView view(StudentRecord r, boolean showContact) {
        String middle = nullToEmpty(r.middleName());
        String display = r.lastName() + ", " + r.firstName() + (middle.isEmpty() ? "" : " " + middle.charAt(0) + ".");
        return new StudentView(r.id(), r.studentNo(), r.firstName(), middle, r.lastName(), display, r.program(),
                r.grade(), r.section(), showContact ? nullToEmpty(r.contact()) : null, r.active(), r.registeredAt());
    }

    private static String state(LoanRecord r, String today) {
        return switch (r.status()) {
            case "ACTIVE" -> r.dueDate().compareTo(today) < 0 ? "OVERDUE" : "ACTIVE";
            case "LOST" -> "LOST";
            default -> "RETURNED";
        };
    }

    /** Audit text without contact details. */
    private static String describe(StudentInput in) {
        return "name=" + in.lastName() + ", " + in.firstName() + (in.middleName().isEmpty() ? "" : " " + in.middleName())
                + "; program=" + in.program() + "; grade=" + in.grade() + "; section=" + in.section();
    }

    private static String describe(StudentRecord r) {
        String middle = nullToEmpty(r.middleName());
        return "name=" + r.lastName() + ", " + r.firstName() + (middle.isEmpty() ? "" : " " + middle)
                + "; program=" + r.program() + "; grade=" + r.grade() + "; section=" + r.section();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
