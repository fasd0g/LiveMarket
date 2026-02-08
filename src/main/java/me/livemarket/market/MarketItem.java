package me.livemarket.market;

import org.bukkit.Material;

public class MarketItem {
    private final Material material;
    private final String category;
    private final double basePrice;
    private final double minPrice;
    private final double maxPrice;
    private final int slot;
    private final int stockTarget;

    private double price;
    private int stock;

    private double buyEma;
    private double sellEma;

    private int buysSinceUpdate;
    private int sellsSinceUpdate;

    private double lastSnapshotPrice;

    public MarketItem(Material material, String category, double basePrice, double minPrice, double maxPrice,
                      double price, int stock, int stockTarget, int slot) {
        this.material = material;
        this.category = category;
        this.basePrice = basePrice;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.price = price;
        this.stock = stock;
        this.stockTarget = stockTarget;
        this.slot = slot;
        this.lastSnapshotPrice = price;
    }

    public Material getMaterial() { return material; }
    public String getCategory() { return category; }
    public double getBasePrice() { return basePrice; }
    public double getMinPrice() { return minPrice; }
    public double getMaxPrice() { return maxPrice; }
    public int getSlot() { return slot; }
    public int getStockTarget() { return stockTarget; }

    public synchronized double getPrice() { return price; }
    public synchronized void setPrice(double price) { this.price = price; }

    public synchronized int getStock() { return stock; }
    public synchronized void setStock(int stock) { this.stock = Math.max(0, stock); }

    public synchronized void addBuyVolume(int qty) {
        double alpha = 0.12;
        buyEma = buyEma * (1 - alpha) + qty * alpha;
        buysSinceUpdate += qty;
    }

    public synchronized void addSellVolume(int qty) {
        double alpha = 0.12;
        sellEma = sellEma * (1 - alpha) + qty * alpha;
        sellsSinceUpdate += qty;
    }

    public synchronized void decayEma(double factor) {
        double keep = Math.max(0.0, Math.min(1.0, 1.0 - factor));
        buyEma *= keep;
        sellEma *= keep;
    }

public synchronized int getBuysSinceUpdate() { return buysSinceUpdate; }
public synchronized int getSellsSinceUpdate() { return sellsSinceUpdate; }

public synchronized void resetTradeCounters() {
    buysSinceUpdate = 0;
    sellsSinceUpdate = 0;
}

    public synchronized double getBuyEma() { return buyEma; }
    public synchronized double getSellEma() { return sellEma; }

    public synchronized void snapshotTrend() {
        lastSnapshotPrice = price;
    }

    public synchronized double trendPercent() {
        if (lastSnapshotPrice <= 0) return 0;
        return ((price - lastSnapshotPrice) / lastSnapshotPrice) * 100.0;
    }

    public String getTrendArrow() {
        double t = trendPercent();
        if (t > 0.25) return "§a↑";
        if (t < -0.25) return "§c↓";
        return "§e→";
    }

    public String getTrendPercentString() {
        double t = trendPercent();
        String sign = t >= 0 ? "+" : "";
        return "§f" + sign + String.format("%.1f%%", t);
    }
}
