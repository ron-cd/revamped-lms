#!/usr/bin/env python3
"""Verifies V001 schema rules with SQLite directly (no Java needed).
Run: python3 scripts/test_schema.py
"""
import pathlib
import re
import sqlite3
import sys

SQL = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/db/migration/V001__initial_schema.sql"
results = []


def fresh():
    con = sqlite3.connect(":memory:", isolation_level=None)
    con.execute("PRAGMA foreign_keys = ON")
    for stmt in re.split(r"^-- @@\s*$", SQL.read_text(), flags=re.M):
        if stmt.strip():
            con.execute(stmt)
    con.execute("INSERT INTO users(username,password_hash,full_name,role_id) VALUES('admin','x','Admin',1)")
    con.execute("INSERT INTO categories(name) VALUES('Fiction')")
    con.execute("INSERT INTO books(title,author,category_id) VALUES('Dune','Herbert',1)")
    con.execute("INSERT INTO book_copies(book_id,copy_code) VALUES(1,'B000001-01')")
    con.execute("INSERT INTO book_copies(book_id,copy_code) VALUES(1,'B000001-02')")
    con.execute("INSERT INTO students(student_no,first_name,last_name,program_strand,grade_level,section)"
                " VALUES('2026-00001','Ana','Reyes','STEM','11','A')")
    return con


def expect_fail(name, fn, needle=None):
    con = fresh()
    try:
        fn(con)
    except sqlite3.Error as e:
        ok = needle is None or needle.lower() in str(e).lower()
        results.append((name, ok, str(e)))
        return
    results.append((name, False, "no error raised"))


def expect_ok(name, fn):
    con = fresh()
    try:
        fn(con)
        results.append((name, True, ""))
    except Exception as e:  # noqa
        results.append((name, False, str(e)))


def issue(con, copy=1, student=1):
    con.execute("BEGIN")
    con.execute("INSERT INTO transactions(student_id,issued_by,issue_date) VALUES(?,1,'2026-11-06')", (student,))
    tid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    con.execute("INSERT INTO transaction_items(transaction_id,copy_id,due_date) VALUES(?,?,'2026-11-20')", (tid, copy))
    iid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    con.execute("UPDATE book_copies SET status='BORROWED' WHERE id=?", (copy,))
    con.execute("COMMIT")
    return iid


def do_return(con, item, copy, cond="GOOD"):
    con.execute("BEGIN")
    con.execute("INSERT INTO returns(item_id,return_date,condition,processed_by) VALUES(?,'2026-11-10',?,1)", (item, cond))
    con.execute("UPDATE transaction_items SET status='RETURNED' WHERE id=?", (item,))
    new = {"GOOD": "AVAILABLE", "MINOR_DAMAGE": "AVAILABLE", "DAMAGED": "DAMAGED", "LOST": "LOST"}[cond]
    con.execute("UPDATE book_copies SET status=? WHERE id=?", (new, copy))
    con.execute("COMMIT")


# ---- tests ----
def t_seed(con):
    assert con.execute("SELECT count(*) FROM roles").fetchone()[0] == 3
    assert con.execute("SELECT count(*) FROM permissions").fetchone()[0] == 16
    admin = con.execute("SELECT count(*) FROM role_permissions WHERE role_id=1").fetchone()[0]
    lib = con.execute("SELECT count(*) FROM role_permissions WHERE role_id=2").fetchone()[0]
    ast = con.execute("SELECT count(*) FROM role_permissions WHERE role_id=3").fetchone()[0]
    assert (admin, lib, ast) == (15, 10, 7), (admin, lib, ast)
    assert con.execute("SELECT value FROM system_settings WHERE key='loan.max_active'").fetchone()[0] == "3"
    # Assistant must NOT have book management or user management
    bad = con.execute("""SELECT count(*) FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id
                         WHERE rp.role_id=3 AND p.code IN ('BOOK_MANAGE','USER_MANAGE','AUDIT_VIEW')""").fetchone()[0]
    assert bad == 0


def t_full_cycle(con):
    item = issue(con)
    assert con.execute("SELECT status FROM book_copies WHERE id=1").fetchone()[0] == "BORROWED"
    do_return(con, item, 1)
    assert con.execute("SELECT status FROM book_copies WHERE id=1").fetchone()[0] == "AVAILABLE"
    # copy can be lent again after return
    issue(con)


def t_damaged_not_available(con):
    item = issue(con)
    do_return(con, item, 1, "DAMAGED")
    assert con.execute("SELECT status FROM book_copies WHERE id=1").fetchone()[0] == "DAMAGED"
    try:
        issue(con)
        raise AssertionError("damaged copy was issued")
    except sqlite3.Error:
        con.execute("ROLLBACK")


def t_rollback(con):
    con.execute("BEGIN")
    con.execute("INSERT INTO transactions(student_id,issued_by,issue_date) VALUES(1,1,'2026-11-06')")
    con.execute("ROLLBACK")
    assert con.execute("SELECT count(*) FROM transactions").fetchone()[0] == 0


def f_double_issue(con):
    issue(con, copy=1)
    issue(con, copy=1)


def f_fk_student(con):
    con.execute("INSERT INTO transactions(student_id,issued_by,issue_date) VALUES(999,1,'2026-11-06')")


def f_dup_student_no(con):
    con.execute("INSERT INTO students(student_no,first_name,last_name,program_strand,grade_level,section)"
                " VALUES('2026-00001','Bob','Cruz','STEM','11','B')")


def f_dup_student_identity(con):
    con.execute("INSERT INTO students(student_no,first_name,last_name,program_strand,grade_level,section)"
                " VALUES('2026-00002','ANA','reyes','STEM','11','A')")


def f_dup_isbn(con):
    con.execute("INSERT INTO books(isbn,title,author) VALUES('9780441013593','A','B')")
    con.execute("INSERT INTO books(isbn,title,author) VALUES('9780441013593','C','D')")


def f_bad_year(con):
    con.execute("INSERT INTO books(title,author,publication_year) VALUES('A','B',12)")


def f_bad_status(con):
    con.execute("UPDATE book_copies SET status='STOLEN' WHERE id=1")


def f_inactive_student(con):
    con.execute("UPDATE students SET status='INACTIVE' WHERE id=1")
    issue(con)


def f_audit_update(con):
    con.execute("INSERT INTO audit_logs(user_id,action) VALUES(1,'LOGIN')")
    con.execute("UPDATE audit_logs SET action='X'")


def f_audit_delete(con):
    con.execute("INSERT INTO audit_logs(user_id,action) VALUES(1,'LOGIN')")
    con.execute("DELETE FROM audit_logs")


def f_delete_history(con):
    issue(con)
    con.execute("DELETE FROM transactions")


def f_delete_student_with_history(con):
    issue(con)
    con.execute("DELETE FROM students WHERE id=1")


def f_delete_book_with_copies(con):
    con.execute("DELETE FROM books WHERE id=1")


def f_duplicate_return(con):
    item = issue(con)
    do_return(con, item, 1)
    con.execute("INSERT INTO returns(item_id,return_date,condition,processed_by) VALUES(?,'2026-11-11','GOOD',1)", (item,))


def f_available_while_loaned(con):
    issue(con)
    con.execute("UPDATE book_copies SET status='AVAILABLE' WHERE id=1")


def f_borrowed_without_loan(con):
    con.execute("UPDATE book_copies SET status='BORROWED' WHERE id=2")


def f_bad_condition(con):
    item = issue(con)
    con.execute("INSERT INTO returns(item_id,return_date,condition,processed_by) VALUES(?,'2026-11-11','OK',1)", (item,))


def f_duplicate_username(con):
    con.execute("INSERT INTO users(username,password_hash,full_name,role_id) VALUES('ADMIN','x','Other',2)")


expect_ok("seed roles/permissions/settings", t_seed)
expect_ok("issue -> return -> reissue cycle", t_full_cycle)
expect_ok("damaged return keeps copy unavailable", t_damaged_not_available)
expect_ok("rolled-back transaction leaves nothing", t_rollback)
expect_fail("same copy cannot be actively loaned twice", f_double_issue)
expect_fail("FK: invalid student reference rejected", f_fk_student, "foreign key")
expect_fail("duplicate student number rejected", f_dup_student_no, "unique")
expect_fail("duplicate student identity (case-insens.) rejected", f_dup_student_identity, "unique")
expect_fail("duplicate ISBN rejected", f_dup_isbn, "unique")
expect_fail("invalid publication year rejected", f_bad_year, "check")
expect_fail("invalid copy status rejected", f_bad_status, "check")
expect_fail("inactive student cannot borrow", f_inactive_student, "inactive")
expect_fail("audit log cannot be updated", f_audit_update, "append-only")
expect_fail("audit log cannot be deleted", f_audit_delete, "append-only")
expect_fail("transaction history cannot be deleted", f_delete_history, "cannot be deleted")
expect_fail("student with history cannot be deleted", f_delete_student_with_history)
expect_fail("book with copies cannot be deleted", f_delete_book_with_copies, "foreign key")
expect_fail("duplicate return rejected", f_duplicate_return)
expect_fail("copy cannot become AVAILABLE while loaned", f_available_while_loaned, "active loan")
expect_fail("copy cannot be BORROWED without a loan", f_borrowed_without_loan, "without an active loan")
expect_fail("invalid return condition rejected", f_bad_condition, "check")
expect_fail("usernames are case-insensitively unique", f_duplicate_username, "unique")

width = max(len(n) for n, _, _ in results)
failed = 0
for name, ok, msg in results:
    print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {'' if ok else msg}")
    failed += (not ok)
print(f"\n{len(results) - failed}/{len(results)} passed")
sys.exit(1 if failed else 0)
