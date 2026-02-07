package me.livemarket.db;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;

public class MarketDatabase {

    public static class StoredItem {
        public final double price;
        public final int stock;
        public StoredItem(double price, int stock) { this.price = price; this.stock = stock; }
    }

    private final JavaPlugin plugin;
    private Connection conn;

    public MarketDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            File data = plugin.getDataFolder();
            if (!data.exists()) data.mkdirs();
            File dbFile = new File(data, "market.db");

            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS market_items (
                        material TEXT PRIMARY KEY,
                        price REAL NOT NULL,
                        stock INTEGER NOT NULL
                    )
                """);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("DB init failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public StoredItem loadItem(String material) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT price, stock FROM market_items WHERE material = ?")) {
            ps.setString(1, material);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new StoredItem(rs.getDouble("price"), rs.getInt("stock"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB loadItem error: " + e.getMessage());
            return null;
        }
    }

    public void deleteItem(String material) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM market_items WHERE material = ?")) {
            ps.setString(1, material);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB deleteItem error: " + e.getMessage());
        }
    }

    public void upsertItem(String material, double price, int stock) {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO market_items(material, price, stock)
            VALUES(?, ?, ?)
            ON CONFLICT(material) DO UPDATE SET price=excluded.price, stock=excluded.stock
        """)) {
            ps.setString(1, material);
            ps.setDouble(2, price);
            ps.setInt(3, stock);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB upsertItem error: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {}
    }
}
