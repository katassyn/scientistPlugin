package pl.yourserver.scientistPlugin.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import pl.yourserver.scientistPlugin.ScientistPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class Database {
    private final ScientistPlugin plugin;
    private HikariDataSource ds;

    public Database(ScientistPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        var cfg = plugin.getConfig();
        String host = cfg.getString("mysql.host");
        int port = cfg.getInt("mysql.port");
        String db = cfg.getString("mysql.database");
        String user = cfg.getString("mysql.user");
        String pass = cfg.getString("mysql.password");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(cfg.getInt("mysql.pool.maxPoolSize", 10));
        hc.setMinimumIdle(cfg.getInt("mysql.pool.minimumIdle", 2));
        hc.setPoolName("ScientistHikari");
        ds = new HikariDataSource(hc);

        createSchema();
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    public void shutdown() {
        if (ds != null) ds.close();
    }

    private void createSchema() {
        try (Connection c = getConnection()) {
            // sci_recipe
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS sci_recipe (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY," +
                            "recipe_key VARCHAR(64) UNIQUE NOT NULL," +
                            "title VARCHAR(96) NOT NULL," +
                            "description TEXT)")) {
                ps.execute();
            }
            // sci_player_recipe
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS sci_player_recipe (" +
                            "player_uuid BINARY(16) NOT NULL," +
                            "recipe_key VARCHAR(64) NOT NULL," +
                            "unlocked TINYINT(1) NOT NULL DEFAULT 0," +
                            "experiments_done INT NOT NULL DEFAULT 0," +
                            "PRIMARY KEY (player_uuid, recipe_key)," +
                            "FOREIGN KEY (recipe_key) REFERENCES sci_recipe(recipe_key))")) {
                ps.execute();
            }
            // sci_experiment
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS sci_experiment (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                            "player_uuid BINARY(16) NOT NULL," +
                            "recipe_key VARCHAR(64) NOT NULL," +
                            "started_at TIMESTAMP NOT NULL," +
                            "end_at TIMESTAMP NOT NULL," +
                            "status ENUM('RUNNING','FINISHED','CLAIMED') NOT NULL DEFAULT 'RUNNING'," +
                            "reagents_json JSON NOT NULL," +
                            "FOREIGN KEY (recipe_key) REFERENCES sci_recipe(recipe_key)," +
                            "INDEX (player_uuid), INDEX (status), INDEX (end_at))")) {
                ps.execute();
            }
            // sci_abyssal_log
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS sci_abyssal_log (" +
                            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                            "player_uuid BINARY(16) NOT NULL," +
                            "item_uid VARCHAR(64) NOT NULL," +
                            "category VARCHAR(32) NOT NULL," +
                            "modifier_key VARCHAR(64) NOT NULL," +
                            "tier TINYINT NOT NULL," +
                            "rolled_min DOUBLE NOT NULL," +
                            "rolled_max DOUBLE NOT NULL," +
                            "final_value DOUBLE NOT NULL," +
                            "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "INDEX (player_uuid), INDEX (item_uid))")) {
                ps.execute();
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[Scientist] Failed to create schema: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

