package com.librarymanagement.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.IntConsumer;

/**
 * Page navigation for tables that load one page at a time (so large datasets are never fully loaded).
 * The caller supplies the total row count; the pager reports the new zero-based page through the callback.
 */
public class Pager extends HBox {

    private final int pageSize;
    private final IntConsumer onPageChange;
    private final Label info = new Label();
    private final Button prev = new Button("Previous");
    private final Button next = new Button("Next");
    private int page = 0;
    private int total = 0;

    public Pager(int pageSize, IntConsumer onPageChange) {
        this.pageSize = pageSize;
        this.onPageChange = onPageChange;
        info.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        prev.setOnAction(e -> go(page - 1));
        next.setOnAction(e -> go(page + 1));

        getStyleClass().add("pager");
        getChildren().addAll(info, spacer, prev, next);
        refresh();
    }

    public int getPage()     { return page; }
    public int getPageSize() { return pageSize; }
    public int getOffset()   { return page * pageSize; }

    /** Call after each load. Resets to the last valid page if the data shrank. */
    public void setTotal(int total) {
        this.total = Math.max(0, total);
        int lastPage = Math.max(0, (this.total - 1) / pageSize);
        if (page > lastPage) page = lastPage;
        refresh();
    }

    /** Go back to the first page (use when search or filters change). */
    public void reset() {
        page = 0;
        refresh();
    }

    private void go(int newPage) {
        page = newPage;
        refresh();
        onPageChange.accept(page);
    }

    private void refresh() {
        int from = total == 0 ? 0 : page * pageSize + 1;
        int to = Math.min(total, (page + 1) * pageSize);
        info.setText("Showing " + from + "-" + to + " of " + total);
        prev.setDisable(page == 0);
        next.setDisable((page + 1) * pageSize >= total);
    }
}
