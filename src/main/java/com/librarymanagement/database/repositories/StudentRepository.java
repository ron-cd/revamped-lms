package com.librarymanagement.database.repositories;

import com.librarymanagement.database.SqlText;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for students and their loan history. Connections are supplied by the service (which owns transactions). */
public class StudentRepository {

    public record StudentRecord(long id, String studentNo, String firstName, String middleName, String lastName,
                                String program, String grade, String section, String contact, boolean active,
                                String registeredAt) {}

    public record LoanRecord(String bookTitle, String copyCode, String issueDate, String dueDate, String status,
                             String returnDate, String condition) {}

    private static final String COLUMNS =
            "id, student_no, first_name, middle_name, last_name, program_strand, grade_level, section, contact, status, registered_at";

    // ------------------------------------------------------------------ reads

    public Optional<StudentRecord> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM students WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean studentNoExists(Connection c, String studentNo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM students WHERE student_no = ?")) {
            ps.setString(1, studentNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Student ID of another student with the same name, grade and section (case-insensitive), if any. */
    public Optional<String> findDuplicateIdentity(Connection c, String first, String last, String grade,
                                                   String section, long excludedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT student_no FROM students
                WHERE lower(first_name) = lower(?) AND lower(last_name) = lower(?)
                  AND grade_level = ? AND section = ? AND id <> ?""")) {
            ps.setString(1, first);
            ps.setString(2, last);
            ps.setString(3, grade);
            ps.setString(4, section);
            ps.setLong(5, excludedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public List<String> studentNosStartingWith(Connection c, String prefix) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT student_no FROM students WHERE student_no LIKE ? ESCAPE '\\'")) {
            ps.setString(1, SqlText.likePrefix(prefix));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    public List<StudentRecord> list(Connection c, String search, String status, String grade,
                                    int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(search, status, grade, params);
        List<StudentRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM students " + where
                + " ORDER BY last_name COLLATE NOCASE, first_name COLLATE NOCASE, id LIMIT ? OFFSET ?")) {
            int i = bind(ps, params);
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
        }
        return rows;
    }

    public int count(Connection c, String search, String status, String grade) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(search, status, grade, params);
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM students " + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String where(String search, String status, String grade, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String pattern = SqlText.likeContains(search);
            clauses.add("(student_no LIKE ? ESCAPE '\\' OR first_name LIKE ? ESCAPE '\\' OR last_name LIKE ? ESCAPE '\\'"
                    + " OR (first_name || ' ' || last_name) LIKE ? ESCAPE '\\'"
                    + " OR (last_name || ', ' || first_name) LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) params.add(pattern);
        }
        if (status != null) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (grade != null) {
            clauses.add("grade_level = ?");
            params.add(grade);
        }
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) ps.setObject(i++, p);
        return i;
    }

    /** Distinct values of one of the three descriptive columns (used to suggest consistent spelling in forms). */
    public List<String> distinctProgram(Connection c) throws SQLException { return distinct(c, "program_strand"); }
    public List<String> distinctGrade(Connection c) throws SQLException   { return distinct(c, "grade_level"); }
    public List<String> distinctSection(Connection c) throws SQLException { return distinct(c, "section"); }

    private List<String> distinct(Connection c, String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT " + column + " FROM students ORDER BY " + column + " COLLATE NOCASE")) {
            while (rs.next()) values.add(rs.getString(1));
        }
        return values;
    }

    // ------------------------------------------------------------------ writes

    public long insert(Connection c, String studentNo, String first, String middle, String last, String program,
                       String grade, String section, String contact) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO students(student_no, first_name, middle_name, last_name, program_strand, grade_level,
                                     section, contact) VALUES(?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, studentNo);
            ps.setString(2, first);
            ps.setString(3, emptyToNull(middle));
            ps.setString(4, last);
            ps.setString(5, program);
            ps.setString(6, grade);
            ps.setString(7, section);
            ps.setString(8, emptyToNull(contact));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void update(Connection c, long id, String first, String middle, String last, String program,
                       String grade, String section, String contact) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE students SET first_name = ?, middle_name = ?, last_name = ?, program_strand = ?,
                                    grade_level = ?, section = ?, contact = ? WHERE id = ?""")) {
            ps.setString(1, first);
            ps.setString(2, emptyToNull(middle));
            ps.setString(3, last);
            ps.setString(4, program);
            ps.setString(5, grade);
            ps.setString(6, section);
            ps.setString(7, emptyToNull(contact));
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    public void setActive(Connection c, long id, boolean active) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE students SET status = ? WHERE id = ?")) {
            ps.setString(1, active ? "ACTIVE" : "INACTIVE");
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ loans (read-only views of circulation data)

    public List<LoanRecord> loans(Connection c, long studentId, int offset, int limit) throws SQLException {
        List<LoanRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT b.title, bc.copy_code, t.issue_date, ti.due_date, ti.status, r.return_date, r.condition
                FROM transaction_items ti
                JOIN transactions t ON t.id = ti.transaction_id
                JOIN book_copies bc ON bc.id = ti.copy_id
                JOIN books b ON b.id = bc.book_id
                LEFT JOIN returns r ON r.item_id = ti.id
                WHERE t.student_id = ? AND ti.status <> 'REVERSED'
                ORDER BY (ti.status = 'ACTIVE') DESC, t.issue_date DESC, ti.id DESC
                LIMIT ? OFFSET ?""")) {
            ps.setLong(1, studentId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LoanRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7)));
                }
            }
        }
        return rows;
    }

    public int countLoans(Connection c, long studentId) throws SQLException {
        return scalar(c, """
                SELECT COUNT(*) FROM transaction_items ti JOIN transactions t ON t.id = ti.transaction_id
                WHERE t.student_id = ? AND ti.status <> 'REVERSED'""", studentId, null);
    }

    public int countActiveLoans(Connection c, long studentId) throws SQLException {
        return scalar(c, """
                SELECT COUNT(*) FROM transaction_items ti JOIN transactions t ON t.id = ti.transaction_id
                WHERE t.student_id = ? AND ti.status = 'ACTIVE'""", studentId, null);
    }

    /** @param today ISO date (yyyy-MM-dd); loans due before it and not returned are overdue */
    public int countOverdueLoans(Connection c, long studentId, String today) throws SQLException {
        return scalar(c, """
                SELECT COUNT(*) FROM transaction_items ti JOIN transactions t ON t.id = ti.transaction_id
                WHERE t.student_id = ? AND ti.status = 'ACTIVE' AND ti.due_date < ?""", studentId, today);
    }

    private static int scalar(Connection c, String sql, long studentId, String text) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, studentId);
            if (text != null) ps.setString(2, text);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private static StudentRecord map(ResultSet rs) throws SQLException {
        return new StudentRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                "ACTIVE".equals(rs.getString(10)), rs.getString(11));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
