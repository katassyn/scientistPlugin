package pl.yourserver.scientistPlugin.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;

import java.util.*;

public class GuiManager implements Listener {
    private final ScientistPlugin plugin;
    private final ItemService itemService;
    private final Map<UUID, String> openGui = new HashMap<>();
    private final Map<UUID, Inventory> activeInventories = new HashMap<>();
    private final Map<UUID, RollState> rollStates = new HashMap<>();
    private final Map<UUID, Integer> pendingChoice = new HashMap<>();
    private final Map<UUID, Integer> recipePage = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> recipeSlotMap = new HashMap<>();

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
        java.util.Set<Integer> reserved = new java.util.HashSet<>();
        int researchSlot = layout.getInt("research_slot", 11);
        int recipeSlot = layout.getInt("recipe_slot", 13);
        int abyssalSlot = layout.getInt("abyssal_slot", 15);
        int helpSlot = layout.getInt("help_slot", 26);
        reserved.add(researchSlot);
        reserved.add(recipeSlot);
        reserved.add(abyssalSlot);
        reserved.add(helpSlot);
        applyBackground(inv, mainSec, reserved);

        inv.setItem(researchSlot, configuredItem(icons.getConfigurationSection("research"), "BREWING_STAND", "&aResearch Lab"));
        inv.setItem(recipeSlot, configuredItem(icons.getConfigurationSection("recipe"), "BOOK", "&eRecipe Book"));
        inv.setItem(abyssalSlot, configuredItem(icons.getConfigurationSection("abyssal"), "ENCHANTING_TABLE", "&5Abyssal Enchanting"));
        inv.setItem(helpSlot, configuredItem(icons.getConfigurationSection("help"), "KNOWLEDGE_BOOK", "&7Help"));

        presentInventory(p, "main", inv);
        sendMessage(p, "open_main", "&aOpening Scientist Lab...");
    }

    public void openResearch(Player p) {
        renderResearch(p);
    }

    private void renderResearch(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection researchSec = gui.getConfigurationSection("research");
        String title = researchSec.getString("title", "Research Lab");
        int size = researchSec.getInt("size", 45);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        ConfigurationSection layout = researchSec.getConfigurationSection("layout");
        int startSlot = layout.getInt("start_button", 40);
        int claimSlot = layout.getInt("claim_button", 41);
        java.util.Set<Integer> reserved = new java.util.HashSet<>();
        reserved.add(startSlot);
        reserved.add(claimSlot);
        int[] inputSlots = layout.getIntegerList("input_slots").stream().mapToInt(i -> i).toArray();
        for (int s : inputSlots) reserved.add(s);
        applyBackground(inv, researchSec, reserved);

        inv.setItem(startSlot, configuredItem(researchSec.getConfigurationSection("icons.start"), "LIME_DYE", "&aStart Experiment"));
        inv.setItem(claimSlot, configuredItem(researchSec.getConfigurationSection("icons.claim"), "CHEST", "&aClaim Finished"));

        // List experiments (last 14)
        java.util.List<pl.yourserver.scientistPlugin.research.ResearchService.UIExperiment> exps = plugin.getResearchService().listExperiments(p.getUniqueId(), 14);
        int placed = 0;
        for (pl.yourserver.scientistPlugin.research.ResearchService.UIExperiment row : exps) {
            int slot = researchGridSlot(placed++);
            String disp = plugin.getConfigManager().recipes().getConfigurationSection("recipes").getConfigurationSection(row.recipeKey).getString("title", row.recipeKey);
            String status = row.status;
            long now = java.time.Instant.now().getEpochSecond();
            long remain = Math.max(0, row.endEpoch - now);
            String mat = status.equals("FINISHED") ? "LIME_WOOL" : status.equals("RUNNING") ? "YELLOW_WOOL" : "GRAY_WOOL";
            ItemStack it = configuredItem(null, mat, (status.equals("FINISHED") ? "&a" : status.equals("RUNNING") ? "&e" : "&7") + disp);
            ItemMeta meta = it.getItemMeta();
            java.util.List<Component> lore = new java.util.ArrayList<>();
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Status: &f" + status));
            if (status.equals("RUNNING")) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Time left: &f" + (remain/60) + "m"));
            if (status.equals("FINISHED")) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&aClick Claim Finished"));
            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
        }

        presentInventory(p, "research", inv);
    }

    private int researchGridSlot(int index) {
        // grid slots: 10..16 and 19..25 (2 rows x7)
        int row = index / 7; int col = index % 7;
        return (row == 0 ? 10 : 19) + col;
    }

    public void openRecipes(Player p) { recipePage.put(p.getUniqueId(), 0); renderRecipes(p); }

    private void renderRecipes(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        ConfigurationSection recipeSec = gui.getConfigurationSection("recipes");
        String title = recipeSec.getString("title", "Recipe Book");
        int size = recipeSec.getInt("size", 54);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        ConfigurationSection layout = recipeSec.getConfigurationSection("layout");
        int next = layout.getInt("next", 53);
        int prev = layout.getInt("prev", 45);
        java.util.Set<Integer> reserved = new java.util.HashSet<>();
        reserved.add(next);
        reserved.add(prev);
        applyBackground(inv, recipeSec, reserved);
        inv.setItem(next, configuredItem(recipeSec.getConfigurationSection("icons.next"), "ARROW", "&aNext"));
        inv.setItem(prev, configuredItem(recipeSec.getConfigurationSection("icons.prev"), "ARROW", "&aPrev"));

        var recSec = plugin.getConfigManager().recipes().getConfigurationSection("recipes");
        if (recSec == null) return;
        java.util.List<String> keys = new java.util.ArrayList<>(recSec.getKeys(false));
        keys.sort(String::compareTo);
        int page = recipePage.getOrDefault(p.getUniqueId(), 0);
        int perPage = 28; // 4*7 grid
        int from = page * perPage;
        int to = Math.min(keys.size(), from + perPage);
        var status = plugin.getResearchService().getPlayerRecipeStatus(p.getUniqueId());
        Map<Integer, String> slotMap = new HashMap<>();
        int placed = 0;
        for (int i = from; i < to; i++) {
            String key = keys.get(i);
            var rs = recSec.getConfigurationSection(key);
            String display = rs.getString("title", key);
            String desc = rs.getString("description", "");
            int needed = rs.getInt("experiments_to_unlock", 5);
            var st = status.getOrDefault(key, new pl.yourserver.scientistPlugin.research.ResearchService.RecipeStatus(false, 0));
            boolean unlocked = st.unlocked;
            int done = st.experimentsDone;
            java.util.List<String> reqs = rs.getStringList("requires");
            ItemStack it = configuredItem(null, unlocked ? "BOOK" : "GRAY_DYE", (unlocked ? "&a" : "&7") + display);
            ItemMeta meta = it.getItemMeta();
            java.util.List<Component> lore = new java.util.ArrayList<>();
            if (!desc.isEmpty()) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7" + desc));
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Progress: &e" + done + "&7/&e" + needed));
            if (reqs != null && !reqs.isEmpty()) {
                lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Requires:"));
                for (String r : reqs) {
                    boolean ru = status.getOrDefault(r, new pl.yourserver.scientistPlugin.research.ResearchService.RecipeStatus(false, 0)).unlocked;
                    lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy((ru ? "&a- " : "&c- ") + r));
                }
            }
            if (unlocked) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&aClick: Open Abyssal Enchanting"));
            meta.lore(lore);
            it.setItemMeta(meta);
            int slot = toGridSlot(placed);
            inv.setItem(slot, it);
            slotMap.put(slot, key);
            placed++;
        }
        presentInventory(p, "recipes", inv);
        recipeSlotMap.put(p.getUniqueId(), slotMap);
    }

    private int toGridSlot(int index) {
        int row = index / 7; // 0..3
        int col = index % 7; // 0..6
        return (row == 0 ? 10 : row == 1 ? 19 : row == 2 ? 28 : 37) + col;
    }

    public void openAbyssal(Player p) {
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
        java.util.Set<Integer> reserved = new java.util.HashSet<>();
        reserved.add(rollSlot);
        reserved.add(selectA);
        reserved.add(selectB);
        reserved.add(reject);
        reserved.add(targetSlot);
        reserved.add(boneSlot);
        applyBackground(inv, abyssSec, reserved);
        inv.setItem(rollSlot, configuredItem(abyssSec.getConfigurationSection("icons.roll"), "ENDER_EYE", "&dRoll"));
        inv.setItem(selectA, configuredItem(abyssSec.getConfigurationSection("icons.a"), "GREEN_DYE", "&aSelect A"));
        inv.setItem(selectB, configuredItem(abyssSec.getConfigurationSection("icons.b"), "BLUE_DYE", "&9Select B"));
        inv.setItem(reject, configuredItem(abyssSec.getConfigurationSection("icons.reject"), "BARRIER", "&cReject"));

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
        java.util.Set<Integer> reserved = new java.util.HashSet<>();
        reserved.add(yes);
        reserved.add(no);
        applyBackground(inv, confirmSec, reserved);
        inv.setItem(yes, configuredItem(confirmSec.getConfigurationSection("icons.yes"), "LIME_WOOL", "&aConfirm"));
        inv.setItem(no, configuredItem(confirmSec.getConfigurationSection("icons.no"), "RED_WOOL", "&cCancel"));
        presentInventory(p, "confirm", inv);
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
        if (display != null && !display.isEmpty()) {
            meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(display));
        }
        if (sec != null && sec.contains("model")) {
            meta.setCustomModelData(sec.getInt("model"));
        }
        it.setItemMeta(meta);
        return it;
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
                int rcs = gui.getInt("main.layout.recipe_slot", 13);
                int as = gui.getInt("main.layout.abyssal_slot", 15);
                int help = gui.getInt("main.layout.help_slot", 26);
                if (rawSlot == rs) openResearch(p);
                if (rawSlot == rcs) openRecipes(p);
                if (rawSlot == as) openAbyssal(p);
                if (rawSlot == help) sendHelp(p);
            }
            case "research" -> {
                ConfigurationSection layout = gui.getConfigurationSection("research.layout");
                int start = layout.getInt("start_button", 40);
                int claimSlot = layout.getInt("claim_button", 41);
                int[] inputSlots = layout.getIntegerList("input_slots").stream().mapToInt(i -> i).toArray();
                if (!topClick) {
                    if (e.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                        if (handleResearchShiftClick(e, inputSlots)) {
                            return;
                        }
                    }
                    cancel = false;
                    break;
                }
                if (rawSlot == start) {
                    plugin.getResearchService().attemptStart(p, e.getInventory());
                    renderResearch(p);
                    return;
                }
                if (rawSlot == claimSlot) {
                    plugin.getResearchService().claimFinished(p);
                    renderResearch(p);
                    return;
                }
                for (int slot : inputSlots) {
                    if (rawSlot == slot) {
                        cancel = false;
                        break;
                    }
                }
            }
            case "recipes" -> {
                int next = gui.getInt("recipes.layout.next", 53);
                int prev = gui.getInt("recipes.layout.prev", 45);
                int page = recipePage.getOrDefault(p.getUniqueId(), 0);
                var recSec = plugin.getConfigManager().recipes().getConfigurationSection("recipes");
                int total = recSec != null ? recSec.getKeys(false).size() : 0;
                int perPage = 28;
                int maxPage = Math.max(0, (total - 1) / perPage);
                if (rawSlot == next) {
                    if (page < maxPage) recipePage.put(p.getUniqueId(), page + 1);
                    renderRecipes(p);
                } else if (rawSlot == prev) {
                    if (page > 0) recipePage.put(p.getUniqueId(), page - 1);
                    renderRecipes(p);
                } else {
                    String key = recipeSlotMap.getOrDefault(p.getUniqueId(), java.util.Collections.emptyMap()).get(rawSlot);
                    if (key != null) {
                        var status = plugin.getResearchService().getPlayerRecipeStatus(p.getUniqueId());
                        boolean unlocked = status.getOrDefault(key, new pl.yourserver.scientistPlugin.research.ResearchService.RecipeStatus(false, 0)).unlocked;
                        if (unlocked) openAbyssal(p);
                    }
                }
            }
            case "abyssal" -> {
                ConfigurationSection layout = gui.getConfigurationSection("abyssal.layout");
                int roll = layout.getInt("roll_button", 31);
                int selectA = layout.getInt("select_a", 30);
                int selectB = layout.getInt("select_b", 32);
                int reject = layout.getInt("reject", 40);
                int targetSlot = layout.getInt("target_slot", 20);
                int boneSlot = layout.getInt("bone_slot", 24);
                if (topClick && (rawSlot == targetSlot || rawSlot == boneSlot)) {
                    cancel = false;
                }
                if (rawSlot == roll) {
                    plugin.getAbyssalService().rollOptions(p, e.getInventory(), rollStates);
                } else if (rawSlot == selectA) {
                    pendingChoice.put(p.getUniqueId(), 0);
                    openConfirm(p);
                } else if (rawSlot == selectB) {
                    pendingChoice.put(p.getUniqueId(), 1);
                    openConfirm(p);
                } else if (rawSlot == reject) {
                    rollStates.remove(p.getUniqueId());
                    sendMessage(p, "reject_done", "&7Roll discarded. No changes applied.");
                    p.closeInventory();
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
                        openGui.put(p.getUniqueId(), "abyssal");
                        p.openInventory(st.invRef);
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
        if (ctx == null) return;
        int size = top.getSize();
        FileConfiguration gui = plugin.getConfigManager().gui();
        java.util.Set<Integer> allowed = new java.util.HashSet<>();
        if (ctx.equals("research")) {
            allowed.addAll(gui.getIntegerList("research.layout.input_slots"));
        } else if (ctx.equals("abyssal")) {
            ConfigurationSection layout = gui.getConfigurationSection("abyssal.layout");
            allowed.add(layout.getInt("target_slot", 20));
            allowed.add(layout.getInt("bone_slot", 24));
        } else {
            e.setCancelled(true);
            return;
        }
        for (int slot : e.getRawSlots()) {
            if (slot < size && !allowed.contains(slot)) {
                e.setCancelled(true);
                return;
            }
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
        if (!(e.getPlayer() instanceof Player p)) {
            rollStates.remove(id);
            pendingChoice.remove(id);
            recipeSlotMap.remove(id);
            return;
        }

        FileConfiguration gui = plugin.getConfigManager().gui();
        if (ctx == null) {
            rollStates.remove(id);
            pendingChoice.remove(id);
            recipeSlotMap.remove(id);
            return;
        }

        if (ctx.equals("confirm")) {
            Integer pending = pendingChoice.remove(id);
            if (pending != null) {
                RollState st = rollStates.get(id);
                if (st != null && st.invRef != null) {
                    presentInventoryLater(p, "abyssal", st.invRef);
                }
            }
            return;
        }

        recipeSlotMap.remove(id);
        pendingChoice.remove(id);

        if (ctx.equals("research")) {
            int[] slots = gui.getIntegerList("research.layout.input_slots").stream().mapToInt(i -> i).toArray();
            refundSlots(p, e.getInventory(), slots);
            rollStates.remove(id);
            return;
        }

        if (ctx.equals("abyssal")) {
            ConfigurationSection layout = gui.getConfigurationSection("abyssal.layout");
            int[] slots = new int[]{ layout.getInt("target_slot", 20), layout.getInt("bone_slot", 24) };
            refundSlots(p, e.getInventory(), slots);
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

    private void applyBackground(Inventory inv, ConfigurationSection section, java.util.Set<Integer> reserved) {
        if (section == null) return;
        ConfigurationSection bg = section.getConfigurationSection("background");
        if (bg == null) return;
        ItemStack filler = configuredItem(bg, "GRAY_STAINED_GLASS_PANE", " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (reserved.contains(i)) continue;
            inv.setItem(i, filler.clone());
        }
    }

    private void sendMessage(Player p, String path, String def) {
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        String msg = plugin.getConfigManager().messages().getString(path, def);
        if (msg == null || msg.isEmpty()) return;
        p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + msg));
    }

    private void sendHelp(Player p) {
        java.util.List<String> lines = plugin.getConfigManager().messages().getStringList("help_lines");
        if (lines == null || lines.isEmpty()) {
            sendMessage(p, "help_fallback", "&7Submit reagents to begin research and unlock recipes.");
            return;
        }
        String prefix = plugin.getConfigManager().messages().getString("prefix", "");
        for (String line : lines) {
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + line));
        }
    }

    private boolean handleResearchShiftClick(InventoryClickEvent e, int[] inputSlots) {
        ItemStack moving = e.getCurrentItem();
        if (moving == null || moving.getType().isAir()) return false;
        String key = itemService.readKey(moving).orElse(null);
        if (key == null) return false;
        Inventory top = e.getView().getTopInventory();
        for (int slot : inputSlots) {
            ItemStack existing = top.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                top.setItem(slot, moving.clone());
                e.getClickedInventory().setItem(e.getSlot(), null);
                e.setCancelled(true);
                return true;
            }
            String existingKey = itemService.readKey(existing).orElse(null);
            if (existingKey != null && existingKey.equals(key) && existing.getAmount() < existing.getMaxStackSize()) {
                int transfer = Math.min(existing.getMaxStackSize() - existing.getAmount(), moving.getAmount());
                existing.setAmount(existing.getAmount() + transfer);
                moving.setAmount(moving.getAmount() - transfer);
                top.setItem(slot, existing);
                if (moving.getAmount() <= 0) {
                    e.getClickedInventory().setItem(e.getSlot(), null);
                } else {
                    e.getClickedInventory().setItem(e.getSlot(), moving);
                }
                e.setCancelled(true);
                return true;
            }
        }
        return false;
    }

    private void refundSlots(Player player, Inventory inv, int[] slots) {
        for (int slot : slots) {
            if (slot < 0 || slot >= inv.getSize()) continue;
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(slot, null);
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            leftover.values().forEach(rem -> player.getWorld().dropItemNaturally(player.getLocation(), rem));
        }
    }
}
