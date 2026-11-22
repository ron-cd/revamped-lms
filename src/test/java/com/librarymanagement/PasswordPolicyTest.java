package com.librarymanagement;

import com.librarymanagement.security.PasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void acceptsStrongPassword() {
        assertTrue(PasswordPolicy.check("Str0ngPassw0rd".toCharArray(), "admin").isEmpty());
    }
    @Test void rejectsShort()            { assertFalse(PasswordPolicy.check("Ab1".toCharArray(), "u").isEmpty()); }
    @Test void rejectsMissingUppercase() { assertFalse(PasswordPolicy.check("alllowercase123".toCharArray(), "u").isEmpty()); }
    @Test void rejectsMissingDigit()     { assertFalse(PasswordPolicy.check("NoDigitsHereAtAll".toCharArray(), "u").isEmpty()); }
    @Test void rejectsUsernameInside()   { assertFalse(PasswordPolicy.check("Libraryadmin123".toCharArray(), "libraryadmin").isEmpty()); }
    @Test void rejectsTooLong()          { assertFalse(PasswordPolicy.check(("Aa1" + "x".repeat(80)).toCharArray(), "u").isEmpty()); }
}
