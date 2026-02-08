package me.livemarket;

import me.livemarket.db.MarketDatabase;
import me.livemarket.market.MarketService;
import me.livemarket.ui.ShopUI;
import me.livemarket.UpdateCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiveMarketPlugin extends JavaPlugin {

    private Economy economy;
    private MarketDatabase database;
    private MarketService market;
    private ShopUI shopUI;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // RU name overrides
        NameOverrides nameOverrides = new NameOverrides(this);
        nameOverrides.load();
        NameUtil.setOverrideProvider(nameOverrides::get);


        if (!hookEconomy()) {
            getLogger().severe("Vault economy not found. Install EssentialsX (or other economy) + Vault.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.database = new MarketDatabase(this);
        this.database.init();

        this.market = new MarketService(this, economy, database);
        this.market.loadFromConfigAndDb();

        this.shopUI = new ShopUI(this, market);

        // Admin: force market update (for testing)
        var upd = getCommand("lmupdate");
        if (upd != null) upd.setExecutor(new UpdateCommand(market));


        getCommand("shop").setExecutor( (sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            if (!p.hasPermission("livemarket.shop.open")) return true;
            shopUI.openMain(p);
            return true;
        });

        getCommand("shopedit").setExecutor( (sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            if (!p.hasPermission("livemarket.shop.edit")) return true;
            shopUI.openMainEdit(p);
            return true;
        });


        Bukkit.getPluginManager().registerEvents(shopUI, this);

        market.scheduleDailyUpdate();

        getLogger().info("LiveMarket enabled.");
    }

    @Override
    public void onDisable() {
        if (market != null) market.shutdown();
        if (database != null) database.close();
    }

    private boolean hookEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }
}