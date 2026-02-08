package me.livemarket;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class GenerateCommand implements CommandExecutor {

    private final JavaPlugin plugin;

    public GenerateCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("livemarket.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }

        int perCat = 12;
        boolean all = false;
        if (args.length >= 1) {
            try {
                if (args[0].equalsIgnoreCase("all") || args[0].equalsIgnoreCase("*")) {
                    all = true;
                } else {
                    perCat = Math.max(1, Math.min(400, Integer.parseInt(args[0])));
                }
            } catch (Exception ignored) {}
        }

        Map<String, List<Material>> byCat = new LinkedHashMap<>();
        byCat.put("armor", new ArrayList<>());
        byCat.put("food", new ArrayList<>());
        byCat.put("tools", new ArrayList<>());
        byCat.put("ores", new ArrayList<>());
        byCat.put("blocks", new ArrayList<>());
        byCat.put("misc", new ArrayList<>());

        for (Material m : Material.values()) {
            if (m.isAir()) continue;
            if (!m.isItem()) continue;
            if (m.isLegacy()) continue;

            String n = m.name();
            if (n.contains("SPAWN_EGG")) continue;
            if (n.contains("COMMAND_BLOCK")) continue;
            if (n.contains("BARRIER")) continue;

            String cat = categorize(m);
            byCat.get(cat).add(m);
        }

        // стабильный порядок
        byCat.replaceAll((k, v) -> v.stream()
                .sorted(Comparator.comparing(Material::name))
                .collect(Collectors.toList()));

        int[] slots = buildSlots54();

        YamlConfiguration out = new YamlConfiguration();
        out.set("generatedAt", new Date().toString());
        out.set("note", "Сгенерировано командой /lmgen. Перенеси блок items: в config.yml (или возьми нужные элементы).");

        for (String cat : byCat.keySet()) {
            List<Material> mats = byCat.get(cat);
            int take = all ? mats.size() : Math.min(perCat, mats.size());
            int slotIdx = 0;

            for (int i = 0; i < take; i++) {
                Material m = mats.get(i);
                int slot = slots[Math.min(slotIdx, slots.length - 1)];
                slotIdx++;

                PriceProfile p = priceProfile(cat, m);

                String path = "items." + m.name();
                out.set(path + ".category", cat);
                out.set(path + ".base", p.base);
                out.set(path + ".min", p.min);
                out.set(path + ".max", p.max);
                out.set(path + ".stock", p.stock);
                out.set(path + ".stockTarget", p.stockTarget);
                out.set(path + ".slot", slot);
            }
        }

        try {
            File f = new File(plugin.getDataFolder(), "generated-items.yml");
            out.save(f);
            sender.sendMessage("§aГотово! Создан файл: §fplugins/LiveMarket/generated-items.yml");
            sender.sendMessage("§7Параметр: /lmgen [countPerCategory|all], сейчас: §f" + (all ? "all" : perCat));
        } catch (Exception e) {
            sender.sendMessage("§cОшибка сохранения: " + e.getMessage());
        }
        return true;
    }

    private static String categorize(Material m) {
        String n = m.name();

        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("ELYTRA") || n.equals("TURTLE_HELMET")) return "armor";

        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD") || n.equals("TRIDENT") || n.equals("FISHING_ROD")) return "tools";

        try { if (m.isEdible()) return "food"; } catch (Throwable ignored) {}

        if (n.endsWith("_INGOT") || n.endsWith("_ORE") || n.equals("COAL") || n.equals("CHARCOAL") || n.equals("DIAMOND")
                || n.equals("EMERALD") || n.equals("REDSTONE") || n.equals("LAPIS_LAZULI") || n.equals("QUARTZ")
                || n.equals("AMETHYST_SHARD") || n.equals("NETHERITE_SCRAP") || n.equals("ANCIENT_DEBRIS")) return "ores";

        if (m.isBlock()) return "blocks";

        return "misc";
    }

    private static int[] buildSlots54() {
        List<Integer> s = new ArrayList<>();
        int[][] ranges = new int[][]{{10,16},{19,25},{28,34},{37,43},{46,52}};
        for (int[] r : ranges) {
            for (int i=r[0]; i<=r[1]; i++) {
                if (i==49 || i==53) continue;
                s.add(i);
            }
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static class PriceProfile {
        double base, min, max;
        int stock, stockTarget;
    }

    private static PriceProfile priceProfile(String cat, Material m) {
        String n = m.name();
        PriceProfile p = new PriceProfile();

        switch (cat) {
            case "blocks" -> {
                p.base = tier(n, 1.6, 2.0, 3.5, 6.0, 12.0);
                p.min = Math.max(0.4, p.base * 0.35);
                p.max = p.base * 4.0;
                p.stock = 256;
                p.stockTarget = 2000;
            }
            case "food" -> {
                p.base = tier(n, 4.0, 6.0, 10.0, 22.0, 120.0);
                if (n.equals("GOLDEN_APPLE")) p.base = 180.0;
                p.min = Math.max(1.0, p.base * 0.45);
                p.max = p.base * 3.6;
                p.stock = 96;
                p.stockTarget = 450;
            }
            case "ores" -> {
                p.base = tier(n, 6.0, 10.0, 25.0, 60.0, 120.0);
                if (n.equals("DIAMOND")) p.base = 120.0;
                if (n.equals("EMERALD")) p.base = 140.0;
                if (n.equals("NETHERITE_SCRAP")) p.base = 600.0;
                if (n.equals("ANCIENT_DEBRIS")) p.base = 900.0;
                p.min = Math.max(2.0, p.base * 0.45);
                p.max = p.base * 4.0;
                p.stock = (n.contains("NETHERITE") || n.contains("ANCIENT")) ? 2 : 64;
                p.stockTarget = (n.contains("NETHERITE") || n.contains("ANCIENT")) ? 8 : 320;
            }
            case "tools" -> {
                p.base = tier(n, 45.0, 85.0, 140.0, 320.0, 520.0);
                if (n.contains("DIAMOND")) p.base = 520.0;
                if (n.contains("NETHERITE")) p.base = 1200.0;
                p.min = Math.max(15.0, p.base * 0.45);
                p.max = p.base * 3.2;
                p.stock = (n.contains("DIAMOND") || n.contains("NETHERITE")) ? 3 : 10;
                p.stockTarget = (n.contains("DIAMOND") || n.contains("NETHERITE")) ? 12 : 40;
            }
            case "armor" -> {
                p.base = tier(n, 110.0, 200.0, 420.0, 900.0, 1800.0);
                if (n.contains("DIAMOND")) p.base = 900.0;
                if (n.contains("NETHERITE")) p.base = 2500.0;
                if (n.equals("ELYTRA")) p.base = 3500.0;
                p.min = Math.max(40.0, p.base * 0.48);
                p.max = p.base * 3.0;
                p.stock = (n.contains("DIAMOND") || n.contains("NETHERITE") || n.equals("ELYTRA")) ? 1 : 6;
                p.stockTarget = (n.contains("DIAMOND") || n.contains("NETHERITE") || n.equals("ELYTRA")) ? 3 : 22;
            }
            default -> {
                p.base = tier(n, 3.0, 6.0, 12.0, 28.0, 60.0);
                if (n.equals("ENDER_PEARL")) p.base = 60.0;
                if (n.equals("BLAZE_ROD")) p.base = 45.0;
                p.min = Math.max(0.9, p.base * 0.4);
                p.max = p.base * 4.0;
                p.stock = 96;
                p.stockTarget = 500;
            }
        }

        p.max = Math.max(p.max, p.base);
        p.min = Math.min(p.min, p.base);

        p.base = round2(p.base);
        p.min = round2(p.min);
        p.max = round2(p.max);
        return p;
    }

    private static double tier(String name, double t1, double t2, double t3, double t4, double t5) {
        if (name.contains("NETHERITE")) return t5;
        if (name.contains("DIAMOND") || name.contains("EMERALD")) return t5;
        if (name.contains("GOLD")) return t4;
        if (name.contains("IRON")) return t3;
        if (name.contains("STONE") || name.contains("COBBLE") || name.contains("DEEPSLATE")) return t2;
        if (name.contains("OAK") || name.contains("SPRUCE") || name.contains("BIRCH") || name.contains("LOG") || name.contains("PLANK")) return t1;
        return t2;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
