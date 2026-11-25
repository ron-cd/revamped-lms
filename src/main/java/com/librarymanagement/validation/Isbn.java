package com.librarymanagement.validation;

import java.util.Optional;

/**
 * ISBN handling. Hyphens and spaces are ignored. A valid ISBN-10 is converted to its ISBN-13 form, so the same
 * book can never be stored twice just because it was typed in both forms.
 */
public final class Isbn {

    private Isbn() {}

    /** @return the canonical 13-digit ISBN (digits only), or empty when the text is not a valid ISBN-10/13 */
    public static Optional<String> canonical(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.replace("-", "").replace(" ", "").toUpperCase();
        if (s.length() == 13 && s.chars().allMatch(Character::isDigit)) {
            return checkDigit13(s.substring(0, 12)) == s.charAt(12) ? Optional.of(s) : Optional.empty();
        }
        if (s.length() == 10 && valid10(s)) {
            String body = "978" + s.substring(0, 9);
            return Optional.of(body + checkDigit13(body));
        }
        return Optional.empty();
    }

    private static boolean valid10(String s) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char ch = s.charAt(i);
            int value;
            if (ch == 'X' && i == 9) value = 10;
            else if (ch >= '0' && ch <= '9') value = ch - '0';
            else return false;
            sum += value * (10 - i);
        }
        return sum % 11 == 0;
    }

    private static char checkDigit13(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
