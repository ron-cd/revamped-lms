package com.librarymanagement.database;

/** Unchecked wrapper so services do not leak SQLException to the UI. Details go to the log only. */
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
