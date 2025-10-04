package pl.yourserver.scientistPlugin.drop;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;

import java.util.*;
import java.util.regex.Pattern;

public class MythicDropListener implements Listener {
    private final ScientistPlugin plugin;
    private final Random rng = new Random();
    private final ItemService itemService;

    public MythicDropListener(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
    }

    @EventHandler
    public void onMythicDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        // Ensure entity is Mythic via reflection
        String internalName = getMythicInternalName(event.getEntity());
        if (internalName == null || internalName.isEmpty()) return;

        int level = killer.getLevel();
        try {
            Class<?> expCls = Class.forName("com.maks.myexperienceplugin.MyExperiencePlugin");
            Object inst = expCls.getMethod("getInstance").invoke(null);
            if (inst != null) {
                Object res = expCls.getMethod("getPlayerLevel", org.bukkit.entity.Player.class).invoke(inst, killer);
                if (res instanceof Integer i) level = i;
            }
        } catch (Throwable ignored) {}
        int minLvl = plugin.getConfig().getInt("drops_settings.min_level_for_drops", 45);
        if (level < minLvl) return;

        String tierTag = tierFromName(internalName);
        if (tierTag == null) tierTag = "I";

        var mythicCfg = plugin.getConfig().getConfigurationSection("mythic_drops");
        if (mythicCfg == null || !mythicCfg.getBoolean("enabled", true)) return;

        List<String> families = familiesFor(internalName, mythicCfg);
        if (families.isEmpty()) families = mythicCfg.getStringList("default_families");
        if (families.isEmpty()) return;

        boolean debug = plugin.getConfig().getBoolean("debug.mode", false);
        double chance = mythicCfg.getConfigurationSection("tier_chance").getDouble(tierTag, 0.05);
        if (!debug && rng.nextDouble() > chance) return;

        String family = families.get(rng.nextInt(families.size()));
        String key = family + "_" + tierTag;

        Location loc = event.getEntity().getLocation();
        itemService.createItem(key).ifPresent(drop -> loc.getWorld().dropItemNaturally(loc, drop));
    }

    private String tierFromName(String n) {
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_inf")) return "I";
        if (lower.endsWith("_hell")) return "II";
        if (lower.endsWith("_blood")) return "III";
        return null;
    }

    private List<String> familiesFor(String name, ConfigurationSection mythicCfg) {
        List<String> out = new ArrayList<>();
        ConfigurationSection groups = mythicCfg.getConfigurationSection("groups");
        if (groups == null) return out;
        for (String g : groups.getKeys(false)) {
            ConfigurationSection gs = groups.getConfigurationSection(g);
            String pattern = gs.getString("pattern", ".*");
            try {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(name).find()) {
                    out.addAll(gs.getStringList("families"));
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private String getMythicInternalName(org.bukkit.entity.Entity ent) {
        try {
            Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythic.getMethod("inst").invoke(null);
            Object mobMgr = mythic.getMethod("getMobManager").invoke(inst);
            try {
                // getActiveMob(UUID)
                Object opt = mobMgr.getClass().getMethod("getActiveMob", java.util.UUID.class).invoke(mobMgr, ent.getUniqueId());
                Object am = (opt instanceof java.util.Optional<?> o) ? o.orElse(null) : opt;
                if (am != null) {
                    try {
                        Object type = am.getClass().getMethod("getType").invoke(am);
                        Object name = type.getClass().getMethod("getInternalName").invoke(type);
                        return String.valueOf(name);
                    } catch (NoSuchMethodException ignored) {
                        Object type = am.getClass().getMethod("getMobType").invoke(am);
                        Object name = type.getClass().getMethod("getInternalName").invoke(type);
                        return String.valueOf(name);
                    }
                }
            } catch (NoSuchMethodException ignored) {}
            try {
                // alternative: getMythicMobInstance(Entity)
                Object ami = mobMgr.getClass().getMethod("getMythicMobInstance", org.bukkit.entity.Entity.class).invoke(mobMgr, ent);
                if (ami != null) {
                    Object type = ami.getClass().getMethod("getType").invoke(ami);
                    Object name = type.getClass().getMethod("getInternalName").invoke(type);
                    return String.valueOf(name);
                }
            } catch (NoSuchMethodException ignored) {}
            // fallback: not mythic
        } catch (Throwable ignored) {}
        return null;
    }
}
