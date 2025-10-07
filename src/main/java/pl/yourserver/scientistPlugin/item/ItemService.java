package pl.yourserver.scientistPlugin.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.model.SciCategory;
import pl.yourserver.scientistPlugin.util.Texts;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Centralises interaction with external item providers (IngredientPouch, MythicMobs YAML) and
 * provides deterministic fallback items tagged with a persistent key so that other subsystems
 * can reliably recognise reagents and bones.
 */
public class ItemService {

    private final ScientistPlugin plugin;
    private final NamespacedKey mappingKey;
    private final Map<String, ItemStack> cache = new ConcurrentHashMap<>();
    private final Plugin ingredientPlugin;
    private final Object ingredientApi;
    private final Method ingredientGetItemMethod;
    private final Object ingredientMappings;
    private final Method ingredientResolveKeyMethod;
    private final Map<String, String> ingredientDisplayLookup;
    private final Object mythicItemManager;
    private final Method mythicGetItemStackMethod;
    private final Method mythicGetItemMethod;
    private final Method mythicBuildStackMethod;
    private final NamespacedKey[] mythicLookupKeys;

    public ItemService(ScientistPlugin plugin) {
        this.plugin = plugin;
        this.mappingKey = new NamespacedKey(plugin, "scientist_item_key");
        this.ingredientPlugin = plugin.getServer().getPluginManager().getPlugin("IngredientPouchPlugin");
        this.ingredientApi = resolveIngredientApi(ingredientPlugin);
        this.ingredientGetItemMethod = findMethod(ingredientApi, "getItem", String.class);
        this.ingredientMappings = resolveIngredientMappings(ingredientApi);
        this.ingredientResolveKeyMethod = findMappingsResolver(ingredientMappings);
        this.ingredientDisplayLookup = buildIngredientDisplayLookup(ingredientPlugin);
        this.mythicItemManager = resolveMythicItemManager();
        this.mythicGetItemStackMethod = findMethod(mythicItemManager, "getItemStack", String.class);
        this.mythicGetItemMethod = findMethod(mythicItemManager, "getItem", String.class);
        this.mythicBuildStackMethod = resolveMythicBuildMethod();
        this.mythicLookupKeys = buildMythicLookupKeys();
    }

    /**
     * Creates a tagged ItemStack for the supplied key. The method first attempts to pull the
     * canonical definition from IngredientPouchPlugin (if present) and falls back to a
     * well-formatted vanilla item when the API is unavailable.
     */
    public Optional<ItemStack> createItem(String key, int amount) {
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        ItemStack base = cache.computeIfAbsent(key, this::resolveFromProviders);
        if (base == null) {
            return Optional.empty();
        }
        ItemStack copy = base.clone();
        copy.setAmount(Math.max(1, amount));
        tagKey(copy, key);
        return Optional.of(copy);
    }

    public Optional<ItemStack> createItem(String key) {
        return createItem(key, 1);
    }

    private ItemStack resolveFromProviders(String key) {
        ItemStack viaPouch = fetchFromIngredientPouch(key);
        if (viaPouch != null && viaPouch.getType() != Material.AIR) {
            return decorateClone(viaPouch, key);
        }

        ItemStack viaMythic = fetchFromMythic(key);
        if (viaMythic != null && viaMythic.getType() != Material.AIR) {
            return decorateClone(viaMythic, key);
        }

        // As a final fallback we produce a minimally formatted placeholder so that
        // administrators notice misconfigured keys instead of silent failures.
        ItemStack placeholder = new ItemStack(Material.PAPER);
        ItemMeta meta = placeholder.getItemMeta();
        meta.displayName(Component.text("Missing mapping: " + key)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Configure ItemMappings for " + key)
                .decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        placeholder.setItemMeta(meta);
        return placeholder;
    }

    private ItemStack decorateClone(ItemStack original, String key) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null && meta.displayName() == null) {
            meta.displayName(Component.text(Texts.prettyKey(key)).decoration(TextDecoration.ITALIC, false));
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private void tagKey(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(mappingKey, PersistentDataType.STRING, key);
        item.setItemMeta(meta);
    }

    public Optional<String> readKey(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String key = meta.getPersistentDataContainer().get(mappingKey, PersistentDataType.STRING);
        if (key != null) {
            return Optional.of(key);
        }
        // IngredientPouch mapping lookup
        Optional<String> pouchKey = resolveIngredientKey(item);
        if (pouchKey.isPresent()) {
            return pouchKey;
        }

        // MythicMobs metadata lookup via PersistentDataContainer
        Optional<String> mythicKey = resolveMythicKey(item);
        if (mythicKey.isPresent()) {
            return mythicKey;
        }

        // Fallback: attempt to parse from display name
        Component name = meta.displayName();
        if (name != null) {
            String raw = name.toString();
            int idx = raw.indexOf("content=");
            if (idx >= 0) {
                String sub = raw.substring(idx + 8);
                int end = sub.indexOf(",");
                if (end > 0) {
                    return Optional.of(sub.substring(0, end).trim());
                }
            }
        }
        return Optional.empty();
    }

    public NamespacedKey getMappingKey() {
        return mappingKey;
    }

    public Optional<SciCategory> categoryFromBoneKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            String[] parts = key.toUpperCase(Locale.ROOT).split("ABYSS_BONE_");
            String suffix = parts.length > 1 ? parts[1] : key.toUpperCase(Locale.ROOT);
            int idx = suffix.lastIndexOf('_');
            if (idx > 0) {
                suffix = suffix.substring(0, idx);
            }
            return Optional.of(SciCategory.valueOf(suffix));
        } catch (IllegalArgumentException ignored) {
        }
        return Optional.empty();
    }

    public int tierFromBoneKey(String key) {
        if (key == null) {
            return 0;
        }
        if (key.endsWith("_III")) return 3;
        if (key.endsWith("_II")) return 2;
        if (key.endsWith("_I")) return 1;
        return 0;
    }

    private Object resolveIngredientApi(Plugin pouchPlugin) {
        if (pouchPlugin == null) {
            return null;
        }
        try {
            Method getApi = pouchPlugin.getClass().getMethod("getAPI");
            Object api = getApi.invoke(pouchPlugin);
            if (api != null) {
                return api;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object resolveIngredientMappings(Object api) {
        if (api == null) return null;
        try {
            Method method = api.getClass().getMethod("getItemMappings");
            return method.invoke(api);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Map<String, String> buildIngredientDisplayLookup(Plugin pouchPlugin) {
        if (pouchPlugin == null) {
            return Collections.emptyMap();
        }
        try {
            Method getItemManager = pouchPlugin.getClass().getMethod("getItemManager");
            Object manager = getItemManager.invoke(pouchPlugin);
            if (manager == null) {
                return Collections.emptyMap();
            }
            Method getIds = findMethod(manager, "getItemIds");
            Method getItem = findMethod(manager, "getItem", String.class);
            if (getIds == null || getItem == null) {
                return Collections.emptyMap();
            }
            Object idsObj = getIds.invoke(manager);
            if (!(idsObj instanceof Collection<?> ids)) {
                return Collections.emptyMap();
            }
            Map<String, String> lookup = new HashMap<>();
            for (Object idObj : ids) {
                String id = String.valueOf(idObj);
                Object stackObj = getItem.invoke(manager, id);
                if (stackObj instanceof ItemStack stack) {
                    String normalized = normalizeDisplay(stack.getItemMeta());
                    if (!normalized.isEmpty()) {
                        lookup.putIfAbsent(normalized, id);
                    }
                }
            }
            return lookup;
        } catch (Throwable ignored) {
        }
        return Collections.emptyMap();
    }

    private String normalizeDisplay(ItemMeta meta) {
        if (meta == null) {
            return "";
        }
        return normalizeDisplay(extractDisplay(meta));
    }

    private String normalizeDisplay(String display) {
        if (display == null) {
            return "";
        }
        String stripped = ChatColor.stripColor(display);
        if (stripped == null) {
            stripped = display;
        }
        stripped = stripped.trim();
        if (stripped.isEmpty()) {
            return "";
        }
        int multIndex = stripped.lastIndexOf(" x");
        if (multIndex > 0) {
            String suffix = stripped.substring(multIndex + 2).trim();
            if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                stripped = stripped.substring(0, multIndex).trim();
            }
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    private String extractDisplay(ItemMeta meta) {
        if (meta == null) {
            return "";
        }
        Component comp = meta.displayName();
        if (comp != null) {
            return PlainTextComponentSerializer.plainText().serialize(comp);
        }
        if (meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return "";
    }
    private Method findMappingsResolver(Object mappings) {
        if (mappings == null) return null;
        for (Method m : mappings.getClass().getMethods()) {
            if (m.getParameterCount() == 1 && ItemStack.class.isAssignableFrom(m.getParameterTypes()[0])) {
                Class<?> ret = m.getReturnType();
                if (String.class.equals(ret) || Optional.class.isAssignableFrom(ret)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Method findMethod(Object target, String name, Class<?>... parameterTypes) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name, parameterTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private ItemStack fetchFromIngredientPouch(String key) {
        if (ingredientApi == null || ingredientGetItemMethod == null) {
            return null;
        }
        try {
            Object res = ingredientGetItemMethod.invoke(ingredientApi, key);
            if (res instanceof ItemStack is) {
                return is;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object resolveMythicItemManager() {
        try {
            Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mythic.getMethod("inst").invoke(null);
            Method getItemManager = mythic.getMethod("getItemManager");
            return getItemManager.invoke(inst);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Method resolveMythicBuildMethod() {
        if (mythicGetItemMethod == null) {
            return null;
        }
        try {
            Class<?> returnType = mythicGetItemMethod.getReturnType();
            if (Optional.class.isAssignableFrom(returnType)) {
                return null; // We'll determine at runtime after unwrap
            }
            return findBuildMethod(returnType);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ItemStack fetchFromMythic(String key) {
        if (mythicItemManager == null) {
            return null;
        }
        try {
            if (mythicGetItemStackMethod != null) {
                Object res = mythicGetItemStackMethod.invoke(mythicItemManager, key);
                res = unwrapOptional(res);
                if (res instanceof ItemStack is) {
                    return is;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (mythicGetItemMethod != null) {
                Object mythicItem = mythicGetItemMethod.invoke(mythicItemManager, key);
                mythicItem = unwrapOptional(mythicItem);
                if (mythicItem != null) {
                    Method build = mythicBuildStackMethod;
                    if (build == null) {
                        build = findBuildMethod(mythicItem.getClass());
                    }
                    if (build != null) {
                        Object stack = build.invoke(mythicItem);
                        if (stack instanceof ItemStack is) {
                            return is;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Method findBuildMethod(Class<?> type) {
        if (type == null) return null;
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0 && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (name.contains("build") || name.contains("generate") || name.contains("create") || name.contains("getitem")) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Method findInternalNameMethod(Class<?> type) {
        if (type == null) return null;
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0 && String.class.equals(m.getReturnType())) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (name.contains("internal") || name.contains("name")) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Object unwrapOptional(Object res) {
        if (res instanceof Optional<?> opt) {
            return opt.orElse(null);
        }
        return res;
    }

    private NamespacedKey[] buildMythicLookupKeys() {
        List<NamespacedKey> keys = new ArrayList<>();
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:mythic_type"));
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:MYTHIC_TYPE"));
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:mythic_item_id"));
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:MYTHIC_ITEM_ID"));
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:key"));
        addIfPresent(keys, NamespacedKey.fromString("mythicmobs:item"));
        return keys.toArray(NamespacedKey[]::new);
    }

    private void addIfPresent(List<NamespacedKey> list, NamespacedKey key) {
        if (key != null) {
            list.add(key);
        }
    }

    private Optional<String> resolveIngredientKey(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        if (ingredientMappings != null && ingredientResolveKeyMethod != null) {
            try {
                Object res = ingredientResolveKeyMethod.invoke(ingredientMappings, item);
                if (res instanceof Optional<?> opt) {
                    Optional<String> mapped = opt.map(String::valueOf);
                    if (mapped.isPresent()) {
                        return mapped;
                    }
                } else if (res instanceof String str && !str.isEmpty()) {
                    return Optional.of(str);
                }
            } catch (Throwable ignored) {
            }
        }
        if (ingredientDisplayLookup != null && !ingredientDisplayLookup.isEmpty()) {
            String normalized = normalizeDisplay(item.getItemMeta());
            if (!normalized.isEmpty()) {
                String mapped = ingredientDisplayLookup.get(normalized);
                if (mapped != null && !mapped.isEmpty()) {
                    return Optional.of(mapped);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveMythicKey(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            for (NamespacedKey key : mythicLookupKeys) {
                if (key == null) continue;
                String value = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (value != null && !value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }

        if (mythicItemManager == null) {
            return Optional.empty();
        }

        for (String methodName : List.of("getMythicItem", "getItem", "getItemFromStack")) {
            try {
                Method lookup = mythicItemManager.getClass().getMethod(methodName, ItemStack.class);
                lookup.setAccessible(true);
                Object mythicItem = lookup.invoke(mythicItemManager, item);
                mythicItem = unwrapOptional(mythicItem);
                if (mythicItem != null) {
                    Method internal = findInternalNameMethod(mythicItem.getClass());
                    if (internal != null) {
                        Object internalName = internal.invoke(mythicItem);
                        if (internalName != null) {
                            return Optional.of(String.valueOf(internalName));
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }
}
