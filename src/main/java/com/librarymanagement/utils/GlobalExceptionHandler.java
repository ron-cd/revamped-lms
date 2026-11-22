package com.librarymanagement.utils;

import com.librarymanagement.database.DatabaseException;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs full technical details; shows users a short, safe message (no SQL, paths or stack traces). */
public final class GlobalExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(new GlobalExceptionHandler());
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Uncaught exception on thread {}", t.getName(), e);
        show(e);
    }

    /** Message that is safe to display for a given failure. */
    public static String userMessage(Throwable e) {
        if (e instanceof ValidationException || e instanceof BusinessRuleException) return e.getMessage();
        if (e instanceof DatabaseException) return "A database problem occurred. Your data was not changed.";
        return "Something went wrong. Please try again. If it keeps happening, check the log files.";
    }

    public static void show(Throwable e) {
        Runnable r = () -> new Alert(Alert.AlertType.ERROR, userMessage(e), ButtonType.OK).showAndWait();
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }
}
