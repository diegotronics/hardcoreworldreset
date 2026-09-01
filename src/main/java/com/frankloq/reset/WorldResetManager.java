package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Unit;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

public class WorldResetManager {

    private static final int PHASE_DELAY_TICKS = 40;

    private static ResetPhase currentPhase = ResetPhase.IDLE;
    private static int phaseTimer = 0;
    private static long newSeed = 0;
    private static boolean countdownLocked = false;

    private static int currentTry = 1;
    private static boolean hasLoadedTryCount = false;

    public static boolean isResetting() {
        return currentPhase != ResetPhase.IDLE;
    }

    public static boolean tryLockCountdown() {
        if (countdownLocked || currentPhase != ResetPhase.IDLE) return false;
        countdownLocked = true;
        return true;
    }

    public static void unlockCountdown() {
        countdownLocked = false;
    }

    public static boolean isCountdownLocked() {
        return countdownLocked;
    }

    // Indestructible try counter system
    public static int getCurrentTry(MinecraftServer server) {
        if (!hasLoadedTryCount) loadTryCount(server);
        return currentTry;
    }

    private static void loadTryCount(MinecraftServer server) {
        try {
            Path stateFile = getWorldFolder(server).resolve("hardcore_state.txt");
            if (Files.exists(stateFile)) {
                currentTry = Integer.parseInt(Files.readString(stateFile).trim());
            } else {
                currentTry = 1;
            }
        } catch (Exception e) {
            currentTry = 1;
        }
        hasLoadedTryCount = true;
    }

    public static void incrementAndSaveTryCount(MinecraftServer server) {
        if (!hasLoadedTryCount) loadTryCount(server);
        currentTry++;
        try {
            Path stateFile = getWorldFolder(server).resolve("hardcore_state.txt");
            Files.writeString(stateFile, String.valueOf(currentTry));
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to save Try Counter!", e);
        }
    }

    public static void tick(MinecraftServer server) {
        if (currentPhase == ResetPhase.IDLE) return;
        if (phaseTimer > 0) {
            phaseTimer--;
            return;
        }

        ResetPhase running = currentPhase;
        long startedAt = System.currentTimeMillis();

        switch (currentPhase) {
            case UNLOADING -> executeUnloadPhase(server);
            case DELETING -> executeDeletionPhase(server);
            case REGENERATING -> executeRegenerationPhase(server);
            case DONE -> executeDonePhase(server);
            default -> {
            }
        }

        // Every phase runs inside a single server tick, so a slow one eats into the
        // dedicated server's max-tick-time budget. Log it to make the culprit obvious
        // if the Watchdog ever shuts the server down mid-reset again.
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > 1000) {
            HardcoreWorldReset.LOGGER.warn("Phase {} held the server thread for {} ms.", running, elapsed);
        }
    }

    public static void beginReset(MinecraftServer server) {
        if (currentPhase != ResetPhase.IDLE) return;

        Long fixedSeed = getFixedSeed(server);
        if (fixedSeed != null) {
            newSeed = fixedSeed;
            HardcoreWorldReset.LOGGER.info("Using seed from server.properties: {}", newSeed);
        } else {
            newSeed = new Random().nextLong();
        }

        // Ensure we load the try count into memory before the reset starts
        if (!hasLoadedTryCount) loadTryCount(server);

        HardcoreWorldReset.LOGGER.info("Starting true world reset. New seed: {}", newSeed);
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Comenzando el borrado del mundo..."), false);

        // Everyone is already in Limbo (in the arena or floating as a spectator). This only
        // catches a straggler, because moving arena players again would pull the culprit out
        // of the pit and the audience off the ring.
        for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getServerWorld().getRegistryKey() != com.frankloq.LimboDimension.LIMBO_KEY) {
                player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                com.frankloq.LimboDimension.teleportToLimbo(player);
            }
        }

        advanceTo(ResetPhase.UNLOADING);
    }

    // Had to do all of this as well for 1.21 compatibility
    private static void executeUnloadPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: UNLOADING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Descargando la caché de memoria..."), false);

        // Get rid of entities
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                WorldInjectionUtils.clearAllEntities(world);

                // Wipes blockEntityTickers and pendingBlockEntityTickers
                try {
                    for (java.lang.reflect.Field field : net.minecraft.world.World.class.getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            java.util.List<?> list = (java.util.List<?>) field.get(world);
                            if (list != null) {
                                list.clear();
                            }
                        }
                    }
                } catch (Exception e) {
                    HardcoreWorldReset.LOGGER.error("Failed to vaporize ghost block entities!", e);
                }
            }
        }

        // Revoke the permanent spawn chunks memory lock
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            overworld.getChunkManager().removeTicket(
                    ChunkTicketType.START, new ChunkPos(overworld.getSpawnPos()), 11, Unit.INSTANCE
            );
        }

        // Stop the engine from writing to the worlds we are about to erase.
        // This replaces a blocking saveAll(flush = true): flushing parks the server thread
        // on StorageIoWorker.completeAll() until every queued chunk write reports back, and
        // that future can stay unfinished forever once the reset has torn entities and block
        // entity tickers out from under it. On a dedicated server the Watchdog then kills the
        // process after max-tick-time (60s by default); singleplayer has no Watchdog, which is
        // why this only ever showed up on real servers. Saving a world moments before deleting
        // its region files is pointless anyway, and skipping it also stops late writes from
        // recreating the files the DELETING phase is removing.
        setSavingDisabled(server, true);

        // Advance to the deleting phase after 40 ticks
        // This wait perfectly ensures no dropped items are ticking when we wipe the ram
        advanceTo(ResetPhase.DELETING);
    }

    private static void setSavingDisabled(MinecraftServer server, boolean disabled) {
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                world.savingDisabled = disabled;
            }
        }
    }

    private static void executeDeletionPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DELETING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Borrando los archivos del mundo anterior..."), false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) {
                // Now we clear the main RAM and it doesn't crash because entities are dead
                WorldInjectionUtils.lobotomizeChunkManager(world);

                // Clear the io buffer too
                WorldInjectionUtils.lobotomizeStorageIO(world);

                // Rip the file handles away from OS
                WorldInjectionUtils.forceCloseRegionFiles(world);
            }
        }

        Path worldFolder = getWorldFolder(server);
        if (worldFolder != null) {
            for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                Path dimPath;
                if (key == World.OVERWORLD) dimPath = worldFolder;
                else if (key == World.NETHER) dimPath = worldFolder.resolve("DIM-1");
                else if (key == World.END) dimPath = worldFolder.resolve("DIM1");
                else
                    dimPath = worldFolder.resolve("dimensions").resolve(key.getValue().getNamespace()).resolve(key.getValue().getPath());

                deleteFolder(dimPath.resolve("region"));
                deleteFolder(dimPath.resolve("poi"));
                deleteFolder(dimPath.resolve("entities"));

                // Saved world state that outlives its terrain: raids keep pointing at villages
                // that no longer exist, and the random sequences keep the old world's loot rolls.
                // scoreboard.dat deliberately stays, since it holds the lives objective.
                deleteFile(dimPath.resolve("data").resolve("raids.dat"));
                deleteFile(dimPath.resolve("data").resolve("random_sequences.dat"));
            }

            HardcoreWorldReset.LOGGER.info("Wiping player data, stats, and advancements...");
            deleteFolder(worldFolder.resolve("advancements"));
            deleteFolder(worldFolder.resolve("stats"));
            deleteFolder(worldFolder.resolve("playerdata"));

            try {
                java.nio.file.Files.createDirectories(worldFolder.resolve("advancements"));
                java.nio.file.Files.createDirectories(worldFolder.resolve("stats"));
                java.nio.file.Files.createDirectories(worldFolder.resolve("playerdata"));
            } catch (java.io.IOException e) {
                HardcoreWorldReset.LOGGER.error("Failed to recreate player folders.", e);
            }
        }

        advanceTo(ResetPhase.REGENERATING);
    }

    private static void executeRegenerationPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: REGENERATING");

        // Physically save the new try count to the text file
        incrementAndSaveTryCount(server);

        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Aplicando la nueva semilla: §e" + newSeed), false);
        writeNewSeedToLevelDat(server, newSeed, false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;

            ServerChunkManager manager = world.getChunkManager();
            net.minecraft.world.gen.chunk.ChunkGenerator chunkGen = manager.getChunkGenerator();

            if (chunkGen instanceof NoiseChunkGenerator noiseGen) {
                NoiseConfig newConfig = NoiseConfig.create(
                        noiseGen.getSettings().value(),
                        server.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),
                        newSeed
                );

                injectByType(manager, NoiseConfig.class, newConfig);
                injectByType(manager.chunkLoadingManager, NoiseConfig.class, newConfig);

                try {
                    net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator newCalculator =
                            chunkGen.createStructurePlacementCalculator(
                                    server.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),
                                    newConfig, newSeed
                            );
                    injectByType(manager.chunkLoadingManager, net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator.class, newCalculator);
                } catch (Exception e) {
                }
            }

            WorldInjectionUtils.injectSeedIntoMemory(world, newSeed);

            if (key.equals(World.OVERWORLD)) {
                world.setTimeOfDay(0L);
                // Temporary dummy spawn to prevent crashes
                world.setSpawnPos(new net.minecraft.util.math.BlockPos(0, 200, 0), 0.0f);
            }

            if (key.equals(World.END)) {
                WorldInjectionUtils.resetEnderDragonFight(world);
            }
        }

        advanceTo(ResetPhase.DONE);
    }

    private static void executeDonePhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DONE");

        // We advance to IDLE immediately outside the execute block so the tick loop doesn't fire this multiple times
        advanceTo(ResetPhase.IDLE);

        server.execute(() -> {
            // While the Limbo arena is running, the players stay there and the arena hands the
            // new world over when it ends. The lock is then released by exitLimbo() instead.
            boolean deferredToArena = false;
            try {
                deferredToArena = finishReset(server);
            } catch (Exception e) {
                HardcoreWorldReset.LOGGER.error("World reset failed while finishing up.", e);
                if (com.frankloq.arena.LimboArena.isRunning()) {
                    // Let the arena wrap up normally; the exit falls back to whatever spawn is set
                    com.frankloq.arena.LimboArena.onWorldReady(server);
                    deferredToArena = true;
                }
            } finally {
                // Always hand the lock back. If this block throws on the way out, holding it
                // would leave the mod permanently unable to start another reset.
                if (!deferredToArena) {
                    countdownLocked = false;
                }
            }
        });
    }

    // Returns true when the players were left in the Limbo arena, which will call exitLimbo()
    // itself once its minimum time has passed. Returns false when everyone was sent to the new
    // world right away.
    private static boolean finishReset(MinecraftServer server) {
        // Deleting and regenerating are done, so the engine may persist worlds again.
        // This runs first so a failure further down can never leave saving switched off.
        setSavingDisabled(server, false);

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld != null) {
            // Calculate the new spawn
            net.minecraft.util.math.BlockPos newSpawn;

            // Protective try-catch block for rapid restarts
            try {
                // We pump the chunk task queue first to clear out ghost tickets from the previous deletion
                for (int i = 0; i < 5; i++) {
                    overworld.getChunkManager().tick(() -> true, true);
                }

                // Attempt the natural spawn
                newSpawn = com.frankloq.reset.WorldSpawnLocator.determineWorldSpawn(overworld);

                overworld.getChunk(newSpawn.getX() >> 4, newSpawn.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, true);

            } catch (IllegalStateException e) {
                HardcoreWorldReset.LOGGER.warn("Chunk engine overloaded by rapid restarts. Catching crash and falling back to 0,0.");

                // We safely fallback to 0, 0 because the REGENERATING phase already added a ticket here,
                // meaning it is guaranteed to be fully loaded and won't crash
                int fallbackY = overworld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                if (fallbackY <= overworld.getBottomY()) fallbackY = 64; // Anti-void protection

                newSpawn = new net.minecraft.util.math.BlockPos(0, fallbackY, 0);
            }

            // Set the actual world spawn to the spawn we just calculated
            overworld.setSpawnPos(newSpawn, 0.0f);

            // Add ticket to load chunks around 0,0 safely
            int spawnRadius = server.getGameRules().getInt(net.minecraft.world.GameRules.SPAWN_CHUNK_RADIUS);
            overworld.getChunkManager().addTicket(
                    net.minecraft.server.world.ChunkTicketType.START,
                    new net.minecraft.util.math.ChunkPos(newSpawn),
                    spawnRadius,
                    net.minecraft.util.Unit.INSTANCE
            );
        }

        // I'm trying to optimize ram usage but idk if it's gonna do something
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            if (key.equals(World.OVERWORLD)) continue;

            ServerWorld world = server.getWorld(key);
            if (world != null) {
                ServerChunkManager manager = world.getChunkManager();
                for (int i = 0; i < 50; i++) {
                    manager.tick(() -> false, true);
                }
            }
        }

        System.gc();

        if (com.frankloq.arena.LimboArena.isRunning()) {
            // The punishment goes on until its minimum time is up; the arena then calls exitLimbo()
            com.frankloq.arena.LimboArena.onWorldReady(server);
            return true;
        }

        exitLimbo(server);
        return false;
    }

    // Moves everyone out of Limbo into the freshly generated world and finishes the reset.
    // Called straight from finishReset() when there is no arena, or by the arena when it ends.
    public static void exitLimbo(MinecraftServer server) {
        try {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld != null) {
                net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPos();

                for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {

                        // Force wipe their RAM cache
                        com.frankloq.reset.WorldInjectionUtils.wipePlayerState(player);

                        // Teleport the player
                        player.teleport(
                                overworld,
                                spawn.getX() + 0.5,
                                spawn.getY() + 1.0,
                                spawn.getZ() + 0.5,
                                0.0f,
                                0.0f
                        );
                    }
                }
            }

            server.getPlayerManager().broadcast(Text.literal("§a[Reset] §7¡Reset del mundo completado! Reapareciendo jugadores..."), false);
            HardcoreWorldReset.onResetComplete(server);
        } finally {
            countdownLocked = false;
        }
    }

    private static <T> void injectByType(Object target, Class<T> fieldType, T value) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        field.set(target, value);
                    } catch (Exception ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static void guaranteeLevelDatOnShutdown(MinecraftServer server) {
        if (newSeed != 0) {
            writeNewSeedToLevelDat(server, newSeed, true);
        }
    }

    private static boolean writeNewSeedToLevelDat(MinecraftServer server, long seed, boolean isShutdown) {
        try {
            Path rootPath = server.getSavePath(WorldSavePath.ROOT).normalize();
            Path levelDatPath = rootPath.resolve("level.dat");

            if (!Files.exists(levelDatPath)) return false;

            NbtCompound root = NbtIo.readCompressed(levelDatPath, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound data = root.getCompound("Data");

            if (data.contains("RandomSeed")) data.putLong("RandomSeed", seed);
            if (data.contains("WorldGenSettings")) {
                NbtCompound wgs = data.getCompound("WorldGenSettings");
                wgs.putLong("seed", seed);
                data.put("WorldGenSettings", wgs);
            }

            // Only a real reset starts the world over. On shutdown this method runs purely to
            // make sure the new seed survives, and zeroing these there would rewind the clock
            // to day one and clear the dragon fight on every single restart after a reset.
            if (!isShutdown) {
                data.putLong("Time", 0L);
                data.putLong("DayTime", 0L);
                if (data.contains("DragonFight")) data.remove("DragonFight");
            }

            root.put("Data", data);
            Files.copy(levelDatPath, rootPath.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
            NbtIo.writeCompressed(root, levelDatPath);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path getWorldFolder(MinecraftServer server) {
        try {
            return server.getSavePath(WorldSavePath.ROOT).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            HardcoreWorldReset.LOGGER.warn("Could not delete {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private static void deleteFolder(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    HardcoreWorldReset.LOGGER.error("windows won't fucking allow this file to removed bc is a lil child: " + p.getFileName(), e);
                }
            });
        } catch (IOException e) {
            HardcoreWorldReset.LOGGER.error("Failed to read folder: " + path, e);
        }
    }

    private static void advanceTo(ResetPhase phase) {
        currentPhase = phase;
        phaseTimer = PHASE_DELAY_TICKS;
    }

    private static Long getFixedSeed(MinecraftServer server) {
        if (!com.frankloq.HardcoreWorldReset.reuseSeed) {
            return null;
        }

        try {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld != null) {
                long currentSeed = overworld.getSeed();
                return currentSeed;
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to fetch current world seed", e);
        }

        return null;
    }
}