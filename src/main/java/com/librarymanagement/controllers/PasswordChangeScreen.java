package com.librarymanagement.controllers;

import com.librarymanagement.security.Session;
import com.librarymanagement.services.UserService;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.PasswordChangeForm;
import com.librarymanagement.utils.GlobalExceptionHandler;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import javafx.concurrent.Task;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Shown right after signing in with a temporary password. Nothing else is reachable until it is replaced. */
public final class PasswordChangeScreen {

    private PasswordChangeScreen() {}

    public static Parent build(UserService users, Session session, Runnable onDone, Runnable onSignOut) {
        StackPane logo = new StackPane(Icon.SHIELD.view(24));
        logo.getStyleClass().add("brand-logo");
        Label title = new Label("Choose a new password");
        title.getStyleClass().add("brand-name");
        Label sub = new Label("Your password was set by an administrator. Replace it to continue.");
        sub.getStyleClass().add("brand-sub");
        sub.setWrapText(true);
        HBox header = new HBox(12, logo, new VBox(1, title, sub));

        PasswordChangeForm form = new PasswordChangeForm();
        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Button update = new Button("Update password");
        update.getStyleClass().add("primary");
        update.setMaxWidth(Double.MAX_VALUE);
        update.setDefaultButton(true);
        Button signOut = new Button("Sign out");
        signOut.getStyleClass().add("ghost");
        signOut.setMaxWidth(Double.MAX_VALUE);
        signOut.setOnAction(e -> onSignOut.run());

        update.setOnAction(e -> {
            error.setVisible(false);
            error.setManaged(false);
            if (!form.validateInput()) return;
            update.setDisable(true);
            char[] current = form.current();
            char[] next = form.newPassword();
            char[] confirm = form.confirm();
            Task<Void> task = new Task<>() {
                @Override protected Void call() {
                    users.changeOwnPassword(session, current, next, confirm);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                form.clear();
                onDone.run();
            });
            task.setOnFailed(ev -> {
                Throwable t = task.getException();
                if (t instanceof ValidationException || t instanceof BusinessRuleException) {
                    error.setText(t.getMessage());
                    error.setVisible(true);
                    error.setManaged(true);
                } else {
                    GlobalExceptionHandler.show(t);
                }
                update.setDisable(false);
            });
            Thread th = new Thread(task, "change-password");
            th.setDaemon(true);
            th.start();
        });

        VBox card = new VBox(14, header, form, error, update, signOut);
        card.getStyleClass().add("card");
        card.setMaxWidth(440);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane root = new StackPane(card);
        root.getStyleClass().add("root-dark");
        return root;
    }

}
