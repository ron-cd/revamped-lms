package com.librarymanagement.ui;

import com.librarymanagement.utils.GlobalExceptionHandler;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;

/** Consistent, themed dialogs. Use confirm() before any destructive action. */
public final class AppDialog {

    private static final Logger log = LoggerFactory.getLogger(AppDialog.class);

    /** Work done when the user confirms a form. Runs on a background thread; throw to show an error in the dialog. */
    @FunctionalInterface
    public interface FormAction {
        void run() throws Exception;
    }

    private AppDialog() {}

    /**
     * A form in a dialog. The confirm button first runs {@code preValidate} (on the UI thread, for required-field checks),
     * then {@code action} on a background thread. Validation and business-rule messages from the action appear inside
     * the dialog and the user can correct and retry; success closes it.
     *
     * @return true if the action completed
     */
    public static boolean form(Window owner, String title, String subtitle, Node content, String confirmText,
                               BooleanSupplier preValidate, FormAction action) {
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirmType = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        VBox box = new VBox(14, heading);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("muted");
            sub.setWrapText(true);
            box.getChildren().add(sub);
        }
        Label error = new Label();
        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        box.getChildren().addAll(content, error);

        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(Theme.stylesheet());
        pane.setHeaderText(null);
        pane.setGraphic(null);
        pane.setContent(box);
        pane.setPrefWidth(460);
        pane.getButtonTypes().addAll(cancelType, confirmType);

        Button confirm = (Button) pane.lookupButton(confirmType);
        confirm.getStyleClass().add("primary");
        Button cancel = (Button) pane.lookupButton(cancelType);

        boolean[] completed = {false};
        confirm.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume(); // the dialog closes only when the action succeeds
            error.setVisible(false);
            error.setManaged(false);
            if (preValidate != null && !preValidate.getAsBoolean()) return;

            confirm.setDisable(true);
            cancel.setDisable(true);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    action.run();
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                completed[0] = true;
                dialog.setResult(confirmType);
                dialog.close();
            });
            task.setOnFailed(ev -> {
                Throwable t = task.getException();
                if (!(t instanceof ValidationException || t instanceof BusinessRuleException)) {
                    log.error("Form action failed: {}", title, t);
                }
                error.setText(GlobalExceptionHandler.userMessage(t));
                error.setVisible(true);
                error.setManaged(true);
                confirm.setDisable(false);
                cancel.setDisable(false);
            });
            Thread thread = new Thread(task, "form-action");
            thread.setDaemon(true);
            thread.start();
        });

        dialog.showAndWait();
        return completed[0];
    }

    /**
     * @param destructive true styles the confirm button red (archive, deactivate, restore, ...)
     * @return true if the user confirmed
     */
    public static boolean confirm(Window owner, String title, String message, String confirmText, boolean destructive) {
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirmType = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);

        Dialog<ButtonType> dialog = base(owner, title, message);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(cancelType, confirmType);

        Button confirmButton = (Button) pane.lookupButton(confirmType);
        confirmButton.getStyleClass().add(destructive ? "danger" : "primary");
        confirmButton.setDefaultButton(!destructive); // Enter must not trigger a destructive action by accident
        Button cancelButton = (Button) pane.lookupButton(cancelType);
        cancelButton.setCancelButton(true);
        cancelButton.setDefaultButton(destructive);

        return dialog.showAndWait().filter(b -> b == confirmType).isPresent();
    }

    /** A message with a single OK button (for errors the user must acknowledge). */
    public static void message(Window owner, String title, String message) {
        ButtonType ok = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = base(owner, title, message);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().add(ok);
        ((Button) pane.lookupButton(ok)).getStyleClass().add("primary");
        dialog.showAndWait();
    }

    private static Dialog<ButtonType> base(Window owner, String title, String message) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");
        Label body = new Label(message);
        body.getStyleClass().add("muted");
        body.setWrapText(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(Theme.stylesheet());
        pane.setHeaderText(null);
        pane.setGraphic(null);
        pane.setContent(new VBox(8, heading, body));
        pane.setPrefWidth(440);
        return dialog;
    }
}
