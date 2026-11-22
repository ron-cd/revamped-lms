package com.librarymanagement;

import com.librarymanagement.config.AppPaths;
import com.librarymanagement.controllers.LoginController;
import com.librarymanagement.controllers.PasswordChangeScreen;
import com.librarymanagement.controllers.SetupController;
import com.librarymanagement.controllers.UsersPage;
import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.MigrationRunner;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.SettingsRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.navigation.AppShell;
import com.librarymanagement.navigation.Navigator;
import com.librarymanagement.navigation.Page;
import com.librarymanagement.navigation.SessionWatcher;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.Session;
import com.librarymanagement.services.AuthService;
import com.librarymanagement.services.SetupService;
import com.librarymanagement.services.UserService;
import com.librarymanagement.ui.AppDialog;
import com.librarymanagement.ui.GalleryPage;
import com.librarymanagement.ui.PasswordChangeForm;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

public class Main extends Application {

    private Logger log;
    private Stage stage;
    private Navigator nav;
    private AuthService auth;
    private UserService userService;
    private SessionWatcher watcher;

    @Override
    public void start(Stage stage) throws Exception {
        AppPaths paths = AppPaths.resolve();
        paths.ensureDirectories();
        System.setProperty("lms.log.dir", paths.logs().toString()); // read by logback.xml
        log = LoggerFactory.getLogger(Main.class);
        GlobalExceptionHandler.install();

        DatabaseManager db = new DatabaseManager(paths.dbFile());
        int applied = new MigrationRunner(db).migrate();
        log.info("Database ready (migrations applied this run: {})", applied);

        UserRepository users = new UserRepository();
        AuditRepository audit = new AuditRepository();
        PasswordHasher hasher = new PasswordHasher();
        Clock clock = Clock.systemUTC();
        SetupService setup = new SetupService(db, users, audit, hasher);
        this.auth = new AuthService(db, users, new SettingsRepository(), audit, hasher, clock);
        this.userService = new UserService(db, users, audit, hasher, clock);

        this.stage = stage;
        stage.setTitle("Library Management System");
        stage.setMinWidth(900);
        stage.setMinHeight(640);
        this.nav = new Navigator(stage);

        if (setup.isSetupRequired()) {
            nav.<SetupController>show("setup.fxml", c -> c.init(setup,
                    () -> showLogin("Administrator account created. Sign in to continue.")));
        } else {
            showLogin(null);
        }
        stage.show();
    }

    private void showLogin(String notice) {
        nav.<LoginController>show("login.fxml", c -> {
            c.init(auth, this::onSignedIn);
            c.setNotice(notice);
        });
    }

    /** A temporary password must be replaced before anything else is reachable. */
    private void onSignedIn(Session session) {
        if (session.mustChangePassword()) {
            show(PasswordChangeScreen.build(userService, session,
                    () -> showShell(session), () -> signOut(session)), session);
        } else {
            showShell(session);
        }
    }

    private void showShell(Session session) {
        AppShell shell = new AppShell();
        shell.register(Page.USERS, () -> UsersPage.build(shell, session, userService));
        shell.register(Page.GALLERY, () -> GalleryPage.build(shell));

        Set<Page> allowed = EnumSet.noneOf(Page.class);
        for (Page p : Page.values()) if (p.isAllowedFor(session)) allowed.add(p);
        shell.setAllowedPages(allowed);
        shell.setAccount(session.fullName(), session.roleLabel(),
                () -> showChangePasswordDialog(session), () -> signOut(session));

        show(shell, session);
        shell.navigate(allowed.contains(Page.DASHBOARD) ? Page.DASHBOARD : allowed.iterator().next());
    }

    /** Puts a view in the window and (re)starts the inactivity watcher for this session. */
    private void show(Parent root, Session session) {
        if (watcher != null) watcher.stop();
        nav.showRoot(root);
        watcher = new SessionWatcher(stage.getScene(), session, auth.clock(), () -> {
            auth.expire(session);
            showLogin("You were signed out after " + session.timeout().toMinutes() + " minutes of inactivity.");
        });
        watcher.start();
    }

    private void showChangePasswordDialog(Session session) {
        PasswordChangeForm form = new PasswordChangeForm();
        char[][] snapshot = new char[3][];
        boolean changed = AppDialog.form(stage, "Change password", null, form, "Update password",
                () -> {
                    boolean ok = form.validateInput();
                    if (ok) {
                        snapshot[0] = form.current();
                        snapshot[1] = form.newPassword();
                        snapshot[2] = form.confirm();
                    }
                    return ok;
                },
                () -> userService.changeOwnPassword(session, snapshot[0], snapshot[1], snapshot[2]));
        if (changed) Toasts.success("Password updated.");
    }

    private void signOut(Session session) {
        if (watcher != null) watcher.stop();
        auth.logout(session);
        showLogin(null);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
