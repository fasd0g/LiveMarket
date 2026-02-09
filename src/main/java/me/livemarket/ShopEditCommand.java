package me.livemarket;

import me.livemarket.ui.ShopUI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ShopEditCommand implements CommandExecutor {

    private final ShopUI ui;

    public ShopEditCommand(ShopUI ui) {
        this.ui = ui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("livemarket.shop.edit")) return true;
        ui.openMainEdit(p);
        return true;
    }
}
