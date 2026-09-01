package com.frankloq;

import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

// Tracks how many lives each player has left.
// Lives are persisted per-UUID in <world>/hardcore_lives.properties so they survive
// relogs and server restarts, and are wiped back to the maximum after a world reset.
public class LivesManager {

    private static final String OBJECTIVE_NAME = "hwr_lives";
    private static final Text OBJECTIVE_TITLE = Text.literal("Vidas");
    private static final String LIVES_FILE = "hardcore_lives.properties";

    private static final Map<UUID, Integer> lives = new HashMap<>();

    // Players with no entry yet are at the configured maximum.
    public static int getLives(ServerPlayerEntity player) {
        return lives.getOrDefault(player.getUuid(), HardcoreWorldReset.maxLives);
    }

    // Removes one life and returns how many remain (never below 0).
    public static int decrementLives(ServerPlayerEntity player) {
        int remaining = Math.max(0, getLives(player) - 1);
        lives.put(player.getUuid(), remaining);
        save(player.getServer());
        syncPlayer(player);
        return remaining;
    }

    // Everyone (online and offline) goes back to the maximum.
    public static void resetAllLives(MinecraftServer server) {
        lives.clear();
        save(server);
        syncAll(server);
    }

    public static void load(MinecraftServer server) {
        lives.clear();
        try {
            Path file = getLivesFile(server);
            if (Files.exists(file)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
                for (String key : props.stringPropertyNames()) {
                    try {
                        lives.put(UUID.fromString(key), Integer.parseInt(props.getProperty(key).trim()));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed entries instead of refusing to boot
                    }
                }
                HardcoreWorldReset.LOGGER.info("Loaded remaining lives for {} player(s).", lives.size());
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to load the lives file!", e);
        }
    }

    private static void save(MinecraftServer server) {
        if (server == null) return;
        try {
            Properties props = new Properties();
            for (Map.Entry<UUID, Integer> entry : lives.entrySet()) {
                // Players at full lives are the default already, so storing them would only
                // grow the file forever with a row per player who ever joined.
                if (entry.getValue() == HardcoreWorldReset.maxLives) continue;
                props.setProperty(entry.getKey().toString(), String.valueOf(entry.getValue()));
            }
            try (OutputStream out = Files.newOutputStream(getLivesFile(server))) {
                props.store(out, "Remaining lives per player UUID");
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to save the lives file!", e);
        }
    }

    // Creates the sidebar-free "Lives" objective and pins it to the Tab player list,
    // so every client (vanilla included) sees everyone's remaining lives next to their name.
    public static void initScoreboard(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(OBJECTIVE_NAME);

        if (objective == null) {
            objective = scoreboard.addObjective(
                    OBJECTIVE_NAME,
                    ScoreboardCriterion.DUMMY,
                    OBJECTIVE_TITLE,
                    ScoreboardCriterion.RenderType.INTEGER,
                    true,
                    null
            );
        } else if (!OBJECTIVE_TITLE.equals(objective.getDisplayName())) {
            // Worlds created before the texts were translated still carry the old title
            objective.setDisplayName(OBJECTIVE_TITLE);
        }

        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.LIST, objective);
    }

    public static void syncPlayer(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerScoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(OBJECTIVE_NAME);

        if (objective == null) {
            initScoreboard(server);
            objective = scoreboard.getNullableObjective(OBJECTIVE_NAME);
        }

        if (objective != null) {
            scoreboard.getOrCreateScore(player, objective).setScore(getLives(player));
        }
    }

    public static void syncAll(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            syncPlayer(player);
        }
    }

    private static Path getLivesFile(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).normalize().resolve(LIVES_FILE);
    }
}
