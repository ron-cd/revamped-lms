package com.librarymanagement.ui;

import javafx.scene.Node;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;

/** Wraps any content; while loading, a dimmed overlay with a spinner blocks interaction with it. */
public class LoadingOverlay extends StackPane {

    private final StackPane overlay = new StackPane();

    public LoadingOverlay(Node content) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(40, 40);
        overlay.getChildren().add(spinner);
        overlay.getStyleClass().add("loading-overlay");
        overlay.setVisible(false);
        getChildren().addAll(content, overlay);
    }

    public void setLoading(boolean loading) {
        overlay.setVisible(loading); // an invisible node does not receive mouse events
    }

    public boolean isLoading() {
        return overlay.isVisible();
    }
}
