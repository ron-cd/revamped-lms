package com.librarymanagement.controllers;

import com.librarymanagement.navigation.AppShell;
import com.librarymanagement.security.Permission;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.PageResult;
import com.librarymanagement.services.StudentService;
import com.librarymanagement.services.StudentService.LoanRow;
import com.librarymanagement.services.StudentService.Options;
import com.librarymanagement.services.StudentService.StatusFilter;
import com.librarymanagement.services.StudentService.StudentInput;
import com.librarymanagement.services.StudentService.StudentProfile;
import com.librarymanagement.services.StudentService.StudentView;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.DataTable;
import com.librarymanagement.ui.FormField;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.Pager;
import com.librarymanagement.ui.SearchField;
import com.librarymanagement.ui.StatCard;
import com.librarymanagement.ui.StatusBadge;
import com.librarymanagement.ui.StatusBadge.Kind;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
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
import java.util.function.Function;

/** Student Management screen: search, filters, add, edit, activate/deactivate and profile with loan history. */
public final class StudentsPage {

    private static final Logger log = LoggerFactory.getLogger(StudentsPage.class);
    private static final int PAGE_SIZE = 20;
    private static final String ALL_GRADES = "All grades";

    private final Session session;
    private final StudentService service;
    private final boolean canManage;

    private final DataTable<StudentView> table = new DataTable<>();
    private final SearchField search = new SearchField("Search by student ID or name");
    private final ComboBox<StatusFilter> statusFilter = new ComboBox<>();
    private final ComboBox<String> gradeFilter = new ComboBox<>();
    private final Pager pager = new Pager(PAGE_SIZE, page -> load());
    private final Button profileButton = new Button("View profile");
    private final Button editButton = new Button("Edit");
    private final Button toggleButton = new Button("Deactivate");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private int loadToken;
    private boolean updatingFilters;

    private StudentsPage(Session session, StudentService service) {
        this.session = session;
        this.service = service;
        this.canManage = session.has(Permission.STUDENT_MANAGE);
    }

    public static Node build(AppShell shell, Session session, StudentService service) {
        return new StudentsPage(session, service).create(shell);
    }

    // ------------------------------------------------------------------ layout

    private Node create(AppShell shell) {
        if (canManage) {
            Button add = new Button("Add student", Icon.PLUS.view(16));
            add.getStyleClass().add("primary");
            add.setOnAction(e -> showAddDialog());
            shell.setActions(add);
        }

        table.addTextColumn("Student ID", StudentView::studentNo);
        table.addTextColumn("Name", StudentView::displayName);
        table.addTextColumn("Program / strand", StudentView::program);
        table.addTextColumn("Grade", StudentView::grade);
        table.addTextColumn("Section", StudentView::section);
        table.addColumn(badgeColumn("Status", s -> s.active() ? "Active" : "Inactive",
                s -> s.active() ? Kind.SUCCESS : Kind.NEUTRAL));
        if (canManage) table.addTextColumn("Contact", s -> s.contact() == null ? "" : s.contact());
        table.setEmptyState(Icon.PEOPLE, "No students found", canManage
                ? "Try a different search or filter, or add a student."
                : "Try a different search or filter.");
        table.getTable().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateButtons());
        table.getTable().setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && selected() != null) showProfile(selected());
        });

        statusFilter.getItems().addAll(StatusFilter.values());
        statusFilter.setValue(StatusFilter.ALL);
        statusFilter.setConverter(new StringConverter<>() {
            @Override public String toString(StatusFilter f) {
                return f == null ? "" : switch (f) {
                    case ALL -> "All statuses";
                    case ACTIVE -> "Active";
                    case INACTIVE -> "Inactive";
                };
            }
            @Override public StatusFilter fromString(String s) { return null; }
        });
        gradeFilter.getItems().add(ALL_GRADES);
        gradeFilter.setValue(ALL_GRADES);
        statusFilter.valueProperty().addListener((obs, was, now) -> filtersChanged());
        gradeFilter.valueProperty().addListener((obs, was, now) -> filtersChanged());

        profileButton.setOnAction(e -> showProfile(selected()));
        editButton.setOnAction(e -> showEditDialog(selected()));
        toggleButton.setOnAction(e -> toggleActive(selected()));

        HBox.setHgrow(search, Priority.ALWAYS);
        HBox toolbar = new HBox(10, search, statusFilter, gradeFilter, profileButton);
        if (canManage) toolbar.getChildren().addAll(editButton, toggleButton);

        debounce.setOnFinished(e -> filtersChanged());
        search.textProperty().addListener((obs, was, now) -> debounce.playFromStart());

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox root = new VBox(14, toolbar, table, pager);
        updateButtons();
        refreshGradeOptions();
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
        StatusFilter status = statusFilter.getValue();
        String grade = ALL_GRADES.equals(gradeFilter.getValue()) ? null : gradeFilter.getValue();
        int offset = pager.getOffset();

        Task<PageResult<StudentView>> task = new Task<>() {
            @Override protected PageResult<StudentView> call() {
                return service.list(session, term, status, grade, offset, PAGE_SIZE);
            }
        };
        task.setOnSucceeded(e -> {
            if (token != loadToken) return;
            PageResult<StudentView> result = task.getValue();
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
            log.error("Could not load students", task.getException());
            table.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "load-students");
    }

    /** Fills the grade filter from the grades that exist, keeping the current choice. */
    private void refreshGradeOptions() {
        Task<Options> task = new Task<>() {
            @Override protected Options call() {
                return service.options(session);
            }
        };
        task.setOnSucceeded(e -> {
            String current = gradeFilter.getValue();
            updatingFilters = true;
            gradeFilter.getItems().setAll(ALL_GRADES);
            gradeFilter.getItems().addAll(task.getValue().grades());
            gradeFilter.setValue(gradeFilter.getItems().contains(current) ? current : ALL_GRADES);
            updatingFilters = false;
        });
        task.setOnFailed(e -> log.warn("Could not load grade options: {}", task.getException().toString()));
        start(task, "load-student-options");
    }

    private void updateButtons() {
        StudentView s = selected();
        boolean has = s != null;
        profileButton.setDisable(!has);
        editButton.setDisable(!has);
        toggleButton.setDisable(!has);
        toggleButton.setText(has && !s.active() ? "Activate" : "Deactivate");
    }

    private StudentView selected() {
        return table.getTable().getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------------------ add / edit

    /** Shared field layout for the add and edit dialogs. */
    private static final class StudentForm {
        final TextField studentNo = new TextField();
        final TextField firstName = new TextField();
        final TextField middleName = new TextField();
        final TextField lastName = new TextField();
        final ComboBox<String> program = editableCombo();
        final ComboBox<String> grade = editableCombo();
        final ComboBox<String> section = editableCombo();
        final TextField contact = new TextField();

        final FormField studentNoField = new FormField("Student ID", studentNo);
        final FormField firstField = new FormField("First name", firstName).required();
        final FormField middleField = new FormField("Middle name", middleName);
        final FormField lastField = new FormField("Last name", lastName).required();
        final FormField programField = new FormField("Program or strand", program).required();
        final FormField gradeField = new FormField("Grade level", grade).required();
        final FormField sectionField = new FormField("Section", section).required();
        final FormField contactField = new FormField("Contact information", contact);

        StudentForm(Options options, StudentView existing) {
            program.getItems().addAll(options.programs());
            grade.getItems().addAll(options.grades());
            section.getItems().addAll(options.sections());
            if (existing == null) {
                studentNo.setPromptText("Leave blank to generate automatically");
            } else {
                studentNo.setText(existing.studentNo());
                studentNo.setDisable(true); // IDs never change
                firstName.setText(existing.firstName());
                middleName.setText(existing.middleName());
                lastName.setText(existing.lastName());
                program.getEditor().setText(existing.program());
                grade.getEditor().setText(existing.grade());
                section.getEditor().setText(existing.section());
                contact.setText(existing.contact() == null ? "" : existing.contact());
            }
        }

        Node layout() {
            GridPane grid = new GridPane();
            grid.setHgap(14);
            grid.setVgap(14);
            ColumnConstraints half = new ColumnConstraints();
            half.setPercentWidth(50);
            grid.getColumnConstraints().addAll(half, half);
            grid.add(studentNoField, 0, 0, 2, 1);
            grid.add(firstField, 0, 1);
            grid.add(middleField, 1, 1);
            grid.add(lastField, 0, 2, 2, 1);
            grid.add(programField, 0, 3, 2, 1);
            grid.add(gradeField, 0, 4);
            grid.add(sectionField, 1, 4);
            grid.add(contactField, 0, 5, 2, 1);
            grid.setPadding(new Insets(4, 0, 0, 0));
            return grid;
        }

        /** Required-field check (the service validates everything again). */
        boolean validate() {
            boolean ok = require(firstField, firstName.getText(), "Enter the first name.");
            ok &= require(lastField, lastName.getText(), "Enter the last name.");
            ok &= require(programField, program.getEditor().getText(), "Enter the program or strand.");
            ok &= require(gradeField, grade.getEditor().getText(), "Enter the grade level.");
            ok &= require(sectionField, section.getEditor().getText(), "Enter the section.");
            return ok;
        }

        StudentInput toInput() {
            return new StudentInput(studentNo.getText(), firstName.getText(), middleName.getText(), lastName.getText(),
                    program.getEditor().getText(), grade.getEditor().getText(), section.getEditor().getText(),
                    contact.getText());
        }

        private static ComboBox<String> editableCombo() {
            ComboBox<String> combo = new ComboBox<>();
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
            return combo;
        }
    }

    private void showAddDialog() {
        StudentForm form = new StudentForm(service.options(session), null);
        StudentInput[] snapshot = new StudentInput[1];
        boolean created = AppDialog.form(window(), "Add student", null, form.layout(), "Add student",
                () -> {
                    boolean ok = form.validate();
                    if (ok) snapshot[0] = form.toInput();
                    return ok;
                },
                () -> service.create(session, snapshot[0]), 560);
        if (created) {
            Toasts.success("Student added.");
            refreshGradeOptions();
            load();
        }
    }

    private void showEditDialog(StudentView student) {
        if (student == null) return;
        StudentForm form = new StudentForm(service.options(session), student);
        StudentInput[] snapshot = new StudentInput[1];
        boolean saved = AppDialog.form(window(), "Edit student", null, form.layout(), "Save changes",
                () -> {
                    boolean ok = form.validate();
                    if (ok) snapshot[0] = form.toInput();
                    return ok;
                },
                () -> service.update(session, student.id(), snapshot[0]), 560);
        if (saved) {
            Toasts.success("Student updated.");
            refreshGradeOptions();
            load();
        }
    }

    private void toggleActive(StudentView student) {
        if (student == null) return;
        boolean deactivate = student.active();
        boolean confirmed = AppDialog.confirm(window(),
                deactivate ? "Deactivate student?" : "Activate student?",
                deactivate ? student.displayName() + " will not be able to borrow books. Any books already borrowed can "
                        + "still be returned, and their history is kept."
                           : student.displayName() + " will be able to borrow books again.",
                deactivate ? "Deactivate" : "Activate", deactivate);
        if (!confirmed) return;

        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                service.setActive(session, student.id(), !deactivate);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            Toasts.success(deactivate ? "Student deactivated." : "Student activated.");
            load();
        });
        task.setOnFailed(e -> {
            log.warn("Student status change failed: {}", task.getException().toString());
            Toasts.error(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "student-status");
    }

    // ------------------------------------------------------------------ profile

    private void showProfile(StudentView student) {
        if (student == null) return;

        Label name = new Label(student.displayName());
        name.getStyleClass().add("section-title");
        Label id = StatusBadge.of(student.studentNo(), Kind.NEUTRAL);
        Label status = StatusBadge.of(student.active() ? "Active" : "Inactive",
                student.active() ? Kind.SUCCESS : Kind.NEUTRAL);
        HBox header = new HBox(10, name, id, status);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        GridPane info = new GridPane();
        info.setHgap(32);
        info.setVgap(10);
        addInfo(info, 0, "PROGRAM / STRAND", student.program());
        addInfo(info, 1, "GRADE AND SECTION", student.grade() + " - " + student.section());
        addInfo(info, 2, "REGISTERED", student.registeredAt() == null ? "" : student.registeredAt().substring(0, 10));
        if (student.contact() != null) addInfo(info, 3, "CONTACT", student.contact().isEmpty() ? "-" : student.contact());

        StatCard current = new StatCard("Currently borrowed", Icon.BOOK);
        StatCard overdue = new StatCard("Overdue", Icon.WARNING);
        StatCard total = new StatCard("Total borrowed", Icon.HISTORY);
        HBox stats = new HBox(12, current, overdue, total);
        for (StatCard c : new StatCard[]{current, overdue, total}) HBox.setHgrow(c, Priority.ALWAYS);

        Label historyTitle = new Label("Borrowing history");
        historyTitle.getStyleClass().add("section-title");

        DataTable<LoanRow> loans = new DataTable<>();
        loans.addTextColumn("Book", LoanRow::bookTitle);
        loans.addTextColumn("Copy", LoanRow::copyCode);
        loans.addTextColumn("Issued", LoanRow::issueDate);
        loans.addTextColumn("Due", LoanRow::dueDate);
        loans.addTextColumn("Returned", r -> r.returnedOn() == null ? "" : r.returnedOn());
        loans.addColumn(badgeColumn("Status", LoanRow::state, StudentsPage::loanKind, StudentsPage::loanText));
        loans.setEmptyState(Icon.BOOK, "No borrowing history", "This student has not borrowed any books yet.");
        loans.setPrefHeight(250);
        loans.setMinHeight(250);

        Pager[] holder = new Pager[1];
        holder[0] = new Pager(8, p -> loadLoans(student.id(), loans, holder[0]));
        Pager loanPager = holder[0];

        VBox content = new VBox(16, header, info, stats, historyTitle, loans, loanPager);
        content.setPadding(new Insets(4, 4, 0, 4));

        Task<StudentProfile> task = new Task<>() {
            @Override protected StudentProfile call() {
                return service.profile(session, student.id());
            }
        };
        task.setOnSucceeded(e -> {
            StudentProfile p = task.getValue();
            current.setValue(String.valueOf(p.currentLoans()));
            overdue.setValue(String.valueOf(p.overdueLoans()));
            total.setValue(String.valueOf(p.totalLoans()));
        });
        task.setOnFailed(e -> log.warn("Could not load profile: {}", task.getException().toString()));
        start(task, "student-profile");
        loadLoans(student.id(), loans, loanPager);

        AppDialog.view(window(), "Student profile", content, 780);
    }

    private void loadLoans(long studentId, DataTable<LoanRow> loans, Pager loanPager) {
        loans.showLoading();
        int offset = loanPager.getOffset();
        Task<PageResult<LoanRow>> task = new Task<>() {
            @Override protected PageResult<LoanRow> call() {
                return service.loans(session, studentId, offset, loanPager.getPageSize());
            }
        };
        task.setOnSucceeded(e -> {
            loanPager.setTotal(task.getValue().total());
            loans.showItems(task.getValue().items());
        });
        task.setOnFailed(e -> {
            log.error("Could not load loan history", task.getException());
            loans.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "student-loans");
    }

    private static void addInfo(GridPane grid, int column, String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("info-label");
        Label v = new Label(value);
        v.getStyleClass().add("info-value");
        grid.add(new VBox(2, l, v), column, 0);
    }

    // ------------------------------------------------------------------ helpers

    private static Kind loanKind(LoanRow r) {
        return switch (r.state()) {
            case "OVERDUE", "LOST" -> Kind.DANGER;
            case "ACTIVE" -> Kind.INFO;
            default -> Kind.SUCCESS;
        };
    }

    private static String loanText(LoanRow r) {
        return switch (r.state()) {
            case "OVERDUE" -> "Overdue";
            case "ACTIVE" -> "Borrowed";
            case "LOST" -> "Lost";
            default -> r.condition() == null ? "Returned" : switch (r.condition()) {
                case "MINOR_DAMAGE" -> "Returned (minor damage)";
                case "DAMAGED" -> "Returned (damaged)";
                default -> "Returned";
            };
        };
    }

    private static <T> TableColumn<T, String> badgeColumn(String title, Function<T, String> text, Function<T, Kind> kind) {
        return badgeColumn(title, text, kind, text);
    }

    /** Column showing a coloured badge; {@code display} gives the label, {@code text} the sort-neutral cell value. */
    private static <T> TableColumn<T, String> badgeColumn(String title, Function<T, String> value,
                                                          Function<T, Kind> kind, Function<T, String> display) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                T row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                if (row == null || item == null || item.isEmpty()) {
                    setGraphic(null);
                } else {
                    setGraphic(StatusBadge.of(display.apply(row), kind.apply(row)));
                }
            }
        });
        return column;
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
