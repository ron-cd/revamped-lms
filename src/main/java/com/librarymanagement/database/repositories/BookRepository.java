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
import java.util.regex.Pattern;

/** SQL for books, copies and categories. Connections are supplied by the service (which owns transactions). */
public class BookRepository {

    public record BookRecord(long id, String isbn, String title, String author, String publisher, Integer year,
                             Long categoryId, String category, String description, boolean archived,
                             int totalCopies, int availableCopies) {}

    public record CopyRecord(long id, long bookId, String code, String status, String acquiredAt, String notes,
                             String dueDate, String borrowerFirst, String borrowerLast) {}

    public record CategoryRecord(long id, String name, int bookCount) {}

    private static final Pattern ISBN_CHARS = Pattern.compile("[0-9Xx -]+");

    private static final String BOOK_SELECT = """
            SELECT b.id, b.isbn, b.title, b.author, b.publisher, b.publication_year, b.category_id, c.name,
                   b.description, b.is_archived,
                   (SELECT COUNT(*) FROM book_copies WHERE book_id = b.id),
                   (SELECT COUNT(*) FROM book_copies WHERE book_id = b.id AND status = 'AVAILABLE')
            FROM books b LEFT JOIN categories c ON c.id = b.category_id""";

    // ------------------------------------------------------------------ books: reads

    public Optional<BookRecord> findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(BOOK_SELECT + " WHERE b.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBook(rs)) : Optional.empty();
            }
        }
    }

    /** Title of another book already using this ISBN, if any. */
    public Optional<String> findTitleByIsbn(Connection c, String isbn, long excludedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT title FROM books WHERE isbn = ? AND id <> ?")) {
            ps.setString(1, isbn);
            ps.setLong(2, excludedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** True when another book has the same title, author, publisher and year (case-insensitive): the same edition. */
    public boolean sameEditionExists(Connection c, String title, String author, String publisher, Integer year,
                                     long excludedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM books
                WHERE lower(title) = lower(?) AND lower(author) = lower(?)
                  AND lower(COALESCE(publisher, '')) = lower(?)
                  AND COALESCE(publication_year, 0) = ? AND id <> ?""")) {
            ps.setString(1, title);
            ps.setString(2, author);
            ps.setString(3, publisher);
            ps.setInt(4, year == null ? 0 : year);
            ps.setLong(5, excludedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** @param availability null, "AVAILABLE" or "UNAVAILABLE"; @param archived null (any), 0 or 1 */
    public List<BookRecord> list(Connection c, String search, Long categoryId, String availability, Integer archived,
                                 int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(search, categoryId, availability, archived, params);
        List<BookRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(BOOK_SELECT + where
                + " ORDER BY b.title COLLATE NOCASE, b.author COLLATE NOCASE, b.id LIMIT ? OFFSET ?")) {
            int i = bind(ps, params);
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapBook(rs));
            }
        }
        return rows;
    }

    public int count(Connection c, String search, Long categoryId, String availability, Integer archived)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(search, categoryId, availability, archived, params);
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM books b" + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String where(String search, Long categoryId, String availability, Integer archived,
                                List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String text = SqlText.likeContains(search);
            // An ISBN may be typed with hyphens or spaces; stored ISBNs have none.
            String isbnText = ISBN_CHARS.matcher(search.trim()).matches()
                    ? SqlText.likeContains(search.replace("-", "").replace(" ", "")) : text;
            clauses.add("(b.title LIKE ? ESCAPE '\\' OR b.author LIKE ? ESCAPE '\\'"
                    + " OR COALESCE(b.publisher, '') LIKE ? ESCAPE '\\'"
                    + " OR COALESCE(b.isbn, '') LIKE ? ESCAPE '\\'"
                    + " OR EXISTS (SELECT 1 FROM book_copies bc WHERE bc.book_id = b.id AND bc.copy_code LIKE ? ESCAPE '\\'))");
            params.add(text);
            params.add(text);
            params.add(text);
            params.add(isbnText);
            params.add(text);
        }
        if (categoryId != null) {
            clauses.add("b.category_id = ?");
            params.add(categoryId);
        }
        if ("AVAILABLE".equals(availability)) {
            clauses.add("EXISTS (SELECT 1 FROM book_copies bc WHERE bc.book_id = b.id AND bc.status = 'AVAILABLE')");
        } else if ("UNAVAILABLE".equals(availability)) {
            clauses.add("NOT EXISTS (SELECT 1 FROM book_copies bc WHERE bc.book_id = b.id AND bc.status = 'AVAILABLE')");
        }
        if (archived != null) {
            clauses.add("b.is_archived = ?");
            params.add(archived);
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int i = 1;
        for (Object p : params) ps.setObject(i++, p);
        return i;
    }

    // ------------------------------------------------------------------ books: writes

    public long insert(Connection c, String isbn, String title, String author, String publisher, Integer year,
                       Long categoryId, String description) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO books(isbn, title, author, publisher, publication_year, category_id, description)
                VALUES(?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            bindBookFields(ps, isbn, title, author, publisher, year, categoryId, description);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void update(Connection c, long id, String isbn, String title, String author, String publisher,
                       Integer year, Long categoryId, String description) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE books SET isbn = ?, title = ?, author = ?, publisher = ?, publication_year = ?,
                                 category_id = ?, description = ?,
                                 updated_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
                WHERE id = ?""")) {
            bindBookFields(ps, isbn, title, author, publisher, year, categoryId, description);
            ps.setLong(8, id);
            ps.executeUpdate();
        }
    }

    private static void bindBookFields(PreparedStatement ps, String isbn, String title, String author,
                                       String publisher, Integer year, Long categoryId, String description)
            throws SQLException {
        ps.setString(1, emptyToNull(isbn));
        ps.setString(2, title);
        ps.setString(3, author);
        ps.setString(4, emptyToNull(publisher));
        if (year == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, year);
        if (categoryId == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setLong(6, categoryId);
        ps.setString(7, emptyToNull(description));
    }

    public void setArchived(Connection c, long id, boolean archived) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE books SET is_archived = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now') WHERE id = ?""")) {
            ps.setInt(1, archived ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ copies

    public List<CopyRecord> copiesOf(Connection c, long bookId) throws SQLException {
        List<CopyRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(COPY_SELECT + " WHERE bc.book_id = ? ORDER BY length(bc.copy_code), bc.copy_code")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(mapCopy(rs));
            }
        }
        return rows;
    }

    public Optional<CopyRecord> findCopy(Connection c, long copyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(COPY_SELECT + " WHERE bc.id = ?")) {
            ps.setLong(1, copyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapCopy(rs)) : Optional.empty();
            }
        }
    }

    private static final String COPY_SELECT = """
            SELECT bc.id, bc.book_id, bc.copy_code, bc.status, bc.acquired_at, bc.notes,
                   ti.due_date, s.first_name, s.last_name
            FROM book_copies bc
            LEFT JOIN transaction_items ti ON ti.copy_id = bc.id AND ti.status = 'ACTIVE'
            LEFT JOIN transactions t ON t.id = ti.transaction_id
            LEFT JOIN students s ON s.id = t.student_id""";

    public List<String> copyCodesOf(Connection c, long bookId) throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT copy_code FROM book_copies WHERE book_id = ?")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) codes.add(rs.getString(1));
            }
        }
        return codes;
    }

    public boolean copyCodeExists(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM book_copies WHERE copy_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long insertCopy(Connection c, long bookId, String code, String notes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO book_copies(book_id, copy_code, notes) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, bookId);
            ps.setString(2, code);
            ps.setString(3, emptyToNull(notes));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void updateCopy(Connection c, long copyId, String status, String notes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE book_copies SET status = ?, notes = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, emptyToNull(notes));
            ps.setLong(3, copyId);
            ps.executeUpdate();
        }
    }

    public void deleteCopy(Connection c, long copyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM book_copies WHERE id = ?")) {
            ps.setLong(1, copyId);
            ps.executeUpdate();
        }
    }

    public boolean copyHasLoanHistory(Connection c, long copyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM transaction_items WHERE copy_id = ?")) {
            ps.setLong(1, copyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int countCopies(Connection c, long bookId) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM book_copies WHERE book_id = ?", bookId);
    }

    public int countBorrowedCopies(Connection c, long bookId) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM book_copies WHERE book_id = ? AND status = 'BORROWED'", bookId);
    }

    // ------------------------------------------------------------------ categories

    public List<CategoryRecord> categories(Connection c) throws SQLException {
        List<CategoryRecord> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT c.id, c.name, (SELECT COUNT(*) FROM books WHERE category_id = c.id)
                     FROM categories c ORDER BY c.name COLLATE NOCASE""")) {
            while (rs.next()) rows.add(new CategoryRecord(rs.getLong(1), rs.getString(2), rs.getInt(3)));
        }
        return rows;
    }

    public Optional<CategoryRecord> findCategory(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT c.id, c.name, (SELECT COUNT(*) FROM books WHERE category_id = c.id)
                FROM categories c WHERE c.id = ?""")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new CategoryRecord(rs.getLong(1), rs.getString(2), rs.getInt(3)))
                        : Optional.empty();
            }
        }
    }

    public boolean categoryNameExists(Connection c, String name, long excludedId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM categories WHERE name = ? AND id <> ?")) {
            ps.setString(1, name);   // the column is COLLATE NOCASE, so this comparison ignores case
            ps.setLong(2, excludedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long insertCategory(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO categories(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void renameCategory(Connection c, long id, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE categories SET name = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void deleteCategory(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM categories WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ mapping

    private static BookRecord mapBook(ResultSet rs) throws SQLException {
        int year = rs.getInt(6);
        boolean noYear = rs.wasNull();
        long category = rs.getLong(7);
        boolean noCategory = rs.wasNull();
        return new BookRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                noYear ? null : year, noCategory ? null : category, rs.getString(8), rs.getString(9),
                rs.getInt(10) == 1, rs.getInt(11), rs.getInt(12));
    }

    private static CopyRecord mapCopy(ResultSet rs) throws SQLException {
        return new CopyRecord(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
    }

    private static int scalar(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
