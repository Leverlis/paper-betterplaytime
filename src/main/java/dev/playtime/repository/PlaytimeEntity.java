package dev.playtime.repository;

import java.util.UUID;

public class PlaytimeEntity {

    private final UUID playerId;
    private final String playerName;
    private final long playtimeSeconds;

    public PlaytimeEntity(UUID playerId, String playerName, long playtimeSeconds) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.playtimeSeconds = playtimeSeconds;
    }

    public UUID getPlayerId()          { return playerId; }
    public String getPlayerName()      { return playerName; }
    public long getPlaytimeSeconds()   { return playtimeSeconds; }
}
