package pl.yourserver.scientistPlugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;

import java.util.*;
import java.util.stream.Collectors;

public class ScientistCommand implements CommandExecutor, TabCompleter {
    private final ScientistPlugin plugin;
    private final ItemService itemService;

    public ScientistCommand(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration msg = plugin.getConfigManager().messages();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sendMessage(sender, "not_player", "&cThis command is only for players.");
                return true;
            }
            plugin.getGuiManager().openMain(p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("scientist.admin")) {
                    sendMessage(sender, "no_permission", "&cYou don't have permission.");
                    return true;
                }
                plugin.getConfigManager().reloadAll();
                sendMessage(sender, "reloaded", "&aScientist configs reloaded.");
            }
            case "progress" -> {
                if (!(sender instanceof Player p)) {
                    sendMessage(sender, "not_player", "&cThis command is only for players.");
                    return true;
                }
                plugin.getResearchService().sendProgress(p);
            }
            case "give" -> {
                if (!sender.hasPermission("scientist.admin")) {
                    sendMessage(sender, "no_permission", "&cYou don't have permission.");
                    return true;
                }
                if (args.length < 2) {
                    sendMessage(sender, "give_usage", "&cUsage: /scientist give <key> [amount]");
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sendMessage(sender, "not_player", "&cThis command is only for players.");
                    return true;
                }
                String key = args[1];
                int amount = 1;
                if (args.length >= 3) {
                    try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
                }
                var itemOpt = itemService.createItem(key, amount);
                if (itemOpt.isEmpty()) {
                    String prefix = plugin.getConfigManager().messages().getString("prefix", "");
                    String template = plugin.getConfigManager().messages().getString("invalid_item_key", "&cUnknown item key: {key}");
                    sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + template.replace("{key}", key)));
                    return true;
                }
                java.util.Map<Integer, ItemStack> leftover = p.getInventory().addItem(itemOpt.get());
                leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                String template = msg.getString("given", "&aGiven &e{amount}&a x &e{key}&a.");
                template = template.replace("{key}", key).replace("{amount}", String.valueOf(amount));
                sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(msg.getString("prefix", "") + template));
            }
            default -> {
                sendMessage(sender, "unknown_subcommand", "&cUnknown subcommand.");
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "give", "progress").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void sendMessage(CommandSender sender, String path, String def) {
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        String text = plugin.getConfigManager().messages().getString(path, def);
        if (text == null) return;
        sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + text));
    }
}
