package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.entity.boss.dragon.EnderDragonFight;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.util.thread.TaskQueue;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.SectionDistanceLevelPropagator;
import net.minecraft.world.StructureLocator;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Reflection helpers that reach into the parts of the chunk engine vanilla keeps private.
 *
 * <p>Everything here looks fields up by <em>type</em>, never by name, and refers to Minecraft
 * classes through class literals only. Outside the development environment every Minecraft
 * class, field and method carries an intermediary name ({@code class_1234}, {@code field_5678}),
 * so a lookup like {@code getSimpleName().equals("StorageIoWorker")} matches in a dev run and
 * silently finds nothing on a real server. That is how an earlier attempt at dropping the queued
 * chunk writes ended up doing nothing at all in production.
 */
public class WorldInjectionUtils {

    // ------------------------------------------------------------------------------- seed

    // Points every in-memory copy of the seed at the new one: the generator options (what
    // getSeed() reads and what a save writes to level.dat), the biome access used for fuzzy
    // biome lookups, and the structure locator behind /locate and structure exclusion zones.
    public static void injectSeedIntoMemory(ServerWorld world, long newSeed) {
        try {
            // Only the overworld owns the level properties; the other dimensions see them
            // through a read-only view, so patching the overworld covers every getSeed()
            net.minecraft.world.WorldProperties properties = world.getLevelProperties();
            if (properties instanceof LevelProperties levelProps) {
                boolean generatorPatched = false;
                Object generatorOptions = firstFieldValue(levelProps, GeneratorOptions.class, true);
                if (generatorOptions != null) {
                    for (Field genField : GeneratorOptions.class.getDeclaredFields()) {
                        if (genField.getType() == long.class && !Modifier.isStatic(genField.getModifiers())) {
                            genField.setAccessible(true);
                            genField.setLong(generatorOptions, newSeed);
                            generatorPatched = true;
                            break;
                        }
                    }
                }

                if (!generatorPatched) {
                    // Falling through means the seed never made it into memory, so the "new" world
                    // would regenerate identically to the old one. Say so instead of failing quietly.
                    HardcoreWorldReset.LOGGER.error(
                            "Could not find the seed field for {}. The world may regenerate with the old seed.",
                            world.getRegistryKey().getValue()
                    );
                }
            }

            // World.getBiome() offsets its lookups with a hash of the seed. Left alone it keeps
            // using the old world's hash for snow, ice, spawning and every other biome query.
            Object biomeAccess = firstFieldValue(world, BiomeAccess.class, true);
            if (biomeAccess != null) {
                setFirstPrimitiveLong(biomeAccess, BiomeAccess.hashSeed(newSeed));
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to inject the new seed into memory", e);
        }
    }

    // The structure locator caches which structures start in which chunk and carries its own
    // seed and noise config. It has to follow the new world or /locate and structure exclusion
    // checks keep answering for the old one.
    public static void refreshStructureLocator(ServerWorld world, NoiseConfig noiseConfig, long newSeed) {
        try {
            Object locator = firstFieldValue(world, StructureLocator.class, true);
            if (locator == null) {
                HardcoreWorldReset.LOGGER.warn("No structure locator found in {}.", world.getRegistryKey().getValue());
                return;
            }
            setFirstPrimitiveLong(locator, newSeed);
            if (noiseConfig != null) {
                setFirstField(locator, NoiseConfig.class, noiseConfig, true);
            }
            for (Field field : fieldsOfType(locator, Map.class, false)) {
                clearCollectionLike(field.get(locator));
            }
            for (Field field : fieldsOfType(locator, Long2ObjectMap.class, false)) {
                clearCollectionLike(field.get(locator));
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to refresh the structure locator", e);
        }
    }

    // Raids are saved data that outlive the terrain: they keep pointing at villages that no
    // longer exist. Deleting the file is not enough, the loaded manager would simply be written
    // back on the next save, so the in-memory copy is replaced with a fresh one as well.
    public static void resetRaids(ServerWorld world) {
        try {
            PersistentStateManager states = world.getPersistentStateManager();
            String raidsId = RaidManager.nameFor(world.getDimensionEntry());
            removeLoadedState(states, raidsId);
            RaidManager raids = states.getOrCreate(RaidManager.getPersistentStateType(world), raidsId);
            setFirstField(world, RaidManager.class, raids, true);
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to reset the raids of {}", world.getRegistryKey().getValue(), e);
        }
    }

    // The random sequences keep the old world's loot rolls. One state is shared by every
    // dimension and saved by the overworld under this vanilla id; the new one is registered
    // there and handed to each of the given worlds.
    public static void resetRandomSequences(ServerWorld overworld, List<ServerWorld> worlds, long newSeed) {
        try {
            RandomSequencesState fresh = new RandomSequencesState(newSeed);
            fresh.markDirty();
            overworld.getPersistentStateManager().set("random_sequences", fresh);
            for (ServerWorld world : worlds) {
                setFirstField(world, RandomSequencesState.class, fresh, true);
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to reset the random sequences", e);
        }
    }

    private static void removeLoadedState(PersistentStateManager states, String id) throws IllegalAccessException {
        Object loaded = firstFieldValue(states, Map.class, false);
        if (loaded instanceof Map<?, ?> map) {
            map.remove(id);
        }
    }

    // ------------------------------------------------------------------------------- tickets

    // Hands every chunk ticket of the world back through the vanilla API, so the chunk engine
    // lowers the levels itself and unloads the chunks the normal way: entities, block entity
    // tickers, scheduled ticks, POIs and light all get cleaned up by the code that owns them.
    // Returns the number of tickets released.
    public static int releaseAllChunkTickets(ServerWorld world) {
        int released = 0;

        // Forced chunks go through the API so the saved "chunks" state forgets them too
        LongSet forced = world.getForcedChunks();
        if (!forced.isEmpty()) {
            for (long packed : new LongArrayList(forced)) {
                world.setChunkForced(ChunkPos.getPackedX(packed), ChunkPos.getPackedZ(packed), false);
                released++;
            }
        }

        try {
            ServerChunkManager chunkManager = world.getChunkManager();
            Object ticketManager = firstFieldValue(chunkManager, ChunkTicketManager.class, false);
            if (!(ticketManager instanceof ChunkTicketManager manager)) {
                HardcoreWorldReset.LOGGER.warn("No ticket manager found in {}.", world.getRegistryKey().getValue());
                return released;
            }

            // The tickets live in the one field declared exactly as a Long2ObjectOpenHashMap;
            // the player map next to it is declared through the Long2ObjectMap interface.
            Long2ObjectOpenHashMap<?> byPosition = null;
            for (Field field : fieldsOfType(manager, Long2ObjectOpenHashMap.class, true)) {
                byPosition = (Long2ObjectOpenHashMap<?>) field.get(manager);
                break;
            }
            if (byPosition == null) {
                HardcoreWorldReset.LOGGER.warn("No ticket map found in {}.", world.getRegistryKey().getValue());
                return released;
            }

            List<Long2ObjectMap.Entry<?>> entries = new ArrayList<>(byPosition.long2ObjectEntrySet());
            for (Long2ObjectMap.Entry<?> entry : entries) {
                if (!(entry.getValue() instanceof SortedArraySet<?> tickets)) continue;
                ChunkPos pos = new ChunkPos(entry.getLongKey());
                for (Object element : new ArrayList<>(tickets)) {
                    if (!(element instanceof ChunkTicket<?> ticket)) continue;
                    Object argument = firstFieldValue(ticket, Object.class, true);
                    removeTicket(manager, ticket, pos, argument);
                    released++;
                }
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to release the chunk tickets of {}", world.getRegistryKey().getValue(), e);
        }

        return released;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeTicket(ChunkTicketManager manager, ChunkTicket<?> ticket, ChunkPos pos, Object argument) {
        manager.removeTicketWithLevel((ChunkTicketType) ticket.getType(), pos, ticket.getLevel(), argument);
    }

    // True once the chunk engine has nothing left: no chunk holders, no unloads in flight and
    // no unload tasks waiting for the main thread.
    public static boolean isChunkEngineIdle(ServerWorld world) {
        ServerChunkManager chunkManager = world.getChunkManager();
        if (chunkManager.getLoadedChunkCount() > 0) return false;

        try {
            ServerChunkLoadingManager loadingManager = chunkManager.chunkLoadingManager;
            for (Field field : ServerChunkLoadingManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                boolean holderMap = Long2ObjectMap.class.isAssignableFrom(type);
                boolean pending = LongSet.class.isAssignableFrom(type) || Queue.class.isAssignableFrom(type);
                if (!holderMap && !pending) continue;

                field.setAccessible(true);
                Object value = field.get(loadingManager);
                if (value instanceof Map<?, ?> map && !map.isEmpty()) return false;
                if (value instanceof Collection<?> collection && !collection.isEmpty()) return false;
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Could not inspect the chunk engine of {}", world.getRegistryKey().getValue(), e);
        }
        return true;
    }

    public static int countLoadedChunks(ServerWorld world) {
        return world.getChunkManager().getLoadedChunkCount();
    }

    // Last resort for chunks that refused to unload in time: forget them. Nothing that owns them
    // gets told, so this is only used after the vanilla unload had its chance.
    public static void dropRemainingChunks(ServerWorld world) {
        try {
            ServerChunkLoadingManager loadingManager = world.getChunkManager().chunkLoadingManager;
            for (Field field : ServerChunkLoadingManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (Long2ObjectMap.class.isAssignableFrom(type) || LongSet.class.isAssignableFrom(type) || Queue.class.isAssignableFrom(type)) {
                    field.setAccessible(true);
                    clearCollectionLike(field.get(loadingManager));
                }
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to drop the remaining chunks of {}", world.getRegistryKey().getValue(), e);
        }
    }

    // ------------------------------------------------------------------------------- storage

    // Drops every chunk, POI and entity write still queued for the world's region files, closes
    // the files and deletes their folders, all of it on the io worker's own thread.
    //
    // A StorageIoWorker keeps the pending writes in a map that only its executor thread touches,
    // and drains that map one chunk at a time. Clearing it from the server thread races with
    // the write in progress, which then reopens a region file inside the folder that is being
    // deleted and puts a chunk of the old world into the new one: in game that is a slab of
    // old-seed terrain with sheer walls where it meets the new chunks. Running the cleanup as
    // a task on the same executor, ahead of the queued writes, makes it impossible for a write
    // to slip through. The folder is deleted by that same task for the same reason: a read that
    // opens a region file between the cleanup and a deletion from another thread would keep
    // writing into a file that no longer has a name, and the new world would never reach the
    // disk until the next restart. The returned future completes when every worker has done so.
    public static CompletableFuture<Void> discardStorage(ServerWorld world) {
        List<StorageIoWorker> workers = collectIoWorkers(world);
        if (workers.isEmpty()) {
            HardcoreWorldReset.LOGGER.warn(
                    "Found no storage io worker in {}. Queued writes from the old world may survive the reset.",
                    world.getRegistryKey().getValue()
            );
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        for (StorageIoWorker worker : workers) {
            futures.add(discardStorage(world, worker));
            folders.add(describeStorage(worker));
        }
        HardcoreWorldReset.LOGGER.info(
                "Discarding the storage of {} io worker(s) in {}: {}", workers.size(), world.getRegistryKey().getValue(), folders
        );
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // The folder an io worker writes to, for the log
    private static String describeStorage(StorageIoWorker worker) {
        try {
            Path directory = storageDirectory(worker);
            if (directory != null) {
                return directory.getFileName().toString();
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    private static Path storageDirectory(StorageIoWorker worker) throws IllegalAccessException {
        Object storage = firstFieldValue(worker, RegionBasedStorage.class, false);
        Object directory = storage == null ? null : firstFieldValue(storage, Path.class, false);
        return directory instanceof Path path ? path : null;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> discardStorage(ServerWorld world, StorageIoWorker worker) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            Object executor = firstFieldValue(worker, TaskExecutor.class, false);
            Object storage = firstFieldValue(worker, RegionBasedStorage.class, false);
            Path directory = storageDirectory(worker);
            // The pending writes plus the per-region blending status cache, both only ever
            // touched from the worker's own thread
            List<Field> caches = fieldsOfType(worker, Map.class, false);
            if (!(executor instanceof TaskExecutor<?>)) {
                throw new IllegalStateException("no task executor in the io worker");
            }

            Runnable cleanup = () -> {
                int dropped = 0;
                int closed = 0;
                try {
                    for (Field field : caches) {
                        if (!(field.get(worker) instanceof Map<?, ?> map)) continue;
                        // Anyone waiting on a dropped write gets released instead of hanging
                        for (Object entry : new ArrayList<>(map.values())) {
                            Object future = firstFieldValue(entry, CompletableFuture.class, false);
                            if (future instanceof CompletableFuture<?> f && !f.isDone()) {
                                ((CompletableFuture<Object>) f).complete(null);
                                dropped++;
                            }
                        }
                        map.clear();
                    }
                    if (storage instanceof RegionBasedStorage regionStorage) {
                        closed = closeRegionFiles(regionStorage);
                    }
                    if (directory != null) {
                        deleteFolder(directory);
                    }
                    HardcoreWorldReset.LOGGER.info("Dropped {} queued write(s), closed {} region file(s) and deleted {} in {}.",
                            dropped, closed, directory == null ? "nothing" : directory.getFileName(), world.getRegistryKey().getValue());
                } catch (Throwable e) {
                    HardcoreWorldReset.LOGGER.error("Failed to discard the storage of {}", world.getRegistryKey().getValue(), e);
                } finally {
                    done.complete(null);
                }
            };

            // Priority 0 is the worker's foreground level, so this runs before the queued writes
            ((TaskExecutor<Object>) executor).send(new TaskQueue.PrioritizedTask(0, cleanup));
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Could not schedule the io cleanup in {}", world.getRegistryKey().getValue(), e);
            done.complete(null);
        }
        return done;
    }

    // Closes and forgets the open region files so the folder can be deleted. Any later access
    // opens a fresh file, which is exactly what the new world wants. Returns how many were closed.
    private static int closeRegionFiles(RegionBasedStorage storage) throws IllegalAccessException {
        int closed = 0;
        for (Field field : fieldsOfType(storage, Long2ObjectLinkedOpenHashMap.class, false)) {
            Object cache = field.get(storage);
            if (cache instanceof Map<?, ?> map) {
                for (Object regionFile : new ArrayList<>(map.values())) {
                    if (regionFile instanceof AutoCloseable closeable) {
                        try {
                            closeable.close();
                            closed++;
                        } catch (Exception e) {
                            HardcoreWorldReset.LOGGER.warn("Could not close a region file: {}", e.toString());
                        }
                    }
                }
                map.clear();
            }
        }
        return closed;
    }

    // Removes a folder and everything in it. Loud about anything it cannot remove: a file that
    // is still open (Windows refuses to delete those) means a piece of the old world survives.
    public static void deleteFolder(Path path) {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    HardcoreWorldReset.LOGGER.error("Could not delete {} from the old world: {}", p.getFileName(), e.toString());
                }
            });
        } catch (IOException e) {
            HardcoreWorldReset.LOGGER.error("Failed to read folder: " + path, e);
        }
    }

    // Every StorageIoWorker reachable from the chunk, POI and entity storages of the world.
    // Each root gets its own visited set: the chunk storage can reach the entity storage's
    // objects near its depth limit, and a shared set would then hide the worker below them.
    private static List<StorageIoWorker> collectIoWorkers(ServerWorld world) {
        List<StorageIoWorker> found = new ArrayList<>();

        collectIoWorkers(world.getChunkManager().chunkLoadingManager, 0, new HashSet<>(), found);
        collectIoWorkers(world.getPointOfInterestStorage(), 0, new HashSet<>(), found);
        try {
            Object entityManager = firstFieldValue(world, ServerEntityManager.class, false);
            collectIoWorkers(entityManager, 0, new HashSet<>(), found);
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.warn("Could not reach the entity storage of {}: {}", world.getRegistryKey().getValue(), e.toString());
        }
        return found;
    }

    private static void collectIoWorkers(Object target, int depth, Set<Object> visited, List<StorageIoWorker> found) {
        if (target == null || depth > 5 || !visited.add(target)) return;

        if (target instanceof StorageIoWorker worker) {
            for (StorageIoWorker known : found) {
                if (known == worker) return;
            }
            found.add(worker);
            return;
        }

        // Never wander out into the rest of the server's object graph
        if (target instanceof ServerWorld
                || target instanceof net.minecraft.server.MinecraftServer
                || target instanceof net.minecraft.server.PlayerManager
                || target instanceof ServerChunkManager) {
            return;
        }

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                // Intermediary names keep the net.minecraft package, so this holds in production too
                if (!field.getType().getName().startsWith("net.minecraft")) continue;
                try {
                    field.setAccessible(true);
                    collectIoWorkers(field.get(target), depth + 1, visited, found);
                } catch (Exception ignored) {
                }
            }
            current = current.getSuperclass();
        }
    }

    // The POI storage never evicts a loaded chunk section. After the region files are gone its
    // memory still describes the old world's beds, workstations and portals, and a chunk that
    // generates over one of those sections is not rescanned because the stale entry looks valid.
    public static void clearPointOfInterestMemory(ServerWorld world) {
        try {
            PointOfInterestStorage storage = world.getPointOfInterestStorage();
            for (Field field : fieldsOfType(storage, Long2ObjectMap.class, false)) {
                clearCollectionLike(field.get(storage));
            }
            for (Field field : fieldsOfType(storage, LongSet.class, false)) {
                clearCollectionLike(field.get(storage));
            }
            // The occupancy tracker behind isNearOccupiedPointOfInterest()
            Object tracker = firstFieldValue(storage, SectionDistanceLevelPropagator.class, false);
            if (tracker != null) {
                for (Field field : fieldsOfType(tracker, Long2ByteMap.class, false)) {
                    clearCollectionLike(field.get(tracker));
                }
            }
        } catch (Exception e) {
            HardcoreWorldReset.LOGGER.error("Failed to clear the POI memory of {}", world.getRegistryKey().getValue(), e);
        }
    }

    // ------------------------------------------------------------------------------- entities

    public static void clearAllEntities(ServerWorld world) {
        java.util.List<net.minecraft.entity.Entity> entitiesToRemove = new java.util.ArrayList<>();
        for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
            if (entity != null && !(entity instanceof net.minecraft.entity.player.PlayerEntity)) {
                entitiesToRemove.add(entity);
            }
        }
        for (net.minecraft.entity.Entity entity : entitiesToRemove) {
            try { entity.discard(); } catch (Exception ignored) {}
        }
    }

    public static void resetEnderDragonFight(ServerWorld endWorld) {
        try {
            for (Field field : ServerWorld.class.getDeclaredFields()) {
                if (field.getType() == EnderDragonFight.class) {
                    field.setAccessible(true);
                    for (java.lang.reflect.Constructor<?> c : field.getType().getDeclaredConstructors()) {
                        if (c.getParameterCount() == 3 && c.getParameterTypes()[0] == ServerWorld.class && c.getParameterTypes()[1] == long.class) {
                            Class<?> dataClass = c.getParameterTypes()[2];
                            Object defaultData = null;
                            for (Field df : dataClass.getDeclaredFields()) {
                                if (java.lang.reflect.Modifier.isStatic(df.getModifiers()) && df.getType() == dataClass) {
                                    df.setAccessible(true);
                                    defaultData = df.get(null);
                                    break;
                                }
                            }
                            Object newFight = c.newInstance(endWorld, endWorld.getSeed(), defaultData);
                            field.set(endWorld, newFight);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            com.frankloq.HardcoreWorldReset.LOGGER.error("Failed to reset the Ender Dragon fight", e);
        }
    }

    public static void wipePlayerState(net.minecraft.server.network.ServerPlayerEntity player) {
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);

        player.getInventory().clear();
        player.getEnderChestInventory().clear();

        player.experienceLevel = 0;
        player.experienceProgress = 0.0f;
        player.setScore(0);

        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(5.0f);
        player.clearStatusEffects();

        player.extinguish();

        // Compatibility for Trinkets mod
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("trinkets")) {
            try {
                // Find the Trinkets API class
                Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
                java.lang.reflect.Method getComponent = trinketsApi.getMethod("getTrinketComponent", net.minecraft.entity.LivingEntity.class);

                // Get the player's Trinket component
                java.util.Optional<?> opt = (java.util.Optional<?>) getComponent.invoke(null, player);

                if (opt.isPresent()) {
                    Object trinketComponent = opt.get();
                    java.lang.reflect.Method getInventory = trinketComponent.getClass().getMethod("getInventory");

                    // Trinkets stores items in a Map<String, Map<String, TrinketInventory>>
                    java.util.Map<?, ?> inventoryMap = (java.util.Map<?, ?>) getInventory.invoke(trinketComponent);

                    // Loop through all custom accessory slots and clear them
                    for (Object groupMapObj : inventoryMap.values()) {
                        java.util.Map<?, ?> groupMap = (java.util.Map<?, ?>) groupMapObj;
                        for (Object trinketInvObj : groupMap.values()) {
                            if (trinketInvObj instanceof net.minecraft.inventory.Inventory) {
                                ((net.minecraft.inventory.Inventory) trinketInvObj).clear();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                com.frankloq.HardcoreWorldReset.LOGGER.error("not wiping allat", e);
            }
        }
    }

    // ------------------------------------------------------------------------------- reflection

    // Instance fields of the target (superclasses included) whose declared type is the given
    // one, or assignable to it when exact is false. All are made accessible.
    private static List<Field> fieldsOfType(Object target, Class<?> type, boolean exact) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                boolean matches = exact ? field.getType() == type : type.isAssignableFrom(field.getType());
                if (!matches) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Exception ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Object firstFieldValue(Object target, Class<?> type, boolean exact) throws IllegalAccessException {
        if (target == null) return null;
        for (Field field : fieldsOfType(target, type, exact)) {
            return field.get(target);
        }
        return null;
    }

    private static boolean setFirstField(Object target, Class<?> type, Object value, boolean exact) throws IllegalAccessException {
        for (Field field : fieldsOfType(target, type, exact)) {
            field.set(target, value);
            return true;
        }
        return false;
    }

    private static boolean setFirstPrimitiveLong(Object target, long value) throws IllegalAccessException {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != long.class) continue;
                field.setAccessible(true);
                field.setLong(target, value);
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static void clearCollectionLike(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.clear();
        } else if (value instanceof Collection<?> collection) {
            collection.clear();
        }
    }
}
