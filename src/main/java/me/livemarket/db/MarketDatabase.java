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

    public void deleteItem(String material) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM market_items WHERE material = ?")) {
            ps.setString(1, material);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("DB deleteItem error: " + e.getMessage());
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

// ===== daily sell limits =====
public int getDailySold(String uuid) {
    try (PreparedStatement ps = conn.prepareStatement("SELECT sold FROM daily_sell_limits WHERE uuid = ?")) {
        ps.setString(1, uuid);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        }
    } catch (SQLException e) {
        plugin.getLogger().warning("DB getDailySold error: " + e.getMessage());
    }
    return 0;
}

public void setDailySold(String uuid, int sold) {
    try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO daily_sell_limits(uuid, sold) VALUES(?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET sold = excluded.sold")) {
        ps.setString(1, uuid);
        ps.setInt(2, sold);
        ps.executeUpdate();
    } catch (SQLException e) {
        plugin.getLogger().warning("DB setDailySold error: " + e.getMessage());
    }
}

public void clearDailySold() {
    try (Statement st = conn.createStatement()) {
        st.executeUpdate("DELETE FROM daily_sell_limits");
    } catch (SQLException e) {
        plugin.getLogger().warning("DB clearDailySold error: " + e.getMessage());
    }
}

// ===== daily deals =====
public java.util.Map<String, Double> loadDailyDeals() {
    java.util.Map<String, Double> out = new java.util.HashMap<>();
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT material, bonus FROM daily_deals")) {
        while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
    } catch (SQLException e) {
        plugin.getLogger().warning("DB loadDailyDeals error: " + e.getMessage());
    }
    return out;
}

public void saveDailyDeals(java.util.Map<String, Double> deals) {
    clearDailyDeals();
    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO daily_deals(material, bonus) VALUES(?, ?)")) {
        for (var en : deals.entrySet()) {
            ps.setString(1, en.getKey());
            ps.setDouble(2, en.getValue());
            ps.addBatch();
        }
        ps.executeBatch();
    } catch (SQLException e) {
        plugin.getLogger().warning("DB saveDailyDeals error: " + e.getMessage());
    }
}

public void clearDailyDeals() {
    try (Statement st = conn.createStatement()) {
        st.executeUpdate("DELETE FROM daily_deals");
    } catch (SQLException e) {
        plugin.getLogger().warning("DB clearDailyDeals error: " + e.getMessage());
    }
}

}