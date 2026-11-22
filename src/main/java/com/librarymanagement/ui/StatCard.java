package com.librarymanagement.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Dashboard statistic: big number, caption and icon. Values are set by the caller from real data. */
public class StatCard extends HBox {

    private final Label value = new Label("-");

    public StatCard(String caption, Icon icon) {
        StackPane iconWrap = new StackPane(icon.view(20));
        iconWrap.getStyleClass().add("stat-icon-wrap");

        value.getStyleClass().add("stat-value");
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("stat-label");

        VBox text = new VBox(2, value, captionLabel);
        HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS);

        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("stat-card");
        getChildren().addAll(text, iconWrap);
    }

    public void setValue(String text) {
        value.setText(text);
    }
}
