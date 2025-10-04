package pl.yourserver.scientistPlugin.drop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;

import java.util.Locale;
import java.util.Random;

public class DropListener implements Listener {
    private final ScientistPlugin plugin;
    private final Random rng = new Random();
    private final ItemService itemService;

    public DropListener(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        var dropsCfg = plugin.getConfig().getConfigurationSection("drops");
        if (dropsCfg == null) return;
        String type = e.getEntityType().name();
        if (!dropsCfg.isConfigurationSection(type)) return;

        // Level gating: prefer MyExperiencePlugin API, fallback to XP bar level
        var killer = e.getEntity().getKiller();
        if (killer == null) return;
        int level = killer.getLevel();
        try {
            Class<?> expCls = Class.forName("com.maks.myexperienceplugin.MyExperiencePlugin");
            var getInst = expCls.getMethod("getInstance");
            Object inst = getInst.invoke(null);
            if (inst != null) {
                var getLvl = expCls.getMethod("getPlayerLevel", org.bukkit.entity.Player.class);
                Object res = getLvl.invoke(inst, killer);
                if (res instanceof Integer i) level = i;
            }
        } catch (ClassNotFoundException ignored) {
            // Plugin not present, keep vanilla XP level
        } catch (Throwable t) {
            // Any reflection error: ignore and fallback
        }
        int minLvl = plugin.getConfig().getInt("drops_settings.min_level_for_drops", 45);
        if (level < minLvl) return;

        boolean debug = plugin.getConfig().getBoolean("debug.mode", false);
        ConfigurationSection sec = dropsCfg.getConfigurationSection(type);
        for (String key : sec.getKeys(false)) {
            double chance = sec.getDouble(key);
            if (debug || rng.nextDouble() <= chance) {
                itemService.createItem(key).ifPresent(e.getDrops()::add);
            }
        }
    }
}
