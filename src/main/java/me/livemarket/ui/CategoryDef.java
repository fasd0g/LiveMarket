package me.livemarket.ui;

import org.bukkit.Material;

public record CategoryDef(
        String key,
        String title,
        Material icon,
        int slot,
        String permission
) {}
