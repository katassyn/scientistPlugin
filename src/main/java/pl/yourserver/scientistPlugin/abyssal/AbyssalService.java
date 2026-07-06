package pl.yourserver.scientistPlugin.abyssal;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
    private static final Map<String, String> TYPE_DESCRIPTIONS = createTypeDescriptions();
    private static final Map<Material, SciCategory> ACCESSORY_MATERIAL_CATEGORY = createAccessoryMaterialMap();

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
        SciCategory boneCategory = detectCategoryFromBone(bone, boneKey);
        int computedTier = boneKey != null ? itemService.tierFromBoneKey(boneKey) : 0;
        if (computedTier == 0) {
            computedTier = detectTier(bone);
        }
        final int tierValue = computedTier;
        if (boneCategory == null || tierValue == 0) {
            sendMessage(p, "need_bone", "&cInsert a valid Abyssal Bone matching the category/tier.");
            return;
        }
        String targetKey = itemService.readKey(target).orElse(null);
        SciCategory targetCategory = detectCategoryFromTarget(target, targetKey);
        if (targetCategory != null && targetCategory != boneCategory) {
            sendMessage(p, "category_mismatch", "&cThat bone cannot be applied to this item.");
            return;
        }
        if (!isTargetCompatibleWithCategory(target, targetKey, boneCategory)) {
            sendMessage(p, "category_mismatch", "&cThat bone cannot be applied to this item.");
            return;
        }
        SciCategory category = boneCategory;

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
                double chance = wcTier.getDouble(String.valueOf(tierValue), 0.0);
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
        st.tier = tierValue;
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
            st.min[i] = tiers.getDouble(tierValue + ".min");
            st.max[i] = tiers.getDouble(tierValue + ".max");
            int slot = (i == 0) ? infoASlot : infoBSlot;
            placed[i] = placeOptionIcon(st, i, inv, icons, src, k, tierValue, st.min[i], st.max[i], slot, i == 0 ? "A" : "B", isWc);
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

    private boolean placeOptionIcon(GuiManager.RollState state,
                                    int index,
                                    Inventory inv,
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
            return false;
        }
        ItemStack it = configuredGuiItem(icons == null ? null : icons.getConfigurationSection("option_info"), "PAPER", "");
        ItemMeta meta = it.getItemMeta();
        Component name = pl.yourserver.scientistPlugin.util.Texts.tierTag(tier)
                .append(Component.text(" "))
                .append(Component.text(pl.yourserver.scientistPlugin.util.Texts.prettyKey(key))
                        .color(NamedTextColor.DARK_PURPLE)
                        .decorate(TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("  [Option " + label + "]").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.displayName(name);

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
            // Try direct path lookup within tiers (e.g., "2.type")
            type = tiers != null ? tiers.getString(tier + ".type") : null;
        }
        if ((type == null || type.isEmpty())) {
            // Fall back to base tier (1) if present
            if (tiers != null) {
                ConfigurationSection baseTier = tiers.getConfigurationSection("1");
                if (baseTier != null) {
                    type = baseTier.getString("type");
                }
                // As last resort, scan any tier that has an explicit type
                if (type == null || type.isEmpty()) {
                    for (String tk : tiers.getKeys(false)) {
                        ConfigurationSection tsec = tiers.getConfigurationSection(tk);
                        if (tsec != null && tsec.contains("type")) {
                            String cand = tsec.getString("type");
                            if (cand != null && !cand.isEmpty()) { type = cand; break; }
                        }
                    }
                }
            }
        }
        if ((type == null || type.isEmpty()) && src.contains("type")) {
            // Very rare: modifier-level type
            type = src.getString("type");
        }
        state.effectType[index] = type;
        String detailed = type != null && !type.isEmpty() ? describeType(type) : null;
        String primaryDesc = detailed;
        List<String> extraLines = new ArrayList<>();
        if (primaryDesc == null || primaryDesc.isBlank()) {
            primaryDesc = "Empowers this item";
        } else {
            String[] sentences = primaryDesc.split("(?<=\\.)\\s+");
            if (sentences.length > 0) {
                primaryDesc = sentences[0].trim();
                if (primaryDesc.endsWith(".")) {
                    primaryDesc = primaryDesc.substring(0, primaryDesc.length() - 1);
                }
                for (int i = 1; i < sentences.length; i++) {
                    String extra = sentences[i].trim();
                    if (!extra.isEmpty()) {
                        extraLines.add(extra);
                    }
                }
            } else {
                primaryDesc = primaryDesc.trim();
                if (primaryDesc.endsWith(".")) {
                    primaryDesc = primaryDesc.substring(0, primaryDesc.length() - 1);
                }
            }
        }
        if (primaryDesc.isEmpty()) {
            primaryDesc = "Empowers this item";
        }
        boolean percentRange = isPercent(fmt, type, key);
        String rangeDesc = sanitizeDescription(primaryDesc);
        if (rangeDesc.isEmpty()) {
            rangeDesc = primaryDesc;
        }
        lore.add(buildRangeDescription(rangeDesc, min, max, percentRange));
        for (String extra : extraLines) {
            lore.add(Component.text(extra)
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (isWildcard) {
            lore.add(Component.text("Wildcard roll").color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Use the button below to pick Option " + label)
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        it.setItemMeta(meta);
        inv.setItem(slot, it);
        return true;
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
        SciCategory categoryEnum;
        try {
            categoryEnum = SciCategory.valueOf(st.category);
        } catch (IllegalArgumentException | NullPointerException ex) {
            sendMessage(p, "category_mismatch", "&cThat bone cannot be applied to this item.");
            return;
        }
        String targetKey = itemService.readKey(target).orElse(null);
        if (!isTargetCompatibleWithCategory(target, targetKey, categoryEnum)) {
            sendMessage(p, "category_mismatch", "&cThat bone cannot be applied to this item.");
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
        Component line = buildAbyssalLoreLine(st.category, key, st.tier, val, st.effectType[choiceIdx]);
        if (rarityIdx >= 0) lore.add(rarityIdx, line); else lore.add(line);
        meta.lore(lore);
        target.setItemMeta(meta);
        invRef.setItem(targetSlot, null);
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(target);
        leftover.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));

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
        PlainTextComponentSerializer plainSerializer = PlainTextComponentSerializer.plainText();
        for (int i = 0; i < lore.size(); i++) {
            Component c = lore.get(i);
            String plain = plainSerializer.serialize(c);
            if (plain != null && plain.toLowerCase(Locale.ROOT).contains("rarity")) return i;
        }
        return -1;
    }

    private Component buildAbyssalLoreLine(String category, String key, int tier, double value, String effectType) {
        Component prefix = Component.text("ABYSSAL")
                .color(NamedTextColor.DARK_BLUE)
                .decoration(TextDecoration.ITALIC, false);
        Component tierComponent = pl.yourserver.scientistPlugin.util.Texts.tierTag(tier);
        String prettyName = pl.yourserver.scientistPlugin.util.Texts.prettyKey(key);
        Component nameComponent = Component.text(prettyName + " - ")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);

        Component descriptionComponent = buildDescriptionComponent(category, key, tier, value, effectType);

        return prefix
                .append(Component.text(" ").decoration(TextDecoration.ITALIC, false))
                .append(tierComponent)
                .append(Component.text(" ").decoration(TextDecoration.ITALIC, false))
                .append(nameComponent)
                .append(descriptionComponent);
    }

    private Component buildDescriptionComponent(String category, String key, int tier, double value, String effectType) {
        // If effectType was not captured earlier, attempt to resolve from config now
        String resolvedType = (effectType != null && !effectType.isEmpty()) ? effectType : findEffectType(category, key, tier);
        String desc = describeType(resolvedType);
        if (desc == null || desc.isEmpty()) {
            desc = "Empowers this item";
        }
        boolean hadPeriod = desc.trim().endsWith(".");
        String trimmed = desc.trim();
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String sanitized = sanitizeDescription(trimmed);
        String lower = sanitized.toLowerCase(Locale.ROOT);
        String connector = chooseConnector(lower);

        String pattern = findFormatPattern(category, key, tier);
        boolean percent = isPercent(pattern, resolvedType, key);
        String valueText = formatSingleValue(value);

        String beforeValue;
        if (connector != null) {
            if (sanitized.endsWith(connector.trim())) {
                beforeValue = sanitized + " ";
            } else {
                beforeValue = sanitized + connector;
            }
        } else if (sanitized.endsWith(":") || sanitized.endsWith("-") || sanitized.endsWith("–")) {
            beforeValue = sanitized + " ";
        } else {
            beforeValue = sanitized + ": ";
        }

        Component base = Component.text(beforeValue)
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component valueComponent = Component.text(valueText)
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false);

        Component result = base.append(valueComponent);
        if (percent) {
            result = result.append(Component.text("%")
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (hadPeriod) {
            result = result.append(Component.text(".")
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    private String findEffectType(String category, String key, int tier) {
        var mods = plugin.getConfigManager().modifiers();
        if (mods == null) return null;
        // Try declared category first
        String t = typeFromPool(mods.getConfigurationSection("modifiers." + category), key, tier);
        if (t != null && !t.isEmpty()) return t;
        // Search all modifier categories
        ConfigurationSection modifiersRoot = mods.getConfigurationSection("modifiers");
        if (modifiersRoot != null) {
            for (String cat : modifiersRoot.getKeys(false)) {
                t = typeFromPool(modifiersRoot.getConfigurationSection(cat), key, tier);
                if (t != null && !t.isEmpty()) return t;
            }
        }
        // Search wildcards
        ConfigurationSection wildRoot = mods.getConfigurationSection("wildcards");
        if (wildRoot != null) {
            for (String group : wildRoot.getKeys(false)) {
                t = typeFromPool(wildRoot.getConfigurationSection(group), key, tier);
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    private String typeFromPool(ConfigurationSection pool, String key, int tier) {
        if (pool == null) return null;
        ConfigurationSection modifier = pool.getConfigurationSection(key);
        if (modifier == null) return null;
        ConfigurationSection tiers = modifier.getConfigurationSection("tiers");
        if (tiers == null) return null;
        String type = null;
        ConfigurationSection tierSection = tiers.getConfigurationSection(String.valueOf(tier));
        if (tierSection != null) type = tierSection.getString("type");
        if (type == null) type = tiers.getString(tier + ".type");
        if (type == null) {
            ConfigurationSection base = tiers.getConfigurationSection("1");
            if (base != null) type = base.getString("type");
        }
        if (type == null) {
            for (String tk : tiers.getKeys(false)) {
                ConfigurationSection ts = tiers.getConfigurationSection(tk);
                if (ts != null && ts.contains("type")) {
                    type = ts.getString("type");
                    if (type != null) break;
                }
            }
        }
        if (type == null && modifier.contains("type")) type = modifier.getString("type");
        return type;
    }

    private Component buildRangeDescription(String description, double min, double max, boolean percent) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##");
        Component base = Component.text(description + " (")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component minComponent = Component.text(df.format(min))
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false);
        Component separator = Component.text(" - ")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component maxComponent = Component.text(df.format(max))
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false);
        Component closing = Component.text(")")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component result = base.append(minComponent)
                .append(separator)
                .append(maxComponent)
                .append(closing);
        if (percent) {
            result = result.append(Component.text("%")
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    private String formatSingleValue(double value) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##");
        return df.format(value);
    }

    private String sanitizeDescription(String text) {
        String sanitized = text;
        sanitized = sanitized.replaceAll("(?i) by a flat amount(?: of)?", " by");
        sanitized = sanitized.replaceAll("(?i) by up to a flat amount", " by up to");
        sanitized = sanitized.replaceAll("(?i) equal to a flat amount", " equal to");
        sanitized = sanitized.replaceAll("(?i) for a flat amount", " for");
        sanitized = sanitized.replaceAll("(?i) bonus effects", " bonus");
        sanitized = sanitized.trim();
        return sanitized;
    }

    private String chooseConnector(String lower) {
        if (lower.startsWith("chance to") || lower.startsWith("chance for") || lower.startsWith("chance ")) {
            return " at ";
        }
        if (lower.startsWith("restores") || lower.startsWith("heals")) {
            return " for ";
        }
        if (lower.startsWith("ignores")) {
            return " by ";
        }
        if (lower.startsWith("boosts") || lower.startsWith("builds") || lower.startsWith("increases")
                || lower.startsWith("reduces") || lower.startsWith("improves") || lower.startsWith("strengthens")
                || lower.startsWith("shortens") || lower.startsWith("expands") || lower.startsWith("speeds")
                || lower.startsWith("amplifies") || lower.startsWith("raises")) {
            return " by ";
        }
        return null;
    }

    private String findFormatPattern(String category, String key, int tier) {
        var mods = plugin.getConfigManager().modifiers();
        if (mods == null) {
            return null;
        }
        String pattern = patternFromPool(mods.getConfigurationSection("modifiers." + category), key, tier);
        if (pattern != null) return pattern;
        ConfigurationSection modifiersRoot = mods.getConfigurationSection("modifiers");
        if (modifiersRoot != null) {
            for (String cat : modifiersRoot.getKeys(false)) {
                pattern = patternFromPool(modifiersRoot.getConfigurationSection(cat), key, tier);
                if (pattern != null) {
                    return pattern;
                }
            }
        }
        ConfigurationSection wildRoot = mods.getConfigurationSection("wildcards");
        if (wildRoot != null) {
            for (String group : wildRoot.getKeys(false)) {
                pattern = patternFromPool(wildRoot.getConfigurationSection(group), key, tier);
                if (pattern != null) {
                    return pattern;
                }
            }
        }
        return null;
    }

    private String patternFromPool(ConfigurationSection pool, String key, int tier) {
        if (pool == null) {
            return null;
        }
        ConfigurationSection modifier = pool.getConfigurationSection(key);
        if (modifier == null) {
            return null;
        }
        ConfigurationSection tiers = modifier.getConfigurationSection("tiers");
        if (tiers == null) {
            return null;
        }
        String fmt = null;
        ConfigurationSection tierSection = tiers.getConfigurationSection(String.valueOf(tier));
        if (tierSection != null) {
            fmt = tierSection.getString("format");
        }
        if (fmt == null) {
            fmt = tiers.getString(tier + ".format");
        }
        if (fmt == null) {
            ConfigurationSection base = tiers.getConfigurationSection("1");
            if (base != null) {
                fmt = base.getString("format");
            }
        }
        return fmt;
    }

    private boolean isPercent(String pattern, String effectType, String key) {
        if (pattern != null && pattern.contains("%")) {
            return true;
        }
        if (effectType != null) {
            String upper = effectType.toUpperCase(Locale.ROOT);
            if (upper.endsWith("_PCT") || upper.contains("CHANCE") || upper.contains("PERCENT")) {
                return true;
            }
        }
        if (key != null) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.contains("PCT") || upper.contains("PERCENT") || upper.contains("CHANCE")) {
                return true;
            }
        }
        return false;
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

    private static Map<Material, SciCategory> createAccessoryMaterialMap() {
        Map<Material, SciCategory> map = new EnumMap<>(Material.class);
        map.put(Material.TNT_MINECART, SciCategory.ACCESSORY_RING1);
        map.put(Material.HOPPER_MINECART, SciCategory.ACCESSORY_RING2);
        map.put(Material.CHEST_MINECART, SciCategory.ACCESSORY_NECKLACE);
        map.put(Material.FURNACE_MINECART, SciCategory.ACCESSORY_ADORNMENT);
        map.put(Material.WHITE_BANNER, SciCategory.ACCESSORY_CLOAK);
        map.put(Material.IRON_DOOR, SciCategory.ACCESSORY_SHIELD);
        map.put(Material.SHIELD, SciCategory.ACCESSORY_SHIELD);
        map.put(Material.MINECART, SciCategory.ACCESSORY_BELT);
        map.put(Material.LEATHER_HORSE_ARMOR, SciCategory.ACCESSORY_GLOVES);
        return Collections.unmodifiableMap(map);
    }

    private SciCategory detectCategoryFromBone(ItemStack bone, String boneKey) {
        if (boneKey != null) {
            Optional<SciCategory> fromKey = itemService.categoryFromBoneKey(boneKey);
            if (fromKey.isPresent()) {
                return fromKey.get();
            }
        }
        return detectCategoryFromText(displayNameLower(bone));
    }

    private SciCategory detectCategoryFromTarget(ItemStack target, String targetKey) {
        if (target == null || target.getType().isAir()) {
            return null;
        }
        Material type = target.getType();
        if (isWeaponMaterial(type)) {
            return SciCategory.WEAPON;
        }
        String typeName = type.name();
        if (typeName.endsWith("_HELMET")) return SciCategory.ARMOR_HELM;
        if (typeName.endsWith("_CHESTPLATE")) return SciCategory.ARMOR_CHEST;
        if (typeName.endsWith("_LEGGINGS") || typeName.endsWith("_PANTS")) return SciCategory.ARMOR_LEGS;
        if (typeName.endsWith("_BOOTS")) return SciCategory.ARMOR_BOOTS;

        SciCategory fromMaterial = ACCESSORY_MATERIAL_CATEGORY.get(type);
        if (fromMaterial != null) {
            return fromMaterial;
        }

        SciCategory fromKey = parseCategoryFromItemKey(targetKey);
        if (fromKey != null) {
            return fromKey;
        }

        return detectCategoryFromText(displayNameLower(target));
    }

    private SciCategory parseCategoryFromItemKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String upper = key.toUpperCase(Locale.ROOT);
        if (matchesAny(upper, "ACCESSORY_RING_1", "ACCESSORY_RING1", "RING_1")) return SciCategory.ACCESSORY_RING1;
        if (matchesAny(upper, "ACCESSORY_RING_2", "ACCESSORY_RING2", "RING_2")) return SciCategory.ACCESSORY_RING2;
        if (matchesAny(upper, "ACCESSORY_NECKLACE", "NECKLACE", "AMULET")) return SciCategory.ACCESSORY_NECKLACE;
        if (matchesAny(upper, "ACCESSORY_ADORNMENT", "ADORNMENT", "BROOCH")) return SciCategory.ACCESSORY_ADORNMENT;
        if (matchesAny(upper, "ACCESSORY_CLOAK", "CLOAK", "CAPE", "MANTLE")) return SciCategory.ACCESSORY_CLOAK;
        if (matchesAny(upper, "ACCESSORY_SHIELD", "SHIELD")) return SciCategory.ACCESSORY_SHIELD;
        if (matchesAny(upper, "ACCESSORY_BELT", "BELT", "GIRDLE")) return SciCategory.ACCESSORY_BELT;
        if (matchesAny(upper, "ACCESSORY_GLOVES", "GLOVES", "GAUNTLET")) return SciCategory.ACCESSORY_GLOVES;
        if (matchesAny(upper, "ARMOR_HELM", "HELMET", "HELM")) return SciCategory.ARMOR_HELM;
        if (matchesAny(upper, "ARMOR_CHEST", "CHEST", "CHESTPLATE", "CUIRASS", "BREASTPLATE")) return SciCategory.ARMOR_CHEST;
        if (matchesAny(upper, "ARMOR_LEGS", "LEGS", "LEGGINGS", "PANTS", "GREAVES")) return SciCategory.ARMOR_LEGS;
        if (matchesAny(upper, "ARMOR_BOOTS", "BOOTS", "SABATON")) return SciCategory.ARMOR_BOOTS;
        if (matchesAny(upper, "WEAPON", "SWORD", "AXE", "BOW", "CROSSBOW", "TRIDENT", "MACE")) return SciCategory.WEAPON;
        return null;
    }

    private SciCategory detectCategoryFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (matchesAny(lower, "weapon", "sword", "axe", "bow", "crossbow", "trident", "mace")) return SciCategory.WEAPON;
        if (matchesAny(lower, "helm", "helmet")) return SciCategory.ARMOR_HELM;
        if (matchesAny(lower, "chest", "breastplate", "cuirass")) return SciCategory.ARMOR_CHEST;
        if (matchesAny(lower, "legs", "leggings", "pants", "greaves")) return SciCategory.ARMOR_LEGS;
        if (matchesAny(lower, "boots", "sabaton", "shoes")) return SciCategory.ARMOR_BOOTS;
        if (matchesAny(lower, "ring 1", "ring1")) return SciCategory.ACCESSORY_RING1;
        if (matchesAny(lower, "ring 2", "ring2")) return SciCategory.ACCESSORY_RING2;
        if (matchesAny(lower, "necklace", "amulet")) return SciCategory.ACCESSORY_NECKLACE;
        if (matchesAny(lower, "adornment", "brooch")) return SciCategory.ACCESSORY_ADORNMENT;
        if (matchesAny(lower, "cloak", "cape", "mantle")) return SciCategory.ACCESSORY_CLOAK;
        if (matchesAny(lower, "shield")) return SciCategory.ACCESSORY_SHIELD;
        if (matchesAny(lower, "belt", "girdle")) return SciCategory.ACCESSORY_BELT;
        if (matchesAny(lower, "glove", "gloves", "gauntlet")) return SciCategory.ACCESSORY_GLOVES;
        return null;
    }

    private boolean isWeaponMaterial(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_SCYTHE")) {
            return true;
        }
        return "BOW".equals(name) || "CROSSBOW".equals(name) || "TRIDENT".equals(name) || "MACE".equals(name);
    }

    private boolean isTargetCompatibleWithCategory(ItemStack target, String targetKey, SciCategory category) {
        if (target == null || category == null) {
            return false;
        }
        Material type = target.getType();
        String upperKey = targetKey != null ? targetKey.toUpperCase(Locale.ROOT) : "";
        String lowerName = displayNameLower(target);
        return switch (category) {
            case WEAPON -> isWeaponMaterial(type)
                    || matchesAny(upperKey, "WEAPON", "_SWORD", "_AXE", "BOW", "CROSSBOW", "TRIDENT", "MACE")
                    || matchesAny(lowerName, "weapon", "sword", "axe", "bow", "crossbow", "trident", "mace");
            case ARMOR_HELM -> (type != null && type.name().endsWith("_HELMET"))
                    || matchesAny(upperKey, "ARMOR_HELM", "HELMET", "HELM")
                    || matchesAny(lowerName, "helm", "helmet");
            case ARMOR_CHEST -> (type != null && type.name().endsWith("_CHESTPLATE"))
                    || matchesAny(upperKey, "ARMOR_CHEST", "CHEST", "CHESTPLATE", "CUIRASS", "BREASTPLATE")
                    || matchesAny(lowerName, "chest", "breastplate", "cuirass");
            case ARMOR_LEGS -> (type != null && (type.name().endsWith("_LEGGINGS") || type.name().endsWith("_PANTS")))
                    || matchesAny(upperKey, "ARMOR_LEGS", "LEGGINGS", "LEGS", "PANTS", "GREAVES")
                    || matchesAny(lowerName, "legs", "leggings", "pants", "greaves");
            case ARMOR_BOOTS -> (type != null && type.name().endsWith("_BOOTS"))
                    || matchesAny(upperKey, "ARMOR_BOOTS", "BOOTS", "SABATON")
                    || matchesAny(lowerName, "boots", "sabaton", "shoes");
            case ACCESSORY_RING1 -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_RING1)
                    || matchesAny(upperKey, "ACCESSORY_RING_1", "ACCESSORY_RING1", "RING_1")
                    || matchesAny(lowerName, "ring 1", "ring1");
            case ACCESSORY_RING2 -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_RING2)
                    || matchesAny(upperKey, "ACCESSORY_RING_2", "ACCESSORY_RING2", "RING_2")
                    || matchesAny(lowerName, "ring 2", "ring2");
            case ACCESSORY_NECKLACE -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_NECKLACE)
                    || matchesAny(upperKey, "ACCESSORY_NECKLACE", "NECKLACE", "AMULET")
                    || matchesAny(lowerName, "necklace", "amulet");
            case ACCESSORY_ADORNMENT -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_ADORNMENT)
                    || matchesAny(upperKey, "ACCESSORY_ADORNMENT", "ADORNMENT", "BROOCH")
                    || matchesAny(lowerName, "adornment", "brooch");
            case ACCESSORY_CLOAK -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_CLOAK)
                    || matchesAny(upperKey, "ACCESSORY_CLOAK", "CLOAK", "CAPE", "MANTLE")
                    || matchesAny(lowerName, "cloak", "cape", "mantle");
            case ACCESSORY_SHIELD -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_SHIELD)
                    || matchesAny(upperKey, "ACCESSORY_SHIELD", "SHIELD")
                    || matchesAny(lowerName, "shield");
            case ACCESSORY_BELT -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_BELT)
                    || matchesAny(upperKey, "ACCESSORY_BELT", "BELT", "GIRDLE")
                    || matchesAny(lowerName, "belt", "girdle");
            case ACCESSORY_GLOVES -> matchesAccessoryMaterial(type, SciCategory.ACCESSORY_GLOVES)
                    || matchesAny(upperKey, "ACCESSORY_GLOVES", "GLOVES", "GAUNTLET")
                    || matchesAny(lowerName, "glove", "gloves", "gauntlet");
        };
    }

    private boolean matchesAccessoryMaterial(Material type, SciCategory expected) {
        SciCategory fromMaterial = ACCESSORY_MATERIAL_CATEGORY.get(type);
        return fromMaterial != null && fromMaterial == expected;
    }

    private boolean matchesAny(String source, String... tokens) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && source.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String displayNameLower(ItemStack item) {
        if (item == null) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        Component comp = meta.displayName();
        if (comp != null) {
            String plain = PlainTextComponentSerializer.plainText().serialize(comp);
            if (plain != null && !plain.isEmpty()) {
                return plain.toLowerCase(Locale.ROOT);
            }
        }
        try {
            if (meta.hasDisplayName()) {
                String legacy = meta.getDisplayName();
                if (legacy != null && !legacy.isEmpty()) {
                    String stripped = ChatColor.stripColor(legacy);
                    String src = stripped != null && !stripped.isEmpty() ? stripped : legacy;
                    return src.toLowerCase(Locale.ROOT);
                }
            }
        } catch (NoSuchMethodError ignored) { }
        return "";
    }

    private int detectTier(ItemStack bone) {
        String name = displayNameLower(bone);
        if (name.contains("[ i ]") || name.contains(" tier i")) return 1;
        if (name.contains("[ ii ]") || name.contains(" tier ii")) return 2;
        if (name.contains("[ iii ]") || name.contains(" tier iii")) return 3;
        return 0;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private void sendMessage(Player p, String path, String defaultText) {
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        String text = path != null ? plugin.getConfigManager().messages().getString(path, defaultText) : defaultText;
        if (text == null) return;
        p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + text));
    }

    private static Map<String, String> createTypeDescriptions() {
        Map<String, String> map = new HashMap<>();
        // Stealth & Detection
        map.put("AGGRO_RADIUS_REDUCE_PCT", "Reduces enemy detection range, making you harder to spot");
        map.put("CAMOUFLAGE_STRENGTH_PCT", "Enhances stealth capabilities and invisibility effects");
        map.put("DECOY_CHANCE_PCT", "Creates a vanishing illusion when hit, briefly confusing enemies");

        // Armor Penetration & Damage
        map.put("ARMOR_PEN_FLAT", "Ignores a flat amount of enemy armor with each strike");
        map.put("ARMOR_PEN_PCT", "Pierces through a percentage of enemy armor, devastating against tanks");
        map.put("DAMAGE_PCT", "Increases all damage output from your attacks");
        map.put("DMG_VS_ELITES_PCT", "Deals additional damage to elite and boss enemies");
        map.put("DMG_VS_MOBS_PCT", "Increases damage against normal monsters");
        map.put("LOW_HP_DMG_PCT", "Unleashes greater damage when fighting at low health");
        map.put("LOW_ENEMY_HP_DMG_PCT", "Executes wounded enemies with increased damage");
        map.put("FIRST_HIT_DMG_PCT", "Delivers a devastating opening strike when engaging enemies");
        map.put("NEXT_ATTACK_DMG_PCT", "Empowers your next attack after blocking");
        map.put("MELEE_DMG_FLAT", "Adds raw melee damage to close-range attacks");

        // Attack Speed & Criticals
        map.put("ATTACK_SPEED_PCT", "Strikes faster, increasing attack frequency");
        map.put("CRIT_CHANCE_PCT", "Raises the chance to land critical strikes");
        map.put("CRIT_DMG_PCT", "Amplifies the damage of critical hits");

        // Lifesteal & Healing
        map.put("LIFESTEAL_PCT", "Drains life from enemies with each hit");
        map.put("LIFESTEAL_BOOST_PCT", "Amplifies all lifesteal effects on you");
        map.put("LIFESTEAL_ON_CRIT_PCT", "Critical strikes siphon health from your target");
        map.put("HEAL_AFTER_BLOCK", "Restores health when successfully blocking attacks");
        map.put("HEAL_ON_KILL", "Grants health regeneration upon slaying enemies");
        map.put("HEALING_DEBUFF_PCT", "Cripples enemy healing and regeneration");

        // Defense & Damage Reduction
        map.put("DR_PCT", "Reduces all incoming damage");
        map.put("LOW_HP_DR_PCT", "Hardens defenses when health drops dangerously low");
        map.put("DR_WHILE_BLOCKING_PCT", "Fortifies your guard while blocking");
        map.put("FLAT_DMG_REDUCE", "Negates a fixed amount of damage from every hit");
        map.put("RANGED_DR_PCT", "Shields against ranged and projectile attacks");
        map.put("PROJECTILE_DR_PCT", "Deflects projectile damage");
        map.put("EXPLOSION_DR_PCT", "Resists explosive and area damage");
        map.put("MAGIC_DR_PCT", "Wards against magical damage");
        map.put("PARTY_DAMAGE_REDUCTION_PCT", "Projects a protective aura, shielding nearby allies");

        // Evasion & Blocking
        map.put("EVADE_CHANCE_PCT", "Grants agility to dodge attacks completely");
        map.put("PROJECTILE_DODGE_PCT", "Sidesteps incoming projectiles");
        map.put("BLOCK_CHANCE_PCT", "Improves your ability to block melee strikes");
        map.put("BLOCK_STUN_CHANCE_PCT", "Stuns attackers when blocking their assault");

        // Crowd Control
        map.put("SLOW_CHANCE_PCT", "Hinders enemy movement with chilling strikes");
        map.put("SLOW_AURA_PCT", "Emanates a freezing aura that slows nearby foes");
        map.put("BLEED_CHANCE_PCT", "Inflicts bleeding wounds that damage over time");
        map.put("BURN_DOT", "Sets enemies ablaze with fire damage");
        map.put("SILENCE_CHANCE_PCT", "Prevents enemies from casting abilities");
        map.put("SLEEP_CHANCE_PCT", "Weakens and disorients struck enemies");
        map.put("MARK_CHANCE_PCT", "Marks targets, making them vulnerable to increased damage");
        map.put("KNOCKBACK_REDUCE_PCT", "Reduces enemy knockback on your attacks");
        map.put("KNOCKBACK_TAKEN_REDUCE_PCT", "Anchors you against knockback effects");

        // Special Attack Effects
        map.put("ECHO_CHANCE_PCT", "Repeats your attack as an echoing strike");
        map.put("CHAIN_LIGHTNING_CHANCE", "Unleashes chain lightning that arcs between enemies");
        map.put("STACKING_DMG_PER_HIT_PCT", "Builds momentum with each consecutive hit");
        map.put("STOMP_DMG", "Creates a damaging shockwave upon landing");
        map.put("FIRE_TRAIL_CHANCE_PCT", "Leaves a blazing trail that burns pursuers");
        map.put("RETURN_DMG_PCT", "Reflects damage back to attackers");

        // Movement & Mobility
        map.put("MOVE_SPEED_PCT", "Increases overall movement speed");
        map.put("OOC_SPEED_PCT", "Hastens travel speed when out of combat");
        map.put("KILL_SPEED_PCT", "Surges with speed after defeating enemies");
        map.put("SPEED_AFTER_BLOCK_PCT", "Gains momentum after blocking");
        map.put("KEEP_SPRINT_CHANCE_PCT", "Maintains sprinting momentum in combat");
        map.put("WATER_SPEED_BOOST", "Grants aquatic grace, enhancing underwater movement");
        map.put("SLOW_RESIST_PCT", "Resists slowing effects");

        // Utility & Cooldowns
        map.put("CDR_PCT", "Reduces ability cooldowns");
        map.put("MAX_HEALTH_PCT", "Increases maximum health capacity");
        map.put("MAX_HEALTH_FLAT", "Grants additional maximum health");
        map.put("HASTE_LEVEL", "Provides Haste effect for faster actions");
        map.put("LUCK_ADD", "Increases fortune, improving rare loot chances");
        map.put("COMBAT_REGEN_PCT", "Regenerates health even during combat");

        // Status Resistance
        map.put("CURSE_RESIST_PCT", "Shortens debuff duration and curse effects");
        map.put("NEG_EFFECT_DURATION_REDUCE_PCT", "Reduces the duration of negative status effects");
        map.put("CLEANSE_CHANCE_PCT", "Purges a debuff when taking damage");

        // Support & Aura
        map.put("AURA_POTENCY_PCT", "Strengthens aura effects you emit");
        map.put("PARTY_BUFF_RADIUS_PCT", "Extends the range of your party buffs");
        map.put("PROJECTILE_SPEED_PCT", "Accelerates your projectiles");
        map.put("SELF_SHIELD_HP", "Grants a protective absorption shield");

        return map;
    }

    private String describeType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        String desc = TYPE_DESCRIPTIONS.get(type);
        if (desc != null) {
            return desc;
        }
        String pretty = pl.yourserver.scientistPlugin.util.Texts.prettyKey(type.toLowerCase(Locale.ROOT));
        return "Provides bonus: " + pretty;
    }
}
