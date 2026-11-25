package com.librarymanagement.controllers;

import com.librarymanagement.navigation.AppShell;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.BookService;
import com.librarymanagement.services.BookService.ArchiveFilter;
import com.librarymanagement.services.BookService.AvailabilityFilter;
import com.librarymanagement.services.BookService.BookInput;
import com.librarymanagement.services.BookService.BookView;
import com.librarymanagement.services.BookService.CategoryView;
import com.librarymanagement.services.PageResult;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.BadgeColumn;
import com.librarymanagement.ui.DataTable;
import com.librarymanagement.ui.FormField;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.Pager;
import com.librarymanagement.ui.SearchField;
import com.librarymanagement.ui.StatusBadge.Kind;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Book Catalog screen: search, filters, add, edit, archive/restore, copies and categories. */
public final class BooksPage {

    private static final Logger log = LoggerFactory.getLogger(BooksPage.class);
    private static final int PAGE_SIZE = 20;

    /** An entry in a category drop-down. A null id means "all categories" (filter) or "no category" (form). */
    record CategoryChoice(Long id, String name) {
        @Override public String toString() { return name; }
    }

    private static final CategoryChoice ALL_CATEGORIES = new CategoryChoice(null, "All categories");
    private static final CategoryChoice NO_CATEGORY = new CategoryChoice(null, "No category");

    private final Session session;
    private final BookService service;
    private final boolean canManage;

    private final DataTable<BookView> table = new DataTable<>();
    private final SearchField search = new SearchField("Search by title, author, ISBN or copy code");
    private final ComboBox<CategoryChoice> categoryFilter = new ComboBox<>();
    private final ComboBox<AvailabilityFilter> availabilityFilter = new ComboBox<>();
    private final ComboBox<ArchiveFilter> archiveFilter = new ComboBox<>();
    private final Pager pager = new Pager(PAGE_SIZE, page -> load());
    private final Button detailsButton = new Button("Details and copies");
    private final Button editButton = new Button("Edit");
    private final Button archiveButton = new Button("Archive");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private int loadToken;
    private boolean updatingFilters;

    private BooksPage(Session session, BookService service) {
        this.session = session;
        this.service = service;
        this.canManage = session.has(Permission.BOOK_MANAGE);
    }

    public static Node build(AppShell shell, Session session, BookService service) {
        return new BooksPage(session, service).create(shell);
    }

    // ------------------------------------------------------------------ layout

    private Node create(AppShell shell) {
        if (canManage) {
            Button categories = new Button("Categories", Icon.BOOK.view(16));
            categories.setOnAction(e -> CategoriesDialog.show(window(), session, service, this::onCategoriesChanged));
            Button add = new Button("Add book", Icon.PLUS.view(16));
            add.getStyleClass().add("primary");
            add.setOnAction(e -> showAddDialog());
            shell.setActions(categories, add);
        }

        table.addTextColumn("Title", BookView::title);
        table.addTextColumn("Author", BookView::author);
        table.addTextColumn("Category", BookView::category);
        table.addTextColumn("ISBN", BookView::isbn);
        table.addTextColumn("Available", b -> b.availableCopies() + " of " + b.totalCopies());
        table.addColumn(BadgeColumn.of("Status", BooksPage::statusText, BooksPage::statusKind));
        table.setEmptyState(Icon.BOOK, "No books found", canManage
                ? "Try a different search or filter, or add a book."
                : "Try a different search or filter.");
        table.getTable().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateButtons());
        table.getTable().setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && selected() != null) showDetails(selected());
        });

        categoryFilter.getItems().add(ALL_CATEGORIES);
        categoryFilter.setValue(ALL_CATEGORIES);

        availabilityFilter.getItems().addAll(AvailabilityFilter.values());
        availabilityFilter.setValue(AvailabilityFilter.ALL);
        availabilityFilter.setConverter(BooksPage.<AvailabilityFilter>converter(f -> switch (f) {
            case ALL -> "Any availability";
            case AVAILABLE -> "Has available copies";
            case UNAVAILABLE -> "No copies available";
        }));

        archiveFilter.getItems().addAll(ArchiveFilter.values());
        archiveFilter.setValue(ArchiveFilter.ACTIVE);
        archiveFilter.setConverter(BooksPage.<ArchiveFilter>converter(f -> switch (f) {
            case ACTIVE -> "Active books";
            case ARCHIVED -> "Archived books";
            case ALL -> "Active and archived";
        }));

        categoryFilter.valueProperty().addListener((obs, was, now) -> filtersChanged());
        availabilityFilter.valueProperty().addListener((obs, was, now) -> filtersChanged());
        archiveFilter.valueProperty().addListener((obs, was, now) -> filtersChanged());

        detailsButton.setOnAction(e -> showDetails(selected()));
        editButton.setOnAction(e -> showEditDialog(selected()));
        archiveButton.setOnAction(e -> toggleArchived(selected()));

        HBox.setHgrow(search, Priority.ALWAYS);
        HBox toolbar = new HBox(10, search, categoryFilter, availabilityFilter, archiveFilter);
        HBox actions = new HBox(10, detailsButton);
        if (canManage) actions.getChildren().addAll(editButton, archiveButton);

        debounce.setOnFinished(e -> filtersChanged());
        search.textProperty().addListener((obs, was, now) -> debounce.playFromStart());

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox root = new VBox(14, toolbar, actions, table, pager);
        updateButtons();
        refreshCategoryFilter();
        load();
        return root;
    }

    // ------------------------------------------------------------------ loading

    private void filtersChanged() {
        if (updatingFilters) return;
        pager.reset();
        load();
    }

    private void load() {
        int token = ++loadToken;
        table.showLoading();
        String term = search.getField().getText();
        CategoryChoice category = categoryFilter.getValue();
        Long categoryId = category == null ? null : category.id();
        AvailabilityFilter availability = availabilityFilter.getValue();
        ArchiveFilter archive = archiveFilter.getValue();
        int offset = pager.getOffset();

        Task<PageResult<BookView>> task = new Task<>() {
            @Override protected PageResult<BookView> call() {
                return service.list(session, term, categoryId, availability, archive, offset, PAGE_SIZE);
            }
        };
        task.setOnSucceeded(e -> {
            if (token != loadToken) return;
            PageResult<BookView> result = task.getValue();
            int before = pager.getPage();
            pager.setTotal(result.total());
            if (pager.getPage() != before) {
                load();
                return;
            }
            table.showItems(result.items());
            updateButtons();
        });
        task.setOnFailed(e -> {
            if (token != loadToken) return;
            log.error("Could not load books", task.getException());
            table.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "load-books");
    }

    /** Fills the category filter from the categories that exist, keeping the current choice. */
    private void refreshCategoryFilter() {
        Task<List<CategoryView>> task = new Task<>() {
            @Override protected List<CategoryView> call() {
                return service.categories(session);
            }
        };
        task.setOnSucceeded(e -> {
            Long currentId = categoryFilter.getValue() == null ? null : categoryFilter.getValue().id();
            updatingFilters = true;
            categoryFilter.getItems().setAll(ALL_CATEGORIES);
            CategoryChoice keep = ALL_CATEGORIES;
            for (CategoryView c : task.getValue()) {
                CategoryChoice choice = new CategoryChoice(c.id(), c.name());
                categoryFilter.getItems().add(choice);
                if (currentId != null && currentId == c.id()) keep = choice;
            }
            categoryFilter.setValue(keep);
            updatingFilters = false;
        });
        task.setOnFailed(e -> log.warn("Could not load categories: {}", task.getException().toString()));
        start(task, "load-categories");
    }

    private void onCategoriesChanged() {
        refreshCategoryFilter();
        load();
    }

    private void updateButtons() {
        BookView b = selected();
        boolean has = b != null;
        detailsButton.setDisable(!has);
        editButton.setDisable(!has);
        archiveButton.setDisable(!has);
        archiveButton.setText(has && b.archived() ? "Restore" : "Archive");
    }

    private BookView selected() {
        return table.getTable().getSelectionModel().getSelectedItem();
    }

    private void showDetails(BookView book) {
        if (book == null) return;
        BookDetailsDialog.show(window(), session, service, book, this::load);
    }

    // ------------------------------------------------------------------ add / edit

    /** Shared field layout for the add and edit dialogs. */
    private static final class BookForm {
        final TextField isbn = new TextField();
        final TextField title = new TextField();
        final TextField author = new TextField();
        final TextField publisher = new TextField();
        final TextField year = new TextField();
        final ComboBox<CategoryChoice> category = new ComboBox<>();
        final TextArea description = new TextArea();
        final TextField copies = new TextField("1");
        final boolean adding;

        final FormField isbnField = new FormField("ISBN", isbn);
        final FormField titleField = new FormField("Title", title).required();
        final FormField authorField = new FormField("Author", author).required();
        final FormField publisherField = new FormField("Publisher", publisher);
        final FormField yearField = new FormField("Publication year", year);
        final FormField categoryField = new FormField("Category", category);
        final FormField descriptionField = new FormField("Description", description);
        final FormField copiesField = new FormField("Number of copies", copies);

        BookForm(List<CategoryView> categories, BookView existing) {
            this.adding = existing == null;
            category.getItems().add(NO_CATEGORY);
            CategoryChoice selected = NO_CATEGORY;
            for (CategoryView c : categories) {
                CategoryChoice choice = new CategoryChoice(c.id(), c.name());
                category.getItems().add(choice);
                if (existing != null && existing.categoryId() != null && existing.categoryId() == c.id()) selected = choice;
            }
            category.setValue(selected);
            category.setMaxWidth(Double.MAX_VALUE);
            description.setPrefRowCount(4);
            description.setWrapText(true);
            isbn.setPromptText("10 or 13 digits, optional");
            year.setPromptText("e.g. 2019");
            if (existing != null) {
                isbn.setText(existing.isbn());
                title.setText(existing.title());
                author.setText(existing.author());
                publisher.setText(existing.publisher());
                year.setText(existing.year() == null ? "" : String.valueOf(existing.year()));
                description.setText(existing.description());
            }
        }

        Node layout() {
            GridPane grid = new GridPane();
            grid.setHgap(14);
            grid.setVgap(14);
            ColumnConstraints half = new ColumnConstraints();
            half.setPercentWidth(50);
            grid.getColumnConstraints().addAll(half, half);
            grid.add(titleField, 0, 0, 2, 1);
            grid.add(authorField, 0, 1, 2, 1);
            grid.add(isbnField, 0, 2);
            grid.add(yearField, 1, 2);
            grid.add(publisherField, 0, 3);
            grid.add(categoryField, 1, 3);
            grid.add(descriptionField, 0, 4, 2, 1);
            if (adding) grid.add(copiesField, 0, 5);
            grid.setPadding(new Insets(4, 0, 0, 0));
            return grid;
        }

        /** Required-field and number-format checks (the service validates everything again). */
        boolean validate() {
            boolean ok = require(titleField, title.getText(), "Enter the title.");
            ok &= require(authorField, author.getText(), "Enter the author.");
            String y = year.getText() == null ? "" : year.getText().trim();
            if (!y.isEmpty() && !y.matches("\\d{4}")) {
                yearField.setError("Enter a four-digit year.");
                ok = false;
            } else {
                yearField.clearError();
            }
            if (adding) {
                String n = copies.getText() == null ? "" : copies.getText().trim();
                if (!n.matches("\\d{1,2}")) {
                    copiesField.setError("Enter a number from 0 to 50.");
                    ok = false;
                } else {
                    copiesField.clearError();
                }
            }
            return ok;
        }

        BookInput toInput() {
            String y = year.getText() == null ? "" : year.getText().trim();
            CategoryChoice c = category.getValue();
            return new BookInput(isbn.getText(), title.getText(), author.getText(), publisher.getText(),
                    y.isEmpty() ? null : Integer.valueOf(y), c == null ? null : c.id(), description.getText());
        }

        int copyCount() {
            return Integer.parseInt(copies.getText().trim());
        }
    }

    private void showAddDialog() {
        BookForm form = new BookForm(service.categories(session), null);
        BookInput[] input = new BookInput[1];
        int[] copies = new int[1];
        boolean created = AppDialog.form(window(), "Add book", null, form.layout(), "Add book",
                () -> {
                    boolean ok = form.validate();
                    if (ok) {
                        input[0] = form.toInput();
                        copies[0] = form.copyCount();
                    }
                    return ok;
                },
                () -> service.create(session, input[0], copies[0]), 600);
        if (created) {
            Toasts.success("Book added.");
            load();
        }
    }

    private void showEditDialog(BookView book) {
        if (book == null) return;
        BookForm form = new BookForm(service.categories(session), book);
        BookInput[] input = new BookInput[1];
        boolean saved = AppDialog.form(window(), "Edit book", null, form.layout(), "Save changes",
                () -> {
                    boolean ok = form.validate();
                    if (ok) input[0] = form.toInput();
                    return ok;
                },
                () -> service.update(session, book.id(), input[0]), 600);
        if (saved) {
            Toasts.success("Book updated.");
            load();
        }
    }

    private void toggleArchived(BookView book) {
        if (book == null) return;
        boolean archive = !book.archived();
        boolean confirmed = AppDialog.confirm(window(),
                archive ? "Archive book?" : "Restore book?",
                archive ? "\"" + book.title() + "\" will be hidden from the catalog and cannot be issued. "
                        + "Its borrowing history is kept, and you can restore it at any time."
                        : "\"" + book.title() + "\" will appear in the catalog again.",
                archive ? "Archive" : "Restore", archive);
        if (!confirmed) return;

        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                service.setArchived(session, book.id(), archive);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            Toasts.success(archive ? "Book archived." : "Book restored.");
            load();
        });
        task.setOnFailed(e -> {
            log.warn("Archive change failed: {}", task.getException().toString());
            Toasts.error(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "book-archive");
    }

    // ------------------------------------------------------------------ helpers

    private static String statusText(BookView b) {
        if (b.archived()) return "Archived";
        if (b.totalCopies() == 0) return "No copies";
        return b.availableCopies() > 0 ? "Available" : "Unavailable";
    }

    private static Kind statusKind(BookView b) {
        if (b.archived()) return Kind.NEUTRAL;
        if (b.totalCopies() == 0) return Kind.WARNING;
        return b.availableCopies() > 0 ? Kind.SUCCESS : Kind.WARNING;
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : label.apply(value); }
            @Override public T fromString(String s) { return null; }
        };
    }

    private static boolean require(FormField field, String value, String message) {
        if (value == null || value.isBlank()) {
            field.setError(message);
            return false;
        }
        field.clearError();
        return true;
    }

    private Window window() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
