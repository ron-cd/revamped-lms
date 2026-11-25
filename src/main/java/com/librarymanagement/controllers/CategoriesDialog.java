package com.librarymanagement.controllers;

import com.librarymanagement.security.Session;
import com.librarymanagement.services.BookService;
import com.librarymanagement.services.BookService.CategoryView;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.DataTable;
import com.librarymanagement.ui.FormField;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Add, rename and delete book categories. A category that books still use cannot be deleted. */
final class CategoriesDialog {

    private static final Logger log = LoggerFactory.getLogger(CategoriesDialog.class);

    private final Session session;
    private final BookService service;
    private final DataTable<CategoryView> table = new DataTable<>();
    private final Button renameButton = new Button("Rename");
    private final Button deleteButton = new Button("Delete");
    private final VBox root = new VBox(14);
    private boolean changed;

    private CategoriesDialog(Session session, BookService service) {
        this.session = session;
        this.service = service;
    }

    /** @param onChanged runs after the dialog closes if any category was added, renamed or deleted */
    static void show(Window owner, Session session, BookService service, Runnable onChanged) {
        CategoriesDialog dialog = new CategoriesDialog(session, service);
        dialog.build();
        dialog.load();
        AppDialog.view(owner, "Book categories", dialog.root, 520);
        if (dialog.changed) onChanged.run();
    }

    private void build() {
        table.addTextColumn("Category", CategoryView::name);
        table.addTextColumn("Books", c -> String.valueOf(c.bookCount()));
        table.setEmptyState(Icon.BOOK, "No categories yet", "Add a category to organise the catalog.");
        table.setPrefHeight(280);
        table.setMinHeight(280);
        table.getTable().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateButtons());

        Button add = new Button("Add category", Icon.PLUS.view(16));
        add.getStyleClass().add("primary");
        add.setOnAction(e -> showAdd());
        renameButton.setOnAction(e -> showRename(selected()));
        deleteButton.setOnAction(e -> delete(selected()));

        VBox.setVgrow(table, Priority.ALWAYS);
        root.getChildren().addAll(table, new HBox(10, add, renameButton, deleteButton));
        root.setPadding(new Insets(4, 4, 0, 4));
        updateButtons();
    }

    private void updateButtons() {
        boolean has = selected() != null;
        renameButton.setDisable(!has);
        deleteButton.setDisable(!has);
    }

    private CategoryView selected() {
        return table.getTable().getSelectionModel().getSelectedItem();
    }

    private void load() {
        table.showLoading();
        Task<List<CategoryView>> task = new Task<>() {
            @Override protected List<CategoryView> call() {
                return service.categories(session);
            }
        };
        task.setOnSucceeded(e -> {
            table.showItems(task.getValue());
            updateButtons();
        });
        task.setOnFailed(e -> {
            log.error("Could not load categories", task.getException());
            table.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "load-categories-dialog");
    }

    private void showAdd() {
        TextField name = new TextField();
        FormField field = new FormField("Category name", name).required();
        String[] value = new String[1];
        boolean added = AppDialog.form(window(), "Add category", null, field, "Add category",
                () -> {
                    if (name.getText() == null || name.getText().isBlank()) {
                        field.setError("Enter a category name.");
                        return false;
                    }
                    field.clearError();
                    value[0] = name.getText();
                    return true;
                },
                () -> service.createCategory(session, value[0]), 420);
        if (added) {
            changed = true;
            Toasts.success("Category added.");
            load();
        }
    }

    private void showRename(CategoryView category) {
        if (category == null) return;
        TextField name = new TextField(category.name());
        FormField field = new FormField("Category name", name).required();
        String[] value = new String[1];
        boolean renamed = AppDialog.form(window(), "Rename category", null, field, "Save",
                () -> {
                    if (name.getText() == null || name.getText().isBlank()) {
                        field.setError("Enter a category name.");
                        return false;
                    }
                    field.clearError();
                    value[0] = name.getText();
                    return true;
                },
                () -> service.renameCategory(session, category.id(), value[0]), 420);
        if (renamed) {
            changed = true;
            Toasts.success("Category renamed.");
            load();
        }
    }

    private void delete(CategoryView category) {
        if (category == null) return;
        boolean confirmed = AppDialog.confirm(window(), "Delete category?",
                "\"" + category.name() + "\" will be deleted. Only categories that no book uses can be deleted.",
                "Delete", true);
        if (!confirmed) return;

        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                service.deleteCategory(session, category.id());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            changed = true;
            Toasts.success("Category deleted.");
            load();
        });
        task.setOnFailed(e -> {
            log.warn("Category delete failed: {}", task.getException().toString());
            AppDialog.message(window(), "Could not delete category", GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "category-delete");
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
