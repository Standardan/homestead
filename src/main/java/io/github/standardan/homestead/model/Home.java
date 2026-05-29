package io.github.standardan.homestead.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable snapshot of a saved home. A Java 'record' gives us a tidy,
 * value-style class: the fields, constructor, getters, equals/hashCode and
 * toString are all generated for us.
 */
public record Home(String name, String world, double x, double y, double z, float yaw, float pitch) {

    /** Build a Home from a player's current Location. */
    public static Home from(String name, Location loc) {
        return new Home(name, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /**
     * Rebuild a Bukkit Location from the stored coordinates, or null if the
     * world no longer exists (e.g. it was deleted since the home was set).
     */
    public @Nullable Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }
}
