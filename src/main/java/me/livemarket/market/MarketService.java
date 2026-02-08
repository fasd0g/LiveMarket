package me.livemarket.market;

import me.livemarket.db.MarketDatabase;
import me.livemarket.ui.CategoryDef;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;

public class MarketService {
    private final java.util.Map<org.bukkit.Material, Double> dailyDealBonus = new java.util.HashMap<>();
    private double sellMultiplier;
    private int dailySellLimitGlobal;


    private final JavaPlugin plugin;
    private final Economy economy;
    private final MarketDatabase db;

    private final Map<Material, MarketItem> items = new ConcurrentHashMap<>();
    private final Map<String, CategoryDef> categories = new LinkedHashMap<>();

    private BukkitTask dailyTask;

    private final Map<UUID, Long> clickCooldown = new ConcurrentHashMap<>();

    private double maxStep;
    private double kDemand;
    private double sStock;
    private long cooldownMs;

    private boolean sellPressureEnabled;
    private double sellPressureK;
    private double sellPressureMinMultiplier;

    private boolean instantAdjustEnabled;
    private double instantMaxStepPerTrade;

    private ZoneId zoneId;
    private int hour;
    private int minute;

    public MarketService(JavaPlugin plugin, Economy economy, MarketDatabase db) {
        sellMultiplier = plugin.getConfig().getDouble("settings.economy.sellMultiplier", 0.65);
        dailySellLimitGlobal = plugin.getConfig().getInt("settings.economy.dailySellLimitGlobal", 384);

        this.plugin = plugin;
        this.economy = economy;
        this.db = db;
    }

    public void loadFromConfigAndDb() {
        var cfg = plugin.getConfig();

        maxStep = cfg.getDouble("settings.update.maxStepPerUpdate", 0.08);
        kDemand = cfg.getDouble("settings.update.kDemand", 0.45);
        sStock = cfg.getDouble("settings.update.sStock", 0.25);
        sellMultiplier = cfg.getDouble("settings.economy.sellMultiplier", cfg.getDouble("settings.update.sellMultiplier", 0.65));cooldownMs = cfg.getLong("settings.update.clickCooldownMs", 250);

        sellPressureEnabled = cfg.getBoolean("settings.update.sellPressure.enabled", true);
        sellPressureK = cfg.getDouble("settings.update.sellPressure.k", 0.18);
        sellPressureMinMultiplier = cfg.getDouble("settings.update.sellPressure.minMultiplier", 0.55);

        instantAdjustEnabled = cfg.getBoolean("settings.update.instantAdjustment.enabled", true);
        instantMaxStepPerTrade = cfg.getDouble("settings.update.instantAdjustment.maxStepPerTrade", 0.01);

        String zone = cfg.getString("settings.updateTime.zoneId", "Europe/Moscow");
        zoneId = ZoneId.of(zone);
        hour = cfg.getInt("settings.updateTime.hour", 22);
        minute = cfg.getInt("settings.updateTime.minute", 0);

        // категории
        categories.clear();
        ConfigurationSection csec = cfg.getConfigurationSection("categories");
        if (csec != null) {
            for (String key : csec.getKeys(false)) {
                ConfigurationSection s = csec.getConfigurationSection(key);
                if (s == null) continue;
                String title = s.getString("title", key);
                String iconName = s.getString("icon", "CHEST");
                int slot = s.getInt("slot", 0);
                String perm = s.getString("permission", "livemarket.category." + key);

                Material icon;
                try { icon = Material.valueOf(iconName.toUpperCase()); }
                catch (Exception e) { icon = Material.CHEST; }

                categories.put(key.toLowerCase(), new CategoryDef(key.toLowerCase(), title, icon, slot, perm));
            }
        }

        // товары
        items.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("items");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            Material mat;
            try { mat = Material.valueOf(key.toUpperCase()); }
            catch (Exception ignored) {
                plugin.getLogger().warning("Unknown material in config: " + key);
                continue;
            }

            var s = sec.getConfigurationSection(key);
            if (s == null) continue;

            String category = s.getString("category", "misc").toLowerCase();
            double base = s.getDouble("base");
            double min = s.getDouble("min");
            double max = s.getDouble("max");
            int stock = s.getInt("stock", 0);
            int stockTarget = s.getInt("stockTarget", 0);
            int slot = s.getInt("slot", 0);

            MarketDatabase.StoredItem stored = db.loadItem(mat.name());
            double price = stored != null ? stored.price : base;
            int stockDb = stored != null ? stored.stock : stock;

            MarketItem item = new MarketItem(mat, category, base, min, max, price, stockDb, stockTarget, slot);
            items.put(mat, item);

            db.upsertItem(mat.name(), item.getPrice(), item.getStock());
        }
    }

    public Map<String, CategoryDef> getCategories() {
        return categories;
    }

    public MarketItem getItem(Material mat) {
        return items.get(mat);
    }

    public List<MarketItem> getByCategory(String categoryKey) {
        String c = categoryKey.toLowerCase();
        List<MarketItem> out = new ArrayList<>();
        for (MarketItem it : items.values()) {
            if (it.getCategory().equalsIgnoreCase(c)) out.add(it);
        }
        return out;
    }

    public String format(double v) {
        return String.format("$%.2f", v);
    }

    /** Локализованное (РУССКОЕ) название предмета для сообщений. */
    private String localizedMaterialName(Player p, Material mat) {
        try {
            java.util.Locale ru = java.util.Locale.forLanguageTag("ru-RU");
            Component c = Component.translatable(mat.translationKey());
            Component rendered = GlobalTranslator.render(c, ru);
            String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
            if (plain != null && !plain.isBlank()) return plain;
        } catch (Throwable ignored) {}
        return mat.name();
    }

    public boolean isCooldownOk(Player p) {
        long now = System.currentTimeMillis();
        long last = clickCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cooldownMs) return false;
        clickCooldown.put(p.getUniqueId(), now);
        return true;
    }

    // ===== таймер до следующего обновления =====

    public String getTimeUntilNextUpdateString() {
        long seconds = secondsUntilNextUpdate();
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public long secondsUntilNextUpdate() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        Duration d = Duration.between(now.toInstant(), next.toInstant());
        return Math.max(0, d.getSeconds());
    }

    // ===== ежедневное обновление рынка =====

    public void scheduleDailyUpdate() {
        long delayTicks = ticksUntilNextUpdate();
        long dayTicks = 24L * 60L * 60L * 20L;

        if (dailyTask != null) dailyTask.cancel();

        dailyTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::runDailyUpdate,
                delayTicks,
                dayTicks
        );
    }

    private long ticksUntilNextUpdate() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);

        Duration d = Duration.between(now.toInstant(), next.toInstant());
        long seconds = Math.max(1, d.getSeconds());
        long ticks = seconds * 20L;
        return Math.min(ticks, Integer.MAX_VALUE);
    }

    
/** Принудительно выполнить обновление рынка (для админ-команд/тестов). */
public void forceDailyUpdate() {
    runDailyUpdate();
}

private void runDailyUpdate() {
        for (MarketItem it : items.values()) {
            updatePrice(it);
            db.upsertItem(it.getMaterial().name(), it.getPrice(), it.getStock());
            it.snapshotTrend();
            it.resetTradeCounters();
        }

        String msg = plugin.getConfig().getString("settings.messages.marketUpdateBroadcast",
                "§a[Рынок] §fРынок обновлён! Цены пересчитаны.");

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(msg));
    }

    private void updatePrice(MarketItem it) {
        double buy = it.getBuyEma();
        double sell = it.getSellEma();

        double ratio = (buy + 1.0) / (sell + 1.0);
        double demandFactor = Math.pow(ratio, kDemand);
        if (demandFactor > 1.0) demandFactor = 1.0; // цена покупки не растёт

        int stock = it.getStock();
        int target = it.getStockTarget();
        double stockFactor = Math.pow(((target + 1.0) / (stock + 1.0)), sStock);
        if (stockFactor > 1.0) stockFactor = 1.0; // дефицит не повышает цену

        double targetPrice = it.getBasePrice() * demandFactor * stockFactor;
        if (targetPrice > it.getBasePrice()) targetPrice = it.getBasePrice();
        targetPrice = clamp(targetPrice, it.getMinPrice(), it.getMaxPrice());

        double cur = it.getPrice();
        double maxDelta = Math.abs(cur * maxStep);
        double delta = clamp(targetPrice - cur, -maxDelta, maxDelta);
        double next = clamp(cur + delta, it.getMinPrice(), it.getMaxPrice());

        // Если с прошлого обновления никто не покупал этот товар — цена НЕ должна расти
        if (next > cur && it.getBuysSinceUpdate() <= 0) next = cur;
        // hard clamp to max/min
        next = clamp(next, it.getMinPrice(), it.getMaxPrice());
        it.setPrice(next);

        it.decayEma(0.15);
    }

    
public double getEffectiveBuyPrice(MarketItem it) {
    double p = it.getPrice();
    double bonus = dailyDealBonus.getOrDefault(it.getMaterial(), 0.0);
    if (bonus > 0) p = p * (1.0 + bonus);
    // цена покупки не превышает max
    p = clamp(p, it.getMinPrice(), it.getMaxPrice());
    return p;
}

public double getDailyDealBonus(MarketItem it) {
    return dailyDealBonus.getOrDefault(it.getMaterial(), 0.0);
}

private void generateDailyDeals() {
    dailyDealBonus.clear();
    if (!plugin.getConfig().getBoolean("settings.dailyDeal.enabled", true)) {
        db.clearDailyDeals();
        return;
    }
    int count = plugin.getConfig().getInt("settings.dailyDeal.count", 3);
    double bMin = plugin.getConfig().getDouble("settings.dailyDeal.bonusMin", 0.20);
    double bMax = plugin.getConfig().getDouble("settings.dailyDeal.bonusMax", 0.40);
    if (count <= 0) { db.clearDailyDeals(); return; }
    if (bMax < bMin) { double t=bMax; bMax=bMin; bMin=t; }

    java.util.List<org.bukkit.Material> mats = new java.util.ArrayList<>(items.keySet());
    java.util.Collections.shuffle(mats);
    int picked = 0;
    java.util.Random rnd = new java.util.Random();
    for (org.bukkit.Material m : mats) {
        if (picked >= count) break;
        MarketItem it = items.get(m);
        if (it == null) continue;
        // не берём "пустые" категории-заглушки
        double bonus = bMin + (bMax - bMin) * rnd.nextDouble();
        bonus = Math.round(bonus * 100.0) / 100.0;
        dailyDealBonus.put(m, bonus);
        picked++;
    }
    java.util.Map<String, Double> save = new java.util.HashMap<>();
    for (var en : dailyDealBonus.entrySet()) save.put(en.getKey().name(), en.getValue());
    db.saveDailyDeals(save);
}

private void announceDailyDeals() {
    if (!plugin.getConfig().getBoolean("settings.dailyDeal.announce", true)) return;
    if (dailyDealBonus.isEmpty()) return;
    String prefix = plugin.getConfig().getString("settings.dailyDeal.prefix", "§6[Рынок]");
    java.util.List<String> lines = new java.util.ArrayList<>();
    lines.add(prefix + " §eТовары дня:");
    for (var en : dailyDealBonus.entrySet()) {
        MarketItem it = items.get(en.getKey());
        if (it == null) continue;
        String nameRu = formatMaterialName(en.getKey());
        int pct = (int) Math.round(en.getValue() * 100.0);
        lines.add("§e- " + nameRu + " §7(+" + pct + "% к покупке)");
    }
    for (String l : lines) org.bukkit.Bukkit.broadcastMessage(l);
}

private void updatePriceWithStep(MarketItem it, double step) {
        double buy = it.getBuyEma();
        double sell = it.getSellEma();

        double ratio = (buy + 1.0) / (sell + 1.0);
        double demandFactor = Math.pow(ratio, kDemand);
        if (demandFactor > 1.0) demandFactor = 1.0;

        int stock = it.getStock();
        int target = it.getStockTarget();
        double stockFactor = Math.pow(((target + 1.0) / (stock + 1.0)), sStock);
        if (stockFactor > 1.0) stockFactor = 1.0;

        double targetPrice = it.getBasePrice() * demandFactor * stockFactor;
        if (targetPrice > it.getBasePrice()) targetPrice = it.getBasePrice();
        targetPrice = clamp(targetPrice, it.getMinPrice(), it.getMaxPrice());

        double cur = it.getPrice();
        double maxDelta = Math.abs(cur * step);
        double delta = clamp(targetPrice - cur, -maxDelta, maxDelta);
        double next = clamp(cur + delta, it.getMinPrice(), it.getMaxPrice());
        // Если с прошлого обновления никто не покупал этот товар — цена НЕ должна расти
        if (next > cur && it.getBuysSinceUpdate() <= 0) next = cur;
        // hard clamp to max/min
        next = clamp(next, it.getMinPrice(), it.getMaxPrice());
        it.setPrice(next);
    }

/**
 * Обновление цены, но НЕЛЬЗЯ повышать цену (используется после продаж).
 */
private void updatePriceWithStepNoIncrease(MarketItem it, double step) {
    double before = it.getPrice();
    updatePriceWithStep(it, step);
    double after = it.getPrice();
    if (after > before) it.setPrice(before);
}

private double clamp(double v, double mn, double mx) {
        return Math.max(mn, Math.min(mx, v));
    }

    // ===== цены =====

    public double getBuyPrice(MarketItem it) {
        return getEffectiveBuyPrice(it);
    }

    public double getSellPrice(MarketItem it) {
        double mult = sellMultiplier;

        if (sellPressureEnabled) {
            double buy = it.getBuyEma();
            double sell = it.getSellEma();
            double ratio = (buy + 1.0) / (sell + 1.0);
            double pressure = Math.pow(ratio, sellPressureK);
            double effective = mult * pressure;
            mult = Math.max(sellPressureMinMultiplier, Math.min(mult, effective));
        }

        return it.getPrice() * mult;
    }

    // ===== сделки =====

    public boolean buy(Player p, MarketItem it, int qty) {
        if (!p.hasPermission("livemarket.buy")) return true;
        if (qty <= 0) return false;

        synchronized (it) {
            if (it.getStock() < qty) {
                p.sendMessage("§cНа складе рынка нет товара. Жди, пока кто-то продаст этот предмет.");
                return false;
            }
        }

        double cost = getBuyPrice(it) * qty;
        if (!economy.has(p, cost)) {
            p.sendMessage("§cНе хватает денег: нужно " + format(cost));
            return false;
        }

        var res = economy.withdrawPlayer(p, cost);
        if (!res.transactionSuccess()) {
            p.sendMessage("§cОшибка оплаты: " + res.errorMessage);
            return false;
        }

        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(it.getMaterial(), qty));
        if (!left.isEmpty()) {
            economy.depositPlayer(p, cost);
            p.sendMessage("§cИнвентарь полон. Покупка отменена.");
            return false;
        }

        synchronized (it) {
            it.setStock(it.getStock() - qty);
            it.addBuyVolume(qty);
            if (instantAdjustEnabled) updatePriceWithStep(it, instantMaxStepPerTrade);
        }

        p.sendMessage("§aКуплено: §f" + qty + " §f" + localizedMaterialName(p, it.getMaterial()) + " §7за §f" + format(cost));
        return true;
    }

    public boolean sell(Player p, MarketItem it, int qty) {
        if (!p.hasPermission("livemarket.sell")) return true;
        if (qty <= 0) return false;

        // Лимит склада: если рынок заполнен, продавать нельзя
        synchronized (it) {
            int stock = it.getStock();
            int limit = it.getStockTarget();
            if (limit > 0 && stock >= limit) {
                p.sendMessage("§cРынок заполнен для этого товара: §f" + stock + "§7/§f" + limit + "§c. Продажа недоступна.");
                return false;
            }
            if (limit > 0 && stock + qty > limit) {
                p.sendMessage("§cНельзя продать столько: лимит склада §f" + stock + "§7/§f" + limit + "§c.");
                return false;
            }
        }

        int has = countInInventory(p, it.getMaterial());
        if (has < qty) {
            p.sendMessage("§cУ тебя нет столько предметов.");
            return false;
        }

        double baseSellUnit = getSellPrice(it);

        DurabilityRemoveResult rr = removeFromInventoryWithDurability(p, it.getMaterial(), qty);
        if (rr.removed <= 0) {
            p.sendMessage("§cНе удалось забрать предметы.");
            return false;
        }

        double durabilityMult = rr.avgDurabilityMultiplier;
        double revenue = baseSellUnit * rr.removed * durabilityMult;

        economy.depositPlayer(p, revenue);

        synchronized (it) {
            it.setStock(it.getStock() + rr.removed);
            it.addSellVolume(rr.removed);
            if (instantAdjustEnabled) updatePriceWithStepNoIncrease(it, instantMaxStepPerTrade);
        }

        p.sendMessage("§aПродано: §f" + rr.removed + " §f" + localizedMaterialName(p, it.getMaterial())
                + " §7за §f" + format(revenue)
                + " §7(прочность: §f" + String.format("%.0f%%", durabilityMult * 100) + "§7)");
        return true;
    }

    private int countInInventory(Player p, Material mat) {
        int c = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == mat) c += is.getAmount();
        }
        return c;
    }

    private static class DurabilityRemoveResult {
        int removed;
        double avgDurabilityMultiplier;
    }

    private DurabilityRemoveResult removeFromInventoryWithDurability(Player p, Material mat, int qty) {
        int toRemove = qty;
        var inv = p.getInventory();

        double sumMult = 0.0;
        int counted = 0;

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack is = inv.getItem(i);
            if (is == null || is.getType() != mat) continue;

            int take = Math.min(is.getAmount(), toRemove);

            double mult = 1.0;
            if (is.getItemMeta() instanceof Damageable dmg) {
                int max = is.getType().getMaxDurability();
                if (max > 0) {
                    int damage = dmg.getDamage();
                    int remaining = Math.max(0, max - damage);
                    mult = Math.max(0.0, Math.min(1.0, (double) remaining / (double) max));
                }
            }

            sumMult += mult * take;
            counted += take;

            is.setAmount(is.getAmount() - take);
            if (is.getAmount() <= 0) inv.setItem(i, null);

            toRemove -= take;
            if (toRemove <= 0) break;
        }

        DurabilityRemoveResult rr = new DurabilityRemoveResult();
        rr.removed = qty - toRemove;
        rr.avgDurabilityMultiplier = counted > 0 ? (sumMult / counted) : 1.0;
        return rr;
    }

// ===== админ-редактирование ассортимента =====

/** Добавить предмет в рынок (с дефолтными параметрами) и сохранить в config.yml. */
public synchronized boolean addMarketItem(Material mat, String category, int slot) {
    if (items.containsKey(mat)) return false;

    String cat = category.toLowerCase();
    PriceProfile p = defaultProfile(cat, mat);

    MarketItem item = new MarketItem(mat, cat, p.base, p.min, p.max, p.base, p.stock, p.stockTarget, slot);
    items.put(mat, item);

    String path = "items." + mat.name();
    plugin.getConfig().set(path + ".category", cat);
    plugin.getConfig().set(path + ".base", p.base);
    plugin.getConfig().set(path + ".min", p.min);
    plugin.getConfig().set(path + ".max", p.max);
    plugin.getConfig().set(path + ".stock", p.stock);
    plugin.getConfig().set(path + ".stockTarget", p.stockTarget);
    plugin.getConfig().set(path + ".slot", slot);
    plugin.saveConfig();

    db.upsertItem(mat.name(), item.getPrice(), item.getStock());
    return true;
}

/** Удалить предмет из рынка и сохранить в config.yml. */
public synchronized boolean removeMarketItem(Material mat) {
    MarketItem removed = items.remove(mat);
    if (removed == null) return false;

    plugin.getConfig().set("items." + mat.name(), null);
    plugin.saveConfig();

    db.deleteItem(mat.name());
    return true;
}

private static class PriceProfile {
    double base, min, max;
    int stock, stockTarget;
}


private static double armorPieceMultiplier(String name) {
    // пропорции по количеству материала в крафте: шлем 5, нагрудник 8, поножи 7, ботинки 4
    if (name.endsWith("_CHESTPLATE")) return 8.0 / 8.0; // 1.0
    if (name.endsWith("_LEGGINGS")) return 7.0 / 8.0;
    if (name.endsWith("_HELMET")) return 5.0 / 8.0;
    if (name.endsWith("_BOOTS")) return 4.0 / 8.0;
    return 1.0;
}

private PriceProfile defaultProfile(String cat, Material mat) {
    String n = mat.name();
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
            boolean rare = n.contains("NETHERITE") || n.contains("ANCIENT");
            p.stock = rare ? 2 : 64;
            p.stockTarget = rare ? 8 : 320;
        }
        case "tools" -> {
            p.base = tier(n, 45.0, 85.0, 140.0, 320.0, 520.0);
            if (n.contains("DIAMOND")) p.base = 520.0;
            if (n.contains("NETHERITE")) p.base = 1200.0;
            p.min = Math.max(15.0, p.base * 0.45);
            p.max = p.base * 3.2;
            boolean good = n.contains("DIAMOND") || n.contains("NETHERITE");
            p.stock = good ? 3 : 10;
            p.stockTarget = good ? 12 : 40;
        }
        case "armor" -> {
    // База за "нагрудник" (8 материалов), дальше умножаем на коэффициент части
    double baseChest;
    if (n.contains("NETHERITE")) baseChest = 2500.0;
    else if (n.contains("DIAMOND")) baseChest = 1800.0;
    else if (n.contains("GOLD")) baseChest = 900.0;
    else if (n.contains("IRON")) baseChest = 260.0;
    else if (n.contains("CHAINMAIL")) baseChest = 420.0;
    else if (n.contains("LEATHER")) baseChest = 140.0;
    else baseChest = 220.0;

    if (n.equals("ELYTRA")) baseChest = 3500.0;
    if (n.equals("TURTLE_HELMET")) baseChest = 420.0; // особый случай

    double k = armorPieceMultiplier(n);
    p.base = baseChest * k;

    p.min = Math.max(40.0, p.base * 0.48);
    p.max = p.base * 3.0;

    boolean top = n.contains("DIAMOND") || n.contains("NETHERITE") || n.equals("ELYTRA");
    p.stock = top ? 1 : 6;
    p.stockTarget = top ? 3 : 22;
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

    p.base = round2(p.base);
    p.min = round2(Math.min(p.min, p.base));
    p.max = round2(Math.max(p.max, p.base));
    return p;
}

private double tier(String name, double t1, double t2, double t3, double t4, double t5) {
    if (name.contains("NETHERITE")) return t5;
    if (name.contains("DIAMOND") || name.contains("EMERALD")) return t5;
    if (name.contains("GOLD")) return t4;
    if (name.contains("IRON")) return t3;
    if (name.contains("STONE") || name.contains("COBBLE") || name.contains("DEEPSLATE")) return t2;
    if (name.contains("OAK") || name.contains("SPRUCE") || name.contains("BIRCH") || name.contains("LOG") || name.contains("PLANK")) return t1;
    return t2;
}

private double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
}

    public void shutdown() {
        if (dailyTask != null) dailyTask.cancel();
        for (MarketItem it : items.values()) {
            db.upsertItem(it.getMaterial().name(), it.getPrice(), it.getStock());
        }
    }
}