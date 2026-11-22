package com.librarymanagement.ui;

import javafx.scene.control.Label;

/** Small coloured pill for statuses such as Available, Borrowed, Overdue. */
public final class StatusBadge {

    public enum Kind {
        NEUTRAL("badge-neutral"), SUCCESS("badge-success"), WARNING("badge-warning"),
        DANGER("badge-danger"), INFO("badge-info");

        final String styleClass;

        Kind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private StatusBadge() {}

    public static Label of(String text, Kind kind) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", kind.styleClass);
        return label;
    }
}
