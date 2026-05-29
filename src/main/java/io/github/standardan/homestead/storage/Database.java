package io.github.standardan.homestead.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the SQLite connection and a single background thread that ALL database
 * work runs on.
 *
 * Why a single background thread?
 *  - Minecraft runs game logic on one "main thread". Touching disk/database
 *    there freezes the whole server (TPS drops, players rubber-band).
 *  - Funnelling every query through one dedicated thread keeps DB work OFF the
 *    main thread, and because it's a SINGLE thread we never get concurrent
 *    access to the SQLite connection (SQLite dislikes that).
 */
public final class Database {

    private final Plugin plugin;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Homestead-DB");
        t.setDaemon(true); // don't keep the JVM alive on shutdown
        return t;
    });
    private Connection connection;

    public Database(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Opens the connection and creates the schema. Runs once, at startup. */
    public void connect() throws SQLException, ClassNotFoundException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new SQLException("Could not create plugin data folder");
        }
        // Paper loads the driver in an isolated classloader, so we register it
        // explicitly rather than relying on JDBC auto-discovery.
        Class.forName("org.sqlite.JDBC");

        File dbFile = new File(plugin.getDataFolder(), "homes.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS homes (
                        owner TEXT   NOT NULL,
                        name  TEXT   NOT NULL,
                        world TEXT   NOT NULL,
                        x     REAL   NOT NULL,
                        y     REAL   NOT NULL,
                        z     REAL   NOT NULL,
                        yaw   REAL   NOT NULL,
                        pitch REAL   NOT NULL,
                        PRIMARY KEY (owner, name)
                    )
                    """);
        }
    }

    Connection connection() {
        return connection;
    }

    /**
     * Runs a piece of database work on the DB thread and hands back a
     * CompletableFuture that completes (on that thread) when it's done.
     */
    <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(work.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Cleanly stops the DB thread and closes the connection at shutdown. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error closing database: " + e.getMessage());
        }
    }

    /** Like Supplier, but allowed to throw checked SQL exceptions. */
    @FunctionalInterface
    interface SqlSupplier<T> {
        T get() throws Exception;
    }
}
