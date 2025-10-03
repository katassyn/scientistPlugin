package pl.yourserver.scientistPlugin.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.io.File;
import java.io.IOException;

public class ConfigManager {
    private final ScientistPlugin plugin;

    private File messagesFile;
    private FileConfiguration messages;

    private File recipesFile;
    private FileConfiguration recipes;

    private File modifiersFile;
    private FileConfiguration modifiers;

    private File guiFile;
    private FileConfiguration gui;

    public ConfigManager(ScientistPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        loadMessages();
        loadRecipes();
        loadModifiers();
        loadGui();
    }

    public void reloadAll() {
        plugin.reloadConfig();
        loadMessages();
        loadRecipes();
        loadModifiers();
        loadGui();
    }

    private void loadMessages() {
        messagesFile = ensureResource("messages_en.yml");
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadRecipes() {
        recipesFile = ensureResource("recipes.yml");
        recipes = YamlConfiguration.loadConfiguration(recipesFile);
    }

    private void loadModifiers() {
        modifiersFile = ensureResource("modifiers.yml");
        modifiers = YamlConfiguration.loadConfiguration(modifiersFile);
    }

    private void loadGui() {
        guiFile = ensureResource("gui.yml");
        gui = YamlConfiguration.loadConfiguration(guiFile);
    }

    private File ensureResource(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            plugin.saveResource(name, false);
        }
        return f;
    }

    public FileConfiguration config() { return plugin.getConfig(); }
    public FileConfiguration messages() { return messages; }
    public FileConfiguration recipes() { return recipes; }
    public FileConfiguration modifiers() { return modifiers; }
    public FileConfiguration gui() { return gui; }
}

