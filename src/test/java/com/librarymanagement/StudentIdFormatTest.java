package com.librarymanagement;

import com.librarymanagement.services.StudentIdFormat;
import com.librarymanagement.validation.StudentRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StudentIdFormatTest {

    @Test
    void formatsAndPads() {
        StudentIdFormat f = StudentIdFormat.parse("{YEAR}-{SEQ:5}");
        assertEquals("2026-00001", f.format(2026, 1));
        assertEquals("2026-00042", f.format(2026, 42));
        assertEquals("2026-123456", f.format(2026, 123456)); // grows beyond the padding
    }

    @Test
    void supportsShortYearAndSuffixLayouts() {
        assertEquals("S26-0007", StudentIdFormat.parse("S{YY}-{SEQ:4}").format(2026, 7));
        StudentIdFormat suffix = StudentIdFormat.parse("{SEQ:3}/{YEAR}");
        assertEquals("005/2026", suffix.format(2026, 5));
        assertEquals(5, suffix.extractSequence(2026, "005/2026").getAsInt());
    }

    @Test
    void extractsSequenceOnlyFromMatchingIds() {
        StudentIdFormat f = StudentIdFormat.parse("{YEAR}-{SEQ:5}");
        assertEquals(42, f.extractSequence(2026, "2026-00042").getAsInt());
        assertTrue(f.extractSequence(2026, "2025-00042").isEmpty()); // other year
        assertTrue(f.extractSequence(2026, "2026-00A42").isEmpty());  // not numeric
        assertTrue(f.extractSequence(2026, "LEGACY-7").isEmpty());    // manual ID of another style
        assertTrue(f.extractSequence(2026, "2026-").isEmpty());
    }

    @Test
    void rejectsInvalidPatterns() {
        for (String bad : new String[]{"", "{YEAR}", "{SEQ:0}", "{SEQ:5}{SEQ:3}", "{SEQ:5}-{FOO}", "{SEQ:5} x",
                "a;b{SEQ:3}", "{SEQ:12}"}) {
            assertThrows(IllegalArgumentException.class, () -> StudentIdFormat.parse(bad), "should reject: " + bad);
        }
    }

    @Test
    void fallsBackToDefaultWhenSettingIsInvalid() {
        assertEquals("2026-00003", StudentIdFormat.parseOrDefault("nonsense").format(2026, 3));
    }

    // ---- field rules
    @Test
    void acceptsRealisticNames() {
        assertTrue(StudentRules.check("", "Ana", "M.", "Reyes", "STEM", "Grade 11", "A", "0917 123 4567").isEmpty());
        assertTrue(StudentRules.check("", "José", "", "Niño", "BSIT", "1st Year", "IT-1A", "").isEmpty());
        assertTrue(StudentRules.check("", "Mary-Jane", "", "O'Brien", "ABM", "Grade 12", "B", "").isEmpty());
    }

    @Test
    void rejectsBadNamesAndMissingFields() {
        assertFalse(StudentRules.check("", "An4", "", "Reyes", "STEM", "11", "A", "").isEmpty());
        assertFalse(StudentRules.check("", "Ana'); DROP TABLE students;--", "", "Reyes", "STEM", "11", "A", "").isEmpty());
        assertEquals(5, StudentRules.check("", "", "", "", "", "", "", "").size());
    }

    @Test
    void rejectsBadStudentNumberAndContact() {
        assertFalse(StudentRules.check("a b", "Ana", "", "Reyes", "STEM", "11", "A", "").isEmpty());
        assertTrue(StudentRules.check("2026-00001", "Ana", "", "Reyes", "STEM", "11", "A", "").isEmpty());
        assertFalse(StudentRules.check("", "Ana", "", "Reyes", "STEM", "11", "A", "x".repeat(101)).isEmpty());
        assertFalse(StudentRules.check("", "Ana", "", "Reyes", "STEM", "11", "A", "a\u0007b").isEmpty());
    }

    @Test
    void normalizeTrimsAndCollapsesSpaces() {
        assertEquals("Ana Marie", StudentRules.normalize("  Ana   Marie "));
        assertEquals("", StudentRules.normalize(null));
    }
}
