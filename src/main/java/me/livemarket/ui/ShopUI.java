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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class ShopUI implements Listener {

    private final JavaPlugin plugin;
    private final MarketService market;

    private static final int BACK_SLOT = 49;

    public ShopUI(JavaPlugin plugin, MarketService market) {
        this.plugin = plugin;
        this.market = market;
    }

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

        p.openInventory(inv);
    }

    private ItemStack buildCategoryItem(CategoryDef cat) {
        ItemStack is = new ItemStack(cat.icon());
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a" + cat.title());
            meta.setLore(List.of("§7Открыть категорию"));
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

    public void openCategory(Player p, CategoryDef cat) {
        FileConfiguration cfg = plugin.getConfig();
        String prefix = cfg.getString("settings.gui.categoryTitlePrefix", "§aРынок: §f");
        int size = normalizeSize(cfg.getInt("settings.gui.size", 54));

        Inventory inv = Bukkit.createInventory(new MarketHolder(cat.key()), size, prefix + cat.title());

        for (MarketItem it : market.getByCategory(cat.key())) {
            int slot = it.getSlot();
            if (slot < 0 || slot >= size) continue;
            inv.setItem(slot, buildMarketItem(it));
        }

        inv.setItem(BACK_SLOT, backButton());
        addAuctionButton(inv, p);

        p.openInventory(inv);
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
            lore.add("");

            if (it.getStock() <= 0) {
                lore.add("§cНет на складе. Можно купить только когда игроки продадут.");
                lore.add("");
            }

            lore.add("§aЛКМ §7— купить 1");
            lore.add("§aShift+ЛКМ §7— купить 16");
            lore.add("§bПКМ §7— продать 1");
            lore.add("§bShift+ПКМ §7— продать 16");

            meta.setLore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (!(e.getView().getTopInventory().getHolder() instanceof MarketHolder holder)) return;

        e.setCancelled(true);
        if (!market.isCooldownOk(p)) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // auction button click (works in both main and category)
        int ahSlot = plugin.getConfig().getInt("settings.gui.auctionButton.slot", 53);
        if (e.getSlot() == ahSlot && p.hasPermission("livemarket.auction")) {
            p.closeInventory();
            p.performCommand("ah");
            return;
        }

        // MAIN menu: click category by slot
        if (holder.getCategory() == null) {
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

        // CATEGORY menu
        if (e.getSlot() == BACK_SLOT && clicked.getType() == Material.ARROW) {
            openMain(p);
            return;
        }

        MarketItem it = market.getItem(clicked.getType());
        if (it == null) return;

        int qty = e.isShiftClick() ? 16 : 1;

        if (e.isLeftClick()) {
            market.buy(p, it, qty);
        } else if (e.isRightClick()) {
            market.sell(p, it, qty);
        }

        // обновить видимый слот
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Inventory top = p.getOpenInventory().getTopInventory();
            int slot = it.getSlot();
            if (slot >= 0 && slot < top.getSize()) {
                top.setItem(slot, buildMarketItem(it));
            }
        }, 1L);
    }
}
