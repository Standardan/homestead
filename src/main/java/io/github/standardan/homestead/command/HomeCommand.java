package io.github.standardan.homestead.command;

import io.github.standardan.homestead.HomesteadPlugin;
import io.github.standardan.homestead.model.Home;
import io.github.standardan.homestead.storage.HomeRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all four home commands. One class is registered as the executor for
 * /home, /sethome, /delhome and /homes, and we dispatch on the command name.
 *
 * It also implements Listener so it can keep a per-player cache of home names,
 * which powers instant tab-completion without touching the database on each
 * keystroke.
 */
public final class HomeCommand implements CommandExecutor, TabCompleter, Listener {

    private static final String PREFIX = "<dark_aqua>[Homestead]</dark_aqua> ";
    private static final int MAX_NAME_LENGTH = 32;

    private final HomesteadPlugin plugin;
    private final HomeRepository homes;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // Per-player cache of home names, for tab-completion. Thread-safe because
    // it's written from the DB thread (on join) and read from the main thread.
    private final Map<UUID, Set<String>> nameCache = new ConcurrentHashMap<>();
    // When each player may next teleport (epoch millis).
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    public HomeCommand(HomesteadPlugin plugin, HomeRepository homes) {
        this.plugin = plugin;
        this.homes = homes;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Homestead commands can only be used by players.");
            return true;
        }
        if (!player.hasPermission("homestead.use")) {
            message(player, "<red>You don't have permission to use homes.");
            return true;
        }

        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> setHome(player, args.length > 0 ? args[0] : "home");
            case "home" -> goHome(player, args.length > 0 ? args[0] : "home");
            case "delhome" -> {
                if (args.length == 0) {
                    message(player, "<red>Usage: /delhome <name>");
                } else {
                    deleteHome(player, args[0]);
                }
            }
            case "homes" -> listHomes(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setHome(Player player, String name) {
        if (!isValidName(name)) {
            message(player, "<red>Home names use letters, numbers, _ and - (max " + MAX_NAME_LENGTH + ").");
            return;
        }
        UUID id = player.getUniqueId();
        Home home = Home.from(name, player.getLocation());

        // First find out if this name already exists (overwrites don't count
        // against the limit), then count, then save - each step async, results
        // applied back on the main thread.
        homes.find(id, name).thenAccept(existing -> homes.count(id).thenAccept(count ->
                plugin.sync(() -> {
                    int limit = homeLimit(player);
                    boolean overwrite = existing.isPresent();
                    if (!overwrite && count >= limit) {
                        message(player, "<red>You've reached your home limit (" + limit + "). "
                                + "Delete one with <yellow>/delhome <name></yellow>.");
                        return;
                    }
                    homes.save(id, home).thenRun(() -> plugin.sync(() -> {
                        nameCache.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(name);
                        message(player, "<green>Home <white>" + name + "</white> "
                                + (overwrite ? "updated." : "set."));
                    }));
                })));
    }

    private void goHome(Player player, String name) {
        UUID id = player.getUniqueId();

        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(id, 0L);
        if (!player.hasPermission("homestead.cooldown.bypass") && now < until) {
            long secs = (until - now + 999) / 1000;
            message(player, "<red>Please wait " + secs + "s before teleporting again.");
            return;
        }

        homes.find(id, name).thenAccept(found -> plugin.sync(() -> {
            if (found.isEmpty()) {
                message(player, "<red>You have no home named <white>" + name + "</white>.");
                return;
            }
            Location loc = found.get().toLocation();
            if (loc == null) {
                message(player, "<red>That home's world is no longer loaded.");
                return;
            }
            int cooldown = plugin.getConfig().getInt("teleport-cooldown-seconds", 3);
            if (cooldown > 0 && !player.hasPermission("homestead.cooldown.bypass")) {
                cooldownUntil.put(id, System.currentTimeMillis() + cooldown * 1000L);
            }
            // teleportAsync loads the destination chunk off the main thread, then
            // teleports - smoother than a plain teleport into ungenerated terrain.
            player.teleportAsync(loc).thenAccept(ok -> message(player,
                    ok ? "<green>Teleported to <white>" + name + "</white>."
                       : "<red>Teleport failed - try again."));
        }));
    }

    private void deleteHome(Player player, String name) {
        UUID id = player.getUniqueId();
        homes.delete(id, name).thenAccept(removed -> plugin.sync(() -> {
            if (removed) {
                Set<String> cached = nameCache.get(id);
                if (cached != null) {
                    cached.removeIf(n -> n.equalsIgnoreCase(name));
                }
                message(player, "<green>Deleted home <white>" + name + "</white>.");
            } else {
                message(player, "<red>You have no home named <white>" + name + "</white>.");
            }
        }));
    }

    private void listHomes(Player player) {
        UUID id = player.getUniqueId();
        homes.findAll(id).thenAccept(list -> plugin.sync(() -> {
            if (list.isEmpty()) {
                message(player, "<gray>You haven't set any homes yet. Try <yellow>/sethome</yellow>.");
                return;
            }
            Component line = mm.deserialize(PREFIX + "<gray>Your homes (" + list.size() + "): ");
            boolean first = true;
            for (Home home : list) {
                if (!first) {
                    line = line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
                }
                line = line.append(Component.text(home.name(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/home " + home.name()))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to teleport", NamedTextColor.GRAY))));
                first = false;
            }
            player.sendMessage(line);
        }));
    }

    // --- tab completion -----------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return Collections.emptyList();
        }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!cmd.equals("home") && !cmd.equals("delhome")) {
            return Collections.emptyList();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return nameCache.getOrDefault(player.getUniqueId(), Set.of()).stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    // --- cache lifecycle ----------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        homes.findAll(id).thenAccept(list -> {
            Set<String> names = ConcurrentHashMap.newKeySet();
            for (Home home : list) {
                names.add(home.name());
            }
            nameCache.put(id, names);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        nameCache.remove(id);
        cooldownUntil.remove(id);
    }

    // --- helpers ------------------------------------------------------------

    /** A player's home limit: highest homestead.homes.<n> they have, else config default. */
    private int homeLimit(Player player) {
        if (player.hasPermission("homestead.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int limit = plugin.getConfig().getInt("default-home-limit", 3);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (perm.startsWith("homestead.homes.")) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring("homestead.homes.".length())));
                } catch (NumberFormatException ignored) {
                    // e.g. "homestead.homes.unlimited" - not a number, skip.
                }
            }
        }
        return limit;
    }

    private boolean isValidName(String name) {
        return name.length() <= MAX_NAME_LENGTH && name.matches("[A-Za-z0-9_-]+");
    }

    private void message(CommandSender to, String miniMessage) {
        to.sendMessage(mm.deserialize(PREFIX + miniMessage));
    }
}
