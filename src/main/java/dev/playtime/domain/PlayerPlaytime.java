package dev.playtime.domain;

import java.time.Duration;

public record PlayerPlaytime(String playerName, Duration totalPlaytime) {}
