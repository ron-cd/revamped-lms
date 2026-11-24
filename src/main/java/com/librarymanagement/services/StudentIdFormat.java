package com.librarymanagement.services;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configurable student ID pattern (setting "student.id_format"), for example "{YEAR}-{SEQ:5}" -> 2026-00001.
 * Tokens: {YEAR} four-digit year, {YY} two-digit year, {SEQ:n} running number padded to n digits (1-9).
 * Exactly one {SEQ:n} is required. Other characters may be letters, digits, dot, dash, slash or underscore.
 */
public final class StudentIdFormat {

    public static final String DEFAULT = "{YEAR}-{SEQ:5}";
    private static final Pattern SEQ = Pattern.compile("\\{SEQ:(\\d)\\}");
    private static final Pattern LITERALS = Pattern.compile("[A-Za-z0-9._/-]*");

    private final String before;
    private final String after;
    private final int width;

    private StudentIdFormat(String before, String after, int width) {
        this.before = before;
        this.after = after;
        this.width = width;
    }

    /** @throws IllegalArgumentException with a user-safe message when the pattern is not valid */
    public static StudentIdFormat parse(String pattern) {
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("The student ID format is empty.");
        Matcher m = SEQ.matcher(pattern);
        if (!m.find()) throw new IllegalArgumentException("The student ID format must contain {SEQ:n}, for example {YEAR}-{SEQ:5}.");
        int width = Integer.parseInt(m.group(1));
        if (width < 1) throw new IllegalArgumentException("{SEQ:n} needs n between 1 and 9.");
        String before = pattern.substring(0, m.start());
        String after = pattern.substring(m.end());
        if (SEQ.matcher(after).find()) throw new IllegalArgumentException("The student ID format may contain {SEQ:n} only once.");
        for (String part : new String[]{before, after}) {
            String literal = part.replace("{YEAR}", "").replace("{YY}", "");
            if (!LITERALS.matcher(literal).matches()) {
                throw new IllegalArgumentException("The student ID format has an unknown token or invalid character.");
            }
        }
        return new StudentIdFormat(before, after, width);
    }

    /** Falls back to the default pattern when the stored setting is not valid. */
    public static StudentIdFormat parseOrDefault(String pattern) {
        try {
            return parse(pattern);
        } catch (IllegalArgumentException e) {
            return parse(DEFAULT);
        }
    }

    public String prefix(int year) {
        return render(before, year);
    }

    public String suffix(int year) {
        return render(after, year);
    }

    public String format(int year, long sequence) {
        String digits = Long.toString(sequence);
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < width; i++) padded.append('0');
        return prefix(year) + padded + digits + suffix(year);
    }

    /** The running number inside an existing ID of this format and year, if it matches. */
    public OptionalInt extractSequence(int year, String studentNo) {
        String prefix = prefix(year);
        String suffix = suffix(year);
        if (studentNo == null || studentNo.length() <= prefix.length() + suffix.length()
                || !studentNo.startsWith(prefix) || !studentNo.endsWith(suffix)) {
            return OptionalInt.empty();
        }
        String middle = studentNo.substring(prefix.length(), studentNo.length() - suffix.length());
        if (middle.length() > 9 || !middle.chars().allMatch(Character::isDigit)) return OptionalInt.empty();
        return OptionalInt.of(Integer.parseInt(middle));
    }

    private static String render(String template, int year) {
        return template.replace("{YEAR}", String.valueOf(year))
                .replace("{YY}", String.format("%02d", year % 100));
    }
}
