package com.librarymanagement.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Field rules for books, copies and categories. Inputs are normalised first (trimmed, repeated spaces collapsed). */
public final class BookRules {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern CATEGORY = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,&()/'’+-]*");

    public static final int TITLE_MAX = 255;
    public static final int AUTHOR_MAX = 255;
    public static final int PUBLISHER_MAX = 255;
    public static final int DESCRIPTION_MAX = 2000;
    public static final int NOTES_MAX = 255;
    public static final int CATEGORY_MAX = 80;
    public static final int MIN_YEAR = 1000;
    public static final int MAX_COPIES_PER_BOOK = 200;
    public static final int MAX_COPIES_PER_ADD = 50;

    private BookRules() {}

    /** Trims and collapses internal whitespace; null becomes "". */
    public static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Trims, keeps line breaks (descriptions may have paragraphs) but collapses runs of spaces. */
    public static String normalizeMultiline(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * @param isbn      what the user typed (may be empty)
     * @param year      null when not provided
     * @param maxYear   latest accepted publication year
     */
    public static List<String> checkBook(String isbn, String title, String author, String publisher, Integer year,
                                         String description, int maxYear) {
        List<String> problems = new ArrayList<>();
        if (!isbn.isEmpty() && Isbn.canonical(isbn).isEmpty()) {
            problems.add("ISBN is not valid. Enter a 10 or 13 digit ISBN (hyphens are fine), or leave it empty.");
        }
        text(problems, title, "Title", TITLE_MAX, true);
        text(problems, author, "Author", AUTHOR_MAX, true);
        text(problems, publisher, "Publisher", PUBLISHER_MAX, false);
        if (year != null && (year < MIN_YEAR || year > maxYear)) {
            problems.add("Publication year must be between " + MIN_YEAR + " and " + maxYear + ".");
        }
        if (description.length() > DESCRIPTION_MAX) {
            problems.add("Description must be at most " + DESCRIPTION_MAX + " characters.");
        } else if (CONTROL.matcher(description).find()) {
            problems.add("Description contains invalid characters.");
        }
        return problems;
    }

    public static List<String> checkNotes(String notes) {
        List<String> problems = new ArrayList<>();
        if (notes.length() > NOTES_MAX) {
            problems.add("Notes must be at most " + NOTES_MAX + " characters.");
        } else if (CONTROL.matcher(notes).find()) {
            problems.add("Notes contain invalid characters.");
        }
        return problems;
    }

    public static List<String> checkCategory(String name) {
        List<String> problems = new ArrayList<>();
        if (name.isEmpty()) {
            problems.add("Category name is required.");
        } else if (name.length() > CATEGORY_MAX) {
            problems.add("Category name must be at most " + CATEGORY_MAX + " characters.");
        } else if (!CATEGORY.matcher(name).matches()) {
            problems.add("Category name contains invalid characters.");
        }
        return problems;
    }

    private static void text(List<String> problems, String value, String label, int max, boolean required) {
        if (value.isEmpty()) {
            if (required) problems.add(label + " is required.");
        } else if (value.length() > max) {
            problems.add(label + " must be at most " + max + " characters.");
        } else if (CONTROL.matcher(value).find()) {
            problems.add(label + " contains invalid characters.");
        }
    }
}
