package com.librarymanagement.controllers;

import com.librarymanagement.services.SetupService;
import com.librarymanagement.utils.GlobalExceptionHandler;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** UI glue only: reads fields, calls SetupService on a background thread, shows the result. */
public class SetupController {

    @FXML private TextField fullNameField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label errorLabel;
    @FXML private Button createButton;

    private SetupService service;
    private Runnable onComplete;

    public void init(SetupService service, Runnable onComplete) {
        this.service = service;
        this.onComplete = onComplete;
    }

    @FXML
    private void onCreate() {
        errorLabel.setText("");
        createButton.setDisable(true); // prevents double submission

        String username = usernameField.getText();
        String fullName = fullNameField.getText();
        char[] pw = passwordField.getText().toCharArray();
        char[] confirm = confirmField.getText().toCharArray();

        Task<Long> task = new Task<>() {
            @Override protected Long call() {
                return service.createInitialAdministrator(username, fullName, pw, confirm);
            }
        };
        task.setOnSucceeded(e -> {
            passwordField.clear();
            confirmField.clear();
            onComplete.run();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            if (t instanceof ValidationException || t instanceof BusinessRuleException) {
                errorLabel.setText(t.getMessage());
            } else {
                GlobalExceptionHandler.show(t);
            }
            createButton.setDisable(false);
        });
        Thread th = new Thread(task, "setup-admin");
        th.setDaemon(true);
        th.start();
    }
}
