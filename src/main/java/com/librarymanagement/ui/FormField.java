package com.librarymanagement.ui;

import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * A labelled input with an optional required marker and an inline error message.
 * Same look and behaviour on every form: red border + message under the field.
 */
public class FormField extends VBox {

    private final Control control;
    private final Label errorLabel = new Label();
    private final HBox labelRow = new HBox(4);

    public FormField(String label, Control control) {
        this.control = control;
        Label name = new Label(label);
        name.getStyleClass().add("field-label");
        labelRow.getChildren().add(name);

        errorLabel.getStyleClass().add("error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        control.setMaxWidth(Double.MAX_VALUE);
        setSpacing(6);
        getChildren().addAll(labelRow, control, errorLabel);
    }

    /** Adds the red asterisk. */
    public FormField required() {
        Label star = new Label("*");
        star.getStyleClass().add("required");
        labelRow.getChildren().add(star);
        return this;
    }

    public void setError(String message) {
        if (!control.getStyleClass().contains("invalid")) control.getStyleClass().add("invalid");
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    public void clearError() {
        control.getStyleClass().remove("invalid");
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    public Control getControl() {
        return control;
    }
}
