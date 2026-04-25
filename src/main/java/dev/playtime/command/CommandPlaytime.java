package dev.playtime.command;

import dev.playtime.application.PlaytimeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CommandPlaytime implements CommandExecutor, TabCompleter {

    private final PlaytimeService playtimeService;
    private final JavaPlugin plugin;
    private final Map<String, String> messages;
    private final long rateLimitMillis;

    // letzter Aufruf pro UUID für den Rate-Limiter
    private final Map<UUID, Long> lastUsed = new ConcurrentHashMap<>();

    public CommandPlaytime(PlaytimeService playtimeService, JavaPlugin plugin,
                           Map<String, String> messages, int rateLimitSeconds) {
        this.playtimeService  = playtimeService;
        this.plugin           = plugin;
        this.messages         = messages;
        this.rateLimitMillis  = rateLimitSeconds * 1000L;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        // Rate Limiter – gilt nur für Spieler, nicht für die Konsole
        if (sender instanceof Player player && rateLimitMillis > 0) {
            long now = System.currentTimeMillis();
            Long last = lastUsed.get(player.getUniqueId());
            if (last != null) {
                long elapsed = now - last;
                if (elapsed < rateLimitMillis) {
                    long remaining = (rateLimitMillis - elapsed + 999) / 1000;
                    String msg = msg("rate-limited").replace("{remaining}", String.valueOf(remaining));
                    sender.sendMessage(legacy(msg));
                    return true;
                }
            }
            lastUsed.put(player.getUniqueId(), now);
        }

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Dieser Befehl kann nur von Spielern genutzt werden.");
                return true;
            }
            playtimeService.getPlaytime(player.getUniqueId())
                    .thenAccept(opt -> {
                        Component msg = opt.isEmpty()
                                ? legacy(msg("no-data"))
                                : legacy(msg("own").replace("{time}",
                                        playtimeService.format(opt.get().totalPlaytime())));
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> { if (player.isOnline()) player.sendMessage(msg); });
                    });
        } else {
            if (!sender.hasPermission("dev.playtime.other")) {
                sender.sendMessage(legacy(msg("no-permission")));
                return true;
            }
            String targetName = args[0];
            playtimeService.getPlaytimeByName(targetName)
                    .thenAccept(opt -> {
                        Component msg = opt.isEmpty()
                                ? legacy(msg("not-found").replace("{player}", targetName))
                                : legacy(msg("other")
                                        .replace("{player}", opt.get().playerName())
                                        .replace("{time}", playtimeService.format(opt.get().totalPlaytime())));
                        if (sender instanceof Player p) {
                            plugin.getServer().getScheduler().runTask(plugin,
                                    () -> { if (p.isOnline()) p.sendMessage(msg); });
                        } else {
                            sender.sendMessage(msg);
                        }
                    });
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("dev.playtime.other")) {
            String prefix = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private String msg(String key) {
        return messages.getOrDefault(key, "<key:" + key + ">");
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
