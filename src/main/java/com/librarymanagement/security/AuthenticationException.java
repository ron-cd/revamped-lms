package com.librarymanagement.security;

import com.librarymanagement.validation.BusinessRuleException;

/** Login failed. The message is deliberately identical for every cause so it cannot reveal which usernames exist. */
public class AuthenticationException extends BusinessRuleException {
    public AuthenticationException(String message) {
        super(message);
    }
}
