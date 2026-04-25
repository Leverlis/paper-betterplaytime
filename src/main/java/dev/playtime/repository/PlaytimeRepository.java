package dev.playtime.repository;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class PlaytimeRepository {

    private final DataSource dataSource;

    public PlaytimeRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void createTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS dev_playtime (
                        player_id        VARCHAR(36) NOT NULL PRIMARY KEY,
                        player_name      VARCHAR(16) NOT NULL,
                        playtime_seconds BIGINT      NOT NULL DEFAULT 0,
                        last_seen        BIGINT      NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_dev_playtime_name
                        ON dev_playtime (player_name)
                    """);
        }
    }

    public Optional<PlaytimeEntity> findById(UUID playerId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_id, player_name, playtime_seconds, last_seen FROM dev_playtime WHERE player_id = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<PlaytimeEntity> findByNameIgnoreCase(String name) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_id, player_name, playtime_seconds, last_seen FROM dev_playtime WHERE player_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    // Zeit addieren falls bereits vorhanden, sonst neu anlegen
    public void upsert(UUID playerId, String playerName, long delta, Instant now) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO dev_playtime (player_id, player_name, playtime_seconds, last_seen)
                     VALUES (?, ?, ?, ?)
                     ON DUPLICATE KEY UPDATE
                         playtime_seconds = playtime_seconds + VALUES(playtime_seconds),
                         player_name      = VALUES(player_name),
                         last_seen        = VALUES(last_seen)
                     """)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, playerName);
            ps.setLong(3, delta);
            ps.setLong(4, now.getEpochSecond());
            ps.executeUpdate();
        }
    }

    private PlaytimeEntity mapRow(ResultSet rs) throws SQLException {
        return new PlaytimeEntity(
                UUID.fromString(rs.getString("player_id")),
                rs.getString("player_name"),
                rs.getLong("playtime_seconds")
        );
    }
}
