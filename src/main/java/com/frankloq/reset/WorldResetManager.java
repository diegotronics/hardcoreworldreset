package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * The erase-and-regenerate pipeline, run one phase per server tick:
 *
 * <pre>
 *  UNLOADING     entities discarded, every chunk ticket handed back to the engine
 *  DRAINING      the engine unloads the chunks itself; once it is empty the generators are
 *                switched to the new seed and the io workers drop the writes they still hold,
 *                close the region files and delete their folders (on their own thread)
 *  DELETING      whatever is left on disk removed, along with the player data
 *  REGENERATING  level.dat, try counter, raids, dragon fight and a temporary spawn
 *  DONE          spawn found in the new world, players handed over (or left to the arena)
 * </pre>
 *
 * Earlier versions skipped the unload and tore the chunk holders and io queues out of the
 * engine by reflection while it kept running. Whatever was mid-flight then finished after the
 * files were gone: a queued write recreated a region file with a chunk of the old world, and
 * the new world came up with slabs of old-seed terrain and sheer walls where they met the new
 * chunks, worse with every reset. Letting the engine finish first is what makes the deletion
 * safe, and it also cleans up the tick schedulers, POIs and light data that used to leak.
 */
public class WorldResetManager {

    private static final int PHASE_DELAY_TICKS = 40;
    // How long the engine gets to unload every chunk the vanilla way before the leftovers are
    // dropped, and how long the io workers get to drop their queues. Both are upper bounds:
    // the phase moves on as soon as the work is done.
    private static final int MAX_UNLOAD_TICKS = 600;
    private static final int MAX_IO_TICKS = 200;

    private static ResetPhase currentPhase = ResetPhase.IDLE;
    private static int phaseTimer = 0;
    private static long newSeed = 0;
    private static boolean countdownLocked = false;

    // DRAINING bookkeeping
    private static int drainTicks = 0;
    private static int ioTicks = 0;
    private static CompletableFuture<Void> pendingIo = null;

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
            case DRAINING -> executeDrainPhase(server);
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
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            // A player still on the vanilla death screen has to respawn before they can move
            player = PlayerRespawner.ensureAlive(server, player);
            if (player.getServerWorld().getRegistryKey() != com.frankloq.LimboDimension.LIMBO_KEY) {
                player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                com.frankloq.LimboDimension.teleportToLimbo(player);
            }
        }

        advanceTo(ResetPhase.UNLOADING, PHASE_DELAY_TICKS);
    }

    private static void executeUnloadPhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: UNLOADING");
        server.getPlayerManager().broadcast(Text.literal("§5[Reset] §7Descargando el mundo anterior..."), false);

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;

            WorldInjectionUtils.clearAllEntities(world);

            // With no ticket left the engine lowers every chunk level itself and unloads the
            // chunks through its own path over the next ticks, which is the only way the
            // block entity tickers, scheduled ticks, POIs and light data get cleaned up too.
            int released = WorldInjectionUtils.releaseAllChunkTickets(world);
            HardcoreWorldReset.LOGGER.info("Released {} chunk ticket(s) in {}; {} chunk(s) loaded.",
                    released, key.getValue(), WorldInjectionUtils.countLoadedChunks(world));
        }

        drainTicks = 0;
        ioTicks = 0;
        pendingIo = null;
        advanceTo(ResetPhase.DRAINING, 0);
    }

    // Runs every tick until the old world is out of memory and off the io queues
    private static void executeDrainPhase(MinecraftServer server) {
        if (pendingIo == null) {
            drainTicks++;

            boolean idle = true;
            int loaded = 0;
            for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                ServerWorld world = server.getWorld(key);
                if (world == null) continue;

                // The server's own pass over the unload queue stops when the tick budget runs
                // out; an extra unbounded pass per tick gets through it in a handful of ticks
                world.getChunkManager().tick(() -> true, false);

                if (!WorldInjectionUtils.isChunkEngineIdle(world)) {
                    idle = false;
                    loaded += WorldInjectionUtils.countLoadedChunks(world);
                }
            }

            if (!idle && drainTicks < MAX_UNLOAD_TICKS) {
                if (drainTicks % 100 == 0) {
                    HardcoreWorldReset.LOGGER.info("Still unloading the old world: {} chunk(s) left after {} ticks.", loaded, drainTicks);
                }
                return;
            }

            if (idle) {
                HardcoreWorldReset.LOGGER.info("The old world is fully unloaded after {} tick(s).", drainTicks);
            } else {
                HardcoreWorldReset.LOGGER.warn("{} chunk(s) refused to unload within {} ticks; dropping them.", loaded, MAX_UNLOAD_TICKS);
                for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                    ServerWorld world = server.getWorld(key);
                    if (world == null) continue;
                    // A player still standing in a doomed dimension keeps its chunks alive and
                    // is the usual reason for this; name them so the log explains itself
                    for (ServerPlayerEntity player : world.getPlayers()) {
                        HardcoreWorldReset.LOGGER.warn("{} is still in {} during the reset.", player.getName().getString(), key.getValue());
                    }
                    WorldInjectionUtils.dropRemainingChunks(world);
                }
            }

            // From here until the new world is ready nothing may save: no autosave, and (as a
            // side effect of this flag) no more chunk unloads either, so no new writes reach
            // the io workers behind the cleanup below.
            setSavingDisabled(server, true);

            // The generators switch to the new seed right now, while nothing is loaded. Any
            // chunk that gets loaded from here on, for whatever reason, is generated for the
            // new world; a window with the old seed still in place is how old terrain ended
            // up next to new terrain at spawn.
            applyNewSeed(server);

            // Each io worker drops its queue, closes its region files and deletes its own
            // folder (region, poi, entities) on its own thread
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
                ServerWorld world = server.getWorld(key);
                if (world != null) futures.add(WorldInjectionUtils.discardStorage(world));
            }
            pendingIo = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            ioTicks = 0;
            return;
        }

        ioTicks++;
        if (!pendingIo.isDone() && ioTicks < MAX_IO_TICKS) return;
        if (!pendingIo.isDone()) {
            HardcoreWorldReset.LOGGER.warn("The io workers did not drop their queues within {} ticks; deleting anyway.", MAX_IO_TICKS);
        }
        pendingIo = null;

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world != null) WorldInjectionUtils.clearPointOfInterestMemory(world);
        }

        advanceTo(ResetPhase.DELETING, 0);
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

        // Block entity tickers of chunks that had to be dropped instead of unloaded
        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;
            try {
                for (Field field : World.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        List<?> list = (List<?>) field.get(world);
                        if (list != null) {
                            list.clear();
                        }
                    }
                }
            } catch (Exception e) {
                HardcoreWorldReset.LOGGER.error("Failed to vaporize ghost block entities!", e);
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

                // Normally gone already, deleted by the io workers themselves in the DRAINING
                // phase; this only catches a storage no worker was found for
                WorldInjectionUtils.deleteFolder(dimPath.resolve("region"));
                WorldInjectionUtils.deleteFolder(dimPath.resolve("poi"));
                WorldInjectionUtils.deleteFolder(dimPath.resolve("entities"));

                // Saved world state that outlives its terrain: raids keep pointing at villages
                // that no longer exist, and the random sequences keep the old world's loot rolls.
                // The in-memory copies are replaced in the REGENERATING phase.
                // scoreboard.dat deliberately stays, since it holds the lives objective.
                deleteFile(dimPath.resolve("data").resolve("raids.dat"));
                deleteFile(dimPath.resolve("data").resolve("raids_end.dat"));
                deleteFile(dimPath.resolve("data").resolve("random_sequences.dat"));
            }

            HardcoreWorldReset.LOGGER.info("Wiping player data, stats, and advancements...");
            WorldInjectionUtils.deleteFolder(worldFolder.resolve("advancements"));
            WorldInjectionUtils.deleteFolder(worldFolder.resolve("stats"));
            WorldInjectionUtils.deleteFolder(worldFolder.resolve("playerdata"));

            try {
                Files.createDirectories(worldFolder.resolve("advancements"));
                Files.createDirectories(worldFolder.resolve("stats"));
                Files.createDirectories(worldFolder.resolve("playerdata"));
            } catch (IOException e) {
                HardcoreWorldReset.LOGGER.error("Failed to recreate player folders.", e);
            }
        }

        advanceTo(ResetPhase.REGENERATING, PHASE_DELAY_TICKS);
    }

    // Points every generator, cache and seed holder of the reset dimensions at the new seed.
    // Called the moment the chunk engine is empty, before anything can load a chunk again.
    private static void applyNewSeed(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        List<ServerWorld> worlds = new ArrayList<>();

        for (RegistryKey<World> key : WorldUnloader.RESET_DIMENSIONS) {
            ServerWorld world = server.getWorld(key);
            if (world == null) continue;
            worlds.add(world);

            ServerChunkManager manager = world.getChunkManager();
            ChunkGenerator chunkGen = manager.getChunkGenerator();

            // Same recipe the engine uses when it opens a world: noise generators bring their
            // own settings, anything else gets the placeholder ones
            ChunkGeneratorSettings settings = chunkGen instanceof NoiseChunkGenerator noiseGen
                    ? noiseGen.getSettings().value()
                    : ChunkGeneratorSettings.createMissingSettings();
            NoiseConfig newConfig = NoiseConfig.create(
                    settings,
                    server.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),
                    newSeed
            );
            injectByType(manager.chunkLoadingManager, NoiseConfig.class, newConfig);

            try {
                StructurePlacementCalculator newCalculator = chunkGen.createStructurePlacementCalculator(
                        server.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),
                        newConfig, newSeed
                );
                injectByType(manager.chunkLoadingManager, StructurePlacementCalculator.class, newCalculator);
                newCalculator.tryCalculate();
            } catch (Exception e) {
                HardcoreWorldReset.LOGGER.error("Failed to rebuild the structure placement of {}", key.getValue(), e);
            }

            WorldInjectionUtils.injectSeedIntoMemory(world, newSeed);
            WorldInjectionUtils.refreshStructureLocator(world, newConfig, newSeed);
        }

        if (overworld != null) {
            WorldInjectionUtils.resetRandomSequences(overworld, worlds, newSeed);
        }
        HardcoreWorldReset.LOGGER.info("The generators now run on seed {}.", newSeed);
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

            WorldInjectionUtils.resetRaids(world);

            if (key.equals(World.OVERWORLD)) {
                world.setTimeOfDay(0L);
                // Temporary dummy spawn to prevent crashes. Vanilla starts loading the spawn
                // chunks around it right away, with the generators already on the new seed.
                world.setSpawnPos(new net.minecraft.util.math.BlockPos(0, 200, 0), 0.0f);
            }

            if (key.equals(World.END)) {
                WorldInjectionUtils.resetEnderDragonFight(world);
            }
        }

        advanceTo(ResetPhase.DONE, PHASE_DELAY_TICKS);
    }

    private static void executeDonePhase(MinecraftServer server) {
        HardcoreWorldReset.LOGGER.info("Phase: DONE");

        // We advance to IDLE immediately outside the execute block so the tick loop doesn't fire this multiple times
        advanceTo(ResetPhase.IDLE, 0);

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

            // Set the actual world spawn to the spawn we just calculated. Vanilla moves the
            // spawn chunk ticket along with it, sized by the spawnChunkRadius game rule.
            overworld.setSpawnPos(newSpawn, 0.0f);
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
            // Clients that asked to respawn while the world was being erased get it now
            PlayerRespawner.flushDeferredRefreshes(server);

            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld != null) {
                net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPos();

                for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
                    player = PlayerRespawner.ensureAlive(server, player);
                    if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {

                        // The Limbo track ends here; the reset stinger plays on arrival
                        com.frankloq.ModSounds.stopLimboTrack(player);

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

    private static void advanceTo(ResetPhase phase, int delayTicks) {
        currentPhase = phase;
        phaseTimer = delayTicks;
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
