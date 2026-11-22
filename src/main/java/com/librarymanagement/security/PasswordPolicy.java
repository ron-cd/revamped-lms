package com.librarymanagement.security;

import java.util.ArrayList;
import java.util.List;

/** Password rules, enforced in the service layer (the UI only mirrors them for feedback). */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 72; // BCrypt ignores bytes beyond 72

    private PasswordPolicy() {}

    public static List<String> check(char[] password, String username) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length < MIN_LENGTH) {
            problems.add("Password must be at least " + MIN_LENGTH + " characters.");
            return problems;
        }
        if (password.length > MAX_LENGTH) problems.add("Password must be at most " + MAX_LENGTH + " characters.");
        boolean upper = false, lower = false, digit = false;
        for (char ch : password) {
            if (Character.isUpperCase(ch)) upper = true;
            else if (Character.isLowerCase(ch)) lower = true;
            else if (Character.isDigit(ch)) digit = true;
        }
        if (!upper) problems.add("Password needs an uppercase letter.");
        if (!lower) problems.add("Password needs a lowercase letter.");
        if (!digit) problems.add("Password needs a number.");
        if (username != null && !username.isBlank()
                && new String(password).toLowerCase().contains(username.toLowerCase())) {
            problems.add("Password must not contain the username.");
        }
        return problems;
    }
}
