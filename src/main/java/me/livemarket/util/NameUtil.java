package me.livemarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public final class NameUtil {
    private static final Locale RU = Locale.forLanguageTag("ru-ru");

    private NameUtil() {}

private static final Map<String, String> OVERRIDES = new HashMap<>();

public static void init(JavaPlugin plugin) {
    try {
        File f = new File(plugin.getDataFolder(), "names.yml");
        if (!f.exists()) {
            plugin.saveResource("names.yml", false);
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        OVERRIDES.clear();
        for (String k : y.getKeys(false)) {
            String v = y.getString(k);
            if (v != null && !v.isBlank()) OVERRIDES.put(k.toUpperCase(Locale.ROOT), v);
        }
    } catch (Throwable t) {
        // ignore
    }
}

    public static String ru(Material mat) {
        String o = OVERRIDES.get(mat.name());
        if (o != null) return o;
        try {
            String key = mat.translationKey(); // item.minecraft.* or block.minecraft.*
            Component rendered = GlobalTranslator.render(Component.translatable(key), RU);
            String s = PlainTextComponentSerializer.plainText().serialize(rendered);
            if (s != null && !s.isBlank()) return s;
        } catch (Throwable ignored) {}
        return prettify(mat.name());
    }

    private static String prettify(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
                cap = false;
            } else {
                out.append(c);
            }
            if (c == ' ') cap = true;
        }
        return out.toString();
    }
}
