
package me.livemarket;

import me.livemarket.market.MarketService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class UpdateCommand implements CommandExecutor {

    private final MarketService market;

    public UpdateCommand(MarketService market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("livemarket.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }

        market.forceDailyUpdate();
        sender.sendMessage("§aРынок обновлён вручную.");
        return true;
    }
}
