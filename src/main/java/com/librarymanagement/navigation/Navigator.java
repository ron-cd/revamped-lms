package com.librarymanagement.navigation;

import com.librarymanagement.ui.Theme;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.function.Consumer;

/** Swaps the root view of the main window (setup screen, later the login screen, then the app shell). */
public class Navigator {

    private final Stage stage;

    public Navigator(Stage stage) {
        this.stage = stage;
    }

    /** Loads an FXML view; the controller can be configured before it is shown. */
    public <C> void show(String fxml, Consumer<C> configure) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + fxml));
            Parent root = loader.load();
            if (configure != null) configure.accept(loader.getController());
            showRoot(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load view " + fxml, e);
        }
    }

    /** Shows an already-built view (for example the {@link AppShell}). */
    public void showRoot(Parent root) {
        Scene scene = stage.getScene();
        if (scene == null) {
            scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(Theme.stylesheet());
            stage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
    }
}
