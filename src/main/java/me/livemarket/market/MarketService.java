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
    private double sellMultiplier;
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
        this.plugin = plugin;
        this.economy = economy;
        this.db = db;
    }

    public void loadFromConfigAndDb() {
        var cfg = plugin.getConfig();

        maxStep = cfg.getDouble("settings.update.maxStepPerUpdate", 0.08);
        kDemand = cfg.getDouble("settings.update.kDemand", 0.45);
        sStock = cfg.getDouble("settings.update.sStock", 0.25);
        sellMultiplier = cfg.getDouble("settings.update.sellMultiplier", 0.85);
        cooldownMs = cfg.getLong("settings.update.clickCooldownMs", 250);

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

    /** Локализованное название предмета для сообщений (берёт язык клиента игрока). */
    private String localizedMaterialName(Player p, Material mat) {
        try {
            var locale = p.locale();
            Component c = Component.translatable(mat.translationKey());
            Component rendered = GlobalTranslator.render(c, locale);
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

    /** Возвращает строку HH:mm:ss до следующего обновления (22:00 МСК по умолчанию). */
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

    public void scheduleDailyUpdateAtMoscowTime() {
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

    private void runDailyUpdate() {
        for (MarketItem it : items.values()) {
            updatePrice(it);
            db.upsertItem(it.getMaterial().name(), it.getPrice(), it.getStock());
            it.snapshotTrend();
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
        // Цена покупки НЕ должна расти: игнорируем спрос (ratio>1)
        if (demandFactor > 1.0) demandFactor = 1.0;

        int stock = it.getStock();
        int target = it.getStockTarget();
        double stockFactor = Math.pow(((target + 1.0) / (stock + 1.0)), sStock);
        // Цена покупки НЕ должна расти: дефицит не повышает цену
        if (stockFactor > 1.0) stockFactor = 1.0;

        double targetPrice = it.getBasePrice() * demandFactor * stockFactor;
        // Не выше базовой цены
        if (targetPrice > it.getBasePrice()) targetPrice = it.getBasePrice();
        targetPrice = clamp(targetPrice, it.getMinPrice(), it.getMaxPrice());

        double cur = it.getPrice();
        double maxDelta = Math.abs(cur * maxStep);
        double delta = clamp(targetPrice - cur, -maxDelta, maxDelta);
        double next = clamp(cur + delta, it.getMinPrice(), it.getMaxPrice());

        it.setPrice(next);

        it.decayEma(0.15);
    }

    /**
     * Микро-обновление цены сразу после сделки, чтобы игроки видели реакцию рынка моментально.
     * Использует тот же таргет, но меньший шаг.
     */
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
        it.setPrice(next);
    }

    private double clamp(double v, double mn, double mx) {
        return Math.max(mn, Math.min(mx, v));
    }

    // ===== цены =====

    public double getBuyPrice(MarketItem it) {
        return it.getPrice();
    }

    public double getSellPrice(MarketItem it) {
        double mult = sellMultiplier;

        // Если предмет часто продают — дополнительно режем sell-выручку
        if (sellPressureEnabled) {
            double buy = it.getBuyEma();
            double sell = it.getSellEma();
            double ratio = (buy + 1.0) / (sell + 1.0); // <1 если sell>buy
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
            // Товар должен заканчиваться: если stock=0 — купить нельзя
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
            if (instantAdjustEnabled) updatePriceWithStep(it, instantMaxStepPerTrade);
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

    public void shutdown() {
        if (dailyTask != null) dailyTask.cancel();
        for (MarketItem it : items.values()) {
            db.upsertItem(it.getMaterial().name(), it.getPrice(), it.getStock());
        }
    }
}
