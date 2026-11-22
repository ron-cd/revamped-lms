package com.librarymanagement.validation;

import java.util.List;

/** Thrown by services when input is invalid. Messages are safe to show to the user. */
public class ValidationException extends RuntimeException {
    private final List<String> problems;

    public ValidationException(List<String> problems) {
        super(String.join("\n", problems));
        this.problems = List.copyOf(problems);
    }

    public ValidationException(String problem) {
        this(List.of(problem));
    }

    public List<String> problems() { return problems; }
}
