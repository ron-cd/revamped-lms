package com.librarymanagement.security;

import java.security.SecureRandom;

/** Random temporary passwords for new or reset accounts. Always satisfies {@link PasswordPolicy}. */
public final class PasswordGenerator {

    // ambiguous characters (0 O 1 l I) are left out so the password can be read out or copied by hand
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {}

    public static String generate() {
        char[] out = new char[12];
        String all = UPPER + LOWER + DIGITS;
        out[0] = pick(UPPER);
        out[1] = pick(LOWER);
        out[2] = pick(DIGITS);
        for (int i = 3; i < out.length; i++) out[i] = pick(all);
        for (int i = out.length - 1; i > 0; i--) { // Fisher-Yates shuffle
            int j = RANDOM.nextInt(i + 1);
            char t = out[i]; out[i] = out[j]; out[j] = t;
        }
        return new String(out);
    }

    private static char pick(String chars) {
        return chars.charAt(RANDOM.nextInt(chars.length()));
    }
}
