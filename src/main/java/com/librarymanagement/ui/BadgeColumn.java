package com.librarymanagement.ui;

import com.librarymanagement.ui.StatusBadge.Kind;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;

import java.util.function.Function;

/** A table column that shows each row's status as a coloured badge. */
public final class BadgeColumn {

    private BadgeColumn() {}

    public static <T> TableColumn<T, String> of(String title, Function<T, String> label, Function<T, Kind> kind) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(label.apply(cell.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                T row = (empty || getTableRow() == null) ? null : getTableRow().getItem();
                if (row == null || item == null || item.isEmpty()) {
                    setGraphic(null);
                } else {
                    setGraphic(StatusBadge.of(item, kind.apply(row)));
                }
            }
        });
        return column;
    }
}
