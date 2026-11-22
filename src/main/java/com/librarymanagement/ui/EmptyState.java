package com.librarymanagement.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Centered icon + title + message (+ optional action button). Also used for error states. */
public final class EmptyState {

    private EmptyState() {}

    public static VBox of(Icon icon, String title, String message, Node action) {
        StackPane iconWrap = new StackPane(icon.view(28));
        iconWrap.getStyleClass().add("empty-icon-wrap");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-title");

        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("empty-message");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(380);

        VBox box = new VBox(10, iconWrap, titleLabel, messageLabel);
        box.getStyleClass().add("empty-state");
        if (action != null) box.getChildren().add(action);
        return box;
    }

    public static VBox error(String message) {
        return of(Icon.WARNING, "Something went wrong", message, null);
    }
}
