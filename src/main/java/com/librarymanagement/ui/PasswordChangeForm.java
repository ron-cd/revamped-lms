package com.librarymanagement.ui;

import javafx.scene.control.PasswordField;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Current + new + confirm password fields with the policy hint. Used by the account dialog and the forced-change screen. */
public class PasswordChangeForm extends VBox {

    private final PasswordField current = new PasswordField();
    private final PasswordField newPassword = new PasswordField();
    private final PasswordField confirm = new PasswordField();
    private final FormField currentField = new FormField("Current password", current).required();
    private final FormField newField = new FormField("New password", newPassword).required();
    private final FormField confirmField = new FormField("Confirm new password", confirm).required();

    public PasswordChangeForm() {
        super(14);
        Label hint = new Label("At least 10 characters with an uppercase letter, a lowercase letter and a number.");
        hint.getStyleClass().add("faint");
        hint.setWrapText(true);
        getChildren().addAll(currentField, newField, hint, confirmField);
    }

    /** Marks empty fields; true when all three are filled in. */
    public boolean validateInput() {
        boolean ok = true;
        ok &= check(currentField, current, "Enter your current password.");
        ok &= check(newField, newPassword, "Enter a new password.");
        ok &= check(confirmField, confirm, "Repeat the new password.");
        return ok;
    }

    private static boolean check(FormField field, PasswordField input, String message) {
        if (input.getText().isEmpty()) {
            field.setError(message);
            return false;
        }
        field.clearError();
        return true;
    }

    public char[] current()     { return current.getText().toCharArray(); }
    public char[] newPassword() { return newPassword.getText().toCharArray(); }
    public char[] confirm()     { return confirm.getText().toCharArray(); }

    public void clear() {
        current.clear();
        newPassword.clear();
        confirm.clear();
    }

    public void focus() {
        current.requestFocus();
    }
}
