package dev.playtime.application;

import dev.playtime.domain.PlayerPlaytime;
import dev.playtime.repository.PlaytimeEntity;
import dev.playtime.repository.PlaytimeRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PlaytimeService implements Listener {

    private static final long FLUSH_INTERVAL_TICKS = 1200;

    private final PlaytimeRepository repository;
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Clock clock;

    private record SessionEntry(String playerName, Instant start, Instant end) {
        boolean isEnded() { return end != null; }
    }

    private final Map<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public PlaytimeService(PlaytimeRepository repository, JavaPlugin plugin, Logger logger, Clock clock) {
        this.repository = repository;
        this.plugin = plugin;
        this.logger = logger;
        this.clock = clock;
    }

    public void start() {
        flushTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushAll, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        // letzte Sessions vor dem Shutdown wegschreiben
        flushAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        SessionEntry old = sessions.put(uuid, new SessionEntry(name, Instant.now(clock), null));
        if (old != null && old.isEnded()) {
            // Reconnect vor Flush: alte Session sofort wegschreiben
            long delta = Duration.between(old.start(), old.end()).toSeconds();
            if (delta > 0)
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                        () -> writeToDb(uuid, old.playerName(), delta));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.computeIfPresent(event.getPlayer().getUniqueId(),
                (id, entry) -> new SessionEntry(entry.playerName(), entry.start(), Instant.now(clock)));
    }

    public CompletableFuture<Optional<PlayerPlaytime>> getPlaytime(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PlaytimeEntity> entity = repository.findById(playerId);
                if (entity.isPresent()) {
                    return Optional.of(buildWithSession(entity.get(), playerId));
                }
                // Spieler noch nicht in DB: Session direkt aus dem Cache lesen
                SessionEntry entry = sessions.get(playerId);
                if (entry != null && !entry.isEnded()) {
                    return Optional.of(new PlayerPlaytime(entry.playerName(),
                            Duration.between(entry.start(), Instant.now(clock))));
                }
                return Optional.<PlayerPlaytime>empty();
            } catch (Exception ex) {
                logger.severe("[Playtime] Fehler beim Laden der Playtime für " + playerId + ": " + ex.getMessage());
                return Optional.<PlayerPlaytime>empty();
            }
        }).orTimeout(5, TimeUnit.SECONDS)
          .exceptionally(ex -> Optional.empty());
    }

    public CompletableFuture<Optional<PlayerPlaytime>> getPlaytimeByName(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PlaytimeEntity> entity = repository.findByNameIgnoreCase(name);
                if (entity.isPresent()) {
                    return Optional.of(buildWithSession(entity.get(), entity.get().getPlayerId()));
                }
                // Spieler noch nicht in DB: Session direkt aus dem Cache lesen
                for (Map.Entry<UUID, SessionEntry> e : sessions.entrySet()) {
                    if (e.getValue().playerName().equalsIgnoreCase(name) && !e.getValue().isEnded()) {
                        SessionEntry live = e.getValue();
                        return Optional.of(new PlayerPlaytime(live.playerName(),
                                Duration.between(live.start(), Instant.now(clock))));
                    }
                }
                return Optional.<PlayerPlaytime>empty();
            } catch (Exception ex) {
                logger.severe("[Playtime] Fehler beim Laden der Playtime für " + name + ": " + ex.getMessage());
                return Optional.<PlayerPlaytime>empty();
            }
        }).orTimeout(5, TimeUnit.SECONDS)
          .exceptionally(ex -> Optional.empty());
    }

    public String format(Duration duration) {
        long total   = duration.abs().toSeconds();
        long months  = total / 2_592_000L;
        long weeks   = (total % 2_592_000L) / 604_800L;
        long days    = (total % 604_800L) / 86_400L;
        long hours   = (total % 86_400L) / 3_600L;
        long minutes = (total % 3_600L) / 60L;
        long seconds = total % 60L;

        StringJoiner sj = new StringJoiner(" ");
        if (months  > 0) sj.add(String.format("%02dmo", months));
        if (weeks   > 0) sj.add(String.format("%02dw",  weeks));
        if (days    > 0) sj.add(String.format("%02dd",  days));
        if (hours   > 0) sj.add(String.format("%02dh",  hours));
        if (minutes > 0) sj.add(String.format("%02dm",  minutes));
        sj.add(String.format("%02ds", seconds));
        return sj.toString();
    }

    private void flushAll() {
        sessions.forEach((uuid, entry) -> {
            if (entry.isEnded()) {
                if (!sessions.remove(uuid, entry)) return;
                long delta = Duration.between(entry.start(), entry.end()).toSeconds();
                if (delta > 0) writeToDb(uuid, entry.playerName(), delta);
            } else {
                Instant now = Instant.now(clock);
                long delta = Duration.between(entry.start(), now).toSeconds();
                if (delta <= 0) return;
                // CAS: verhindert, dass zwei Threads dieselbe Session doppelt schreiben
                if (!sessions.replace(uuid, entry, new SessionEntry(entry.playerName(), now, null))) return;
                writeToDb(uuid, entry.playerName(), delta);
            }
        });
    }

    private void writeToDb(UUID uuid, String name, long delta) {
        try {
            repository.upsert(uuid, name, delta, Instant.now(clock));
        } catch (Exception e) {
            logger.warning("[Playtime] Flush-Fehler für " + name + ": " + e.getMessage());
        }
    }

    // DB-Zeit + laufende Session zusammenrechnen
    private PlayerPlaytime buildWithSession(PlaytimeEntity entity, UUID playerId) {
        Duration base = Duration.ofSeconds(entity.getPlaytimeSeconds());
        SessionEntry entry = sessions.get(playerId);
        if (entry != null) {
            Instant end = entry.isEnded() ? entry.end() : Instant.now(clock);
            base = base.plus(Duration.between(entry.start(), end));
        }
        return new PlayerPlaytime(entity.getPlayerName(), base);
    }
}