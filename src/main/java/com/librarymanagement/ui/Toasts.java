package com.librarymanagement.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Short, non-blocking messages shown in the bottom-right corner of the main window.
 * Call {@link #install(StackPane)} once with the window's root stack; then use success()/error()/... from any thread.
 */
public final class Toasts {

    public enum Type {
        SUCCESS("toast-success", Icon.CHECK, "icon-success"),
        ERROR("toast-error", Icon.WARNING, "icon-danger"),
        WARNING("toast-warning", Icon.WARNING, "icon-warning"),
        INFO("toast-info", Icon.INFO, "icon-info");

        final String styleClass;
        final Icon icon;
        final String iconClass;

        Type(String styleClass, Icon icon, String iconClass) {
            this.styleClass = styleClass;
            this.icon = icon;
            this.iconClass = iconClass;
        }
    }

    private static final int MAX_VISIBLE = 4;
    private static final double VISIBLE_MS = 3500;

    private static VBox box;

    private Toasts() {}

    public static void install(StackPane host) {
        box = new VBox(10);
        box.setPickOnBounds(false); // empty space around toasts must not block clicks
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(box, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(box, new Insets(0, 24, 24, 0));
        host.getChildren().add(box);
    }

    public static void success(String message) { show(Type.SUCCESS, message); }
    public static void error(String message)   { show(Type.ERROR, message); }
    public static void warning(String message) { show(Type.WARNING, message); }
    public static void info(String message)    { show(Type.INFO, message); }

    public static void show(Type type, String message) {
        if (Platform.isFxApplicationThread()) display(type, message);
        else Platform.runLater(() -> display(type, message));
    }

    private static void display(Type type, String message) {
        if (box == null) return;

        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(320);

        HBox toast = new HBox(10, type.icon.view(18, type.iconClass), text);
        toast.getStyleClass().addAll("toast", type.styleClass);
        toast.setOpacity(0);

        while (box.getChildren().size() >= MAX_VISIBLE) box.getChildren().remove(0);
        box.getChildren().add(toast);

        FadeTransition in = new FadeTransition(Duration.millis(150), toast);
        in.setToValue(1);
        in.play();

        PauseTransition wait = new PauseTransition(Duration.millis(VISIBLE_MS));
        wait.setOnFinished(e -> dismiss(toast));
        wait.play();
        toast.setOnMouseClicked(e -> { wait.stop(); dismiss(toast); });
    }

    private static void dismiss(HBox toast) {
        if (!box.getChildren().contains(toast)) return;
        FadeTransition out = new FadeTransition(Duration.millis(250), toast);
        out.setToValue(0);
        out.setOnFinished(e -> box.getChildren().remove(toast));
        out.play();
    }
}
