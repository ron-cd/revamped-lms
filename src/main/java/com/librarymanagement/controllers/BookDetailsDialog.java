package com.librarymanagement.controllers;

import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.BookService;
import com.librarymanagement.services.BookService.BookView;
import com.librarymanagement.services.BookService.CopyView;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.BadgeColumn;
import com.librarymanagement.ui.DataTable;
import com.librarymanagement.ui.FormField;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.StatusBadge;
import com.librarymanagement.ui.StatusBadge.Kind;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Details of one book and its physical copies, with add, edit and remove for users who may manage books. */
final class BookDetailsDialog {

    private static final Logger log = LoggerFactory.getLogger(BookDetailsDialog.class);

    private final Session session;
    private final BookService service;
    private final BookView book;
    private final boolean canManage;
    private final DataTable<CopyView> copies = new DataTable<>();
    private final Label summary = new Label();
    private final Button addButton = new Button("Add copies", Icon.PLUS.view(16));
    private final Button editButton = new Button("Edit copy");
    private final Button removeButton = new Button("Remove copy");
    private final VBox root = new VBox(16);
    private boolean changed;

    private BookDetailsDialog(Session session, BookService service, BookView book) {
        this.session = session;
        this.service = service;
        this.book = book;
        this.canManage = session.has(Permission.BOOK_MANAGE);
    }

    /** @param onChanged runs after the dialog closes if any copy was changed, so the catalog can refresh */
    static void show(Window owner, Session session, BookService service, BookView book, Runnable onChanged) {
        BookDetailsDialog dialog = new BookDetailsDialog(session, service, book);
        dialog.build();
        dialog.loadCopies();
        AppDialog.view(owner, "Book details", dialog.root, 820);
        if (dialog.changed) onChanged.run();
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        Label title = new Label(book.title());
        title.getStyleClass().add("section-title");
        title.setWrapText(true);
        HBox header = new HBox(10, title);
        header.setAlignment(Pos.CENTER_LEFT);
        if (book.archived()) header.getChildren().add(StatusBadge.of("Archived", Kind.NEUTRAL));

        GridPane info = new GridPane();
        info.setHgap(32);
        info.setVgap(12);
        addInfo(info, 0, 0, "AUTHOR", book.author());
        addInfo(info, 1, 0, "CATEGORY", dash(book.category()));
        addInfo(info, 2, 0, "ISBN", dash(book.isbn()));
        addInfo(info, 0, 1, "PUBLISHER", dash(book.publisher()));
        addInfo(info, 1, 1, "PUBLICATION YEAR", book.year() == null ? "-" : String.valueOf(book.year()));

        root.getChildren().addAll(header, info);
        if (!book.description().isEmpty()) {
            Label description = new Label(book.description());
            description.setWrapText(true);
            description.getStyleClass().add("muted");
            root.getChildren().add(description);
        }

        Label copiesTitle = new Label("Copies");
        copiesTitle.getStyleClass().add("section-title");
        summary.getStyleClass().add("muted");
        HBox titleRow = new HBox(12, copiesTitle, summary);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        copies.addTextColumn("Copy code", CopyView::code);
        copies.addColumn(BadgeColumn.of("Status", c -> statusText(c.status()), c -> statusKind(c.status())));
        copies.addTextColumn("Borrowed by", c -> c.borrower() == null ? "" : c.borrower());
        copies.addTextColumn("Due", c -> c.dueDate() == null ? "" : c.dueDate());
        copies.addTextColumn("Notes", CopyView::notes);
        copies.setEmptyState(Icon.BOOK, "No copies yet", canManage
                ? "Add copies so this book can be borrowed."
                : "This book has no copies yet.");
        copies.setPrefHeight(240);
        copies.setMinHeight(240);
        copies.getTable().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateButtons());

        addButton.setOnAction(e -> showAddCopies());
        editButton.setOnAction(e -> showEditCopy(selected()));
        removeButton.setOnAction(e -> removeCopy(selected()));
        HBox actions = new HBox(10);
        if (canManage) actions.getChildren().addAll(addButton, editButton, removeButton);
        addButton.setDisable(book.archived());

        VBox.setVgrow(copies, Priority.ALWAYS);
        root.getChildren().addAll(titleRow, copies);
        if (canManage) root.getChildren().add(actions);
        root.setPadding(new Insets(4, 4, 0, 4));
        updateButtons();
    }

    private void updateButtons() {
        boolean has = selected() != null;
        editButton.setDisable(!has);
        removeButton.setDisable(!has);
    }

    private CopyView selected() {
        return copies.getTable().getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------------------ loading

    private void loadCopies() {
        copies.showLoading();
        Task<List<CopyView>> task = new Task<>() {
            @Override protected List<CopyView> call() {
                return service.copies(session, book.id());
            }
        };
        task.setOnSucceeded(e -> {
            List<CopyView> list = task.getValue();
            copies.showItems(list);
            long available = list.stream().filter(c -> "AVAILABLE".equals(c.status())).count();
            summary.setText(list.isEmpty() ? "" : available + " of " + list.size() + " available");
            updateButtons();
        });
        task.setOnFailed(e -> {
            log.error("Could not load copies", task.getException());
            copies.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "load-copies");
    }

    // ------------------------------------------------------------------ changes

    private void showAddCopies() {
        TextField count = new TextField("1");
        TextField notes = new TextField();
        notes.setPromptText("Optional, applies to all new copies");
        FormField countField = new FormField("Number of copies", count).required();
        FormField notesField = new FormField("Notes", notes);
        int[] number = new int[1];
        String[] noteText = new String[1];

        boolean added = AppDialog.form(window(), "Add copies", book.title(), new VBox(14, countField, notesField),
                "Add copies",
                () -> {
                    String t = count.getText() == null ? "" : count.getText().trim();
                    if (!t.matches("\\d{1,2}") || Integer.parseInt(t) < 1 || Integer.parseInt(t) > 50) {
                        countField.setError("Enter a number from 1 to 50.");
                        return false;
                    }
                    countField.clearError();
                    number[0] = Integer.parseInt(t);
                    noteText[0] = notes.getText();
                    return true;
                },
                () -> service.addCopies(session, book.id(), number[0], noteText[0]), 460);
        if (added) {
            changed = true;
            Toasts.success(number[0] == 1 ? "Copy added." : number[0] + " copies added.");
            loadCopies();
        }
    }

    private void showEditCopy(CopyView copy) {
        if (copy == null) return;
        boolean onLoan = "BORROWED".equals(copy.status());
        ComboBox<String> status = new ComboBox<>();
        status.setMaxWidth(Double.MAX_VALUE);
        if (onLoan) {
            status.getItems().add("BORROWED");
            status.setDisable(true);
        } else {
            status.getItems().addAll("AVAILABLE", "MAINTENANCE", "DAMAGED", "LOST");
        }
        status.setConverter(new StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : statusText(s); }
            @Override public String fromString(String s) { return null; }
        });
        status.setValue(copy.status());
        TextField notes = new TextField(copy.notes());
        FormField statusField = new FormField("Status", status);
        FormField notesField = new FormField("Notes", notes);
        VBox content = new VBox(14, statusField, notesField);
        if (onLoan) {
            Label hint = new Label("This copy is on loan. Its status changes when it is returned.");
            hint.getStyleClass().add("muted");
            hint.setWrapText(true);
            content.getChildren().add(hint);
        }
        String[] chosenStatus = new String[1];
        String[] chosenNotes = new String[1];

        boolean saved = AppDialog.form(window(), "Edit copy " + copy.code(), book.title(), content, "Save changes",
                () -> {
                    chosenStatus[0] = status.getValue();
                    chosenNotes[0] = notes.getText();
                    return true;
                },
                () -> service.updateCopy(session, copy.id(), chosenStatus[0], chosenNotes[0]), 460);
        if (saved) {
            changed = true;
            Toasts.success("Copy updated.");
            loadCopies();
        }
    }

    private void removeCopy(CopyView copy) {
        if (copy == null) return;
        boolean confirmed = AppDialog.confirm(window(), "Remove copy?",
                "Copy " + copy.code() + " will be removed. This is only possible for copies that were added by mistake "
                        + "and have never been borrowed.",
                "Remove", true);
        if (!confirmed) return;

        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                service.removeCopy(session, copy.id());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            changed = true;
            Toasts.success("Copy removed.");
            loadCopies();
        });
        task.setOnFailed(e -> {
            log.warn("Copy removal failed: {}", task.getException().toString());
            // shown in a dialog (not a toast): the book dialog is modal and may hide toasts behind it
            AppDialog.message(window(), "Could not remove copy", GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "copy-remove");
    }

    // ------------------------------------------------------------------ helpers

    static String statusText(String status) {
        return switch (status) {
            case "AVAILABLE" -> "Available";
            case "BORROWED" -> "Borrowed";
            case "DAMAGED" -> "Damaged";
            case "LOST" -> "Lost";
            case "MAINTENANCE" -> "In maintenance";
            default -> status;
        };
    }

    static Kind statusKind(String status) {
        return switch (status) {
            case "AVAILABLE" -> Kind.SUCCESS;
            case "BORROWED" -> Kind.INFO;
            case "MAINTENANCE" -> Kind.WARNING;
            default -> Kind.DANGER;
        };
    }

    private static void addInfo(GridPane grid, int column, int row, String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("info-label");
        Label v = new Label(value);
        v.getStyleClass().add("info-value");
        v.setWrapText(true);
        grid.add(new VBox(2, l, v), column, row);
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
