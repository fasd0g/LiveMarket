package me.livemarket.ui;

import me.livemarket.market.MarketItem;
import me.livemarket.market.MarketService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class ShopUI implements Listener {

    private final JavaPlugin plugin;
    private final MarketService market;

    private static final int BACK_SLOT = 49;

    /** Игрок -> открытая категория (null = главное меню). */
    private final Map<UUID, String> viewerCategories = new HashMap<>();

    public ShopUI(JavaPlugin plugin, MarketService market) {
        this.plugin = plugin;
        this.market = market;
        startRefreshTask();
    }

    private void startRefreshTask() {
        // Раз в секунду обновляем таймер (и цены/склад) в открытом GUI.
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            if (viewerCategories.isEmpty()) return;

            for (UUID id : new ArrayList<>(viewerCategories.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) {
                    viewerCategories.remove(id);
                    continue;
                }
                var holder = p.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof MarketHolder mh) {
                    if (mh.getCategory() == null) refreshMain(p);
                    else refreshCategory(p, mh.getCategory());
                } else if (holder instanceof EditHolder eh) {
                    if (eh.getCategory() == null) refreshMainEdit(p);
                    else refreshCategoryEdit(p, eh.getCategory());
                } else {
                    viewerCategories.remove(id);
                }
            }
        }, 20L, 20L);
    }

    // ===== обычный режим =====

    public void openMain(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        String title = cfg.getString("settings.gui.mainTitle", "§aЖивой рынок");
        int size = normalizeSize(cfg.getInt("settings.gui.size", 54));

        Inventory inv = Bukkit.createInventory(new MarketHolder(null), size, title);

        for (CategoryDef cat : market.getCategories().values()) {
            if (!p.hasPermission(cat.permission())) continue;
            if (cat.slot() < 0 || cat.slot() >= inv.getSize()) continue;
            inv.setItem(cat.slot(), buildCategoryItem(cat));
        }

        addAuctionButton(inv, p);

        viewerCategories.put(p.getUniqueId(), null);
        p.openInventory(inv);
    }

    public void openCategory(Player p, CategoryDef cat) {
        FileConfiguration cfg = plugin.getConfig();
        String prefix = cfg.getString("settings.gui.categoryTitlePrefix", "§aРынок: §f");
        int size = normalizeSize(cfg.getInt("settings.gui.size", 54));

        Inventory inv = Bukkit.createInventory(new MarketHolder(cat.key()), size, prefix + cat.title());

        for (MarketItem it : market.getByCategory(cat.key())) {
            int slot = it.getSlot();
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, buildMarketItem(it));
        }

        inv.setItem(BACK_SLOT, backButton());
        addAuctionButton(inv, p);

        viewerCategories.put(p.getUniqueId(), cat.key());
        p.openInventory(inv);
    }

    private void refreshMain(Player p) {
        Inventory inv = p.getOpenInventory().getTopInventory();

        for (CategoryDef cat : market.getCategories().values()) {
            if (!p.hasPermission(cat.permission())) continue;
            if (cat.slot() < 0 || cat.slot() >= inv.getSize()) continue;
            inv.setItem(cat.slot(), buildCategoryItem(cat));
        }

        addAuctionButton(inv, p);
    }

    private void refreshCategory(Player p, String categoryKey) {
        Inventory inv = p.getOpenInventory().getTopInventory();

        for (MarketItem it : market.getByCategory(categoryKey)) {
            int slot = it.getSlot();
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, buildMarketItem(it));
        }

        inv.setItem(BACK_SLOT, backButton());
        addAuctionButton(inv, p);
    }

    // ===== режим редактора =====

    public void openMainEdit(Player p) {
        FileConfiguration cfg = plugin.getConfig();
        String title = "§cРынок (редактор)";
        int size = normalizeSize(cfg.getInt("settings.gui.size", 54));
        Inventory inv = Bukkit.createInventory(new EditHolder(null), size, title);

        for (CategoryDef cat : market.getCategories().values()) {
            if (cat.slot() < 0 || cat.slot() >= inv.getSize()) continue;
            inv.setItem(cat.slot(), buildCategoryItem(cat));
        }
        addAuctionButton(inv, p);

        viewerCategories.put(p.getUniqueId(), null);
        p.openInventory(inv);
    }

    public void openCategoryEdit(Player p, CategoryDef cat) {
        FileConfiguration cfg = plugin.getConfig();
        String title = "§cРедактор: §f" + cat.title();
        int size = normalizeSize(cfg.getInt("settings.gui.size", 54));
        Inventory inv = Bukkit.createInventory(new EditHolder(cat.key()), size, title);

        for (MarketItem it : market.getByCategory(cat.key())) {
            int slot = it.getSlot();
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, buildMarketItem(it));
        }

        inv.setItem(BACK_SLOT, backButton());
        addAuctionButton(inv, p);

        viewerCategories.put(p.getUniqueId(), cat.key());
        p.openInventory(inv);
    }

    private void refreshMainEdit(Player p) {
        Inventory inv = p.getOpenInventory().getTopInventory();
        for (CategoryDef cat : market.getCategories().values()) {
            if (cat.slot() < 0 || cat.slot() >= inv.getSize()) continue;
            inv.setItem(cat.slot(), buildCategoryItem(cat));
        }
        addAuctionButton(inv, p);
    }

    private void refreshCategoryEdit(Player p, String categoryKey) {
        Inventory inv = p.getOpenInventory().getTopInventory();
        for (MarketItem it : market.getByCategory(categoryKey)) {
            int slot = it.getSlot();
            if (slot < 0 || slot >= inv.getSize()) continue;
            inv.setItem(slot, buildMarketItem(it));
        }
        inv.setItem(BACK_SLOT, backButton());
        addAuctionButton(inv, p);
    }

    // ===== item builders =====

    private ItemStack buildCategoryItem(CategoryDef cat) {
        ItemStack is = new ItemStack(cat.icon());
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a" + cat.title());
            // Убираем отображение характеристик (броня/урон/скорость) у категорий
            meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP
            );
            meta.setLore(List.of(
                    "§7Открыть категорию",
                    "§7До обновления: §f" + market.getTimeUntilNextUpdateString()
            ));
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack buildMarketItem(MarketItem it) {
        ItemStack is = new ItemStack(it.getMaterial());
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + it.getMaterial().name());

            double buy = market.getBuyPrice(it);
            double sell = market.getSellPrice(it);

            List<String> lore = new ArrayList<>();
            lore.add("§7Buy: §f" + market.format(buy));
            lore.add("§7Sell: §f" + market.format(sell) + " §8(учтёт прочность)");
            lore.add("§7Trend: " + it.getTrendArrow() + " " + it.getTrendPercentString());
            lore.add("§7Stock: §f" + it.getStock() + " §7/ §f" + it.getStockTarget());
            lore.add("§7До обновления: §f" + market.getTimeUntilNextUpdateString());
            lore.add("");

            if (it.getStock() <= 0) {
                lore.add("§cНет на складе. Можно купить только когда игроки продадут.");
                lore.add("");
            }

            lore.add("§aЛКМ §7— купить 1");
            lore.add("§aShift+ЛКМ §7— купить 16");
            lore.add("§bПКМ §7— продать 1");
            lore.add("§bShift+ПКМ §7— продать 16");
            lore.add("§8(в редакторе: курсор=добавить, Shift+ПКМ=удалить)");

            meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private ItemStack backButton() {
        ItemStack is = new ItemStack(Material.ARROW);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§eНазад");
            is.setItemMeta(meta);
        }
        return is;
    }

    private void addAuctionButton(Inventory inv, Player p) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("settings.gui.auctionButton.enabled", true)) return;
        if (!p.hasPermission("livemarket.auction")) return;

        int slot = cfg.getInt("settings.gui.auctionButton.slot", 53);
        if (slot < 0 || slot >= inv.getSize()) return;

        Material icon;
        try {
            icon = Material.valueOf(cfg.getString("settings.gui.auctionButton.icon", "GOLD_INGOT").toUpperCase());
        } catch (Exception e) {
            icon = Material.GOLD_INGOT;
        }

        ItemStack is = new ItemStack(icon);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(cfg.getString("settings.gui.auctionButton.name", "§6Аукцион"));
            List<String> lore = cfg.getStringList("settings.gui.auctionButton.lore");
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            is.setItemMeta(meta);
        }

        inv.setItem(slot, is);
    }

    private int normalizeSize(int size) {
        size = Math.max(9, Math.min(54, size));
        return (size / 9) * 9;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            viewerCategories.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        Object holder = top.getHolder();
        boolean isEdit = holder instanceof EditHolder;
        String category = null;
        if (holder instanceof MarketHolder mh) category = mh.getCategory();
        if (holder instanceof EditHolder eh) category = eh.getCategory();
        if (!(holder instanceof MarketHolder) && !(holder instanceof EditHolder)) return;

        // В обычном режиме клики отменяем. В редакторе — разрешаем расстановку, но свои действия тоже перехватываем.
        if (!isEdit) {
            e.setCancelled(true);
            if (!market.isCooldownOk(p)) return;
        }

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) clicked = null;

        // Аукцион кнопка
        int ahSlot = plugin.getConfig().getInt("settings.gui.auctionButton.slot", 53);
        if (e.getSlot() == ahSlot && p.hasPermission("livemarket.auction")) {
            p.closeInventory();
            p.performCommand("ah");
            return;
        }

        // ===== РЕДАКТОР =====
        if (isEdit) {
            if (!p.hasPermission("livemarket.shop.edit")) {
                p.sendMessage("§cНет прав на редактирование рынка.");
                e.setCancelled(true);
                return;
            }

            // главное меню редактора: открываем категории
            if (category == null) {
                if (clicked == null) return;
                for (CategoryDef cat : market.getCategories().values()) {
                    if (e.getSlot() == cat.slot()) {
                        openCategoryEdit(p, cat);
                        e.setCancelled(true);
                        return;
                    }
                }
                return;
            }

            // назад
            if (clicked != null && e.getSlot() == BACK_SLOT && clicked.getType() == Material.ARROW) {
                openMainEdit(p);
                e.setCancelled(true);
                return;
            }

            // удалить: Shift+ПКМ по предмету
            if (clicked != null && e.isShiftClick() && e.isRightClick()) {
                MarketItem it = market.getItem(clicked.getType());
                if (it != null) {
                    market.removeMarketItem(clicked.getType());
                    top.setItem(e.getSlot(), null);
                    p.sendMessage("§aУдалено из рынка: §f" + clicked.getType().name());
                    e.setCancelled(true);
                }
                return;
            }

            // добавить: предмет на курсоре в слот (снимаем 1)
            ItemStack cursor = e.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                Material mat = cursor.getType();
                if (market.getItem(mat) != null) {
                    p.sendMessage("§cЭтот предмет уже есть в рынке.");
                    e.setCancelled(true);
                    return;
                }
                if (e.getSlot() == BACK_SLOT || e.getSlot() == ahSlot) {
                    p.sendMessage("§cНельзя добавлять в этот слот.");
                    e.setCancelled(true);
                    return;
                }

                boolean ok = market.addMarketItem(mat, category, e.getSlot());
                if (ok) {
                    cursor.setAmount(cursor.getAmount() - 1);
                    if (cursor.getAmount() <= 0) e.setCursor(null);
                    top.setItem(e.getSlot(), buildMarketItem(market.getItem(mat)));
                    p.sendMessage("§aДобавлено в рынок: §f" + mat.name() + " §7(категория " + category + ")");
                    e.setCancelled(true);
                }
                return;
            }

            return;
        }

        // ===== ОБЫЧНЫЙ РЕЖИМ =====

        // главное меню: категории
        if (category == null) {
            if (clicked == null) return;
            for (CategoryDef cat : market.getCategories().values()) {
                if (e.getSlot() == cat.slot()) {
                    if (!p.hasPermission(cat.permission())) {
                        p.sendMessage("§cНет прав на категорию.");
                        return;
                    }
                    openCategory(p, cat);
                    return;
                }
            }
            return;
        }

        // назад
        if (clicked != null && e.getSlot() == BACK_SLOT && clicked.getType() == Material.ARROW) {
            openMain(p);
            return;
        }

        // товар
        if (clicked == null) return;
        MarketItem it = market.getItem(clicked.getType());
        if (it == null) return;

        int qty = e.isShiftClick() ? 16 : 1;
        if (e.isLeftClick()) market.buy(p, it, qty);
        else if (e.isRightClick()) market.sell(p, it, qty);

        // сразу обновляем GUI
        refreshCategory(p, category);
    }
}
