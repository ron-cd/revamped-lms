package com.librarymanagement.controllers;

import com.librarymanagement.navigation.AppShell;
import com.librarymanagement.security.PasswordGenerator;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.PageResult;
import com.librarymanagement.services.UserService;
import com.librarymanagement.services.UserService.RoleOption;
import com.librarymanagement.services.UserService.UserSummary;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.DataTable;
import com.librarymanagement.ui.FormField;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.Pager;
import com.librarymanagement.ui.SearchField;
import com.librarymanagement.ui.StatusBadge;
import com.librarymanagement.ui.StatusBadge.Kind;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** User Management screen: list, search, add, edit, reset password, activate/deactivate, unlock. UI glue only. */
public final class UsersPage {

    private static final Logger log = LoggerFactory.getLogger(UsersPage.class);
    private static final int PAGE_SIZE = 20;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Session session;
    private final UserService service;

    private final DataTable<UserSummary> table = new DataTable<>();
    private final SearchField search = new SearchField("Search by username or name");
    private final Pager pager = new Pager(PAGE_SIZE, page -> load());
    private final Button editButton = new Button("Edit");
    private final Button resetButton = new Button("Reset password");
    private final Button toggleButton = new Button("Deactivate");
    private final Button unlockButton = new Button("Unlock");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private int loadToken;

    private UsersPage(Session session, UserService service) {
        this.session = session;
        this.service = service;
    }

    public static Node build(AppShell shell, Session session, UserService service) {
        UsersPage page = new UsersPage(session, service);
        return page.create(shell);
    }

    // ------------------------------------------------------------------ layout

    private Node create(AppShell shell) {
        Button add = new Button("Add user", Icon.PLUS.view(16));
        add.getStyleClass().add("primary");
        add.setOnAction(e -> showCreateDialog());
        shell.setActions(add);

        table.addTextColumn("Username", UserSummary::username);
        table.addTextColumn("Full name", UserSummary::fullName);
        table.addColumn(badgeColumn("Role", UsersPage::roleLabel,
                u -> "ADMINISTRATOR".equals(u.roleName()) ? Kind.INFO : Kind.NEUTRAL));
        table.addColumn(badgeColumn("Status", UsersPage::statusText, UsersPage::statusKind));
        table.addColumn(badgeColumn("Password", u -> u.mustChangePassword() ? "Temporary" : "", u -> Kind.WARNING));
        table.addTextColumn("Last sign-in", u -> formatInstant(u.lastLoginAt()));
        table.setEmptyState(Icon.PEOPLE, "No users found", "Try a different search, or add a user.");
        table.getTable().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> updateButtons());

        editButton.setOnAction(e -> showEditDialog(selected()));
        resetButton.setOnAction(e -> showResetDialog(selected()));
        toggleButton.setOnAction(e -> toggleActive(selected()));
        unlockButton.setOnAction(e -> unlock(selected()));

        HBox.setHgrow(search, Priority.ALWAYS);
        HBox toolbar = new HBox(10, search, editButton, resetButton, toggleButton, unlockButton);

        debounce.setOnFinished(e -> {
            pager.reset();
            load();
        });
        search.textProperty().addListener((obs, was, now) -> debounce.playFromStart());

        VBox.setVgrow(table, Priority.ALWAYS);
        VBox root = new VBox(14, toolbar, table, pager);
        updateButtons();
        load();
        return root;
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        int token = ++loadToken;
        table.showLoading();
        String term = search.getField().getText();
        int offset = pager.getOffset();

        Task<PageResult<UserSummary>> task = new Task<>() {
            @Override protected PageResult<UserSummary> call() {
                return service.list(session, term, offset, PAGE_SIZE);
            }
        };
        task.setOnSucceeded(e -> {
            if (token != loadToken) return; // a newer request replaced this one
            PageResult<UserSummary> result = task.getValue();
            int before = pager.getPage();
            pager.setTotal(result.total());
            if (pager.getPage() != before) { // the last page vanished (for example after a search); reload
                load();
                return;
            }
            table.showItems(result.items());
            updateButtons();
        });
        task.setOnFailed(e -> {
            if (token != loadToken) return;
            log.error("Could not load users", task.getException());
            table.showError(GlobalExceptionHandler.userMessage(task.getException()));
        });
        start(task, "load-users");
    }

    private void updateButtons() {
        UserSummary u = selected();
        boolean has = u != null;
        boolean self = has && u.id() == session.userId();
        editButton.setDisable(!has);
        resetButton.setDisable(!has || self);
        toggleButton.setDisable(!has || self);
        toggleButton.setText(has && !u.active() ? "Activate" : "Deactivate");
        unlockButton.setDisable(!has || !u.locked());
    }

    private UserSummary selected() {
        return table.getTable().getSelectionModel().getSelectedItem();
    }

    // ------------------------------------------------------------------ dialogs

    private void showCreateDialog() {
        List<RoleOption> roles = service.roles(session);

        TextField username = new TextField();
        FormField usernameField = new FormField("Username", username).required();
        TextField fullName = new TextField();
        FormField nameField = new FormField("Full name", fullName).required();
        ComboBox<RoleOption> role = roleCombo(roles);
        FormField roleField = new FormField("Role", role).required();
        TextField temp = new TextField(PasswordGenerator.generate());
        FormField tempField = new FormField("Temporary password", temp).required();
        Button generate = new Button("Generate");
        generate.setOnAction(e -> temp.setText(PasswordGenerator.generate()));
        Label hint = new Label("The user must replace this password the first time they sign in. Give it to them in person.");
        hint.getStyleClass().add("faint");
        hint.setWrapText(true);

        VBox content = new VBox(14, usernameField, nameField, roleField,
                new VBox(6, tempField, new HBox(10, generate)), hint);

        Object[] snapshot = new Object[4];
        boolean created = AppDialog.form(window(), "Add user", null, content, "Create user",
                () -> {
                    boolean ok = require(usernameField, username.getText(), "Enter a username.");
                    ok &= require(nameField, fullName.getText(), "Enter the full name.");
                    ok &= requireSelection(roleField, role);
                    ok &= require(tempField, temp.getText(), "Enter a temporary password.");
                    if (ok) {
                        snapshot[0] = username.getText();
                        snapshot[1] = fullName.getText();
                        snapshot[2] = role.getValue().id();
                        snapshot[3] = temp.getText().toCharArray();
                    }
                    return ok;
                },
                () -> service.create(session, (String) snapshot[0], (String) snapshot[1], (Integer) snapshot[2],
                        (char[]) snapshot[3]));
        if (created) {
            Toasts.success("User created. They must change the temporary password at first sign-in.");
            load();
        }
    }

    private void showEditDialog(UserSummary user) {
        if (user == null) return;
        List<RoleOption> roles = service.roles(session);
        boolean self = user.id() == session.userId();

        TextField username = new TextField(user.username());
        username.setDisable(true);
        FormField usernameField = new FormField("Username", username);
        TextField fullName = new TextField(user.fullName());
        FormField nameField = new FormField("Full name", fullName).required();
        ComboBox<RoleOption> role = roleCombo(roles);
        roles.stream().filter(r -> r.id() == user.roleId()).findFirst().ifPresent(role::setValue);
        role.setDisable(self);
        FormField roleField = new FormField("Role", role).required();

        VBox content = new VBox(14, usernameField, nameField, roleField);
        if (self) {
            Label hint = new Label("You cannot change your own role.");
            hint.getStyleClass().add("faint");
            content.getChildren().add(hint);
        }

        Object[] snapshot = new Object[2];
        boolean saved = AppDialog.form(window(), "Edit user", null, content, "Save changes",
                () -> {
                    boolean ok = require(nameField, fullName.getText(), "Enter the full name.");
                    ok &= requireSelection(roleField, role);
                    if (ok) {
                        snapshot[0] = fullName.getText();
                        snapshot[1] = role.getValue().id();
                    }
                    return ok;
                },
                () -> service.update(session, user.id(), (String) snapshot[0], (Integer) snapshot[1]));
        if (saved) {
            Toasts.success("User updated.");
            load();
        }
    }

    private void showResetDialog(UserSummary user) {
        if (user == null) return;
        TextField temp = new TextField(PasswordGenerator.generate());
        FormField tempField = new FormField("New temporary password", temp).required();
        Button generate = new Button("Generate");
        generate.setOnAction(e -> temp.setText(PasswordGenerator.generate()));
        VBox content = new VBox(14, new VBox(6, tempField, new HBox(10, generate)));

        char[][] snapshot = new char[1][];
        boolean done = AppDialog.form(window(), "Reset password",
                "Set a temporary password for " + user.fullName() + ". They must change it at their next sign-in, "
                        + "and any lockout is cleared.",
                content, "Reset password",
                () -> {
                    boolean ok = require(tempField, temp.getText(), "Enter a temporary password.");
                    if (ok) snapshot[0] = temp.getText().toCharArray();
                    return ok;
                },
                () -> service.resetPassword(session, user.id(), snapshot[0]));
        if (done) {
            Toasts.success("Password reset. Give the temporary password to " + user.fullName() + " in person.");
            load();
        }
    }

    private void toggleActive(UserSummary user) {
        if (user == null) return;
        boolean deactivate = user.active();
        boolean confirmed = AppDialog.confirm(window(),
                deactivate ? "Deactivate account?" : "Activate account?",
                deactivate ? user.fullName() + " will no longer be able to sign in. Their history is kept."
                           : user.fullName() + " will be able to sign in again.",
                deactivate ? "Deactivate" : "Activate", deactivate);
        if (confirmed) {
            runAsync(deactivate ? "Account deactivated." : "Account activated.",
                    () -> service.setActive(session, user.id(), !deactivate));
        }
    }

    private void unlock(UserSummary user) {
        if (user == null) return;
        runAsync("Account unlocked.", () -> service.unlock(session, user.id()));
    }

    // ------------------------------------------------------------------ helpers

    private void runAsync(String successMessage, Runnable work) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                work.run();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            Toasts.success(successMessage);
            load();
        });
        task.setOnFailed(e -> {
            Throwable t = task.getException();
            log.warn("User action failed: {}", t.toString());
            Toasts.error(GlobalExceptionHandler.userMessage(t));
            load();
        });
        start(task, "user-action");
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private Window window() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    private static boolean require(FormField field, String value, String message) {
        if (value == null || value.isBlank()) {
            field.setError(message);
            return false;
        }
        field.clearError();
        return true;
    }

    private static boolean requireSelection(FormField field, ComboBox<?> combo) {
        if (combo.getValue() == null) {
            field.setError("Select a role.");
            return false;
        }
        field.clearError();
        return true;
    }

    private static ComboBox<RoleOption> roleCombo(List<RoleOption> roles) {
        ComboBox<RoleOption> combo = new ComboBox<>();
        combo.getItems().addAll(roles);
        combo.setPromptText("Select a role");
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(RoleOption r) { return r == null ? "" : r.label(); }
            @Override public RoleOption fromString(String s) { return null; }
        });
        return combo;
    }

    private static TableColumn<UserSummary, String> badgeColumn(String title, Function<UserSummary, String> text,
                                                                Function<UserSummary, Kind> kind) {
        TableColumn<UserSummary, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(text.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                UserSummary row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                if (row == null || item == null || item.isEmpty()) {
                    setGraphic(null);
                } else {
                    setGraphic(StatusBadge.of(item, kind.apply(row)));
                }
            }
        });
        return column;
    }

    private static String roleLabel(UserSummary u) {
        String lower = u.roleName().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String statusText(UserSummary u) {
        if (!u.active()) return "Inactive";
        return u.locked() ? "Locked" : "Active";
    }

    private static Kind statusKind(UserSummary u) {
        if (!u.active()) return Kind.NEUTRAL;
        return u.locked() ? Kind.WARNING : Kind.SUCCESS;
    }

    private static String formatInstant(String iso) {
        if (iso == null || iso.isEmpty()) return "Never";
        try {
            return DATE_TIME.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }
}
