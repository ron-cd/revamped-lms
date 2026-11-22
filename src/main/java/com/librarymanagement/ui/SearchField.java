package com.librarymanagement.ui;

import javafx.beans.property.StringProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Text field with a search icon. Bind to {@link #textProperty()} to react to typing. */
public class SearchField extends HBox {

    private static final PseudoClass FOCUSED = PseudoClass.getPseudoClass("focused");
    private final TextField field = new TextField();

    public SearchField(String prompt) {
        field.setPromptText(prompt);
        HBox.setHgrow(field, Priority.ALWAYS);
        getStyleClass().add("search-box");
        getChildren().addAll(Icon.SEARCH.view(16), field);
        field.focusedProperty().addListener((obs, was, now) -> pseudoClassStateChanged(FOCUSED, now));
    }

    public StringProperty textProperty() {
        return field.textProperty();
    }

    public TextField getField() {
        return field;
    }
}
