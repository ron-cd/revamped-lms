package com.librarymanagement.security;

import com.librarymanagement.validation.BusinessRuleException;

/** The signed-in user lacks the permission for an operation (or the session has ended). */
public class AuthorizationException extends BusinessRuleException {
    public AuthorizationException(String message) {
        super(message);
    }
}
