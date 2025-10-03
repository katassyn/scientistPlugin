package pl.yourserver.scientistPlugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.yourserver.scientistPlugin.command.ScientistCommand;
import pl.yourserver.scientistPlugin.config.ConfigManager;
import pl.yourserver.scientistPlugin.db.Database;
import pl.yourserver.scientistPlugin.drop.DropListener;
import pl.yourserver.scientistPlugin.gui.GuiManager;
import pl.yourserver.scientistPlugin.research.ResearchService;
import pl.yourserver.scientistPlugin.abyssal.AbyssalService;

public final class ScientistPlugin extends JavaPlugin {

    private static ScientistPlugin instance;
    private ConfigManager configManager;
    private Database database;
    private ResearchService researchService;
    private AbyssalService abyssalService;
    private GuiManager guiManager;

    public static ScientistPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.configManager.loadAll();

        this.database = new Database(this);
        this.database.init();

        this.researchService = new ResearchService(this);
        this.abyssalService = new AbyssalService(this);
        this.guiManager = new GuiManager(this);

        // Commands
        ScientistCommand cmd = new ScientistCommand(this);
        getCommand("scientist").setExecutor(cmd);
        getCommand("scientist").setTabCompleter(cmd);

        // Listeners
        Bukkit.getPluginManager().registerEvents(guiManager, this);
        Bukkit.getPluginManager().registerEvents(new DropListener(this), this);
        Bukkit.getPluginManager().registerEvents(new pl.yourserver.scientistPlugin.abyssal.AbyssalEffectsListener(this, abyssalService), this);
        Bukkit.getPluginManager().registerEvents(new pl.yourserver.scientistPlugin.abyssal.StatsSyncService(this), this);
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            try {
                Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
                Bukkit.getPluginManager().registerEvents(new pl.yourserver.scientistPlugin.drop.MythicDropListener(this), this);
            } catch (ClassNotFoundException ignored) {}
        }

        // Start research poller
        this.researchService.startReadyPoller();
    }

    @Override
    public void onDisable() {
        if (researchService != null) researchService.shutdown();
        if (database != null) database.shutdown();
    }

    public ConfigManager getConfigManager() { return configManager; }
    public Database getDatabase() { return database; }
    public ResearchService getResearchService() { return researchService; }
    public AbyssalService getAbyssalService() { return abyssalService; }
    public GuiManager getGuiManager() { return guiManager; }
}
