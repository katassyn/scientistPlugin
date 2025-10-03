package pl.yourserver.scientistPlugin.abyssal;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.lang.reflect.Method;
import java.util.Locale;

public class StatsSyncService implements Listener {
    private final ScientistPlugin plugin;
    private final NamespacedKey abyssalKey;

    public StatsSyncService(ScientistPlugin plugin) {
        this.plugin = plugin;
        this.abyssalKey = new NamespacedKey(plugin, "abyssal");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTask(plugin, () -> sync(e.getPlayer())); }
    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) { Bukkit.getScheduler().runTask(plugin, () -> sync(e.getPlayer())); }
    @EventHandler
    public void onInv(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> sync(p)); }
    @EventHandler
    public void onQuit(PlayerQuitEvent e) { /* no-op */ }

    private void sync(Player p) {
        try {
            Class<?> expCls = Class.forName("com.maks.myexperienceplugin.MyExperiencePlugin");
            Object expInst = expCls.getMethod("getInstance").invoke(null);
            Object seh = expCls.getMethod("getSkillEffectsHandler").invoke(expInst);
            Method getStats = seh.getClass().getMethod("getPlayerStats", Player.class);
            Object stats = getStats.invoke(seh, p);
            if (stats == null) return;

            // Reset only the fields we manage by reapplying as additive deltas (keep class skills intact)
            double critAdd = 0.0;
            double critDmgAdd = 0.0;
            double atkSpeedAdd = 0.0;
            double luckAdd = 0.0;
            double spellMultAdd = 0.0; // as multiplier addition (e.g., +0.05 for 5%)
            double cdrAdd = 0.0; // cooldown reduction %
            double channelSpeedAdd = 0.0; // channeling speed %
            double auraPotencyAdd = 0.0; // aura potency %
            double auraRadiusAdd = 0.0; // aura radius %

            // Iterate over armor and hand items
            applyModifier(p.getInventory().getItem(EquipmentSlot.HEAD), (key, val) -> {
                switch (key) {
                    case "clairvoyance" -> critAdd += val;
                    case "lucidity" -> cdrAdd += val;
                    case "mindforge" -> spellMultAdd += val / 100.0;
                    case "third_eye" -> critDmgAdd += val; // approx
                }
            });
            applyModifier(p.getInventory().getItem(EquipmentSlot.HAND), (key, val) -> {
                switch (key) {
                    case "cold_focus" -> critDmgAdd += val;
                    case "chrono_edge" -> atkSpeedAdd += val;
                }
            });
            applyModifier(p.getInventory().getItem(EquipmentSlot.FEET), (key, val) -> {
                // Boots handled via attributes/event; no stat integration needed here
            });
            // Trinkets accessories via reflection
            try {
                Class<?> trinketsCls = Class.forName("com.maks.trinketsplugin.TrinketsPlugin");
                Object trInst = trinketsCls.getMethod("getInstance").invoke(null);
                Object dbm = trinketsCls.getMethod("getDatabaseManager").invoke(trInst);
                Object pdata = dbm.getClass().getMethod("getPlayerData", java.util.UUID.class).invoke(dbm, p.getUniqueId());
                if (pdata != null) {
                    Class<?> accType = Class.forName("com.maks.trinketsplugin.AccessoryType");
                    Object[] values = (Object[]) accType.getMethod("values").invoke(null);
                    Method getAcc = pdata.getClass().getMethod("getAccessory", accType);
                    double leechAmp = 0.0;
                    for (Object at : values) {
                        Object item = getAcc.invoke(pdata, at);
                        if (item instanceof org.bukkit.inventory.ItemStack is) {
                            applyModifier(is, (key, val) -> {
                                switch (key) {
                                    case "prosperity", "harvesters_touch" -> { /* LUCK handled below */ }
                                    case "precision" -> critAdd += val;
                                    case "quick_hands" -> atkSpeedAdd += val;
                                    case "leech_amp" -> { /* store for PDC */ }
                                    case "arcane_attunement" -> cdrAdd += val;
                                    case "ritual_focus" -> channelSpeedAdd += val;
                                    case "oath_sigil" -> auraPotencyAdd += val;
                                    case "harmonize" -> auraRadiusAdd += val;
                                }
                            });
                            // read raw for leech_amp specifically
                            leechAmp += readValue(is, "leech_amp");
                            luckAdd += readValue(is, "prosperity");
                            luckAdd += readValue(is, "harvesters_touch");
                        }
                    }
                    // Persist leech amp on player PDC for effects to read
                    if (leechAmp > 0) {
                        p.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "leech_amp"), org.bukkit.persistence.PersistentDataType.DOUBLE, leechAmp);
                    } else {
                        p.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "leech_amp"));
                    }
                }
            } catch (Throwable ignored) { }

            // Apply to PlayerSkillStats via reflection
            Class<?> statsCls = stats.getClass();
            // crit chance
            try { statsCls.getMethod("addCriticalChance", double.class).invoke(stats, critAdd); } catch (NoSuchMethodException ignored) {}
            // crit damage bonus
            try { statsCls.getMethod("addCriticalDamageBonus", double.class).invoke(stats, critDmgAdd); } catch (NoSuchMethodException ignored) {}
            // attack speed
            try { statsCls.getMethod("addAttackSpeed", double.class).invoke(stats, atkSpeedAdd); } catch (NoSuchMethodException ignored) {}
            // luck bonus
            try { statsCls.getMethod("addLuckBonus", double.class).invoke(stats, luckAdd); } catch (NoSuchMethodException ignored) {}
            // spell damage multiplier
            if (spellMultAdd != 0.0) {
                try { statsCls.getMethod("multiplySpellDamageMultiplier", double.class).invoke(stats, 1.0 + spellMultAdd); } catch (NoSuchMethodException ignored) {}
            }
            // cooldown reduction
            if (cdrAdd != 0.0) {
                boolean ok = false;
                try { statsCls.getMethod("addCooldownReduction", double.class).invoke(stats, cdrAdd); ok = true; } catch (NoSuchMethodException ignored) {}
                if (!ok) try { statsCls.getMethod("multiplyCooldownMultiplier", double.class).invoke(stats, Math.max(0.0, 1.0 - cdrAdd/100.0)); ok = true; } catch (NoSuchMethodException ignored) {}
                if (!ok) try { statsCls.getMethod("addCdr", double.class).invoke(stats, cdrAdd); } catch (NoSuchMethodException ignored) {}
            }
            // channeling speed
            if (channelSpeedAdd != 0.0) {
                boolean ok = false;
                try { statsCls.getMethod("addChannelingSpeed", double.class).invoke(stats, channelSpeedAdd); ok = true; } catch (NoSuchMethodException ignored) {}
                if (!ok) try { statsCls.getMethod("multiplyChannelingSpeedMultiplier", double.class).invoke(stats, 1.0 + channelSpeedAdd/100.0); } catch (NoSuchMethodException ignored) {}
            }
            // aura potency
            if (auraPotencyAdd != 0.0) {
                boolean ok = false;
                try { statsCls.getMethod("addAuraPotency", double.class).invoke(stats, auraPotencyAdd); ok = true; } catch (NoSuchMethodException ignored) {}
                if (!ok) try { statsCls.getMethod("multiplyAuraPotencyMultiplier", double.class).invoke(stats, 1.0 + auraPotencyAdd/100.0); } catch (NoSuchMethodException ignored) {}
            }
            // aura radius
            if (auraRadiusAdd != 0.0) {
                boolean ok = false;
                try { statsCls.getMethod("addAuraRadius", double.class).invoke(stats, auraRadiusAdd); ok = true; } catch (NoSuchMethodException ignored) {}
                if (!ok) try { statsCls.getMethod("multiplyAuraRadiusMultiplier", double.class).invoke(stats, 1.0 + auraRadiusAdd/100.0); } catch (NoSuchMethodException ignored) {}
            }

            // Refresh to apply
            seh.getClass().getMethod("refreshPlayerStats", Player.class).invoke(seh, p);
        } catch (ClassNotFoundException cnf) {
            // MyExperiencePlugin not installed
        } catch (Throwable t) {
            // avoid crashing if API changes
        }
    }

    private interface Applier { void apply(String key, double value); }

    private void applyModifier(ItemStack item, Applier applier) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        String raw = meta.getPersistentDataContainer().get(abyssalKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return;
        try {
            JsonObject jo = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            String key = jo.get("modifier_key").getAsString();
            double val = jo.get("value").getAsDouble();
            applier.apply(key, val);
        } catch (Exception ignored) {}
    }

    private double readValue(ItemStack item, String matchKey) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return 0.0;
            String raw = meta.getPersistentDataContainer().get(abyssalKey, PersistentDataType.STRING);
            if (raw == null || raw.isEmpty()) return 0.0;
            JsonObject jo = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (!jo.has("modifier_key") || !jo.get("modifier_key").getAsString().equals(matchKey)) return 0.0;
            return jo.get("value").getAsDouble();
        } catch (Exception e) { return 0.0; }
    }
}
