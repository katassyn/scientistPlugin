package pl.yourserver.scientistPlugin.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class ScientistCommand implements CommandExecutor, TabCompleter {
    private final ScientistPlugin plugin;

    public ScientistCommand(ScientistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration msg = plugin.getConfigManager().messages();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                String text = msg.getString("not_player", "&cThis command is only for players.");
                sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
                return true;
            }
            plugin.getGuiManager().openMain(p);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("scientist.admin")) {
                    String text = msg.getString("no_permission", "&cYou don't have permission.");
                    sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
                    return true;
                }
                plugin.getConfigManager().reloadAll();
                String text = msg.getString("reloaded", "&aScientist configs reloaded.");
                sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            }
            default -> {
                String text = msg.getString("unknown_subcommand", "&cUnknown subcommand.");
                sender.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            }
        }

        return true;
    }

    private ItemStack createPlaceholderItem(String key, int amount) {
        // Placeholder vanilla item if external item systems are not hooked.
        Material mat = Material.PAPER;
        if (key.toLowerCase(Locale.ROOT).contains("bone")) mat = Material.ALLIUM; // flower icon
        ItemStack it = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(key));
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
