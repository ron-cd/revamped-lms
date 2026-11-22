package com.librarymanagement.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves and creates the per-user application data folders.
 * Windows: %LOCALAPPDATA%\LibraryManagementSystem. Other systems fall back to the user's home.
 * The folder can be overridden with -Dlms.data.dir=... (used by tests).
 */
public final class AppPaths {

    private final Path root;

    private AppPaths(Path root) {
        this.root = root;
    }

    public static AppPaths resolve() {
        String override = System.getProperty("lms.data.dir");
        if (override != null && !override.isBlank()) {
            return new AppPaths(Paths.get(override));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Paths.get(localAppData)
                : Paths.get(System.getProperty("user.home"), ".local", "share");
        return new AppPaths(base.resolve("LibraryManagementSystem"));
    }

    public static AppPaths at(Path root) {
        return new AppPaths(root);
    }

    public Path root()      { return root; }
    public Path database()  { return root.resolve("database"); }
    public Path dbFile()    { return database().resolve("library.db"); }
    public Path backups()   { return root.resolve("backups"); }
    public Path reports()   { return root.resolve("reports"); }
    public Path logs()      { return root.resolve("logs"); }
    public Path config()    { return root.resolve("config"); }
    public Path covers()    { return root.resolve("covers"); }

    /** Creates every folder if missing. Never deletes anything. */
    public void ensureDirectories() throws IOException {
        for (Path p : new Path[]{root, database(), backups(), reports(), logs(), config(), covers()}) {
            Files.createDirectories(p);
        }
    }
}
