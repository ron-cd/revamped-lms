package com.librarymanagement.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Field rules for student records. Inputs are normalised first (trimmed, repeated spaces collapsed). */
public final class StudentRules {

    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L} .'’-]*");
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .,&()/'’-]*");
    private static final Pattern STUDENT_NO = Pattern.compile("[A-Za-z0-9._/-]{3,30}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    public static final int NAME_MAX = 80;
    public static final int PROGRAM_MAX = 60;
    public static final int GRADE_MAX = 20;
    public static final int SECTION_MAX = 20;
    public static final int CONTACT_MAX = 100;

    private StudentRules() {}

    /** Trims and collapses internal whitespace; null becomes "". */
    public static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** @param studentNo may be empty when the ID is generated automatically */
    public static List<String> check(String studentNo, String first, String middle, String last,
                                     String program, String grade, String section, String contact) {
        List<String> problems = new ArrayList<>();
        if (!studentNo.isEmpty() && !STUDENT_NO.matcher(studentNo).matches()) {
            problems.add("Student ID must be 3-30 characters: letters, numbers, dot, dash, slash or underscore.");
        }
        requireName(problems, first, "First name", true);
        requireName(problems, middle, "Middle name", false);
        requireName(problems, last, "Last name", true);
        requireLabel(problems, program, "Program or strand", PROGRAM_MAX);
        requireLabel(problems, grade, "Grade level", GRADE_MAX);
        requireLabel(problems, section, "Section", SECTION_MAX);
        if (contact.length() > CONTACT_MAX) {
            problems.add("Contact information must be at most " + CONTACT_MAX + " characters.");
        } else if (CONTROL.matcher(contact).find()) {
            problems.add("Contact information contains invalid characters.");
        }
        return problems;
    }

    private static void requireName(List<String> problems, String value, String label, boolean required) {
        if (value.isEmpty()) {
            if (required) problems.add(label + " is required.");
        } else if (value.length() > NAME_MAX) {
            problems.add(label + " must be at most " + NAME_MAX + " characters.");
        } else if (!NAME.matcher(value).matches()) {
            problems.add(label + " may contain only letters, spaces, dots, hyphens and apostrophes.");
        }
    }

    private static void requireLabel(List<String> problems, String value, String label, int max) {
        if (value.isEmpty()) {
            problems.add(label + " is required.");
        } else if (value.length() > max) {
            problems.add(label + " must be at most " + max + " characters.");
        } else if (!LABEL.matcher(value).matches()) {
            problems.add(label + " contains invalid characters.");
        }
    }
}
