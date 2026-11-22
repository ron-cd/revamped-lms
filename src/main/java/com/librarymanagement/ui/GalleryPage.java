package com.librarymanagement.ui;

import com.librarymanagement.navigation.AppShell;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * Developer-only showcase of every reusable component, so the design can be reviewed in one place.
 * Contains SAMPLE data only and is removed before release (Phase 7).
 */
public final class GalleryPage {

    private record Row(String title, String author, String status) {}

    private static final List<Row> SAMPLE = List.of(
            new Row("Sample book 01", "Author A", "Available"),
            new Row("Sample book 02", "Author B", "Borrowed"),
            new Row("Sample book 03", "Author C", "Overdue"),
            new Row("Sample book 04", "Author D", "Damaged"),
            new Row("Sample book 05", "Author E", "Available"),
            new Row("Sample book 06", "Author F", "Borrowed"),
            new Row("Sample book 07", "Author G", "Available"),
            new Row("Sample book 08", "Author H", "Lost"),
            new Row("Sample book 09", "Author I", "Available"),
            new Row("Sample book 10", "Author J", "Available"),
            new Row("Sample book 11", "Author K", "Borrowed"),
            new Row("Sample book 12", "Author L", "Available"));

    private GalleryPage() {}

    public static Node build(AppShell shell) {
        Button addButton = new Button("Add item", Icon.PLUS.view(16));
        addButton.getStyleClass().add("primary");
        addButton.setOnAction(e -> Toasts.info("Page action buttons live in the top bar."));
        shell.setActions(addButton);

        VBox content = new VBox(20,
                note(),
                statCards(),
                buttons(shell),
                forms(),
                badges(),
                table(),
                dialogsAndToasts(shell));
        content.setPadding(new Insets(0, 8, 24, 0));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    // ------------------------------------------------------------------ sections

    private static Node note() {
        Label l = new Label("Developer page. Everything below uses sample data and is removed before release.");
        l.getStyleClass().add("muted");
        return l;
    }

    private static Node statCards() {
        HBox row = new HBox(16);
        String[] captions = {"Total Books", "Available Copies", "Registered Students", "Overdue Books"};
        Icon[] icons = {Icon.BOOK, Icon.CHECK, Icon.PEOPLE, Icon.WARNING};
        String[] values = {"1,248", "1,017", "436", "12"};
        for (int i = 0; i < captions.length; i++) {
            StatCard card = new StatCard(captions[i], icons[i]);
            card.setValue(values[i]);
            HBox.setHgrow(card, Priority.ALWAYS);
            card.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(card);
        }
        return section("Stat cards", "Dashboard statistics (real values come from the database).", row);
    }

    private static Node buttons(AppShell shell) {
        Button primary = new Button("Primary");
        primary.getStyleClass().add("primary");
        Button secondary = new Button("Secondary");
        Button danger = new Button("Danger");
        danger.getStyleClass().add("danger");
        Button ghost = new Button("Ghost");
        ghost.getStyleClass().add("ghost");
        Button withIcon = new Button("With icon", Icon.PLUS.view(16));
        withIcon.getStyleClass().add("primary");
        Button disabled = new Button("Disabled");
        disabled.setDisable(true);
        Button iconOnly = new Button();
        iconOnly.setGraphic(Icon.SETTINGS.view(18));
        iconOnly.getStyleClass().addAll("button", "icon-button");

        FlowPane row = new FlowPane(12, 12, primary, secondary, danger, ghost, withIcon, disabled, iconOnly);
        return section("Buttons", "Primary for the main action, danger for destructive ones, ghost for low emphasis.", row);
    }

    private static Node forms() {
        TextField title = new TextField();
        title.setPromptText("e.g. Introduction to Algorithms");
        FormField titleField = new FormField("Book title", title).required();

        PasswordField password = new PasswordField();
        FormField passwordField = new FormField("Password", password);

        ComboBox<String> category = new ComboBox<>();
        category.getItems().addAll("Fiction", "Science", "History");
        category.setPromptText("Select a category");
        FormField categoryField = new FormField("Category", category);

        DatePicker due = new DatePicker();
        FormField dueField = new FormField("Due date", due);

        CheckBox check = new CheckBox("Include archived books");
        SearchField search = new SearchField("Search by title, author or ISBN");

        Button validate = new Button("Validate form");
        validate.getStyleClass().add("primary");
        validate.setOnAction(e -> {
            if (title.getText().isBlank()) {
                titleField.setError("Book title is required.");
                Toasts.error("Please fix the highlighted field.");
            } else {
                titleField.clearError();
                Toasts.success("Form is valid.");
            }
        });

        VBox left = new VBox(14, titleField, categoryField, dueField);
        VBox right = new VBox(14, passwordField, new VBox(6, label("Search"), search), check, validate);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        return section("Form fields", "Required marker, inline error under the field, red border.", new HBox(24, left, right));
    }

    private static Node badges() {
        FlowPane row = new FlowPane(10, 10,
                StatusBadge.of("Available", StatusBadge.Kind.SUCCESS),
                StatusBadge.of("Borrowed", StatusBadge.Kind.INFO),
                StatusBadge.of("Overdue", StatusBadge.Kind.DANGER),
                StatusBadge.of("Under maintenance", StatusBadge.Kind.WARNING),
                StatusBadge.of("Inactive", StatusBadge.Kind.NEUTRAL));
        return section("Status badges", "Used for copy status, student status and transaction status.", row);
    }

    private static Node table() {
        DataTable<Row> data = new DataTable<>();
        data.addTextColumn("Title", Row::title);
        data.addTextColumn("Author", Row::author);
        TableColumn<Row, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().status()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                StatusBadge.Kind kind = switch (item) {
                    case "Available" -> StatusBadge.Kind.SUCCESS;
                    case "Borrowed" -> StatusBadge.Kind.INFO;
                    case "Overdue", "Lost" -> StatusBadge.Kind.DANGER;
                    default -> StatusBadge.Kind.WARNING;
                };
                setGraphic(StatusBadge.of(item, kind));
            }
        });
        data.addColumn(status);
        data.setEmptyState(Icon.BOOK, "No books found", "Try a different search, or add your first book.");
        data.setPrefHeight(300);
        data.setMinHeight(300);

        Pager pager = new Pager(5, page -> showPage(data, page * 5));
        pager.setTotal(SAMPLE.size());
        showPage(data, 0);

        Button loading = new Button("Show loading");
        loading.setOnAction(e -> {
            data.showLoading();
            PauseTransition wait = new PauseTransition(Duration.seconds(1.2));
            wait.setOnFinished(ev -> showPage(data, pager.getOffset()));
            wait.play();
        });
        Button empty = new Button("Show empty");
        empty.setOnAction(e -> data.showItems(List.of()));
        Button error = new Button("Show error");
        error.setOnAction(e -> data.showError("The book list could not be loaded. Please try again."));
        Button reload = new Button("Show data");
        reload.setOnAction(e -> showPage(data, pager.getOffset()));

        HBox states = new HBox(10, loading, empty, error, reload);
        return section("Data table", "Loading, empty, error and paged states share one component.",
                states, data, pager);
    }

    private static void showPage(DataTable<Row> data, int offset) {
        int end = Math.min(SAMPLE.size(), offset + 5);
        data.showItems(SAMPLE.subList(offset, end));
    }

    private static Node dialogsAndToasts(AppShell shell) {
        Button confirm = new Button("Confirm dialog");
        confirm.setOnAction(e -> {
            boolean yes = AppDialog.confirm(shell.getScene().getWindow(), "Save changes?",
                    "Your changes will be saved to the record.", "Save", false);
            Toasts.info(yes ? "Confirmed." : "Cancelled.");
        });
        Button destructive = new Button("Destructive dialog");
        destructive.getStyleClass().add("danger");
        destructive.setOnAction(e -> {
            boolean yes = AppDialog.confirm(shell.getScene().getWindow(), "Archive this book?",
                    "The book will be hidden from the catalog. Its borrowing history is kept.", "Archive", true);
            Toasts.warning(yes ? "Archived (demo only, nothing changed)." : "Cancelled.");
        });
        Button ok = new Button("Message dialog");
        ok.setOnAction(e -> AppDialog.message(shell.getScene().getWindow(), "Backup failed",
                "The backup could not be created. Check that the destination folder is available."));

        Button s = new Button("Success toast");
        s.setOnAction(e -> Toasts.success("Book added to the catalog."));
        Button er = new Button("Error toast");
        er.setOnAction(e -> Toasts.error("Could not save the student record."));
        Button w = new Button("Warning toast");
        w.setOnAction(e -> Toasts.warning("This student has overdue books."));
        Button i = new Button("Info toast");
        i.setOnAction(e -> Toasts.info("Backup scheduled for tonight."));

        FlowPane row = new FlowPane(12, 12, confirm, destructive, ok, s, er, w, i);
        return section("Dialogs and toasts", "Dialogs for decisions, toasts for quick non-blocking feedback.", row);
    }

    // ------------------------------------------------------------------ helpers

    private static VBox section(String title, String description, Node... content) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        Label d = new Label(description);
        d.getStyleClass().add("muted");
        VBox box = new VBox(12, new VBox(2, t, d));
        box.getChildren().addAll(content);
        box.getStyleClass().add("card");
        return box;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        return l;
    }
}
