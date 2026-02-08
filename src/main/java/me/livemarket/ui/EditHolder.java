package me.livemarket.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class EditHolder implements InventoryHolder {
    private final String category; // null = main

    public EditHolder(String category) {
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
