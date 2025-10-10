package pl.yourserver.scientistPlugin.abyssal;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.gui.GuiManager;
import pl.yourserver.scientistPlugin.item.ItemService;
import pl.yourserver.scientistPlugin.model.SciCategory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class AbyssalService {
    private final ScientistPlugin plugin;
    private final NamespacedKey pdcKey;
    private final ItemService itemService;
    private final SecureRandom rng = new SecureRandom();

    public AbyssalService(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.pdcKey = new NamespacedKey(plugin, "abyssal");
    }

    public boolean hasAbyssal(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(pdcKey, PersistentDataType.STRING);
    }

    public void rollOptions(Player p, Inventory inv, Map<UUID, GuiManager.RollState> rollStates) {
        var msg = plugin.getConfigManager().messages();
        var gui = plugin.getConfigManager().gui();
        int targetSlot = gui.getInt("abyssal.layout.target_slot", 20);
        int boneSlot = gui.getInt("abyssal.layout.bone_slot", 24);
        ItemStack target = inv.getItem(targetSlot);
        ItemStack bone = inv.getItem(boneSlot);
        if (target == null || target.getType().isAir()) {
            sendMessage(p, "need_target", "&cInsert a valid target item.");
            return;
        }
        if (hasAbyssal(target)) {
            sendMessage(p, "already_modified", "&cThis item already has an Abyssal Modifier.");
            return;
        }
        if (bone == null || bone.getType().isAir()) {
            sendMessage(p, "need_bone", "&cInsert a matching bone.");
            return;
        }
        String boneKey = itemService.readKey(bone).orElse(null);
        SciCategory category = detectCategory(target, bone, boneKey);
        int tier = boneKey != null ? itemService.tierFromBoneKey(boneKey) : detectTier(bone);
        if (category == null || tier == 0) {
            sendMessage(p, "need_bone", "&cInsert a valid Abyssal Bone matching the category/tier.");
            return;
        }

        // Build weighted pools, with optional wildcards
        FileConfiguration mods = plugin.getConfigManager().modifiers();
        FileConfiguration cfg = plugin.getConfigManager().config();
        ConfigurationSection pools = mods.getConfigurationSection("modifiers." + category.name());
        if (pools == null) {
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy("&cNo modifiers pool for category: &f" + category));
            return;
        }
        List<String> catKeys = new ArrayList<>(pools.getKeys(false));
        if (catKeys.size() < 1) {
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy("&cNot enough modifiers to roll for category: &f" + category));
            return;
        }

        boolean allowWild = cfg.getConfigurationSection("roll_rules") != null && cfg.getConfigurationSection("roll_rules").getBoolean("allow_wildcards", true);
        ConfigurationSection wcTier = cfg.getConfigurationSection("roll_rules.wildcard_chance_by_tier");

        // Determine wildcard group key by category
        String wcGroup = null;
        if (category.name().startsWith("ACCESSORY_")) wcGroup = "WILDCARD_ACCESSORY";
        else if (category.name().startsWith("ARMOR_")) wcGroup = "WILDCARD_ARMOR";
        else if (category == SciCategory.WEAPON) wcGroup = "WILDCARD_WEAPON";
        ConfigurationSection wildPools = wcGroup != null ? mods.getConfigurationSection("wildcards." + wcGroup) : null;
        List<String> wcKeys = wildPools != null ? new ArrayList<>(wildPools.getKeys(false)) : Collections.emptyList();

        // Helper to choose from category or wildcard based on chance
        class Pick {
            String key;
            boolean fromWildcard;
        }
        java.util.function.Supplier<Pick> picker = () -> {
            Pick out = new Pick();
            boolean rollWild = false;
            if (allowWild && wildPools != null && !wcKeys.isEmpty() && wcTier != null) {
                double chance = wcTier.getDouble(String.valueOf(tier), 0.0);
                rollWild = rng.nextDouble() <= chance;
            }
            if (rollWild) {
                out.key = pickWeighted(wildPools, wcKeys);
                out.fromWildcard = true;
            } else {
                out.key = pickWeighted(pools, catKeys);
                out.fromWildcard = false;
            }
            return out;
        };

        Pick pa = picker.get();
        Pick pb;
        do { pb = picker.get(); } while (Objects.equals(pa.key, pb.key));

        GuiManager.RollState st = new GuiManager.RollState();
        st.category = category.name();
        st.tier = tier;
        st.keys[0] = pa.key;
        st.keys[1] = pb.key;

        ConfigurationSection icons = gui.getConfigurationSection("abyssal.icons");
        int infoASlot = gui.getInt("abyssal.layout.option_a_display", 29);
        int infoBSlot = gui.getInt("abyssal.layout.option_b_display", 33);
        int selectASlot = gui.getInt("abyssal.layout.select_a", 30);
        int selectBSlot = gui.getInt("abyssal.layout.select_b", 32);
        int rejectSlot = gui.getInt("abyssal.layout.reject", 40);

        boolean[] placed = new boolean[2];
        for (int i = 0; i < 2; i++) {
            String k = st.keys[i];
            boolean isWc = (i == 0 ? pa.fromWildcard : pb.fromWildcard);
            ConfigurationSection src = isWc ? wildPools.getConfigurationSection(k) : pools.getConfigurationSection(k);
            if (src == null) {
                continue;
            }
            ConfigurationSection tiers = src.getConfigurationSection("tiers");
            if (tiers == null) {
                continue;
            }
            st.min[i] = tiers.getDouble(tier + ".min");
            st.max[i] = tiers.getDouble(tier + ".max");
            int slot = (i == 0) ? infoASlot : infoBSlot;
            placeOptionIcon(inv, icons, src, k, tier, st.min[i], st.max[i], slot, i == 0 ? "A" : "B", isWc);
            placed[i] = true;
        }

        if (!placed[0] || !placed[1]) {
            sendMessage(p, "roll_failed", "&cUnable to build modifier previews. Check configuration.");
            return;
        }

        if (selectASlot >= 0 && selectASlot < inv.getSize()) {
            inv.setItem(selectASlot, configuredGuiItem(icons == null ? null : icons.getConfigurationSection("select_a"), "LIME_DYE", "&aAccept Option A"));
        }
        if (selectBSlot >= 0 && selectBSlot < inv.getSize()) {
            inv.setItem(selectBSlot, configuredGuiItem(icons == null ? null : icons.getConfigurationSection("select_b"), "LIGHT_BLUE_DYE", "&bAccept Option B"));
        }
        if (rejectSlot >= 0 && rejectSlot < inv.getSize()) {
            inv.setItem(rejectSlot, configuredGuiItem(icons == null ? null : icons.getConfigurationSection("reject"), "BARRIER", "&cDiscard"));
        }

        st.invRef = inv;
        rollStates.put(p.getUniqueId(), st);

        sendMessage(p, "roll_ready", "&aRolled two options. Choose A or B, or Reject.");
    }

    private void placeOptionIcon(Inventory inv,
                                 ConfigurationSection icons,
                                 ConfigurationSection src,
                                 String key,
                                 int tier,
                                 double min,
                                 double max,
                                 int slot,
                                 String label,
                                 boolean isWildcard) {
        if (slot < 0 || slot >= inv.getSize()) {
            return;
        }
        ItemStack it = configuredGuiItem(icons == null ? null : icons.getConfigurationSection("option_info"), "PAPER", "");
        ItemMeta meta = it.getItemMeta();
        // Fancy name: [ Tier ] Pretty Name  [Option X]
        Component name = pl.yourserver.scientistPlugin.util.Texts.tierTag(tier)
                .append(Component.text(" "))
                .append(Component.text(pl.yourserver.scientistPlugin.util.Texts.prettyKey(key))
                        .color(NamedTextColor.DARK_PURPLE)
                        .decorate(TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("  [Option " + label + "]").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.displayName(name);

        // Format range using tier's format if present
        List<Component> lore = new ArrayList<>();
        ConfigurationSection tiers = src.getConfigurationSection("tiers");
        ConfigurationSection tierSection = tiers != null ? tiers.getConfigurationSection(String.valueOf(tier)) : null;
        String fmt = tierSection != null ? tierSection.getString("format") : null;
        if (fmt == null && tiers != null) {
            fmt = tiers.getString(tier + ".format", fmt);
        }
        if (fmt == null && tiers != null) {
            ConfigurationSection baseTier = tiers.getConfigurationSection("1");
            if (baseTier != null) {
                fmt = baseTier.getString("format");
            }
        }
        String type = tierSection != null ? tierSection.getString("type") : null;
        if (type == null || type.isEmpty()) {
            type = tiers != null ? tiers.getString(tier + ".type") : null;
        }
        if ((type == null || type.isEmpty()) && src.contains("type")) {
            type = src.getString("type");
        }
        if (type != null && !type.isEmpty()) {
            lore.add(Component.text("Effect: " + pl.yourserver.scientistPlugin.util.Texts.prettyKey(type.toLowerCase(Locale.ROOT)))
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        String rangeStr;
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##");
        if (fmt != null && fmt.contains("{v}")) {
            String minStr = fmt.replace("{v}", df.format(min));
            String maxStr = fmt.replace("{v}", df.format(max));
            rangeStr = minStr + " – " + maxStr;
        } else {
            rangeStr = "+" + df.format(min) + " – +" + df.format(max);
        }
        lore.add(Component.text("Range: " + rangeStr).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (isWildcard) {
            lore.add(Component.text("Wildcard roll").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Use the button below to pick Option " + label)
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        inv.setItem(slot, it);
    }

    private ItemStack configuredGuiItem(ConfigurationSection sec, String fallbackMaterial, String fallbackName) {
        String materialName = sec != null ? sec.getString("material", fallbackMaterial) : fallbackMaterial;
        String display = sec != null ? sec.getString("name", fallbackName) : fallbackName;
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(materialName == null ? fallbackMaterial : materialName.toUpperCase(Locale.ROOT));
        if (mat == null) {
            mat = org.bukkit.Material.matchMaterial(fallbackMaterial);
        }
        if (mat == null) {
            mat = org.bukkit.Material.PAPER;
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (display != null && !display.isEmpty()) {
            meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(display));
        }
        List<String> loreLines = sec != null ? sec.getStringList("lore") : Collections.emptyList();
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy(line));
            }
            meta.lore(lore);
        }
        if (sec != null && sec.contains("model")) {
            meta.setCustomModelData(sec.getInt("model"));
        }
        it.setItemMeta(meta);
        return it;
    }

    public void applySelected(Player p, Map<UUID, GuiManager.RollState> rollStates, int choiceIdx) {
        GuiManager.RollState st = rollStates.get(p.getUniqueId());
        if (st == null) return;
        var gui = plugin.getConfigManager().gui();
        var msg = plugin.getConfigManager().messages();
        int targetSlot = gui.getInt("abyssal.layout.target_slot", 20);
        int boneSlot = gui.getInt("abyssal.layout.bone_slot", 24);
        Inventory invRef = st.invRef;
        ItemStack target = invRef.getItem(targetSlot);
        ItemStack bone = invRef.getItem(boneSlot);
        if (target == null || target.getType().isAir() || bone == null || bone.getType().isAir()) return;
        if (hasAbyssal(target)) {
            sendMessage(p, "already_modified", "&cThis item already has an Abyssal Modifier.");
            return;
        }

        String key = st.keys[choiceIdx];
        double min = st.min[choiceIdx];
        double max = st.max[choiceIdx];
        double val = round2(min + rng.nextDouble() * (max - min));

        // Set PDC
        ItemMeta meta = target.getItemMeta();
        JsonObject jo = new JsonObject();
        jo.addProperty("modifier_key", key);
        jo.addProperty("tier", st.tier);
        jo.addProperty("value", val);
        meta.getPersistentDataContainer().set(pdcKey, PersistentDataType.STRING, jo.toString());

        // Insert lore line above Rarity line
        List<Component> lore = meta.lore();
        if (lore == null) lore = new ArrayList<>();
        int rarityIdx = findRarityLine(lore);
        Component line = buildAbyssalLoreLine(key, st.tier, val, st.category);
        if (rarityIdx >= 0) lore.add(rarityIdx, line); else lore.add(line);
        meta.lore(lore);
        target.setItemMeta(meta);
        invRef.setItem(targetSlot, target);

        // Consume bone
        bone.setAmount(bone.getAmount() - 1);
        invRef.setItem(boneSlot, bone.getAmount() <= 0 ? null : bone);

        // Log DB
        logApplication(p, target, st.category, key, st.tier, min, max, val);

        String success = msg.getString("applied_success", "Applied {name}");
        success = success.replace("{name}", pl.yourserver.scientistPlugin.util.Texts.prettyKey(key))
                .replace("{tier}", String.valueOf(st.tier))
                .replace("{value}", String.format(Locale.US, "%.2f", val));
        sendMessage(p, null, success);
        p.closeInventory();
        rollStates.remove(p.getUniqueId());
    }

    private void logApplication(Player p, ItemStack item, String category, String key, int tier, double rolledMin, double rolledMax, double finalValue) {
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sci_abyssal_log (player_uuid,item_uid,category,modifier_key,tier,rolled_min,rolled_max,final_value) VALUES (UNHEX(REPLACE(?,'-','')),?,?,?,?,?,?,?)")) {
            ps.setString(1, p.getUniqueId().toString());
            ps.setString(2, makeItemUid(item));
            ps.setString(3, category);
            ps.setString(4, key);
            ps.setInt(5, tier);
            ps.setDouble(6, rolledMin);
            ps.setDouble(7, rolledMax);
            ps.setDouble(8, finalValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String makeItemUid(ItemStack is) {
        // Simple hash based on type + timestamp; servers can override with better UID system
        return is.getType().name() + ":" + Long.toHexString(Instant.now().toEpochMilli());
    }

    private int findRarityLine(List<Component> lore) {
        for (int i = 0; i < lore.size(); i++) {
            Component c = lore.get(i);
            String plain = (c instanceof TextComponent tc) ? tc.content() : c.toString();
            if (plain != null && plain.toLowerCase(Locale.ROOT).contains("rarity")) return i;
        }
        return -1;
    }

    private Component buildAbyssalLoreLine(String key, int tier, double value, String category) {
        // Build: bold white label, colored tier tag, bold colored name, gray suffix
        Component prefix = Component.text("Abyssal Modifier:")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);

        String tierText = switch (tier) { case 1 -> "[ I ]"; case 2 -> "[ II ]"; default -> "[ III ]"; };
        NamedTextColor tierColor = switch (tier) { case 1 -> NamedTextColor.BLUE; case 2 -> NamedTextColor.DARK_PURPLE; default -> NamedTextColor.GOLD; };
        Component tierC = Component.text(" ").append(Component.text(tierText).color(tierColor)).decoration(TextDecoration.ITALIC, false);

        // Name in bold purple (stylistic per spec example)
        Component nameC = Component.text(" ").append(Component.text(pl.yourserver.scientistPlugin.util.Texts.prettyKey(key))
                .color(NamedTextColor.DARK_PURPLE)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));

        Component valC = Component.text(" ").append(Component.text("(" + valueString(key, value) + ")").color(NamedTextColor.GRAY)).decoration(TextDecoration.ITALIC, false);
        return prefix.append(tierC).append(nameC).append(valC);
    }

    private String valueString(String key, double v) {
        // Try to format using modifiers.yml format if present
        var mods = plugin.getConfigManager().modifiers();
        // We don't know category here to fetch type-specific format; return generic + value.
        return "+" + String.format(Locale.US, "%.2f", v);
    }

    private String pickWeighted(ConfigurationSection pool, List<String> keys) {
        int total = 0;
        int[] weights = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            int w = pool.getConfigurationSection(keys.get(i)).getInt("weight", 10);
            weights[i] = w; total += w;
        }
        int r = rng.nextInt(Math.max(1, total));
        int acc = 0;
        for (int i = 0; i < keys.size(); i++) {
            acc += weights[i];
            if (r < acc) return keys.get(i);
        }
        return keys.get(keys.size()-1);
    }

    private SciCategory detectCategory(ItemStack target, ItemStack bone, String boneKey) {
        if (boneKey != null) {
            Optional<SciCategory> fromKey = itemService.categoryFromBoneKey(boneKey);
            if (fromKey.isPresent()) {
                return fromKey.get();
            }
        }

        // Try deducing from target type first (weapons/armor)
        var t = target.getType().name();
        if (t.endsWith("_SWORD") || t.endsWith("_AXE") || t.equals("BOW") || t.equals("CROSSBOW") || t.equals("TRIDENT")) {
            return SciCategory.WEAPON;
        }
        if (t.endsWith("_HELMET")) return SciCategory.ARMOR_HELM;
        if (t.endsWith("_CHESTPLATE")) return SciCategory.ARMOR_CHEST;
        if (t.endsWith("_LEGGINGS")) return SciCategory.ARMOR_LEGS;
        if (t.endsWith("_BOOTS")) return SciCategory.ARMOR_BOOTS;
        if (t.equals("SHIELD")) return SciCategory.ACCESSORY_SHIELD;

        // Fallback: parse bone name
        String dn = Optional.ofNullable(bone.getItemMeta()).map(ItemMeta::displayName).map(Component::toString).orElse("").toLowerCase(Locale.ROOT);
        if (dn.contains("weapon")) return SciCategory.WEAPON;
        if (dn.contains("helm")) return SciCategory.ARMOR_HELM;
        if (dn.contains("chest")) return SciCategory.ARMOR_CHEST;
        if (dn.contains("legs")) return SciCategory.ARMOR_LEGS;
        if (dn.contains("boots")) return SciCategory.ARMOR_BOOTS;
        if (dn.contains("ring 1") || dn.contains("ring1")) return SciCategory.ACCESSORY_RING1;
        if (dn.contains("ring 2") || dn.contains("ring2")) return SciCategory.ACCESSORY_RING2;
        if (dn.contains("necklace")) return SciCategory.ACCESSORY_NECKLACE;
        if (dn.contains("adornment")) return SciCategory.ACCESSORY_ADORNMENT;
        if (dn.contains("cloak")) return SciCategory.ACCESSORY_CLOAK;
        if (dn.contains("shield")) return SciCategory.ACCESSORY_SHIELD;
        if (dn.contains("belt")) return SciCategory.ACCESSORY_BELT;
        if (dn.contains("gloves")) return SciCategory.ACCESSORY_GLOVES;
        return null;
    }

    private int detectTier(ItemStack bone) {
        String name = Optional.ofNullable(bone.getItemMeta()).map(ItemMeta::displayName).map(Component::toString).orElse("");
        if (name.contains("[ I ]")) return 1;
        if (name.contains("[ II ]")) return 2;
        if (name.contains("[ III ]")) return 3;
        // Fallback by color or other markers could be added
        return 0;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private void sendMessage(Player p, String path, String defaultText) {
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        String text = path != null ? plugin.getConfigManager().messages().getString(path, defaultText) : defaultText;
        if (text == null) return;
        p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + text));
    }
}
