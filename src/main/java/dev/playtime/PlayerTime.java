package dev.playtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.playtime.application.PlaytimeService;
import dev.playtime.command.CommandPlaytime;
import dev.playtime.repository.PlaytimeRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PlayerTime extends JavaPlugin {

    private HikariDataSource dataSource;
    private PlaytimeService playtimeService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            dataSource = buildDataSource();
        } catch (Exception e) {
            getLogger().severe("Datenbankverbindung fehlgeschlagen: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlaytimeRepository repository = new PlaytimeRepository(dataSource);
        try {
            repository.createTable();
        } catch (SQLException e) {
            getLogger().severe("Tabelle konnte nicht erstellt werden: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playtimeService = new PlaytimeService(repository, this, getLogger(), Clock.systemUTC());
        playtimeService.start();
        getServer().getPluginManager().registerEvents(playtimeService, this);

        Map<String, String> messages = loadMessages();
        int rateLimitSeconds = Math.max(0, getConfig().getInt("rate-limit-seconds", 3));
        PluginCommand cmd = Objects.requireNonNull(getCommand("playtime"), "'playtime' fehlt in plugin.yml");
        CommandPlaytime cmdHandler = new CommandPlaytime(playtimeService, this, messages, rateLimitSeconds);
        cmd.setExecutor(cmdHandler);
        cmd.setTabCompleter(cmdHandler);

        getLogger().info("PlayerTime aktiviert.");
    }

    @Override
    public void onDisable() {
        if (playtimeService != null) playtimeService.stop();
        if (dataSource != null) dataSource.close();
        getLogger().info("PlayerTime deaktiviert.");
    }

    private HikariDataSource buildDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(getConfig().getString("database.url", "jdbc:mysql://localhost:3306/minecraft"));
        cfg.setUsername(getConfig().getString("database.username", "root"));
        cfg.setPassword(getConfig().getString("database.password", ""));
        cfg.setMaximumPoolSize(getConfig().getInt("database.pool-size", 5));
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        return new HikariDataSource(cfg);
    }

    private Map<String, String> loadMessages() {
        Map<String, String> msgs = new HashMap<>();
        var section = getConfig().getConfigurationSection("playtime");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                msgs.put(key, section.getString(key, ""));
            }
        }
        return msgs;
    }
}

