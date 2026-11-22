package com.librarymanagement.controllers;

import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.utils.GlobalExceptionHandler;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.ValidationException;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

import java.util.function.Consumer;

/** UI glue only: collects credentials, calls AuthService on a background thread, shows the result. */
public class LoginController {

    @FXML private StackPane logoWrap;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField visiblePasswordField;
    @FXML private Button toggleButton;
    @FXML private Label noticeLabel;
    @FXML private Label errorLabel;
    @FXML private Button loginButton;

    private AuthService auth;
    private Consumer<Session> onSuccess;

    @FXML
    private void initialize() {
        logoWrap.getChildren().add(Icon.BOOK.view(26));
        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());
        showPassword(false);
        toggleButton.setGraphic(Icon.EYE.view(18));
        noticeLabel.setVisible(false);
        noticeLabel.setManaged(false);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    public void init(AuthService auth, Consumer<Session> onSuccess) {
        this.auth = auth;
        this.onSuccess = onSuccess;
        usernameField.requestFocus();
    }

    /** Informational message above the form (for example "You were signed out after 15 minutes of inactivity."). */
    public void setNotice(String message) {
        noticeLabel.setText(message);
        noticeLabel.setVisible(message != null);
        noticeLabel.setManaged(message != null);
    }

    @FXML
    private void onTogglePassword() {
        boolean show = !visiblePasswordField.isVisible();
        showPassword(show);
        toggleButton.setGraphic((show ? Icon.EYE_OFF : Icon.EYE).view(18));
    }

    private void showPassword(boolean show) {
        visiblePasswordField.setVisible(show);
        visiblePasswordField.setManaged(show);
        passwordField.setVisible(!show);
        passwordField.setManaged(!show);
    }

    @FXML
    private void onLogin() {
        showError(null);
        loginButton.setDisable(true); // prevents double submission

        String username = usernameField.getText();
        char[] password = passwordField.getText().toCharArray();

        Task<Session> task = new Task<>() {
            @Override protected Session call() {
                return auth.login(username, password);
            }
        };
        task.setOnSucceeded(e -> {
            passwordField.clear();
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            passwordField.clear();
            if (t instanceof ValidationException || t instanceof BusinessRuleException) {
                showError(t.getMessage());
            } else {
                GlobalExceptionHandler.show(t);
            }
            loginButton.setDisable(false);
            passwordField.requestFocus();
        });
        Thread th = new Thread(task, "login");
        th.setDaemon(true);
        th.start();
    }

    private void showError(String message) {
        errorLabel.setText(message == null ? "" : message);
        errorLabel.setVisible(message != null);
        errorLabel.setManaged(message != null);
    }
}
