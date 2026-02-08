package me.livemarket;

import me.livemarket.market.MarketItem;
import me.livemarket.market.MarketService;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class PriceCommand implements CommandExecutor {

    private final MarketService market;

    public PriceCommand(MarketService market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("livemarket.price")) return true;

        if (args.length < 1) {
            sender.sendMessage("§cUsage: /price <material>");
            return true;
        }
        Material mat;
        try {
            mat = Material.valueOf(args[0].toUpperCase());
        } catch (Exception e) {
            sender.sendMessage("§cUnknown material.");
            return true;
        }

        MarketItem item = market.getItem(mat);
        if (item == null) {
            sender.sendMessage("§cThis item is not in market config.");
            return true;
        }

        sender.sendMessage("§a" + mat + " §7Buy: §f" + market.format(market.getBuyPrice(item))
                + " §7Sell: §f" + market.format(market.getSellPrice(item))
                + " §7Stock: §f" + item.getStock()
                + " §7Trend: " + item.getTrendArrow() + " " + item.getTrendPercentString());
        return true;
    }
}
