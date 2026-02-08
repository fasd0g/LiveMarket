package me.livemarket.util;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

public final class NameOverrides {

    private final JavaPlugin plugin;
    private final Map<Material, String> map = new EnumMap<>(Material.class);

    public NameOverrides(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        map.clear();
        try {
            File file = new File(plugin.getDataFolder(), "names.yml");
            if (!file.exists()) {
                plugin.saveResource("names.yml", false);
            }
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            for (String key : yml.getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key.toUpperCase());
                    String name = yml.getString(key);
                    if (name != null && !name.isBlank()) {
                        map.put(mat, name);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("names.yml: unknown material '" + key + "'");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to load names.yml: " + t.getMessage());
        }
    }

    public String get(Material mat) {
        return map.get(mat);
    }
}
