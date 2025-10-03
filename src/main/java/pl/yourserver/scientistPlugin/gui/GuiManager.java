package pl.yourserver.scientistPlugin.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.util.*;

public class GuiManager implements Listener {
    private final ScientistPlugin plugin;
    private final Map<UUID, String> openGui = new HashMap<>();
    private final Map<UUID, RollState> rollStates = new HashMap<>();
    private final Map<UUID, Integer> pendingChoice = new HashMap<>();
    private final Map<UUID, Integer> recipePage = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> recipeSlotMap = new HashMap<>();

    public GuiManager(ScientistPlugin plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        String title = gui.getString("main.title", "Scientist Lab");
        int size = gui.getInt("main.size", 27);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));

        var icons = gui.getConfigurationSection("main.icons");
        var layout = gui.getConfigurationSection("main.layout");

        inv.setItem(layout.getInt("research_slot"), icon(icons.getConfigurationSection("research").getString("material"), icons.getConfigurationSection("research").getString("name")));
        inv.setItem(layout.getInt("recipe_slot"), icon(icons.getConfigurationSection("recipe").getString("material"), icons.getConfigurationSection("recipe").getString("name")));
        inv.setItem(layout.getInt("abyssal_slot"), icon(icons.getConfigurationSection("abyssal").getString("material"), icons.getConfigurationSection("abyssal").getString("name")));
        inv.setItem(layout.getInt("help_slot"), icon(icons.getConfigurationSection("help").getString("material"), icons.getConfigurationSection("help").getString("name")));

        openGui.put(p.getUniqueId(), "main");
        p.openInventory(inv);
    }

    public void openResearch(Player p) {
        renderResearch(p);
    }

    private void renderResearch(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        String title = gui.getString("research.title", "Research Lab");
        int size = gui.getInt("research.size", 45);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        int startSlot = gui.getInt("research.layout.start_button", 40);
        var startIcon = gui.getConfigurationSection("research.icons.start");
        inv.setItem(startSlot, icon(startIcon.getString("material"), startIcon.getString("name")));

        // Claim button
        int claimSlot = gui.getInt("research.layout.claim_button", 41);
        String claimMat = Optional.ofNullable(gui.getConfigurationSection("research.icons.claim")).map(cs -> cs.getString("material")).orElse("CHEST");
        String claimName = Optional.ofNullable(gui.getConfigurationSection("research.icons.claim")).map(cs -> cs.getString("name")).orElse("&aClaim Finished");
        inv.setItem(claimSlot, icon(claimMat, claimName));

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
            ItemStack it = icon(mat, (status.equals("FINISHED") ? "&a" : status.equals("RUNNING") ? "&e" : "&7") + disp);
            ItemMeta meta = it.getItemMeta();
            java.util.List<Component> lore = new java.util.ArrayList<>();
            lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Status: &f" + status));
            if (status.equals("RUNNING")) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&7Time left: &f" + (remain/60) + "m"));
            if (status.equals("FINISHED")) lore.add(pl.yourserver.scientistPlugin.util.Texts.legacy("&aClick Claim Finished"));
            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
        }

        openGui.put(p.getUniqueId(), "research");
        p.openInventory(inv);
    }

    private int researchGridSlot(int index) {
        // grid slots: 10..16 and 19..25 (2 rows x7)
        int row = index / 7; int col = index % 7;
        return (row == 0 ? 10 : 19) + col;
    }

    public void openRecipes(Player p) { recipePage.put(p.getUniqueId(), 0); renderRecipes(p); }

    private void renderRecipes(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        String title = gui.getString("recipes.title", "Recipe Book");
        int size = gui.getInt("recipes.size", 54);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        int next = gui.getInt("recipes.layout.next", 53);
        int prev = gui.getInt("recipes.layout.prev", 45);
        inv.setItem(next, icon(gui.getConfigurationSection("recipes.icons.next").getString("material"), gui.getConfigurationSection("recipes.icons.next").getString("name")));
        inv.setItem(prev, icon(gui.getConfigurationSection("recipes.icons.prev").getString("material"), gui.getConfigurationSection("recipes.icons.prev").getString("name")));

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
            ItemStack it = icon(unlocked ? "BOOK" : "GRAY_DYE", (unlocked ? "&a" : "&7") + display);
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
        recipeSlotMap.put(p.getUniqueId(), slotMap);
        openGui.put(p.getUniqueId(), "recipes");
        p.openInventory(inv);
    }

    private int toGridSlot(int index) {
        int row = index / 7; // 0..3
        int col = index % 7; // 0..6
        return (row == 0 ? 10 : row == 1 ? 19 : row == 2 ? 28 : 37) + col;
    }

    public void openAbyssal(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        String title = gui.getString("abyssal.title", "Abyssal Enchanting");
        int size = gui.getInt("abyssal.size", 45);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        int rollSlot = gui.getInt("abyssal.layout.roll_button", 31);
        var rollIcon = gui.getConfigurationSection("abyssal.icons.roll");
        inv.setItem(rollSlot, icon(rollIcon.getString("material"), rollIcon.getString("name")));
        openGui.put(p.getUniqueId(), "abyssal");
        p.openInventory(inv);
    }

    public void openConfirm(Player p) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        String title = gui.getString("confirm.title", "Confirm Abyssal Application");
        int size = gui.getInt("confirm.size", 27);
        Inventory inv = Bukkit.createInventory(p, size, Component.text(title));
        int yes = gui.getInt("confirm.layout.yes", 11);
        int no = gui.getInt("confirm.layout.no", 15);
        var yesIcon = gui.getConfigurationSection("confirm.icons.yes");
        var noIcon = gui.getConfigurationSection("confirm.icons.no");
        inv.setItem(yes, icon(yesIcon.getString("material"), yesIcon.getString("name")));
        inv.setItem(no, icon(noIcon.getString("material"), noIcon.getString("name")));
        openGui.put(p.getUniqueId(), "confirm");
        p.openInventory(inv);
    }

    private ItemStack icon(String materialName, String display) {
        Material mat = Material.matchMaterial(materialName == null ? "PAPER" : materialName.toUpperCase(Locale.ROOT));
        if (mat == null) mat = Material.PAPER;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(pl.yourserver.scientistPlugin.util.Texts.legacy(display == null ? "" : display));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        String ctx = openGui.get(he.getUniqueId());
        if (ctx == null) return;
        e.setCancelled(true);
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        FileConfiguration gui = plugin.getConfigManager().gui();

        switch (ctx) {
            case "main" -> {
                int rs = gui.getInt("main.layout.research_slot");
                int rcs = gui.getInt("main.layout.recipe_slot");
                int as = gui.getInt("main.layout.abyssal_slot");
                if (e.getRawSlot() == rs) openResearch(p);
                if (e.getRawSlot() == rcs) openRecipes(p);
                if (e.getRawSlot() == as) openAbyssal(p);
            }
            case "research" -> {
                int start = gui.getInt("research.layout.start_button", 40);
                if (e.getRawSlot() == start) {
                    plugin.getResearchService().attemptStart(p, e.getInventory());
                    renderResearch(p);
                    return;
                }
                int claimSlot = gui.getInt("research.layout.claim_button", 41);
                if (e.getRawSlot() == claimSlot) {
                    plugin.getResearchService().claimFinished(p);
                    renderResearch(p);
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
                if (e.getRawSlot() == next) {
                    if (page < maxPage) recipePage.put(p.getUniqueId(), page + 1);
                    renderRecipes(p);
                } else if (e.getRawSlot() == prev) {
                    if (page > 0) recipePage.put(p.getUniqueId(), page - 1);
                    renderRecipes(p);
                } else {
                    String key = recipeSlotMap.getOrDefault(p.getUniqueId(), java.util.Collections.emptyMap()).get(e.getRawSlot());
                    if (key != null) {
                        var status = plugin.getResearchService().getPlayerRecipeStatus(p.getUniqueId());
                        boolean unlocked = status.getOrDefault(key, new pl.yourserver.scientistPlugin.research.ResearchService.RecipeStatus(false, 0)).unlocked;
                        if (unlocked) openAbyssal(p);
                    }
                }
            }
            case "abyssal" -> {
                int roll = gui.getInt("abyssal.layout.roll_button", 31);
                int selectA = gui.getInt("abyssal.layout.select_a", 30);
                int selectB = gui.getInt("abyssal.layout.select_b", 32);
                int reject = gui.getInt("abyssal.layout.reject", 40);
                if (e.getRawSlot() == roll) {
                    plugin.getAbyssalService().rollOptions(p, e.getInventory(), rollStates);
                } else if (e.getRawSlot() == selectA) {
                    pendingChoice.put(p.getUniqueId(), 0);
                    openConfirm(p);
                } else if (e.getRawSlot() == selectB) {
                    pendingChoice.put(p.getUniqueId(), 1);
                    openConfirm(p);
                } else if (e.getRawSlot() == reject) {
                    rollStates.remove(p.getUniqueId());
                    String text = plugin.getConfigManager().messages().getString("reject_done", "&7Roll discarded. No changes applied.");
                    p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
                    p.closeInventory();
                }
            }
            case "confirm" -> {
                int yes = gui.getInt("confirm.layout.yes", 11);
                int no = gui.getInt("confirm.layout.no", 15);
                if (e.getRawSlot() == yes) {
                    Integer choice = pendingChoice.remove(p.getUniqueId());
                    if (choice != null) {
                        plugin.getAbyssalService().applySelected(p, rollStates, choice);
                    }
                } else if (e.getRawSlot() == no) {
                    pendingChoice.remove(p.getUniqueId());
                    p.closeInventory();
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        openGui.remove(id);
        rollStates.remove(id);
        pendingChoice.remove(id);
        recipeSlotMap.remove(id);
    }

    public static class RollState {
        public String[] keys = new String[2];
        public double[] min = new double[2];
        public double[] max = new double[2];
        public int tier;
        public String category;
        public Inventory invRef;
    }
}
