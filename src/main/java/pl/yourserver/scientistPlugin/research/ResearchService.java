package pl.yourserver.scientistPlugin.research;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class ResearchService {
    private final ScientistPlugin plugin;
    private final Gson gson = new Gson();
    private int taskId = -1;

    public ResearchService(ScientistPlugin plugin) {
        this.plugin = plugin;
    }

    public void startReadyPoller() {
        // every 60s mark finished
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try (Connection c = plugin.getDatabase().getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE sci_experiment SET status='FINISHED' WHERE status='RUNNING' AND end_at <= NOW()")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }, 20L * 60, 20L * 60).getTaskId();
    }

    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    public void attemptStart(Player p, Inventory inv) {
        FileConfiguration gui = plugin.getConfigManager().gui();
        FileConfiguration recipesCfg = plugin.getConfigManager().recipes();
        int[] slots = gui.getIntegerList("research.layout.input_slots").stream().mapToInt(i -> i).toArray();
        Map<String, Integer> counts = new HashMap<>();

        for (int s : slots) {
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            String key = extractKeyFromItem(it);
            if (key == null) continue;
            counts.merge(key, it.getAmount(), Integer::sum);
        }

        // Concurrency check
        int running = 0;
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sci_experiment WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND status='RUNNING'")) {
            ps.setString(1, p.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) running = rs.getInt(1); }
        } catch (SQLException e) { e.printStackTrace(); }
        int maxConc = plugin.getConfig().getInt("experiments.max_concurrent_per_player", 2);
        if (running >= maxConc) {
            String text = plugin.getConfigManager().messages().getString("experiments_max_reached", "&cYou have reached the maximum concurrent experiments.");
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return;
        }

        // Find first recipe we can satisfy (and prerequisites met)
        ConfigurationSection recs = recipesCfg.getConfigurationSection("recipes");
        if (recs == null) return;
        String chosenKey = null;
        int durationHours = plugin.getConfig().getInt("experiments.default_duration_hours", 4);
        for (String rKey : recs.getKeys(false)) {
            ConfigurationSection rs = recs.getConfigurationSection(rKey);
            ConfigurationSection req = rs.getConfigurationSection("reagents");
            boolean ok = true;
            for (String k : req.getKeys(false)) {
                int need = req.getInt(k);
                int have = counts.getOrDefault(k, 0);
                if (have < need) { ok = false; break; }
            }
            if (ok && prerequisitesMet(p.getUniqueId(), rs)) {
                chosenKey = rKey;
                durationHours = rs.getInt("duration_hours", durationHours);
                break;
            }
        }
        if (chosenKey == null) {
            String text = plugin.getConfigManager().messages().getString("experiment_no_match", "&cNo matching experiment for these reagents or prerequisites not met.");
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return;
        }

        // Remove items from inventory slots
        ConfigurationSection req = recs.getConfigurationSection(chosenKey).getConfigurationSection("reagents");
        Map<String, Integer> toRemove = new HashMap<>();
        req.getKeys(false).forEach(k -> toRemove.put(k, req.getInt(k)));
        for (int s : slots) {
            if (toRemove.isEmpty()) break;
            ItemStack it = inv.getItem(s);
            if (it == null || it.getType().isAir()) continue;
            String k = extractKeyFromItem(it);
            if (k == null || !toRemove.containsKey(k)) continue;
            int need = toRemove.get(k);
            int take = Math.min(need, it.getAmount());
            it.setAmount(it.getAmount() - take);
            need -= take;
            if (need <= 0) toRemove.remove(k); else toRemove.put(k, need);
            inv.setItem(s, it.getAmount() <= 0 ? null : it);
        }

        // Insert DB row
        boolean debug = plugin.getConfig().getBoolean("debug.mode", false);
        long endAt = Instant.now().plusSeconds(debug ? plugin.getConfig().getLong("debug.short_experiments_seconds", 5L) : durationHours * 3600L).getEpochSecond();
        Map<String, Integer> reagentsUsed = new HashMap<>();
        req.getKeys(false).forEach(k -> reagentsUsed.put(k, req.getInt(k)));
        try (Connection c = plugin.getDatabase().getConnection()) {
            // ensure recipe exists
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO sci_recipe (recipe_key,title,description) VALUES (?,?,?)")) {
                String title = recs.getConfigurationSection(chosenKey).getString("title", chosenKey);
                String desc = recs.getConfigurationSection(chosenKey).getString("description", "");
                ps.setString(1, chosenKey);
                ps.setString(2, title);
                ps.setString(3, desc);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sci_experiment (player_uuid,recipe_key,started_at,end_at,status,reagents_json) VALUES (UNHEX(REPLACE(?,'-','')),?,FROM_UNIXTIME(?),FROM_UNIXTIME(?),'RUNNING',?)")) {
                ps.setString(1, p.getUniqueId().toString());
                ps.setString(2, chosenKey);
                ps.setLong(3, Instant.now().getEpochSecond());
                ps.setLong(4, endAt);
                ps.setString(5, gson.toJson(reagentsUsed));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        String title = recs.getConfigurationSection(chosenKey).getString("title", chosenKey);
        final String sendTitle = title;
        final int sendHours = durationHours;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String msgText = plugin.getConfigManager().messages().getString("experiment_started", "Experiment started");
            msgText = msgText.replace("{recipe}", sendTitle).replace("{hours}", String.valueOf(sendHours));
            p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(msgText));
        });
    }

    private boolean prerequisitesMet(UUID uuid, ConfigurationSection recipeSection) {
        List<String> reqs = recipeSection.getStringList("requires");
        if (reqs == null || reqs.isEmpty()) return true;
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT unlocked FROM sci_player_recipe WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND recipe_key=? LIMIT 1")) {
            for (String key : reqs) {
                ps.setString(1, uuid.toString());
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 1) return false;
                }
            }
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String extractKeyFromItem(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        String dn = String.valueOf(meta.displayName());
        // Expect exact key as display (for placeholder). Servers with MythicMobs can adapt mapping here.
        if (dn != null && dn.length() > 0) {
            // Component text format includes content=...; do a simple heuristic
            int i = dn.indexOf("content=");
            if (i >= 0) {
                String sub = dn.substring(i + 8);
                int end = sub.indexOf(",");
                if (end > 0) return sub.substring(0, end).trim();
            }
        }
        return null;
    }

    public void sendProgress(Player p) {
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT recipe_key, status, UNIX_TIMESTAMP(end_at) end_ts FROM sci_experiment WHERE player_uuid = UNHEX(REPLACE(?,'-','')) ORDER BY id DESC LIMIT 10")) {
            ps.setString(1, p.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                p.sendMessage(plugin.getConfigManager().messages().getString("progress_header"));
                while (rs.next()) {
                    any = true;
                    String key = rs.getString("recipe_key");
                    String status = rs.getString("status");
                    long endTs = rs.getLong("end_ts");
                    long now = Instant.now().getEpochSecond();
                    long remain = Math.max(0, endTs - now);
                    p.sendMessage(" - " + key + " [" + status + "] " + (status.equals("RUNNING") ? (remain/60) + "m left" : "ready"));
                }
                if (!any) p.sendMessage(plugin.getConfigManager().messages().getString("no_running_experiments"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void claimFinished(Player p) {
        // Increment experiments_done and unlock if threshold reached
        try (Connection c = plugin.getDatabase().getConnection()) {
            // Fetch finished experiments
            List<String> finished = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, recipe_key FROM sci_experiment WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND status='FINISHED'")) {
                ps.setString(1, p.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) finished.add(rs.getString("recipe_key"));
                }
            }
            if (finished.isEmpty()) {
                p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy("&7No finished experiments to claim."));
                return;
            }
            // Update claimed
            try (PreparedStatement ps = c.prepareStatement("UPDATE sci_experiment SET status='CLAIMED' WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND status='FINISHED'")) {
                ps.setString(1, p.getUniqueId().toString());
                ps.executeUpdate();
            }
            // Increment per-recipe counters and unlock
            FileConfiguration recipesCfg = plugin.getConfigManager().recipes();
            for (String r : finished) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO sci_player_recipe (player_uuid,recipe_key,unlocked,experiments_done) VALUES (UNHEX(REPLACE(?,'-','')),?,0,0) ON DUPLICATE KEY UPDATE experiments_done = experiments_done + 1")) {
                    ins.setString(1, p.getUniqueId().toString());
                    ins.setString(2, r);
                    ins.executeUpdate();
                }
                int threshold = recipesCfg.getConfigurationSection("recipes").getConfigurationSection(r).getInt("experiments_to_unlock", 5);
                try (PreparedStatement upd = c.prepareStatement("UPDATE sci_player_recipe SET unlocked = (experiments_done >= ?) WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND recipe_key=?")) {
                    upd.setInt(1, threshold);
                    upd.setString(2, p.getUniqueId().toString());
                    upd.setString(3, r);
                    upd.executeUpdate();
                }
                String text = plugin.getConfigManager().messages().getString("claim_success", "&aClaimed experiment rewards for {recipe}.");
                text = text.replace("{recipe}", r);
                p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static class UIExperiment {
        public final String recipeKey;
        public final String status;
        public final long endEpoch;
        public UIExperiment(String recipeKey, String status, long endEpoch) {
            this.recipeKey = recipeKey; this.status = status; this.endEpoch = endEpoch;
        }
    }

    public java.util.List<UIExperiment> listExperiments(UUID uuid, int limit) {
        java.util.List<UIExperiment> list = new java.util.ArrayList<>();
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT recipe_key, status, UNIX_TIMESTAMP(end_at) end_ts FROM sci_experiment WHERE player_uuid = UNHEX(REPLACE(?,'-','')) ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new UIExperiment(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /**
     * Fetch per-player recipe status map (unlocked flag and experiments_done)
     */
    public Map<String, RecipeStatus> getPlayerRecipeStatus(java.util.UUID uuid) {
        Map<String, RecipeStatus> map = new HashMap<>();
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT recipe_key, unlocked, experiments_done FROM sci_player_recipe WHERE player_uuid=UNHEX(REPLACE(?,'-',''))")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    boolean unlocked = rs.getInt(2) == 1;
                    int done = rs.getInt(3);
                    map.put(key, new RecipeStatus(unlocked, done));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    public static class RecipeStatus {
        public final boolean unlocked;
        public final int experimentsDone;
        public RecipeStatus(boolean unlocked, int experimentsDone) {
            this.unlocked = unlocked;
            this.experimentsDone = experimentsDone;
        }
    }
}
