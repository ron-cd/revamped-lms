package com.librarymanagement.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shared validation for user accounts (used by first-run setup and user management). */
public final class UserRules {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    public static final int FULL_NAME_MAX = 120;

    private UserRules() {}

    /** @return problems in user-safe wording; empty if both values are valid (inputs should already be trimmed) */
    public static List<String> check(String username, String fullName) {
        List<String> problems = new ArrayList<>();
        if (username == null || !USERNAME.matcher(username).matches()) {
            problems.add("Username must be 3-50 characters: letters, numbers, dot, dash or underscore.");
        }
        problems.addAll(checkFullName(fullName));
        return problems;
    }

    public static List<String> checkFullName(String fullName) {
        List<String> problems = new ArrayList<>();
        if (fullName == null || fullName.isEmpty() || fullName.length() > FULL_NAME_MAX) {
            problems.add("Full name is required (max " + FULL_NAME_MAX + " characters).");
        }
        return problems;
    }
}
