package pl.yourserver.scientistPlugin.api;

import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PublicAPI {
    public static boolean isRecipeUnlocked(UUID uuid, String recipeKey) {
        ScientistPlugin plugin = ScientistPlugin.getInstance();
        if (plugin == null || plugin.getDatabase() == null) return false;
        try (Connection c = plugin.getDatabase().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT unlocked FROM sci_player_recipe WHERE player_uuid=UNHEX(REPLACE(?,'-','')) AND recipe_key=? LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, recipeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 1;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static Map<String, String> getResearchTitles() {
        ScientistPlugin plugin = ScientistPlugin.getInstance();
        Map<String, String> titles = new LinkedHashMap<>();
        if (plugin == null || plugin.getResearchService() == null) {
            return titles;
        }
        titles.putAll(plugin.getResearchService().getRecipeTitles());
        return titles;
    }

    public static String getResearchTitle(String key) {
        if (key == null) {
            return "";
        }
        return getResearchTitles().getOrDefault(key, key);
    }
}
