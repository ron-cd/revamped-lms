package com.librarymanagement.ui;

import java.util.Objects;

/** Single access point for the application stylesheet, so every window (including dialogs) uses the same one. */
public final class Theme {

    private static final String STYLESHEET =
            Objects.requireNonNull(Theme.class.getResource("/css/theme.css"), "theme.css missing").toExternalForm();

    private Theme() {}

    public static String stylesheet() {
        return STYLESHEET;
    }
}
