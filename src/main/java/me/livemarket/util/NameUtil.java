package me.livemarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;

import java.util.Locale;

public final class NameUtil {
    private static volatile java.util.function.Function<Material, String> overrideProvider;

    public static void setOverrideProvider(java.util.function.Function<Material, String> provider) {
        overrideProvider = provider;
    }

    private static final Locale RU = Locale.forLanguageTag("ru-ru");

    private NameUtil() {}

    public static String ru(Material mat) {
        try {
            var p = overrideProvider;
            if (p != null) {
                String o = p.apply(mat);
                if (o != null && !o.isBlank()) return o;
            }
        } catch (Throwable ignored) {}

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
