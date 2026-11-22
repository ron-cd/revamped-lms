package com.librarymanagement.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.function.Function;

/**
 * Standard table used by every list screen. Handles the four states consistently:
 * loading (spinner overlay), ready, empty (friendly message) and error (message instead of rows).
 * Use together with {@link Pager} so only one page of rows is held in memory.
 */
public class DataTable<T> extends StackPane {

    private final TableView<T> table = new TableView<>();
    private final StackPane stateHost = new StackPane();
    private final LoadingOverlay overlay;

    private Icon emptyIcon = Icon.INFO;
    private String emptyTitle = "Nothing here yet";
    private String emptyMessage = "There are no records to show.";

    @SuppressWarnings("deprecation") // the plain constant works on every JavaFX 21 build
    public DataTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new javafx.scene.control.Label()); // our own states replace the default text
        stateHost.setVisible(false);
        stateHost.setPickOnBounds(false);

        overlay = new LoadingOverlay(new StackPane(table, stateHost));
        getChildren().add(overlay);
        getStyleClass().add("data-table");
    }

    public TableView<T> getTable() {
        return table;
    }

    public TableColumn<T, String> addTextColumn(String title, Function<T, String> getter) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        table.getColumns().add(column);
        return column;
    }

    public void addColumn(TableColumn<T, ?> column) {
        table.getColumns().add(column);
    }

    public void setEmptyState(Icon icon, String title, String message) {
        this.emptyIcon = icon;
        this.emptyTitle = title;
        this.emptyMessage = message;
    }

    public void showLoading() {
        overlay.setLoading(true);
    }

    public void showItems(List<T> items) {
        overlay.setLoading(false);
        table.getItems().setAll(items);
        if (items.isEmpty()) {
            showState(EmptyState.of(emptyIcon, emptyTitle, emptyMessage, null));
        } else {
            stateHost.setVisible(false);
            table.setVisible(true);
        }
    }

    /** @param message user-safe text (never a stack trace or SQL) */
    public void showError(String message) {
        overlay.setLoading(false);
        table.getItems().clear();
        showState(EmptyState.error(message));
    }

    private void showState(javafx.scene.Node node) {
        stateHost.getChildren().setAll(node);
        stateHost.setVisible(true);
        table.setVisible(false);
    }
}
