package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerRespawner {

    // Players whose client asked to respawn while the world was being erased. The respawn is
    // replayed once the new world exists, see refreshClient().
    private static final Set<UUID> deferredRefreshes = new HashSet<>();

    // A player who died with lives to spare and never pressed "respawn" is still a dead entity:
    // health at zero and, a second after the death, removed from the world. Reviving or moving
    // that entity in place is what strands their client on the death screen: the teleport is
    // ignored because the entity is removed, the revive makes the server consider them alive,
    // and from then on the respawn button does nothing because vanilla drops respawn requests
    // from living players. So they get the real respawn first. Returns the entity to use from
    // here on, which is a new object when a respawn happened.
    public static ServerPlayerEntity ensureAlive(MinecraftServer server, ServerPlayerEntity player) {
        if (player.isDisconnected()) return player;

        boolean dead = player.isDead();
        if (!dead && !player.isRemoved() && !player.notInAnyWorld) return player;

        // Alive but removed is a player watching the End credits; keep everything they have
        boolean alive = !dead;
        player.notInAnyWorld = false;
        ServerPlayerEntity fresh = respawn(server, player, alive);
        HardcoreWorldReset.LOGGER.info("Respawned {} before moving them (was {}).",
                fresh.getName().getString(), dead ? "dead on the death screen" : "out of the world");
        return fresh;
    }

    // PlayerManager.respawnPlayer() builds the new entity but leaves it to the caller to point
    // the connection at it, exactly like the vanilla respawn request handler does. Without that
    // the connection keeps ticking and moving the old, removed entity, and the new one never
    // syncs its health or position to the client.
    private static ServerPlayerEntity respawn(MinecraftServer server, ServerPlayerEntity player, boolean alive) {
        ServerPlayerEntity fresh = server.getPlayerManager().respawnPlayer(
                player, alive, alive ? Entity.RemovalReason.CHANGED_DIMENSION : Entity.RemovalReason.KILLED);
        fresh.networkHandler.player = fresh;
        return fresh;
    }

    public static void ensureAllAlive(MinecraftServer server) {
        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            ensureAlive(server, player);
        }
    }

    // The client shows the death screen although the server has the player alive, so pressing
    // "respawn" is ignored by vanilla. A respawn that keeps everything is the one packet
    // sequence that closes that screen; afterwards the player goes back to where they were,
    // in the same game mode, so the arena or the Limbo never notices.
    public static void refreshClient(MinecraftServer server, ServerPlayerEntity player) {
        if (WorldResetManager.isResetting()) {
            // The respawn would load a chunk of the world that is being erased; replay it once
            // the new world exists (exitLimbo() flushes the list)
            deferredRefreshes.add(player.getUuid());
            player.sendMessage(Text.literal("§7Un momento, el mundo se está regenerando..."), false);
            return;
        }

        ServerWorld world = player.getServerWorld();
        Vec3d pos = player.getPos();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        GameMode gameMode = player.interactionManager.getGameMode();

        // Without a bed to look for, the respawn target is the world spawn and the client is
        // spared the "no home bed" notice; it is wiped by the reset anyway
        player.setSpawnPoint(null, null, 0.0f, false, false);
        player.notInAnyWorld = false;

        ServerPlayerEntity fresh = respawn(server, player, true);
        fresh.changeGameMode(gameMode);
        fresh.teleport(world, pos.x, pos.y, pos.z, yaw, pitch);

        HardcoreWorldReset.LOGGER.info("Refreshed the client of {}, who was stuck on the death screen.", fresh.getName().getString());
    }

    // Once the new world is ready and before anyone is moved into it
    public static void flushDeferredRefreshes(MinecraftServer server) {
        if (deferredRefreshes.isEmpty()) return;
        for (UUID id : new ArrayList<>(deferredRefreshes)) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) {
                refreshClient(server, player);
            }
        }
        deferredRefreshes.clear();
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        deferredRefreshes.remove(player.getUuid());
    }

    public static void respawnAllPlayers(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) return;

        // Fetch the calculated natural world spawn point
        BlockPos worldSpawn = overworld.getSpawnPos();

        net.minecraft.server.command.ServerCommandSource silentSource = server.getCommandSource().withSilent();

        server.getCommandManager().executeWithPrefix(silentSource, "clear @a");

        server.getCommandManager().executeWithPrefix(silentSource, "weather clear");

        server.getCommandManager().executeWithPrefix(silentSource, "recipe take @a *");

        server.getCommandManager().executeWithPrefix(silentSource, "recipe give @a minecraft:crafting_table");

        for (ServerPlayerEntity player : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            player = ensureAlive(server, player);

            // Use AdvancementEntry instead of Advancement for it to work in 1.20.2
            for (net.minecraft.advancement.AdvancementEntry advancementEntry : server.getAdvancementLoader().getAdvancements()) {
                net.minecraft.advancement.AdvancementProgress progress = player.getAdvancementTracker().getProgress(advancementEntry);
                if (progress.isAnyObtained()) {
                    java.util.List<String> obtainedCriteria = new java.util.ArrayList<>();
                    progress.getObtainedCriteria().forEach(obtainedCriteria::add);
                    for (String criterion : obtainedCriteria) {
                        player.getAdvancementTracker().revokeCriterion(advancementEntry, criterion);
                    }
                }
            }

            player.setExperienceLevel(0);
            player.setExperiencePoints(0);
            player.experienceProgress = 0.0f;
            player.totalExperience = 0;

            player.clearStatusEffects();

            player.setHealth(20.0f);
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
            player.getHungerManager().setExhaustion(0.0f);
            player.setFireTicks(0);
            player.setFrozenTicks(0);
            player.setAir(player.getMaxAir());
            player.fallDistance = 0.0f;

            // Delete their old spawnpoint
            player.setSpawnPoint(null, null, 0.0f, false, false);

            // Use the chunk scanner to find a perfectly safe block near the spawn
            net.minecraft.util.math.BlockPos fuzzySpawn = com.frankloq.reset.WorldSpawnLocator.checkAndGetSafePos(overworld, worldSpawn.getX(), worldSpawn.getZ());

            // Fallback If the scanner somehow returns null (bc someone flooded the spawn with lava idk)
            if (fuzzySpawn == null) {
                int fallbackY = overworld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldSpawn.getX(), worldSpawn.getZ());
                if (fallbackY <= overworld.getBottomY()) fallbackY = 64; // Anti-void protection
                fuzzySpawn = new net.minecraft.util.math.BlockPos(worldSpawn.getX(), fallbackY, worldSpawn.getZ());
            }

            // Force load the specific chunk to prevent suffocation
            overworld.getChunkManager().getChunk(fuzzySpawn.getX() >> 4, fuzzySpawn.getZ() >> 4, ChunkStatus.FULL, true);

            // Drop them safely onto the surface
            player.teleport(
                    overworld,
                    fuzzySpawn.getX() + 0.5,
                    fuzzySpawn.getY() + 0.1,
                    fuzzySpawn.getZ() + 0.5,
                    0.0f, 0.0f
            );

            player.changeGameMode(GameMode.SURVIVAL);
        }
    }
}
