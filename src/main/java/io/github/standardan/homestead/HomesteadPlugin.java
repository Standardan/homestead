package io.github.standardan.homestead;

import io.github.standardan.homestead.command.HomeCommand;
import io.github.standardan.homestead.storage.Database;
import io.github.standardan.homestead.storage.HomeRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;

public final class HomesteadPlugin extends JavaPlugin {

    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        database = new Database(this);
        try {
            database.connect();
        } catch (Exception e) {
            // If the database can't open, the plugin can't work - fail loudly
            // and disable ourselves rather than limp along half-broken.
            getLogger().severe("Failed to initialise database, disabling plugin: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HomeRepository homes = new HomeRepository(database);
        HomeCommand handler = new HomeCommand(this, homes);

        // One handler object serves all four commands.
        for (String name : List.of("home", "sethome", "delhome", "homes")) {
            PluginCommand command = Objects.requireNonNull(
                    getCommand(name), name + " is missing from plugin.yml");
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(handler, this);

        getLogger().info("Homestead enabled.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    /**
     * Run a task on the main server thread. Database callbacks finish on a
     * background thread, but anything that touches players or the world must
     * hop back here first. Guarded so we don't schedule during shutdown.
     */
    public void sync(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }
}
