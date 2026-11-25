package com.librarymanagement;

import com.librarymanagement.validation.BookRules;
import com.librarymanagement.validation.Isbn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookRulesTest {

    @Test
    void validIsbn13IsKeptWithoutHyphens() {
        assertEquals("9780441013593", Isbn.canonical("978-0-441-01359-3").orElseThrow());
        assertEquals("9780441013593", Isbn.canonical(" 9780441013593 ").orElseThrow());
    }

    @Test
    void isbn10IsConvertedToIsbn13() {
        assertEquals("9780441013593", Isbn.canonical("0-441-01359-7").orElseThrow());
        assertEquals("9780804429573", Isbn.canonical("080442957X").orElseThrow()); // check digit X
        assertEquals("9780804429573", Isbn.canonical("080442957x").orElseThrow());
    }

    @Test
    void wrongChecksumOrShapeIsRejected() {
        assertTrue(Isbn.canonical("9780441013594").isEmpty());
        assertTrue(Isbn.canonical("0441013590").isEmpty());
        assertTrue(Isbn.canonical("12345").isEmpty());
        assertTrue(Isbn.canonical("abcdefghij").isEmpty());
        assertTrue(Isbn.canonical("").isEmpty());
        assertTrue(Isbn.canonical(null).isEmpty());
    }

    @Test
    void acceptsAGoodBook() {
        assertTrue(BookRules.checkBook("", "Dune", "Frank Herbert", "Chilton", 1965, "A desert planet.", 2027).isEmpty());
        assertTrue(BookRules.checkBook("9780441013593", "Dune", "Herbert", "", null, "", 2027).isEmpty());
        assertTrue(BookRules.checkBook("", "Dune", "Herbert", "", 2027, "Line one\nLine two", 2027).isEmpty());
    }

    @Test
    void requiresTitleAndAuthor() {
        assertEquals(2, BookRules.checkBook("", "", "", "", null, "", 2027).size());
    }

    @Test
    void rejectsBadIsbnYearAndLength() {
        assertFalse(BookRules.checkBook("123", "Dune", "Herbert", "", null, "", 2027).isEmpty());
        assertFalse(BookRules.checkBook("", "Dune", "Herbert", "", 999, "", 2027).isEmpty());
        assertFalse(BookRules.checkBook("", "Dune", "Herbert", "", 2028, "", 2027).isEmpty());
        assertFalse(BookRules.checkBook("", "x".repeat(256), "Herbert", "", null, "", 2027).isEmpty());
        assertFalse(BookRules.checkBook("", "Dune", "Herbert", "", null, "d".repeat(2001), 2027).isEmpty());
    }

    @Test
    void rejectsControlCharacters() {
        assertFalse(BookRules.checkBook("", "Du\u0007ne", "Herbert", "", null, "", 2027).isEmpty());
        assertFalse(BookRules.checkNotes("a\u0000b").isEmpty());
    }

    @Test
    void notesAndCategoryRules() {
        assertTrue(BookRules.checkNotes("Cover torn at the corner").isEmpty());
        assertFalse(BookRules.checkNotes("n".repeat(256)).isEmpty());
        assertTrue(BookRules.checkCategory("Science & Technology").isEmpty());
        assertFalse(BookRules.checkCategory("").isEmpty());
        assertFalse(BookRules.checkCategory("<script>").isEmpty());
        assertFalse(BookRules.checkCategory("c".repeat(81)).isEmpty());
    }

    @Test
    void normalizingKeepsParagraphsButCollapsesSpaces() {
        assertEquals("Ana Marie", BookRules.normalize("  Ana   Marie "));
        assertEquals("", BookRules.normalize(null));
        assertEquals("a b\n\nc", BookRules.normalizeMultiline("a  b\r\n\r\n\r\n\r\nc "));
        assertEquals("", BookRules.normalizeMultiline(null));
    }
}
