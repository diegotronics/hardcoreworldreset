package com.frankloq.arena;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

// Remembers who caused each reset. Lives next to hardcore_state.txt in the world folder, which
// the reset never deletes, so the list survives from one attempt to the next.
// One line per culprit: <try number> TAB <player name> TAB <death message as JSON text>.
// The death message is stored as a JSON text component so every client keeps seeing it in its
// own language instead of a fixed English string.
public final class HallOfShame {

    private static final String FILE_NAME = "hardcore_hall_of_shame.txt";

    public record Entry(int tryNumber, String name, Text cause) {
    }

    private HallOfShame() {
    }

    public static void record(MinecraftServer server, int tryNumber, String name, Text cause) {
        try {
            String json = Text.Serialization.toJsonString(cause, server.getRegistryManager());
            String line = tryNumber + "\t" + name + "\t" + json.replace('\n', ' ').replace('\r', ' ') + System.lineSeparator();
            Files.writeString(file(server), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to write the hall of shame", e);
        }
    }

    // The most recent "limit" entries, oldest first
    public static List<Entry> load(MinecraftServer server, int limit) {
        List<Entry> entries = new ArrayList<>();
        try {
            Path path = file(server);
            if (!Files.exists(path)) return entries;

            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                try {
                    Text cause = Text.Serialization.fromJson(parts[2], server.getRegistryManager());
                    entries.add(new Entry(Integer.parseInt(parts[0].trim()), parts[1],
                            cause == null ? Text.literal("?") : cause));
                } catch (Exception ignored) {
                    // A corrupt line should not hide the rest of the list
                }
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to read the hall of shame", e);
        }

        if (entries.size() > limit) {
            return new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
        }
        return entries;
    }

    private static Path file(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).normalize().resolve(FILE_NAME);
    }
}
