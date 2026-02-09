package me.livemarket;

import me.livemarket.db.MarketDatabase;
import me.livemarket.market.MarketService;
import me.livemarket.ui.ShopUI;
import me.livemarket.util.NameUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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

        if (!hookEconomy()) {
            getLogger().severe("Vault economy not found. Install EssentialsX (or other economy) + Vault.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        NameUtil.init(this);

        this.database = new MarketDatabase(this);
        this.database.init();

        this.market = new MarketService(this, economy, database);
        this.market.loadFromConfigAndDb();

        this.shopUI = new ShopUI(this, market);

        // Commands
        bind("shop", new ShopCommand(shopUI));
        bind("shopedit", new ShopEditCommand(shopUI));
        bind("lmupdate", new UpdateCommand(market));

        // Events
        Bukkit.getPluginManager().registerEvents(shopUI, this);

        // Scheduler
        market.scheduleDailyUpdate();

        getLogger().info("LiveMarket enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (market != null) market.shutdown();
        } finally {
            if (database != null) database.close();
        }
    }

    private void bind(String name, org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) cmd.setExecutor(exec);
    }

    private boolean hookEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }
}
