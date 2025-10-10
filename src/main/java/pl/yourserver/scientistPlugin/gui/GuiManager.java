package pl.yourserver.scientistPlugin.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;
import pl.yourserver.scientistPlugin.research.ResearchService;

import java.time.Instant;
import java.util.*;

public class GuiManager implements Listener {
    private final ScientistPlugin plugin;
    private final ItemService itemService;
    private final Map<UUID, String> openGui = new HashMap<>();
    private final Map<UUID, Inventory> activeInventories = new HashMap<>();
    private final Map<UUID, RollState> rollStates = new HashMap<>();
    private final Map<UUID, Integer> pendingChoice = new HashMap<>();
    private final Map<UUID, String> selectedResearch = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> researchSlotMap = new HashMap<>();
    private final Map<UUID, Integer> researchPage = new HashMap<>();
    private final Map<UUID, Integer> researchUpdateTasks = new HashMap<>();
    private static final int[] DEFAULT_TEMPLATE_SLOTS = new int[]{
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    public GuiManager(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
    }

    private void presentInventory(Player player, String context, Inventory inventory) {
        UUID id = player.getUniqueId();
        activeInventories.put(id, inventory);
        player.openInventory(inventory);
        openGui.put(id, context);
    }

    private void presentInventoryLater(Player player, String context, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> presentInventory(player, context, inventory));
    }

    public void openMain(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection mainSec = gui.getConfigurationSection("main");
        String title = mainSec.getString("title", "Scientist Lab");
        int size = mainSec.getInt("size", 27);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));

        ConfigurationSection layout = mainSec.getConfigurationSection("layout");
        ConfigurationSection icons = mainSec.getConfigurationSection("icons");
        Set<Integer> reserved = new HashSet<>();
        int researchSlot = layout.getInt("research_slot", 11);
        int craftingSlot = layout.contains("crafting_slot")
                ? layout.getInt("crafting_slot")
                : layout.getInt("recipe_slot", 13);
        int abyssalSlot = layout.getInt("abyssal_slot", 15);
        int helpSlot = layout.getInt("help_slot", 26);
        reserved.add(researchSlot);
        reserved.add(craftingSlot);
        reserved.add(abyssalSlot);
        reserved.add(helpSlot);
        reserved.addAll(collectDecorSlots(mainSec));
        applyBackground(inv, mainSec, reserved);
        applyDecor(inv, mainSec);

        inv.setItem(researchSlot, configuredItem(iconSection(icons, "research"), "BREWING_STAND", "&aResearch Lab"));
        inv.setItem(craftingSlot, configuredItem(iconSection(icons, "crafting", "recipe"), "BOOK", "&eScientist Crafting"));
        inv.setItem(abyssalSlot, configuredItem(iconSection(icons, "abyssal"), "ENCHANTING_TABLE", "&5Abyssal Enchanting"));
        inv.setItem(helpSlot, configuredItem(iconSection(icons, "help"), "KNOWLEDGE_BOOK", "&7Help"));

        presentInventory(p, "main", inv);
        sendMessage(p, "open_main", "&aOpening Scientist Lab...");
    }

    public void openResearch(Player p) {
        renderResearch(p, true);
    }

    private void renderResearch(Player p, boolean scheduleUpdate) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection researchSec = gui.getConfigurationSection("research");
        if (researchSec == null) {
            return;
        }
        String title = researchSec.getString("title", "Research Lab");
        int size = researchSec.getInt("size", 45);

        UUID uuid = p.getUniqueId();
        Inventory tracked = activeInventories.get(uuid);
        boolean reuse = tracked != null
                && "research".equals(openGui.get(uuid))
                && p.getOpenInventory().getTopInventory().equals(tracked)
                && tracked.getSize() == size;
        Inventory inv;
        if (reuse) {
            inv = tracked;
            inv.clear();
        } else {
            inv = Bukkit.createInventory(p, size, Component.text(title));
        }

        ConfigurationSection layout = researchSec.getConfigurationSection("layout");
        if (layout == null) {
            if (!reuse) {
                presentInventory(p, "research", inv);
            } else {
                openGui.put(uuid, "research");
                activeInventories.put(uuid, inv);
            }
            if (scheduleUpdate) {
                startResearchUpdater(p);
            }
            return;
        }

        int[] templateSlots = resolveTemplateSlots(layout.getIntegerList("template_slots"));
        int startSlot = layout.getInt("start_button", 40);
        int claimSlot = layout.getInt("claim_button", 41);
        int prevSlot = layout.getInt("prev_button", 36);
        int nextSlot = layout.getInt("next_button", 44);
        Set<Integer> reserved = new HashSet<>();
        reserved.add(startSlot);
        reserved.add(claimSlot);
        if (prevSlot >= 0) reserved.add(prevSlot);
        if (nextSlot >= 0) reserved.add(nextSlot);
        reserved.addAll(collectDecorSlots(researchSec));

        applyBackground(inv, researchSec, reserved);
        applyDecor(inv, researchSec);

        List<ResearchService.TemplateView> templates = plugin.getResearchService().buildTemplates(uuid);
        Map<String, ResearchService.TemplateView> templateByKey = new HashMap<>();
        templates.forEach(t -> templateByKey.put(t.key, t));
        Map<String, ResearchService.RecipeStatus> status = plugin.getResearchService().getPlayerRecipeStatus(uuid);

        String currentSelection = selectedResearch.get(uuid);
        if (currentSelection == null || !templateByKey.containsKey(currentSelection)) {
            if (!templates.isEmpty()) {
                selectedResearch.put(uuid, templates.get(0).key);
            } else {
                selectedResearch.remove(uuid);
            }
        }

        int rows = templateSlots.length == 0 ? 1 : 3;
        if (templateSlots.length % rows != 0) {
            rows = 1;
        }
        int columnsPerPage = rows > 0 ? templateSlots.length / rows : templateSlots.length;
        if (columnsPerPage <= 0) {
            columnsPerPage = Math.max(1, templateSlots.length);
        }

        List<ColumnData> columns = buildColumns(templates, rows);

        int page = researchPage.getOrDefault(uuid, 0);
        int maxPage = columns.isEmpty() ? 0 : Math.max(0, (columns.size() - 1) / columnsPerPage);
        if (page > maxPage) {
            page = maxPage;
            researchPage.put(uuid, page);
        }
        long runningCount = templates.stream().filter(t -> t.running).count();
        int maxConcurrent = plugin.getConfig().getInt("experiments.max_concurrent_per_player", 1);

        Map<String, Integer> cache = new HashMap<>();
        Map<String, Boolean> hasAllMap = new HashMap<>();
        Map<String, Boolean> canStartMap = new HashMap<>();
        Map<String, List<String>> missingMap = new HashMap<>();
        for (ResearchService.TemplateView tmpl : templates) {
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Integer> reagent : tmpl.reagents.entrySet()) {
                int have = totalAvailable(p, reagent.getKey(), cache);
                if (have < reagent.getValue()) {
                    missing.add(reagent.getKey());
                }
            }
            boolean hasAll = missing.isEmpty();
            boolean canStart = hasAll && tmpl.prerequisitesMet && !tmpl.running && runningCount < maxConcurrent;
            hasAllMap.put(tmpl.key, hasAll);
            canStartMap.put(tmpl.key, canStart);
            missingMap.put(tmpl.key, missing);
        }

        Map<Integer, String> slotMap = new HashMap<>();
        int startIndex = page * columnsPerPage;
        for (int columnOffset = 0; columnOffset < columnsPerPage; columnOffset++) {
            int columnIndex = startIndex + columnOffset;
            ColumnData data = columnIndex < columns.size() ? columns.get(columnIndex) : null;
            for (int row = 0; row < rows; row++) {
                int slotIdx = row * columnsPerPage + columnOffset;
                if (slotIdx >= templateSlots.length) {
                    continue;
                }
                int slot = templateSlots[slotIdx];
                if (data == null) {
                    continue;
                }
                ResearchService.TemplateView tmpl = data.rows[row];
                if (tmpl == null) {
                    continue;
                }
                boolean selected = tmpl.key.equals(selectedResearch.get(uuid));
                Set<String> missingSet = new HashSet<>(missingMap.getOrDefault(tmpl.key, Collections.emptyList()));
                ItemStack icon = buildTemplateIcon(p, tmpl, selected, hasAllMap.getOrDefault(tmpl.key, true), cache, status, missingSet);
                inv.setItem(slot, icon);
                slotMap.put(slot, tmpl.key);
            }
        }
        researchSlotMap.put(uuid, slotMap);

        ItemStack startItem = configuredItem(researchSec.getConfigurationSection("icons.start"), "LIME_DYE", "&aStart Experiment");
        ItemMeta startMeta = startItem.getItemMeta();
        List<Component> startLore = new ArrayList<>();
        String selectedKey = selectedResearch.get(uuid);
        boolean limitReached = runningCount >= maxConcurrent;
        if (limitReached) {
            startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&cYou already run the maximum number of experiments."));
        }
        ResearchService.TemplateView selectedTemplate = selectedKey == null ? null : templateByKey.get(selectedKey);
        boolean selectedCanStart = selectedTemplate != null && canStartMap.getOrDefault(selectedKey, false);
        if (selectedTemplate != null) {
            startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Selected: &f" + selectedTemplate.title));
            startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Duration: &f" + formatDuration(selectedTemplate.durationHours)));
            if (selectedTemplate.running) {
                long remaining = Math.max(0L, selectedTemplate.endEpoch - Instant.now().getEpochSecond());
                startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&bExperiment in progress."));
                startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Time left: &f" + formatRemaining(remaining)));
                startMeta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy("&bExperiment Running"));
            } else {
                if (!selectedTemplate.prerequisitesMet) {
                    startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&cMissing prerequisites."));
                }
                List<String> missing = missingMap.getOrDefault(selectedKey, Collections.emptyList());
                if (!missing.isEmpty()) {
                    startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&cMissing reagents:"));
                    for (String miss : missing) {
                        startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&c- " + pl.yourserver.scientistPlugin.util.Texts.prettyKey(miss)));
                    }
                }
                startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy(selectedCanStart ? "&aClick to begin." : "&cCannot start yet."));
                startMeta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(selectedCanStart ? "&aStart Experiment" : "&cStart Experiment"));
            }
        } else {
            startLore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Select an experiment to begin."));
            startMeta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(limitReached ? "&cStart Experiment" : "&7Start Experiment"));
        }
        Material startType = startItem.getType();
        if (startType != null && startType.name().endsWith("_DYE")) {
            if (selectedTemplate != null && selectedTemplate.running) {
                startItem.setType(Material.CYAN_DYE);
            } else if (limitReached || (selectedTemplate != null && !selectedCanStart)) {
                startItem.setType(Material.GRAY_DYE);
            } else {
                startItem.setType(Material.LIME_DYE);
            }
        }
        startMeta.lore(startLore);
        startItem.setItemMeta(startMeta);
        inv.setItem(startSlot, startItem);

        ItemStack claimItem = configuredItem(researchSec.getConfigurationSection("icons.claim"), "CHEST", "&aClaim Finished");
        inv.setItem(claimSlot, claimItem);

        if (prevSlot >= 0) {
            ItemStack prevItem = configuredItem(researchSec.getConfigurationSection("icons.prev"), "ARROW", "&aPrevious Page");
            if (page <= 0) {
                ItemMeta meta = prevItem.getItemMeta();
                meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy("&7No previous page"));
                prevItem.setItemMeta(meta);
            }
            inv.setItem(prevSlot, prevItem);
        }
        if (nextSlot >= 0) {
            ItemStack nextItem = configuredItem(researchSec.getConfigurationSection("icons.next"), "ARROW", "&aNext Page");
            if (page >= maxPage) {
                ItemMeta meta = nextItem.getItemMeta();
                meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy("&7No more pages"));
                nextItem.setItemMeta(meta);
            }
            inv.setItem(nextSlot, nextItem);
        }

        if (!reuse) {
            presentInventory(p, "research", inv);
        } else {
            openGui.put(uuid, "research");
            activeInventories.put(uuid, inv);
        }
        if (scheduleUpdate) {
            startResearchUpdater(p);
        }
    }

    private void startResearchUpdater(Player player) {
        UUID uuid = player.getUniqueId();
        cancelResearchUpdater(uuid);
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !isResearchOpen(player)) {
                cancelResearchUpdater(uuid);
                return;
            }
            renderResearch(player, false);
        }, 20L, 20L).getTaskId();
        researchUpdateTasks.put(uuid, taskId);
    }

    private boolean isResearchOpen(Player player) {
        UUID uuid = player.getUniqueId();
        if (!"research".equals(openGui.get(uuid))) {
            return false;
        }
        Inventory tracked = activeInventories.get(uuid);
        return tracked != null && player.getOpenInventory().getTopInventory().equals(tracked);
    }

    private void cancelResearchUpdater(UUID uuid) {
        Integer taskId = researchUpdateTasks.remove(uuid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public void openAbyssal(Player p) {
        UUID uid = p.getUniqueId();
        rollStates.remove(uid);
        pendingChoice.remove(uid);
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection abyssSec = gui.getConfigurationSection("abyssal");
        String title = abyssSec.getString("title", "Abyssal Enchanting");
        int size = abyssSec.getInt("size", 45);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        ConfigurationSection layout = abyssSec.getConfigurationSection("layout");
        int rollSlot = layout.getInt("roll_button", 31);
        int selectA = layout.getInt("select_a", 30);
        int selectB = layout.getInt("select_b", 32);
        int reject = layout.getInt("reject", 40);
        int targetSlot = layout.getInt("target_slot", 20);
        int boneSlot = layout.getInt("bone_slot", 24);
        int backSlot = layout.getInt("back_button", 36);
        int infoASlot = layout.getInt("option_a_display", -1);
        int infoBSlot = layout.getInt("option_b_display", -1);
        int instructionsSlot = layout.getInt("instructions_button", -1);
        Set<Integer> reserved = new HashSet<>();
        reserved.add(rollSlot);
        reserved.add(selectA);
        reserved.add(selectB);
        reserved.add(reject);
        reserved.add(targetSlot);
        reserved.add(boneSlot);
        if (backSlot >= 0) reserved.add(backSlot);
        if (infoASlot >= 0) reserved.add(infoASlot);
        if (infoBSlot >= 0) reserved.add(infoBSlot);
        if (instructionsSlot >= 0) reserved.add(instructionsSlot);
        reserved.addAll(collectDecorSlots(abyssSec));
        applyBackground(inv, abyssSec, reserved);
        applyDecor(inv, abyssSec);
        resetAbyssalInterface(inv, abyssSec);
        inv.setItem(rollSlot, configuredItem(abyssSec.getConfigurationSection("icons.roll"), "ENDER_EYE", "&dEnchant"));
        if (backSlot >= 0) {
            inv.setItem(backSlot, configuredItem(abyssSec.getConfigurationSection("icons.back"), "ARROW", "&7Back"));
        }
        if (instructionsSlot >= 0) {
            inv.setItem(instructionsSlot, configuredItem(abyssSec.getConfigurationSection("icons.instructions"), "BOOK", "&fInstructions"));
        }

        presentInventory(p, "abyssal", inv);
    }

    public void openConfirm(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection confirmSec = gui.getConfigurationSection("confirm");
        String title = confirmSec.getString("title", "Confirm Abyssal Application");
        int size = confirmSec.getInt("size", 27);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        ConfigurationSection layout = confirmSec.getConfigurationSection("layout");
        int yes = layout.getInt("yes", 11);
        int no = layout.getInt("no", 15);
        Set<Integer> reserved = new HashSet<>();
        reserved.add(yes);
        reserved.add(no);
        reserved.addAll(collectDecorSlots(confirmSec));
        applyBackground(inv, confirmSec, reserved);
        applyDecor(inv, confirmSec);
        inv.setItem(yes, configuredItem(confirmSec.getConfigurationSection("icons.yes"), "LIME_WOOL", "&aConfirm"));
        inv.setItem(no, configuredItem(confirmSec.getConfigurationSection("icons.no"), "RED_WOOL", "&cCancel"));
        presentInventory(p, "confirm", inv);
    }

    private ConfigurationSection iconSection(ConfigurationSection icons, String primary, String... fallbacks) {
        if (icons == null) return null;
        ConfigurationSection sec = icons.getConfigurationSection(primary);
        if (sec != null) return sec;
        if (fallbacks != null) {
            for (String fb : fallbacks) {
                sec = icons.getConfigurationSection(fb);
                if (sec != null) return sec;
            }
        }
        return null;
    }

    private Set<Integer> collectDecorSlots(ConfigurationSection section) {
        Set<Integer> slots = new HashSet<>();
        if (section == null) return slots;
        ConfigurationSection decor = section.getConfigurationSection("decor");
        if (decor == null) return slots;
        for (String key : decor.getKeys(false)) {
            try {
                slots.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
            }
        }
        return slots;
    }

    private void applyDecor(Inventory inv, ConfigurationSection section) {
        if (section == null) return;
        ConfigurationSection decor = section.getConfigurationSection("decor");
        if (decor == null) return;
        for (String key : decor.getKeys(false)) {
            ConfigurationSection node = decor.getConfigurationSection(key);
            if (node == null) continue;
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            inv.setItem(slot, configuredItem(node, "PAPER", ""));
        }
    }

    private void runScientistShortcut(Player player) {
        String command = plugin.getConfig().getString("shortcuts.scientist.command", "scientist");
        if (command == null || command.isBlank()) {
            command = "scientist";
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String permission = plugin.getConfig().getString("shortcuts.scientist.permission", "mycraftingplugin.use");
        PermissionAttachment attachment = player.addAttachment(plugin);
        if (permission != null && !permission.isBlank()) {
            attachment.setPermission(permission, true);
        }
        String finalCommand = command;
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                player.performCommand(finalCommand);
            } finally {
                player.removeAttachment(attachment);
            }
        });
    }
    private int totalAvailable(Player player, String key, Map<String, Integer> cache) {
        return cache.computeIfAbsent(key, k -> itemService.countTotal(player, k));
    }

    private ItemStack buildTemplateIcon(Player player,
                                        ResearchService.TemplateView template,
                                        boolean selected,
                                        boolean hasAll,
                                        Map<String, Integer> cache,
                                        Map<String, ResearchService.RecipeStatus> status,
                                        Collection<String> missingKeys) {
        Material base;
        if (template.running) {
            base = Material.ENCHANTED_BOOK;
        } else if (!template.prerequisitesMet) {
            base = Material.BARRIER;
        } else if (!hasAll) {
            base = Material.PAPER;
        } else {
            base = Material.BOOK;
        }
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        String color;
        if (template.running) {
            color = "&b";
        } else if (!template.prerequisitesMet) {
            color = "&c";
        } else if (!template.unlocked) {
            color = "&e";
        } else {
            color = "&a";
        }
        if (!hasAll && template.prerequisitesMet && !template.running) {
            color = "&6";
        }
        meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(color + template.title));
        List<Component> lore = new ArrayList<>();
        if (template.description != null && !template.description.isEmpty()) {
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7" + template.description));
        }
        lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Duration: &f" + formatDuration(template.durationHours)));
        lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Progress: &f" + template.progress + "&7/&f" + template.unlockThreshold));
        if (template.running) {
            long remaining = Math.max(0, template.endEpoch - Instant.now().getEpochSecond());
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&bRunning: &f" + formatRemaining(remaining)));
        } else if (!template.prerequisitesMet) {
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&cMissing prerequisites."));
        }
        if (!template.reagents.isEmpty()) {
            Collection<String> missing = missingKeys == null ? Collections.emptySet() : missingKeys;
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Reagents:"));
            for (Map.Entry<String, Integer> entry : template.reagents.entrySet()) {
                int have = totalAvailable(player, entry.getKey(), cache);
                boolean enough = !missing.contains(entry.getKey());
                String pretty = pl.yourserver.scientistPlugin.util.Texts.prettyKey(entry.getKey());
                lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy((enough ? "&a" : "&c") + "- " + pretty + " &7(&f" + have + "&7/&f" + entry.getValue() + "&7)"));
            }
        }
        if (template.requires != null && !template.requires.isEmpty()) {
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Prerequisites:"));
            for (String req : template.requires) {
                boolean unlocked = status.getOrDefault(req, new ResearchService.RecipeStatus(false, 0)).unlocked;
                String prettyReq = pl.yourserver.scientistPlugin.util.Texts.prettyKey(req);
                lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy((unlocked ? "&a" : "&c") + "- " + prettyReq));
            }
        }
        if (selected) {
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&bSelected"));
        }
        meta.lore(lore);
        if (selected) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String formatDuration(int hours) {
        if (hours >= 24) {
            int days = hours / 24;
            int rem = hours % 24;
            if (rem == 0) {
                return days + "d";
            }
            return days + "d " + rem + "h";
        }
        return hours + "h";
    }

    private String formatRemaining(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private int[] resolveTemplateSlots(List<Integer> configured) {
        if (configured != null) {
            List<Integer> filtered = new ArrayList<>();
            for (Integer value : configured) {
                if (value != null) {
                    filtered.add(value);
                }
            }
            if (!filtered.isEmpty() && filtered.size() % 3 == 0 && filtered.size() >= 18) {
                int[] slots = new int[filtered.size()];
                for (int i = 0; i < filtered.size(); i++) {
                    slots[i] = filtered.get(i);
                }
                return slots;
            }
        }
        return Arrays.copyOf(DEFAULT_TEMPLATE_SLOTS, DEFAULT_TEMPLATE_SLOTS.length);
    }

    private List<ColumnData> buildColumns(List<ResearchService.TemplateView> templates, int rows) {
        List<ColumnData> columns = new ArrayList<>();
        Map<String, ColumnData> map = new LinkedHashMap<>();
        int effectiveRows = Math.max(1, rows);
        for (ResearchService.TemplateView tmpl : templates) {
            TierInfo info = parseTierInfo(tmpl.key);
            int tierIdx = info.tier < 1 ? 0 : Math.min(info.tier, effectiveRows) - 1;
            String columnKey;
            if (rows <= 1 || info.tier < 1) {
                columnKey = tmpl.key;
            } else {
                columnKey = info.baseKey.isEmpty() ? tmpl.key : info.baseKey;
            }
            ColumnData column = map.get(columnKey);
            if (column == null) {
                column = new ColumnData(effectiveRows);
                map.put(columnKey, column);
                columns.add(column);
            }
            if (column.rows[tierIdx] == null) {
                column.rows[tierIdx] = tmpl;
            } else {
                String unique = columnKey;
                int suffix = 1;
                while (map.containsKey(unique)) {
                    unique = columnKey + "#" + suffix++;
                }
                ColumnData extra = new ColumnData(effectiveRows);
                extra.rows[tierIdx] = tmpl;
                map.put(unique, extra);
                columns.add(extra);
            }
        }
        return columns;
    }

    private TierInfo parseTierInfo(String key) {
        if (key == null || key.isEmpty()) {
            return new TierInfo("", 0);
        }
        String upper = key.toUpperCase(Locale.ROOT);
        if (upper.endsWith("_III")) {
            return new TierInfo(key.substring(0, key.length() - 4), 3);
        }
        if (upper.endsWith("_II")) {
            return new TierInfo(key.substring(0, key.length() - 3), 2);
        }
        if (upper.endsWith("_I")) {
            return new TierInfo(key.substring(0, key.length() - 2), 1);
        }
        if (upper.endsWith("_T3")) {
            return new TierInfo(key.substring(0, key.length() - 3), 3);
        }
        if (upper.endsWith("_T2")) {
            return new TierInfo(key.substring(0, key.length() - 3), 2);
        }
        if (upper.endsWith("_T1")) {
            return new TierInfo(key.substring(0, key.length() - 3), 1);
        }
        return new TierInfo(key, 1);
    }

    private static final class TierInfo {
        final String baseKey;
        final int tier;

        TierInfo(String baseKey, int tier) {
            this.baseKey = baseKey == null ? "" : baseKey;
            this.tier = tier;
        }
    }

    private static final class ColumnData {
        final ResearchService.TemplateView[] rows;

        ColumnData(int rowsCount) {
            this.rows = new ResearchService.TemplateView[Math.max(1, rowsCount)];
        }
    }

    private ItemStack configuredItem(ConfigurationSection sec, String fallbackMaterial, String fallbackName) {
        String materialName = sec != null ? sec.getString("material", fallbackMaterial) : fallbackMaterial;
        String display = sec != null ? sec.getString("name", fallbackName) : fallbackName;
        Material mat = Material.matchMaterial(materialName == null ? fallbackMaterial : materialName.toUpperCase(Locale.ROOT));
        if (mat == null) {
            mat = Material.matchMaterial(fallbackMaterial);
        }
        if (mat == null) mat = Material.PAPER;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (display != null) {
            meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(display));
        }
        if (sec != null && sec.contains("model")) {
            meta.setCustomModelData(sec.getInt("model"));
        }
        it.setItemMeta(meta);
        return it;
    }

    private boolean consumeBone(Inventory inv, int boneSlot) {
        if (inv == null) {
            return false;
        }
        ItemStack bone = inv.getItem(boneSlot);
        if (bone == null || bone.getType().isAir()) {
            return false;
        }
        int amount = bone.getAmount();
        if (amount <= 1) {
            inv.setItem(boneSlot, null);
        } else {
            bone.setAmount(amount - 1);
            inv.setItem(boneSlot, bone);
        }
        return true;
    }

    private void resetAbyssalInterface(Inventory inv, ConfigurationSection abyssSec) {
        if (abyssSec == null) {
            return;
        }
        ConfigurationSection layout = abyssSec.getConfigurationSection("layout");
        if (layout == null) {
            return;
        }
        int selectA = layout.getInt("select_a", 30);
        int selectB = layout.getInt("select_b", 32);
        int reject = layout.getInt("reject", 40);
        int infoASlot = layout.getInt("option_a_display", -1);
        int infoBSlot = layout.getInt("option_b_display", -1);
        ConfigurationSection icons = abyssSec.getConfigurationSection("icons");
        ItemStack pending = configuredItem(icons == null ? null : icons.getConfigurationSection("pending"), "GRAY_STAINED_GLASS_PANE", "&7Awaiting roll");
        if (infoASlot >= 0 && infoASlot < inv.getSize()) {
            inv.setItem(infoASlot, pending == null ? null : pending.clone());
        }
        if (infoBSlot >= 0 && infoBSlot < inv.getSize()) {
            inv.setItem(infoBSlot, pending == null ? null : pending.clone());
        }
        if (selectA >= 0 && selectA < inv.getSize()) {
            inv.setItem(selectA, null);
        }
        if (selectB >= 0 && selectB < inv.getSize()) {
            inv.setItem(selectB, null);
        }
        if (reject >= 0 && reject < inv.getSize()) {
            inv.setItem(reject, null);
        }
    }

    private void handleAbyssalSlotUpdate(Player player, Inventory inv) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection abyssSec = gui.getConfigurationSection("abyssal");
        ConfigurationSection layout = abyssSec == null ? null : abyssSec.getConfigurationSection("layout");
        if (layout == null) {
            return;
        }
        int targetSlot = layout.getInt("target_slot", 20);
        int boneSlot = layout.getInt("bone_slot", 24);
        UUID uid = player.getUniqueId();

        ItemStack target = inv.getItem(targetSlot);
        ItemStack bone = inv.getItem(boneSlot);

        if (target != null && !target.getType().isAir() && target.getAmount() > 1) {
            ItemStack overflow = target.clone();
            overflow.setAmount(target.getAmount() - 1);
            target.setAmount(1);
            inv.setItem(targetSlot, target);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(overflow);
            leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
            sendMessage(player, "abyssal_single_target", "&7Only one item can be enchanted at a time. Extras returned.");
        }

        if (bone != null && !bone.getType().isAir() && bone.getAmount() > 1) {
            ItemStack overflow = bone.clone();
            overflow.setAmount(bone.getAmount() - 1);
            bone.setAmount(1);
            inv.setItem(boneSlot, bone);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(overflow);
            leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
            sendMessage(player, "abyssal_single_bone", "&7Only one bone can be used per roll. Extras returned.");
        }

        pendingChoice.remove(uid);
        RollState previous = rollStates.remove(uid);
        if (previous != null) {
            resetAbyssalInterface(inv, abyssSec);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        UUID id = he.getUniqueId();
        Inventory top = e.getView().getTopInventory();
        Inventory tracked = activeInventories.get(id);
        if (tracked == null || !tracked.equals(top)) {
            return;
        }
        String ctx = openGui.get(id);
        if (ctx == null) return;
        if (!(he instanceof Player p)) return;
        FileConfiguration gui = plugin.getConfigManager().gui();

        boolean topClick = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        boolean cancel = topClick;
        int rawSlot = e.getRawSlot();

        switch (ctx) {
            case "main" -> {
                int rs = gui.getInt("main.layout.research_slot", 11);
                int cs = gui.contains("main.layout.crafting_slot")
                        ? gui.getInt("main.layout.crafting_slot")
                        : gui.getInt("main.layout.recipe_slot", 13);
                int as = gui.getInt("main.layout.abyssal_slot", 15);
                int help = gui.getInt("main.layout.help_slot", 26);
                if (rawSlot == rs) {
                    openResearch(p);
                } else if (rawSlot == cs) {
                    p.closeInventory();
                    sendMessage(p, "open_crafting_shortcut", "&aOpening Scientist crafting...");
                    runScientistShortcut(p);
                } else if (rawSlot == as) {
                    openAbyssal(p);
                } else if (rawSlot == help) {
                    sendHelp(p);
                }
            }
            case "research" -> {
                if (!topClick) {
                    e.setCancelled(true);
                    return;
                }
                ConfigurationSection layout = gui.getConfigurationSection("research.layout");
                int start = layout.getInt("start_button", 40);
                int claim = layout.getInt("claim_button", 41);
                int prev = layout.getInt("prev_button", 36);
                int next = layout.getInt("next_button", 44);
                Set<Integer> templateSlots = new HashSet<>(layout.getIntegerList("template_slots"));
                if (templateSlots.isEmpty()) {
                    templateSlots.addAll(Arrays.asList(10, 11, 12, 19, 20, 21, 28, 29, 30));
                }
                UUID uid = p.getUniqueId();

                if (rawSlot == start) {
                    String selectedKey = selectedResearch.get(uid);
                    if (selectedKey == null) {
                        sendMessage(p, "research_select_first", "&cSelect an experiment first.");
                    } else {
                        if (plugin.getResearchService().attemptStart(p, selectedKey)) {
                            selectedResearch.put(uid, selectedKey);
                        }
                    }
                    renderResearch(p, true);
                    return;
                }
                if (rawSlot == claim) {
                    plugin.getResearchService().claimFinished(p);
                    renderResearch(p, true);
                    return;
                }
                if (rawSlot == prev) {
                    researchPage.put(uid, Math.max(0, researchPage.getOrDefault(uid, 0) - 1));
                    renderResearch(p, true);
                    return;
                }
                if (rawSlot == next) {
                    researchPage.put(uid, researchPage.getOrDefault(uid, 0) + 1);
                    renderResearch(p, true);
                    return;
                }
                if (templateSlots.contains(rawSlot)) {
                    String key = researchSlotMap.getOrDefault(uid, Collections.emptyMap()).get(rawSlot);
                    if (key != null) {
                        selectedResearch.put(uid, key);
                        renderResearch(p, true);
                    }
                    e.setCancelled(true);
                    return;
                }
                e.setCancelled(true);
            }

            case "abyssal" -> {
                ConfigurationSection abyssSec = gui.getConfigurationSection("abyssal");
                ConfigurationSection layout = abyssSec == null ? null : abyssSec.getConfigurationSection("layout");
                if (layout == null) {
                    e.setCancelled(true);
                    return;
                }
                int roll = layout.getInt("roll_button", 31);
                int selectA = layout.getInt("select_a", 30);
                int selectB = layout.getInt("select_b", 32);
                int reject = layout.getInt("reject", 40);
                int targetSlot = layout.getInt("target_slot", 20);
                int boneSlot = layout.getInt("bone_slot", 24);
                int backSlot = layout.getInt("back_button", -1);
                int instructionsSlot = layout.getInt("instructions_button", -1);
                boolean slotInteraction = rawSlot == targetSlot || rawSlot == boneSlot;
                if (topClick && slotInteraction) {
                    cancel = false;
                    Bukkit.getScheduler().runTask(plugin, () -> handleAbyssalSlotUpdate(p, top));
                } else if (!topClick && (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || e.getAction() == InventoryAction.HOTBAR_SWAP
                        || e.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD)) {
                    Bukkit.getScheduler().runTask(plugin, () -> handleAbyssalSlotUpdate(p, top));
                }
                if (rawSlot == roll) {
                    plugin.getAbyssalService().rollOptions(p, e.getInventory(), rollStates);
                    return;
                }

                if (backSlot >= 0 && rawSlot == backSlot) {
                    refundSlots(p, top, new int[]{targetSlot, boneSlot});
                    rollStates.remove(p.getUniqueId());
                    pendingChoice.remove(p.getUniqueId());
                    openMain(p);
                    return;
                }

                if (instructionsSlot >= 0 && rawSlot == instructionsSlot) {
                    sendAbyssalInstructions(p);
                    return;
                }

                RollState st = rollStates.get(p.getUniqueId());

                if (rawSlot == selectA) {
                    if (st == null) {
                        sendMessage(p, "roll_not_ready", "&cPress Enchant to reveal modifiers first.");
                    } else {
                        pendingChoice.put(p.getUniqueId(), 0);
                        openConfirm(p);
                    }
                    return;
                }
                if (rawSlot == selectB) {
                    if (st == null) {
                        sendMessage(p, "roll_not_ready", "&cPress Enchant to reveal modifiers first.");
                    } else {
                        pendingChoice.put(p.getUniqueId(), 1);
                        openConfirm(p);
                    }
                    return;
                }
                if (rawSlot == reject) {
                    if (st == null) {
                        sendMessage(p, "roll_not_ready", "&cPress Enchant to reveal modifiers first.");
                    } else {
                        boolean consumed = consumeBone(top, boneSlot);
                        rollStates.remove(p.getUniqueId());
                        pendingChoice.remove(p.getUniqueId());
                        refundSlots(p, top, new int[]{targetSlot});
                        resetAbyssalInterface(top, abyssSec);
                        if (consumed) {
                            sendMessage(p, "reject_done", "&7Roll discarded. The bone was consumed.");
                        } else {
                            String prefix = plugin.getConfigManager().messages().getString("prefix", "");
                            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + "&7Roll discarded."));
                        }
                    }
                    return;
                }
            }
            case "confirm" -> {
                int yes = gui.getInt("confirm.layout.yes", 11);
                int no = gui.getInt("confirm.layout.no", 15);
                if (rawSlot == yes) {
                    Integer choice = pendingChoice.remove(p.getUniqueId());
                    if (choice != null) {
                        plugin.getAbyssalService().applySelected(p, rollStates, choice);
                    }
                } else if (rawSlot == no) {
                    pendingChoice.remove(p.getUniqueId());
                    RollState st = rollStates.get(p.getUniqueId());
                    if (st != null && st.invRef != null) {
                        presentInventory(p, "abyssal", st.invRef);
                    } else {
                        p.closeInventory();
                    }
                }
            }
        }

        e.setCancelled(cancel);
    }

        @EventHandler
    public void onDrag(InventoryDragEvent e) {
        UUID id = e.getWhoClicked().getUniqueId();
        Inventory top = e.getView().getTopInventory();
        Inventory tracked = activeInventories.get(id);
        if (tracked == null || !tracked.equals(top)) {
            return;
        }
        String ctx = openGui.get(id);
        if (ctx == null) {
            return;
        }
        if (ctx.equals("abyssal")) {
            FileConfiguration gui = plugin.getConfigManager().gui();
            ConfigurationSection layout = gui.getConfigurationSection("abyssal.layout");
            Set<Integer> allowed = new HashSet<>();
            allowed.add(layout.getInt("target_slot", 20));
            allowed.add(layout.getInt("bone_slot", 24));
            for (int slot : e.getRawSlots()) {
                if (slot < top.getSize() && !allowed.contains(slot)) {
                    e.setCancelled(true);
                    return;
                }
            }
            if (e.getWhoClicked() instanceof Player player) {
                Bukkit.getScheduler().runTask(plugin, () -> handleAbyssalSlotUpdate(player, top));
            }
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Inventory tracked = activeInventories.get(id);
        if (tracked == null || !tracked.equals(e.getInventory())) {
            return;
        }

        activeInventories.remove(id);
        String ctx = openGui.remove(id);

        if (!(e.getPlayer() instanceof Player player)) {
            rollStates.remove(id);
            pendingChoice.remove(id);
            selectedResearch.remove(id);
            researchSlotMap.remove(id);
            researchPage.remove(id);
            cancelResearchUpdater(id);
            return;
        }

        FileConfiguration gui = plugin.getConfigManager().gui();
        if (ctx == null) {
            rollStates.remove(id);
            pendingChoice.remove(id);
            selectedResearch.remove(id);
            researchSlotMap.remove(id);
            researchPage.remove(id);
            cancelResearchUpdater(id);
            return;
        }

        if (ctx.equals("confirm")) {
            Integer pending = pendingChoice.remove(id);
            if (pending != null) {
                RollState st = rollStates.get(id);
                if (st != null && st.invRef != null) {
                    presentInventoryLater(player, "abyssal", st.invRef);
                }
            }
            return;
        }

        pendingChoice.remove(id);

        if (ctx.equals("research")) {
            selectedResearch.remove(id);
            researchSlotMap.remove(id);
            researchPage.remove(id);
            cancelResearchUpdater(id);
            rollStates.remove(id);
            return;
        }

        if (ctx.equals("abyssal")) {
            ConfigurationSection layout = gui.getConfigurationSection("abyssal.layout");
            int[] slots = new int[]{ layout.getInt("target_slot", 20), layout.getInt("bone_slot", 24) };
            refundSlots(player, e.getInventory(), slots);
            rollStates.remove(id);
            return;
        }

        rollStates.remove(id);
    }

    public static class RollState {
        public String[] keys = new String[2];
        public double[] min = new double[2];
        public double[] max = new double[2];
        public int tier;
        public String category;
        public Inventory invRef;
    }
    private void applyBackground(Inventory inv, ConfigurationSection section, Set<Integer> reserved) {
        if (section == null) return;
        ConfigurationSection bg = section.getConfigurationSection("background");
        if (bg == null) return;
        ItemStack filler = configuredItem(bg, "GRAY_STAINED_GLASS_PANE", "");
        for (int i = 0; i < inv.getSize(); i++) {
            if (reserved.contains(i)) continue;
            inv.setItem(i, filler.clone());
        }
    }

    private void sendMessage(Player p, String path, String def) {
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        String msg = (path == null || path.isEmpty())
                ? def
                : plugin.getConfigManager().messages().getString(path, def);
        if (msg == null || msg.isEmpty()) return;
        p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + msg));
    }

    private void sendHelp(Player p) {
        List<String> lines = plugin.getConfigManager().messages().getStringList("help_lines");
        if (lines == null || lines.isEmpty()) {
            sendMessage(p, "help_fallback", "&7Submit reagents to begin research and unlock recipes.");
            return;
        }
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        for (String line : lines) {
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + line));
        }
    }

    private void sendAbyssalInstructions(Player p) {
        List<String> lines = plugin.getConfigManager().messages().getStringList("abyssal_instructions");
        if (lines == null || lines.isEmpty()) {
            sendMessage(p, "abyssal_help_fallback", "&7Place an item and matching bone, then press Enchant.");
            return;
        }
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        for (String line : lines) {
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + line));
        }
    }

    private void refundSlots(Player player, Inventory inv, int[] slots) {
        for (int slot : slots) {
            if (slot < 0 || slot >= inv.getSize()) continue;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(slot, null);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
        }
    }
}
