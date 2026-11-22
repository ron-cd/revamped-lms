package com.librarymanagement.navigation;

import com.librarymanagement.security.Session;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

import java.time.Clock;

/**
 * Signs the user out after a period without keyboard or mouse activity.
 * Activity refreshes the session; a timer checks every few seconds whether it has expired.
 */
public final class SessionWatcher {

    private final Scene scene;
    private final Session session;
    private final Clock clock;
    private final Runnable onExpired;
    private final EventHandler<InputEvent> activity;
    private final Timeline ticker;
    private boolean running;

    public SessionWatcher(Scene scene, Session session, Clock clock, Runnable onExpired) {
        this.scene = scene;
        this.session = session;
        this.clock = clock;
        this.onExpired = onExpired;
        this.activity = e -> this.session.touch(this.clock.instant());
        this.ticker = new Timeline(new KeyFrame(Duration.seconds(5), e -> check()));
        this.ticker.setCycleCount(Timeline.INDEFINITE);
    }

    public void start() {
        if (running) return;
        running = true;
        session.touch(clock.instant());
        scene.addEventFilter(MouseEvent.ANY, activity);
        scene.addEventFilter(KeyEvent.ANY, activity);
        scene.addEventFilter(ScrollEvent.ANY, activity);
        ticker.play();
    }

    public void stop() {
        if (!running) return;
        running = false;
        ticker.stop();
        scene.removeEventFilter(MouseEvent.ANY, activity);
        scene.removeEventFilter(KeyEvent.ANY, activity);
        scene.removeEventFilter(ScrollEvent.ANY, activity);
    }

    private void check() {
        if (running && session.isExpired(clock.instant())) {
            stop();
            onExpired.run();
        }
    }
}
