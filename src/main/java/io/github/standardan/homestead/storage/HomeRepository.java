package io.github.standardan.homestead.storage;

import io.github.standardan.homestead.model.Home;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * All the home-related queries live here (the "repository" / DAO pattern).
 * Every method returns a CompletableFuture and runs on the Database's
 * background thread, so callers never block the main server thread.
 *
 * Every query uses PreparedStatement with '?' placeholders - never string
 * concatenation - which is how you stay safe from SQL injection.
 */
public final class HomeRepository {

    private final Database db;

    public HomeRepository(Database db) {
        this.db = db;
    }

    /** Insert a new home, or overwrite the existing one with the same name. */
    public CompletableFuture<Void> save(UUID owner, Home home) {
        return db.supplyAsync(() -> {
            String sql = "INSERT INTO homes(owner, name, world, x, y, z, yaw, pitch) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(owner, name) DO UPDATE SET "
                    + "world = excluded.world, x = excluded.x, y = excluded.y, "
                    + "z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                ps.setString(2, home.name());
                ps.setString(3, home.world());
                ps.setDouble(4, home.x());
                ps.setDouble(5, home.y());
                ps.setDouble(6, home.z());
                ps.setFloat(7, home.yaw());
                ps.setFloat(8, home.pitch());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Look up one home by name (case-insensitive). */
    public CompletableFuture<Optional<Home>> find(UUID owner, String name) {
        return db.supplyAsync(() -> {
            String sql = "SELECT name, world, x, y, z, yaw, pitch FROM homes "
                    + "WHERE owner = ? AND name = ? COLLATE NOCASE";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Home>empty();
                }
            }
        });
    }

    /** All of a player's homes, alphabetically. */
    public CompletableFuture<List<Home>> findAll(UUID owner) {
        return db.supplyAsync(() -> {
            List<Home> homes = new ArrayList<>();
            String sql = "SELECT name, world, x, y, z, yaw, pitch FROM homes "
                    + "WHERE owner = ? ORDER BY name COLLATE NOCASE";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        homes.add(read(rs));
                    }
                }
            }
            return homes;
        });
    }

    /** Delete a home; the boolean tells the caller whether a row was removed. */
    public CompletableFuture<Boolean> delete(UUID owner, String name) {
        return db.supplyAsync(() -> {
            String sql = "DELETE FROM homes WHERE owner = ? AND name = ? COLLATE NOCASE";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** How many homes a player currently has (for limit checks). */
    public CompletableFuture<Integer> count(UUID owner) {
        return db.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM homes WHERE owner = ?";
            try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /** Map one result-set row into a Home object. */
    private Home read(ResultSet rs) throws SQLException {
        return new Home(
                rs.getString("name"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"));
    }
}
