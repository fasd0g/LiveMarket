package me.livemarket.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MarketHolder implements InventoryHolder {
    private final String category; // null = main

    public MarketHolder(String category) {
        this.category = category;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
