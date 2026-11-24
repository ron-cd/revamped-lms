package com.librarymanagement.navigation;

import com.librarymanagement.ui.EmptyState;
import com.librarymanagement.ui.Icon;
import com.librarymanagement.ui.Toasts;
import com.librarymanagement.utils.GlobalExceptionHandler;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Main window layout: persistent left sidebar, top bar (page title, page actions, notifications, account)
 * and a content area. Screens are registered per {@link Page}; unregistered pages show a "coming soon" state.
 * The set of visible pages can be restricted later by role (see {@link #setAllowedPages(Set)}).
 */
public class AppShell extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(AppShell.class);
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final Map<Page, Supplier<Node>> registry = new EnumMap<>(Page.class);
    private final Map<Page, Button> navButtons = new EnumMap<>(Page.class);
    private final Map<Page, Region> navIndicators = new EnumMap<>(Page.class);
    private final VBox navBox = new VBox(2);
    private final Label titleLabel = new Label();
    private final HBox actionsBox = new HBox(10);
    private final HBox accountBox = new HBox();
    private final StackPane pageHost = new StackPane();
    private final Label badge = new Label();

    private Set<Page> allowed = EnumSet.allOf(Page.class);
    private Page current;

    public AppShell() {
        BorderPane layout = new BorderPane();
        layout.getStyleClass().add("app-bg");
        layout.setLeft(buildSidebar());
        layout.setTop(buildTopBar());

        pageHost.getStyleClass().add("page-host");
        layout.setCenter(pageHost);

        getChildren().add(layout);
        Toasts.install(this);
        rebuildNav();
    }

    // ------------------------------------------------------------------ public API

    public void register(Page page, Supplier<Node> factory) {
        registry.put(page, factory);
    }

    /** Page factories call this (while being built) to place buttons in the top bar. */
    public void setActions(Node... nodes) {
        actionsBox.getChildren().setAll(nodes);
    }

    public void setAllowedPages(Set<Page> pages) {
        this.allowed = pages.isEmpty() ? EnumSet.noneOf(Page.class) : EnumSet.copyOf(pages);
        rebuildNav();
        if (current != null) markActive(current);
    }

    public void setUnreadCount(int count) {
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setVisible(count > 0);
    }

    /** Shows the signed-in user in the top bar. Used by the login flow. */
    public void setAccount(String name, String role, Runnable onChangePassword, Runnable onSignOut) {
        MenuButton menu = new MenuButton(name + "  |  " + role);
        menu.getStyleClass().add("account-button");
        MenuItem changePassword = new MenuItem("Change password");
        changePassword.setGraphic(Icon.SHIELD.view(16));
        changePassword.setOnAction(e -> onChangePassword.run());
        MenuItem signOut = new MenuItem("Sign out");
        signOut.setGraphic(Icon.LOGOUT.view(16));
        signOut.setOnAction(e -> onSignOut.run());
        menu.getItems().addAll(changePassword, signOut);
        accountBox.getChildren().setAll(menu);
    }

    public void clearAccount() {
        accountBox.getChildren().clear();
    }

    public Page currentPage() {
        return current;
    }

    public void navigate(Page page) {
        if (!allowed.contains(page)) return;
        current = page;
        markActive(page);
        titleLabel.setText(page.title());
        actionsBox.getChildren().clear();

        Node content;
        Supplier<Node> factory = registry.get(page);
        if (factory == null) {
            content = centered(EmptyState.of(page.icon(), "Coming soon",
                    page.title() + " is built in a later phase of this project.", null));
        } else {
            try {
                content = factory.get();
            } catch (RuntimeException e) {
                log.error("Failed to open page {}", page, e);
                content = centered(EmptyState.error(GlobalExceptionHandler.userMessage(e)));
            }
        }
        pageHost.getChildren().setAll(content);
    }

    // ------------------------------------------------------------------ building blocks

    private Node buildSidebar() {
        StackPane logo = new StackPane(Icon.BOOK.view(22));
        logo.getStyleClass().add("brand-logo");
        Label name = new Label("Library");
        name.getStyleClass().add("brand-name");
        Label sub = new Label("Management System");
        sub.getStyleClass().add("brand-sub");
        HBox brand = new HBox(logo, new VBox(1, name, sub));
        brand.getStyleClass().add("brand-box");

        navBox.getStyleClass().add("nav-box");
        ScrollPane navScroll = new ScrollPane(navBox);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(navScroll, Priority.ALWAYS);

        Label footer = new Label("Version 0.4.3");
        footer.getStyleClass().add("sidebar-footer");

        VBox sidebar = new VBox(brand, navScroll, footer);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(250);
        sidebar.setMinWidth(250);
        sidebar.setMaxWidth(250);
        return sidebar;
    }

    private Node buildTopBar() {
        titleLabel.getStyleClass().add("page-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        actionsBox.setAlignment(Pos.CENTER_RIGHT);
        accountBox.setAlignment(Pos.CENTER_RIGHT);

        Button bell = new Button();
        bell.setGraphic(Icon.BELL.view(20));
        bell.getStyleClass().addAll("button", "icon-button");
        bell.setTooltip(new Tooltip("Notifications"));
        bell.setOnAction(e -> navigate(Page.NOTIFICATIONS));

        badge.getStyleClass().add("badge-count");
        badge.setVisible(false);
        badge.setMouseTransparent(true);
        StackPane bellWrap = new StackPane(bell, badge);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);

        HBox bar = new HBox(titleLabel, spacer, actionsBox, bellWrap, accountBox);
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private void rebuildNav() {
        navBox.getChildren().clear();
        navButtons.clear();
        navIndicators.clear();

        String lastSection = "";
        for (Page page : Page.values()) {
            if (!allowed.contains(page)) continue;
            String section = page.section();
            if (section != null && !section.equals(lastSection)) {
                Label heading = new Label(section.toUpperCase());
                heading.getStyleClass().add("nav-section");
                navBox.getChildren().add(heading);
                lastSection = section;
            }

            Button button = new Button(page.title(), page.icon().view(18));
            button.getStyleClass().add("nav-item");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> navigate(page));

            Region indicator = new Region();
            indicator.getStyleClass().add("nav-indicator");
            indicator.setVisible(false);
            indicator.setMouseTransparent(true);
            StackPane.setAlignment(indicator, Pos.CENTER_LEFT);

            navBox.getChildren().add(new StackPane(button, indicator));
            navButtons.put(page, button);
            navIndicators.put(page, indicator);
        }
    }

    private void markActive(Page page) {
        navButtons.forEach((p, b) -> b.pseudoClassStateChanged(ACTIVE, p == page));
        navIndicators.forEach((p, r) -> r.setVisible(p == page));
    }

    private static Node centered(Node node) {
        StackPane wrap = new StackPane(node);
        wrap.setAlignment(Pos.CENTER);
        return wrap;
    }
}
