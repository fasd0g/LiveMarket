package me.livemarket.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Сериализация ItemStack в Base64 и обратно.
 *
 * <p>Нужна, чтобы рынок мог хранить кастомные предметы (имя/лоры/чары/CustomModelData)
 * в config.yml.</p>
 */
public final class ItemStackSerializer {

    private ItemStackSerializer() {}

    public static String toBase64(ItemStack item) {
        if (item == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeObject(item);
            oos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ItemStack", e);
        }
    }

    public static ItemStack fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            Object obj = ois.readObject();
            if (obj instanceof ItemStack is) return is;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
