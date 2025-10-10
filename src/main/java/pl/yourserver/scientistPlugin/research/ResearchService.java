package pl.yourserver.scientistPlugin.research;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import pl.yourserver.scientistPlugin.ScientistPlugin;
import pl.yourserver.scientistPlugin.item.ItemService;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class ResearchService {
    private final ScientistPlugin plugin;
    private final ItemService itemService;
    private final Gson gson = new Gson();
    private int taskId = -1;

    public ResearchService(ScientistPlugin plugin, ItemService itemService) {
        this.plugin = plugin;
        this.itemService = itemService;
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

    public boolean attemptStart(Player player, String recipeKey) {
        if (player == null || recipeKey == null || recipeKey.isEmpty()) {
            return false;
        }

        FileConfiguration recipesCfg = plugin.getConfigManager().recipes();
        ConfigurationSection recipe = recipesCfg.getConfigurationSection("recipes." + recipeKey);
        if (recipe == null) {
            String text = plugin.getConfigManager().messages().getString("experiment_no_match", "&cNo matching experiment for these reagents or prerequisites not met.");
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return false;
        }

        Map<String, RecipeStatus> status = getPlayerRecipeStatus(player.getUniqueId());
        if (!prerequisitesMet(status, recipe)) {
            List<String> reqs = recipe.getStringList("requires");
            String missing = (reqs == null || reqs.isEmpty()) ? recipeKey : String.join(", ", reqs);
            String text = plugin.getConfigManager().messages().getString("experiment_prereq", "&cYou must unlock previous tier first: {key}");
            text = text.replace("{key}", missing);
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return false;
        }

        int running = countRunningExperiments(player.getUniqueId());
        int maxConc = plugin.getConfig().getInt("experiments.max_concurrent_per_player", 1);
        if (running >= maxConc) {
            String text = plugin.getConfigManager().messages().getString("experiments_max_reached", "&cYou have reached the maximum concurrent experiments.");
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return false;
        }

        ConfigurationSection reagentsSec = recipe.getConfigurationSection("reagents");
        Map<String, Integer> reagents = new LinkedHashMap<>();
        if (reagentsSec != null) {
            for (String key : reagentsSec.getKeys(false)) {
                reagents.put(key, Math.max(0, reagentsSec.getInt(key)));
            }
        }

        Map<String, Integer> shortages = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : reagents.entrySet()) {
            int have = itemService.countTotal(player, entry.getKey());
            if (have < entry.getValue()) {
                shortages.put(entry.getKey(), entry.getValue() - have);
            }
        }
        if (!shortages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            shortages.forEach((key, miss) -> sb.append(key).append(" x").append(miss).append(", "));
            if (sb.length() > 2) {
                sb.setLength(sb.length() - 2);
            }
            String text = plugin.getConfigManager().messages().getString("experiment_missing_reagents", "&cMissing reagents: {items}");
            text = text.replace("{items}", sb.toString());
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return false;
        }

        if (!itemService.withdrawReagents(player, reagents)) {
            String text = plugin.getConfigManager().messages().getString("experiment_withdraw_failed", "&cCould not withdraw reagents. Try again.");
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(text));
            return false;
        }

        int durationHours = recipe.getInt("duration_hours", plugin.getConfig().getInt("experiments.default_duration_hours", 4));
        long start = Instant.now().getEpochSecond();
        long endAt = start + durationHours * 3600L;

        Map<String, Integer> reagentsUsed = new LinkedHashMap<>(reagents);
        try (Connection c = plugin.getDatabase().getConnection()) {
            String title = recipe.getString("title", recipeKey);
            String desc = recipe.getString("description", "");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sci_experiment_log (player_uuid, recipe_key, started_at, metadata) VALUES (UNHEX(REPLACE(?,'-','')), ?, NOW(), ?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, title);
                ps.setString(3, desc);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sci_experiment (player_uuid,recipe_key,started_at,end_at,status,reagents_json) VALUES (UNHEX(REPLACE(?,'-','')),?,FROM_UNIXTIME(?),FROM_UNIXTIME(?),'RUNNING',?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, recipeKey);
                ps.setLong(3, start);
                ps.setLong(4, endAt);
                ps.setString(5, gson.toJson(reagentsUsed));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        String title = recipe.getString("title", recipeKey);
        final String sendTitle = title;
        final int sendHours = durationHours;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String msgText = plugin.getConfigManager().messages().getString("experiment_started", "Experiment started");
            msgText = msgText.replace("{recipe}", sendTitle).replace("{hours}", String.valueOf(sendHours));
            player.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(msgText));
        });
        return true;
    }

    public List<TemplateView> buildTemplates(UUID uuid) {
        List<TemplateView> templates = new ArrayList<>();
        FileConfiguration recipesCfg = plugin.getConfigManager().recipes();
        ConfigurationSection recSec = recipesCfg.getConfigurationSection("recipes");
        if (recSec == null) {
            return templates;
        }
        Map<String, RecipeStatus> status = getPlayerRecipeStatus(uuid);
        Map<String, ActiveExperiment> running = getRunningExperiments(uuid);
        for (String key : recSec.getKeys(false)) {
            ConfigurationSection recipe = recSec.getConfigurationSection(key);
            LinkedHashMap<String, Integer> reagents = new LinkedHashMap<>();
            ConfigurationSection reagSec = recipe.getConfigurationSection("reagents");
            if (reagSec != null) {
                for (String reagentKey : reagSec.getKeys(false)) {
                    reagents.put(reagentKey, Math.max(0, reagSec.getInt(reagentKey)));
                }
            }
            List<String> requires = recipe.getStringList("requires");
            RecipeStatus rs = status.getOrDefault(key, new RecipeStatus(false, 0));
            boolean prereq = prerequisitesMet(status, recipe);
            ActiveExperiment active = running.get(key);
            templates.add(new TemplateView(
                    key,
                    recipe.getString("title", key),
                    recipe.getString("description", ""),
                    reagents,
                    requires == null ? Collections.emptyList() : requires,
                    recipe.getInt("duration_hours", plugin.getConfig().getInt("experiments.default_duration_hours", 4)),
                    recipe.getInt("experiments_to_unlock", 5),
                    rs.experimentsDone,
                    rs.unlocked,
                    prereq,
                    active != null,
                    active != null ? active.endEpoch : 0L
            ));
        }
        return templates;
    }

    public Map<String, String> getRecipeTitles() {
        Map<String, String> titles = new LinkedHashMap<>();
        FileConfiguration recipesCfg = plugin.getConfigManager().recipes();
        ConfigurationSection recSec = recipesCfg.getConfigurationSection("recipes");
        if (recSec == null) {
            return titles;
        }
        for (String key : recSec.getKeys(false)) {
            ConfigurationSection recipe = recSec.getConfigurationSection(key);
            titles.put(key, recipe.getString("title", key));
        }
        return titles;
    }

    private Map<String, ActiveExperiment> getRunningExperiments(UUID uuid) {
        Map<String, ActiveExperiment> map = new HashMap<>();
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT recipe_key, UNIX_TIMESTAMP(end_at) end_ts FROM sci_experiment WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND status='RUNNING'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("recipe_key"), new ActiveExperiment(rs.getString("recipe_key"), rs.getLong("end_ts")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    private int countRunningExperiments(UUID uuid) {
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sci_experiment WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND status='RUNNING'")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private boolean prerequisitesMet(Map<String, RecipeStatus> statusMap, ConfigurationSection recipeSection) {
        List<String> reqs = recipeSection.getStringList("requires");
        if (reqs == null || reqs.isEmpty()) {
            return true;
        }
        for (String key : reqs) {
            RecipeStatus st = statusMap.get(key);
            if (st == null || !st.unlocked) {
                return false;
            }
        }
        return true;
    }

    public void sendProgress(Player p) {
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT recipe_key, status, UNIX_TIMESTAMP(end_at) end_ts FROM sci_experiment WHERE player_uuid = UNHEX(REPLACE(?,'-','')) ORDER BY id DESC LIMIT 10")) {
            ps.setString(1, p.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                String prefix = plugin.getConfigManager().messages().getString("prefix", "");
                String header = plugin.getConfigManager().messages().getString("progress_header", "&eYour running experiments:");
                p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + header));
                while (rs.next()) {
                    any = true;
                    String key = rs.getString("recipe_key");
                    String status = rs.getString("status");
                    long endTs = rs.getLong("end_ts");
                    long now = Instant.now().getEpochSecond();
                    long remain = Math.max(0, endTs - now);
                    String line = " &7- &f" + key + " &7[" + status + "] " + ("RUNNING".equals(status) ? ((remain / 60) + "m left") : "ready");
                    p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + line));
                }
                if (!any) {
                    String none = plugin.getConfigManager().messages().getString("no_running_experiments", "&7No running experiments.");
                    p.sendMessage(pl.yourserver.scientistPlugin.util.Texts.legacy(prefix + none));
                }
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

    public static class TemplateView {
        public final String key;
        public final String title;
        public final String description;
        public final Map<String, Integer> reagents;
        public final List<String> requires;
        public final int durationHours;
        public final int unlockThreshold;
        public final int progress;
        public final boolean unlocked;
        public final boolean prerequisitesMet;
        public final boolean running;
        public final long endEpoch;

        public TemplateView(String key, String title, String description,
                             Map<String, Integer> reagents, List<String> requires,
                             int durationHours, int unlockThreshold,
                             int progress, boolean unlocked, boolean prerequisitesMet,
                             boolean running, long endEpoch) {
            this.key = key;
            this.title = title;
            this.description = description;
            this.reagents = reagents;
            this.requires = requires;
            this.durationHours = durationHours;
            this.unlockThreshold = unlockThreshold;
            this.progress = progress;
            this.unlocked = unlocked;
            this.prerequisitesMet = prerequisitesMet;
            this.running = running;
            this.endEpoch = endEpoch;
        }
    }

    public static class ActiveExperiment {
        public final String recipeKey;
        public final long endEpoch;

        public ActiveExperiment(String recipeKey, long endEpoch) {
            this.recipeKey = recipeKey;
            this.endEpoch = endEpoch;
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

    public List<UIExperiment> listExperiments(UUID uuid, int limit) {
        List<UIExperiment> list = new ArrayList<>();
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
    public Map<String, RecipeStatus> getPlayerRecipeStatus(UUID uuid) {
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

