package com.librarymanagement.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

/** BCrypt (cost 12) hashing. Plaintext passwords are never stored or logged. */
public final class PasswordHasher {

    private static final int COST = 12;

    public String hash(char[] password) {
        return BCrypt.withDefaults().hashToString(COST, password);
    }

    public boolean verify(char[] password, String hash) {
        if (hash == null || hash.isEmpty()) return false;
        return BCrypt.verifyer().verify(password, hash).verified;
    }
}
