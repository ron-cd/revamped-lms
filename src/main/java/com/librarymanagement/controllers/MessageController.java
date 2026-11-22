package com.librarymanagement.controllers;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** Simple status page shown after setup (the login screen replaces it in Phase 4). */
public class MessageController {
    @FXML private Label titleLabel;
    @FXML private Label bodyLabel;

    public void init(String title, String body) {
        titleLabel.setText(title);
        bodyLabel.setText(body);
    }
}
