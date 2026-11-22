package com.librarymanagement.validation;

/** A well-formed request that breaks a library rule (e.g. setup already completed). Message is user-safe. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) { super(message); }
}
