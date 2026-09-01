package com.frankloq.arena;

import com.frankloq.HardcoreWorldReset;
import com.frankloq.LimboDimension;
import com.frankloq.reset.WorldResetManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The interactive Limbo. While the old world is erased and the new one generates, everyone is
 * locked inside an arena: the player who spent the last life stands in a pit, the rest stand on
 * a ring above it and pelt them with snowballs. Nothing in here can hurt anybody.
 *
 * <pre>
 *  INTRO    5 s      titles, lightning and the "culprit" wall. The reset pipeline runs underneath
 *                    and its server hitches hide behind the cinematics.
 *  ACTIVE   variable the throwing. It lasts at least arena-min-seconds and never ends before
 *                    WorldResetManager reports the new world ready, whichever takes longer.
 *  ENDING   5 s      podium, then everybody is handed to the new world through exitLimbo().
 * </pre>
 *
 * Without a culprit (a scheduled reset) it becomes a snowball free-for-all. A test run started
 * with /hwr arena test skips the reset, treats the world as ready from the start and restores
 * every player afterwards.
 */
public final class LimboArena {

    public enum State { INACTIVE, INTRO, ACTIVE, ENDING }

    private static final String SIDEBAR_OBJECTIVE = "hwr_verdugos";
    private static final int INTRO_TICKS = 100;
    private static final int OUTRO_TICKS = 100;
    private static final int AUDIENCE_STACK = 16;
    private static final int CULPRIT_START_AMMO = 4;
    private static final int CULPRIT_MAX_AMMO = 8;
    private static final int CULPRIT_REFILL_TICKS = 40;
    private static final int HALL_OF_SHAME_ENTRIES = 5;
    private static final double KNOCKBACK_STRENGTH = 0.65;
    private static final String SEPARATOR = "§8§m                                                  ";

    private static State state = State.INACTIVE;
    private static boolean testMode;
    private static boolean worldReady;
    private static int ticks;
    private static int activeTicks;
    private static int endingTicks;
    private static int minActiveTicks;
    private static int tryNumber;
    private static GameProfile culprit;
    private static Text deathCause;
    private static boolean culpritFled;

    private static final Set<UUID> participants = new LinkedHashSet<>();
    private static final Map<UUID, String> names = new HashMap<>();
    private static final Map<UUID, Vec3d> homeSpots = new HashMap<>();
    private static final Map<UUID, Integer> hits = new HashMap<>();
    private static int hitsOnCulprit;
    private static int culpritRevengeHits;

    private static ArmorStandEntity effigy;
    private static DisplayEntity.TextDisplayEntity wallText;
    private static ServerBossBar bossBar;
    // Entities this arena spawned, so the intro sweep can tell them from leftovers
    private static final Set<UUID> ownEntities = new HashSet<>();

    // Test runs only. An entry outlives the run when its player logged out mid-test, so they
    // still get their inventory and position back when they return.
    private static final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    // Players whose snapshot is restored a second after they log in, once their client is ready
    private static final Map<UUID, Integer> pendingRestores = new HashMap<>();

    private LimboArena() {
    }

    // ------------------------------------------------------------------------------ queries

    public static boolean isRunning() {
        return state != State.INACTIVE;
    }

    public static boolean isTestRunning() {
        return isRunning() && testMode;
    }

    public static boolean isParticipant(ServerPlayerEntity player) {
        return isRunning() && participants.contains(player.getUuid());
    }

    public static boolean isCulprit(ServerPlayerEntity player) {
        return culprit != null && player.getUuid().equals(culprit.getId());
    }

    // ------------------------------------------------------------------------------ lifecycle

    // Locks everyone online into the arena. Returns false when the arena cannot or should not
    // run (no Limbo dimension, nobody to throw, nobody to throw at); the caller then falls back
    // to the plain spectator Limbo.
    public static boolean start(MinecraftServer server, GameProfile culpritProfile, Text cause, int tryNo, boolean test) {
        if (state != State.INACTIVE) return false;

        ServerWorld limbo = server.getWorld(LimboDimension.LIMBO_KEY);
        if (limbo == null) {
            HardcoreWorldReset.LOGGER.error("Limbo world not found, the arena cannot run.");
            return false;
        }

        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (players.isEmpty()) return false;

        ServerPlayerEntity culpritPlayer = culpritProfile == null ? null : server.getPlayerManager().getPlayer(culpritProfile.getId());
        int audience = players.size() - (culpritPlayer == null ? 0 : 1);

        // A lone culprit has nobody to punish them, and a free-for-all needs at least two players.
        // Tests are exempt so a single op can walk around the arena.
        if (!test && (audience < 1 || (culpritProfile == null && players.size() < 2))) {
            HardcoreWorldReset.LOGGER.info("Not enough players for the Limbo arena, using the plain Limbo.");
            return false;
        }

        resetState();
        state = State.INTRO;
        testMode = test;
        worldReady = test;
        minActiveTicks = Math.max(0, HardcoreWorldReset.arenaMinSeconds) * 20;
        tryNumber = tryNo;
        culprit = culpritProfile;
        deathCause = cause;
        culpritFled = culpritProfile != null && culpritPlayer == null;

        for (int cx : ArenaBuilder.CHUNK_RANGE) {
            for (int cz : ArenaBuilder.CHUNK_RANGE) {
                limbo.setChunkForced(cx, cz, true);
            }
        }
        removeArenaEntities(limbo);
        ArenaBuilder.build(limbo);
        spawnDisplays(server, limbo);

        bossBar = new ServerBossBar(Text.literal(""), BossBar.Color.RED, BossBar.Style.PROGRESS);
        createSidebar(server);

        int index = 0;
        for (ServerPlayerEntity player : players) {
            if (test) {
                snapshots.put(player.getUuid(), PlayerSnapshot.capture(player));
            }
            participants.add(player.getUuid());
            names.put(player.getUuid(), player.getName().getString());

            boolean isCulprit = player == culpritPlayer;
            Vec3d spot = isCulprit ? ArenaBuilder.PIT_CENTER : ArenaBuilder.audienceSpot(index++, Math.max(1, audience));
            homeSpots.put(player.getUuid(), spot);

            prepare(player);
            LimboDimension.teleportToLimbo(player, spot, ArenaBuilder.yawTowards(spot, ArenaBuilder.PIT_CENTER));

            if (isCulprit) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, StatusEffectInstance.INFINITE, 0, false, false, false));
            } else {
                setSidebarScore(server, player, 0);
            }
            bossBar.addPlayer(player);
        }

        if (culpritFled) {
            spawnEffigy(limbo);
        }
        if (!test && culprit != null) {
            HallOfShame.record(server, tryNo, culprit.getName(), cause == null ? Text.literal("?") : cause);
        }

        playIntro(server);
        HardcoreWorldReset.LOGGER.info("Limbo arena started (culprit: {}, players: {}, test: {}).",
                culprit == null ? "none" : culprit.getName(), players.size(), test);
        return true;
    }

    public static void tick(MinecraftServer server) {
        tickPendingRestores(server);
        if (state == State.INACTIVE) return;

        ServerWorld limbo = server.getWorld(LimboDimension.LIMBO_KEY);
        if (limbo == null) {
            finish(server);
            return;
        }

        ticks++;

        if (onlineParticipants(server).isEmpty()) {
            HardcoreWorldReset.LOGGER.info("Everyone left the Limbo arena, closing it.");
            finish(server);
            return;
        }

        switch (state) {
            case INTRO -> tickIntro(server, limbo);
            case ACTIVE -> tickActive(server, limbo);
            case ENDING -> tickEnding(server);
            default -> {
            }
        }
    }

    // Called by WorldResetManager once the new world exists and has a spawn point
    public static void onWorldReady(MinecraftServer server) {
        if (state == State.INACTIVE || worldReady) return;
        worldReady = true;

        int remaining = Math.max(0, minActiveTicks - activeTicks);
        if (state == State.ACTIVE && remaining > 0) {
            broadcast(server, "§a[Reset] §7¡El nuevo mundo está listo! El castigo continúa §e" + ceilSeconds(remaining) + "s §7más.");
        } else if (state == State.INTRO) {
            broadcast(server, "§a[Reset] §7¡El nuevo mundo está listo!");
        }
        updateBossBar(server);
    }

    // Ends the arena as early as the reset allows. Returns the feedback for the command.
    public static Text skip(MinecraftServer server) {
        if (state == State.INACTIVE) {
            return Text.literal("§c[Arena] §7No hay ninguna arena activa.");
        }
        minActiveTicks = 0;
        if (state == State.INTRO) {
            beginActive(server);
        }
        if (!worldReady) {
            return Text.literal("§e[Arena] §7La arena terminará en cuanto el nuevo mundo esté listo.");
        }
        if (state == State.ACTIVE) {
            beginEnding(server);
        }
        return Text.literal("§a[Arena] §7Arena finalizada.");
    }

    // A real reset is about to start: hand the test players back right now
    public static void abortTest(MinecraftServer server) {
        if (isTestRunning()) {
            finish(server);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (state == State.INACTIVE) return;
        // With the new world ready this sends everyone there, so lives and the timer reset
        // before the save. Otherwise the players stay in Limbo and get rescued on their next
        // login, exactly like a stop in the middle of the old spectator Limbo.
        finish(server);
    }

    // The sidebar objective lives in scoreboard.dat, so a crash mid-arena would leave it behind
    public static void clearStaleSidebar(MinecraftServer server) {
        removeSidebar(server);
    }

    // ------------------------------------------------------------------------------ players

    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        if (state == State.INACTIVE || !participants.contains(player.getUuid())) return;

        if (bossBar != null) {
            bossBar.removePlayer(player);
        }

        MinecraftServer server = player.getServer();
        if (isCulprit(player) && !culpritFled && server != null) {
            culpritFled = true;
            broadcast(server, "§c" + culprit.getName() + " §7huyó como un cobarde. §7Su efigie pagará por él.");

            ServerWorld limbo = server.getWorld(LimboDimension.LIMBO_KEY);
            if (limbo != null) {
                spawnEffigy(limbo);
            }
            if (wallText != null) {
                wallText.setText(buildWallText());
            }
        }
    }

    // A player from a test run logging back in: put them back where they were, a second from
    // now so their client has finished loading. Returns true when a restore was scheduled, so
    // the caller skips the regular Limbo rescue.
    public static boolean restoreSnapshot(ServerPlayerEntity player) {
        if (!snapshots.containsKey(player.getUuid())) return false;

        participants.remove(player.getUuid());
        pendingRestores.put(player.getUuid(), 20);
        return true;
    }

    private static void tickPendingRestores(MinecraftServer server) {
        if (pendingRestores.isEmpty()) return;

        Iterator<Map.Entry<UUID, Integer>> iterator = pendingRestores.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() > 0) continue;
            iterator.remove();

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            PlayerSnapshot snapshot = snapshots.get(entry.getKey());
            if (player == null || snapshot == null) continue; // logged out again; the snapshot waits

            snapshots.remove(entry.getKey());
            snapshot.restore(player);
            player.sendMessage(Text.literal("§a[Arena] §7Te devolvimos a donde estabas antes de la prueba."), false);
        }
    }

    // Fabric ServerLivingEntityEvents.ALLOW_DAMAGE: nothing hurts anyone inside the arena
    public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        return !(entity instanceof ServerPlayerEntity player && isParticipant(player));
    }

    // Fabric ServerLivingEntityEvents.ALLOW_DEATH: the last safety net, e.g. against /kill
    public static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
        if (entity instanceof ServerPlayerEntity player && isParticipant(player)) {
            revive(player);
            return false;
        }
        return true;
    }

    public static void revive(ServerPlayerEntity player) {
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20.0f);
        player.setFireTicks(0);
    }

    // ------------------------------------------------------------------------------ snowballs

    // Called from SnowballHitMixin for every snowball that hits an entity, server side
    public static void onSnowballHit(SnowballEntity snowball, Entity target) {
        if (state != State.ACTIVE && state != State.ENDING) return;
        if (!(snowball.getWorld() instanceof ServerWorld world) || world.getRegistryKey() != LimboDimension.LIMBO_KEY) return;
        if (!(snowball.getOwner() instanceof ServerPlayerEntity thrower) || !participants.contains(thrower.getUuid())) return;

        MinecraftServer server = world.getServer();

        if (target instanceof ServerPlayerEntity victim) {
            if (victim == thrower || !participants.contains(victim.getUuid())) return;

            knockBack(victim, thrower);
            world.sendEntityDamage(victim, victim.getDamageSources().thrown(snowball, thrower));
            world.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, 1.0f);
            world.spawnParticles(ParticleTypes.CRIT, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 8, 0.3, 0.4, 0.3, 0.05);

            boolean onCulprit = isCulprit(victim);
            if (culprit == null || onCulprit) {
                countHit(server, thrower, onCulprit);
            } else if (isCulprit(thrower)) {
                culpritRevengeHits++;
                thrower.playSoundToPlayer(SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f, 0.8f);
            }
            return;
        }

        if (effigy != null && target == effigy) {
            world.playSound(null, effigy.getX(), effigy.getY(), effigy.getZ(),
                    SoundEvents.ENTITY_ARMOR_STAND_HIT, SoundCategory.PLAYERS, 1.0f, 0.9f);
            world.spawnParticles(ParticleTypes.CRIT, effigy.getX(), effigy.getBodyY(0.5), effigy.getZ(), 8, 0.3, 0.4, 0.3, 0.05);
            countHit(server, thrower, true);
        }
    }

    private static void knockBack(ServerPlayerEntity victim, ServerPlayerEntity thrower) {
        // takeKnockback pushes away from the direction given, so point it at the thrower.
        // Its vertical part is capped at a small hop on the ground, which keeps the culprit
        // in the pit however hard they get hit.
        Vec3d before = victim.getVelocity();
        victim.takeKnockback(KNOCKBACK_STRENGTH, thrower.getX() - victim.getX(), thrower.getZ() - victim.getZ());

        // Same dance as PlayerEntity.attack(): the client owns its own movement, so hand it the
        // push directly and put the server-side velocity back instead of letting the entity
        // tracker send it a second time.
        victim.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(victim));
        victim.networkHandler.sendPacket(new DamageTiltS2CPacket(victim));
        victim.velocityModified = false;
        victim.setVelocity(before);
    }

    private static void countHit(MinecraftServer server, ServerPlayerEntity thrower, boolean onCulprit) {
        int total = hits.merge(thrower.getUuid(), 1, Integer::sum);
        if (onCulprit) {
            hitsOnCulprit++;
        }
        thrower.playSoundToPlayer(SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f, 1.3f);
        setSidebarScore(server, thrower, total);
    }

    // ------------------------------------------------------------------------------ phases

    private static void tickIntro(MinecraftServer server, ServerWorld limbo) {
        // Entities left behind by an arena that was cut short (a crash, a stop) sit in chunks
        // that were not loaded yet when start() swept the area. Their entities stream in over
        // the first ticks, so sweep twice.
        if (ticks == 15 || ticks == 80) {
            for (Entity entity : limbo.getOtherEntities(null, ArenaBuilder.BOUNDS,
                    e -> !(e instanceof PlayerEntity) && !ownEntities.contains(e.getUuid()))) {
                entity.discard();
            }
        }

        if (ticks == 20 && culprit != null) {
            ArenaDisplays.strikeLightning(limbo, ArenaBuilder.PIT_CENTER);
        }

        if (ticks == 60) {
            broadcast(server, SEPARATOR);
            if (culprit == null) {
                broadcast(server, "§6☠ §eSe acabó el tiempo del intento §c#" + tryNumber + "§e.");
                broadcast(server, "§7Nadie tiene la culpa esta vez: guerra de bolas de nieve mientras nace el nuevo mundo.");
            } else {
                broadcast(server, "§4☠ §cEl culpable de nuestras desgracias: §e" + culprit.getName() + (culpritFled ? " §7(huyó)" : ""));
                if (deathCause != null) {
                    broadcast(server, Text.literal("§7Causa: §f").append(deathCause));
                }
                broadcast(server, "§7El castigo dura hasta que el nuevo mundo esté listo §8(mínimo " + (minActiveTicks / 20) + "s)§7.");
            }
            broadcast(server, SEPARATOR);
        }

        if (ticks >= INTRO_TICKS) {
            beginActive(server);
        }
    }

    private static void beginActive(MinecraftServer server) {
        state = State.ACTIVE;
        activeTicks = 0;

        for (ServerPlayerEntity player : onlineParticipants(server)) {
            giveInitialAmmo(player);
            if (culprit == null) {
                sendTitle(player, "§b¡GUERRA DE BOLAS DE NIEVE!", "§7Mientras nace el nuevo mundo", 5, 50, 15);
            } else if (isCulprit(player)) {
                sendTitle(player, "§c¡ESQUIVA!", "§7Tienes unas pocas bolas de nieve para vengarte", 5, 50, 15);
            } else {
                sendTitle(player, "§c¡A LINCHARLO!", "§7Bolas de nieve infinitas · apunten al pozo", 5, 50, 15);
            }
            player.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 0.8f, 1.0f);
        }
        updateBossBar(server);
    }

    private static void tickActive(MinecraftServer server, ServerWorld limbo) {
        activeTicks++;

        if (ticks % 10 == 0) {
            refillAmmo(server);
            enforcePositions(server, limbo);
        }
        if (ticks % 100 == 0) {
            feed(server);
            removeStrayItems(limbo);
        }
        if (ticks % 5 == 0) {
            updateBossBar(server);
        }

        if (activeTicks >= minActiveTicks && worldReady) {
            beginEnding(server);
        }
    }

    private static void beginEnding(MinecraftServer server) {
        state = State.ENDING;
        endingTicks = 0;

        announcePodium(server);

        for (ServerPlayerEntity player : onlineParticipants(server)) {
            if (testMode) {
                sendTitle(player, "§aPRUEBA TERMINADA", "§7Restaurando jugadores...", 5, 60, 15);
            } else if (culprit == null) {
                sendTitle(player, "§a¡SE ACABÓ!", "§7El nuevo mundo está listo", 5, 60, 15);
            } else {
                sendTitle(player, "§a✔ CASTIGO CUMPLIDO", "§7El nuevo mundo los espera...", 5, 60, 15);
            }
            player.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.8f, 1.0f);
        }

        if (bossBar != null) {
            bossBar.setColor(BossBar.Color.GREEN);
            bossBar.setPercent(1.0f);
            bossBar.setName(Text.literal(testMode
                    ? "§aPrueba terminada · restaurando jugadores"
                    : "§a✔ Castigo cumplido · viajando al nuevo mundo"));
        }
    }

    private static void tickEnding(MinecraftServer server) {
        endingTicks++;
        if (endingTicks >= OUTRO_TICKS) {
            finish(server);
        }
    }

    // Tears the arena down. In a real reset it also hands the new world over, but only if it
    // is ready; otherwise the pipeline finishes on its own and exits Limbo itself.
    private static void finish(MinecraftServer server) {
        if (state == State.INACTIVE) return;

        boolean wasTest = testMode;
        boolean ready = worldReady;
        ServerWorld limbo = server.getWorld(LimboDimension.LIMBO_KEY);
        List<ServerPlayerEntity> online = onlineParticipants(server);

        if (bossBar != null) {
            bossBar.clearPlayers();
            bossBar.setVisible(false);
        }
        removeSidebar(server);

        if (limbo != null) {
            removeArenaEntities(limbo);
            for (int cx : ArenaBuilder.CHUNK_RANGE) {
                for (int cz : ArenaBuilder.CHUNK_RANGE) {
                    limbo.setChunkForced(cx, cz, false);
                }
            }
        }

        for (ServerPlayerEntity player : online) {
            player.removeStatusEffect(StatusEffects.GLOWING);
            player.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
        }

        resetState();

        if (wasTest) {
            for (ServerPlayerEntity player : online) {
                PlayerSnapshot snapshot = snapshots.remove(player.getUuid());
                if (snapshot != null) {
                    snapshot.restore(player);
                }
            }
            broadcast(server, "§a[Arena] §7Prueba terminada. Jugadores restaurados.");
        } else if (ready) {
            WorldResetManager.exitLimbo(server);
        }

        HardcoreWorldReset.LOGGER.info("Limbo arena finished (test: {}, world ready: {}).", wasTest, ready);
    }

    private static void resetState() {
        state = State.INACTIVE;
        testMode = false;
        worldReady = false;
        ticks = 0;
        activeTicks = 0;
        endingTicks = 0;
        minActiveTicks = 0;
        tryNumber = 0;
        culprit = null;
        deathCause = null;
        culpritFled = false;
        participants.clear();
        names.clear();
        homeSpots.clear();
        hits.clear();
        hitsOnCulprit = 0;
        culpritRevengeHits = 0;
        effigy = null;
        wallText = null;
        bossBar = null;
        ownEntities.clear();
    }

    // ------------------------------------------------------------------------------ helpers

    // Strips a player of anything that could break the arena: pearls, chorus fruit, elytra,
    // potions, totems, mounts... They lose all of it in the reset anyway.
    private static void prepare(ServerPlayerEntity player) {
        if (player.isSleeping()) {
            player.wakeUp(true, true);
        }
        player.stopRiding();
        player.closeHandledScreen();
        player.changeGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.getInventory().selectedSlot = 0;
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
        player.clearStatusEffects();
        revive(player);
        player.fallDistance = 0.0f;
        player.setVelocity(Vec3d.ZERO);
    }

    private static void playIntro(MinecraftServer server) {
        for (ServerPlayerEntity player : onlineParticipants(server)) {
            player.playSoundToPlayer(SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 0.7f, 0.8f);
            if (culprit == null) {
                sendTitle(player, "§6SE ACABÓ EL TIEMPO", "§7Nadie tiene la culpa esta vez", 10, 70, 20);
            } else if (isCulprit(player)) {
                sendTitle(player, "§4¡CULPABLE!", "§7Sobrevive a la ira de tus compañeros", 10, 70, 20);
            } else if (culpritFled) {
                sendTitle(player, "§4☠ EL CULPABLE ☠", "§e" + culprit.getName() + " §7huyó. Su efigie pagará por él", 10, 70, 20);
            } else {
                sendTitle(player, "§4☠ EL CULPABLE ☠", "§e" + culprit.getName() + " §7nos condenó a todos", 10, 70, 20);
            }
        }

        if (bossBar != null) {
            bossBar.setColor(BossBar.Color.RED);
            bossBar.setPercent(1.0f);
            bossBar.setName(Text.literal(culprit == null
                    ? "§6Se acabó el tiempo · preparando la guerra de bolas de nieve"
                    : "§4☠ §cEl culpable de nuestras desgracias: §e" + culprit.getName()));
        }
    }

    private static void spawnDisplays(MinecraftServer server, ServerWorld limbo) {
        // Text displays grow upwards from their position. One pixel is 1/40 of a block times the
        // scale, so the four line wall is two blocks tall and at most twelve wide at scale 2.
        Vec3d wallSpot = new Vec3d(0.5, ArenaBuilder.RING_FLOOR_Y + 2.5, 0.5);
        wallText = ArenaDisplays.spawnText(limbo, wallSpot, buildWallText(), 2.0f, 240);
        remember(wallText);

        // The hall of shame hangs over the north and south stretches of the ring
        Text hall = buildHallText(HallOfShame.load(server, HALL_OF_SHAME_ENTRIES));
        remember(ArenaDisplays.spawnText(limbo, new Vec3d(0.5, ArenaBuilder.RING_FLOOR_Y + 3.2, -8.5), hall, 1.2f, 300));
        remember(ArenaDisplays.spawnText(limbo, new Vec3d(0.5, ArenaBuilder.RING_FLOOR_Y + 3.2, 9.5), hall, 1.2f, 300));
    }

    private static Text buildWallText() {
        MutableText text = Text.empty();
        if (culprit == null) {
            text.append(Text.literal("SE ACABÓ EL TIEMPO").formatted(Formatting.GOLD, Formatting.BOLD)).append("\n")
                    .append(Text.literal("Nadie tiene la culpa esta vez").formatted(Formatting.GRAY)).append("\n")
                    .append(Text.literal("Guerra de bolas de nieve mientras nace el nuevo mundo").formatted(Formatting.AQUA)).append("\n")
                    .append(Text.literal("Intento #" + tryNumber).formatted(Formatting.DARK_GRAY));
            return text;
        }

        text.append(Text.literal("☠ EL CULPABLE DE NUESTRAS DESGRACIAS ☠").formatted(Formatting.DARK_RED, Formatting.BOLD)).append("\n")
                .append(Text.literal(culprit.getName() + (culpritFled ? " (huyó)" : "")).formatted(Formatting.YELLOW, Formatting.BOLD)).append("\n");
        if (deathCause != null) {
            text.append(deathCause.copy().formatted(Formatting.GRAY)).append("\n");
        }
        text.append(Text.literal("Intento #" + tryNumber + " · terminado").formatted(Formatting.DARK_GRAY));
        return text;
    }

    private static Text buildHallText(List<HallOfShame.Entry> history) {
        MutableText text = Text.literal("MURO DE LA VERGÜENZA").formatted(Formatting.RED, Formatting.BOLD);
        if (history.isEmpty()) {
            return text.append("\n").append(Text.literal("Aún no hay culpables registrados").formatted(Formatting.GRAY));
        }
        // Newest first; the death message already names the player
        for (int i = history.size() - 1; i >= 0; i--) {
            HallOfShame.Entry entry = history.get(i);
            text.append("\n")
                    .append(Text.literal("#" + entry.tryNumber() + " · ").formatted(Formatting.DARK_GRAY))
                    .append(entry.cause().copy().formatted(Formatting.GRAY));
        }
        return text;
    }

    private static void spawnEffigy(ServerWorld limbo) {
        if (effigy != null || culprit == null) return;
        effigy = ArenaDisplays.spawnEffigy(limbo, ArenaBuilder.PIT_CENTER, culprit,
                Text.literal("§c" + culprit.getName() + " §7(huyó como un cobarde)"));
        remember(effigy);
    }

    private static void remember(Entity entity) {
        if (entity != null) {
            ownEntities.add(entity.getUuid());
        }
    }

    private static void giveInitialAmmo(ServerPlayerEntity player) {
        int amount = isCulprit(player) ? CULPRIT_START_AMMO : AUDIENCE_STACK;
        player.getInventory().setStack(0, new ItemStack(Items.SNOWBALL, amount));
        player.getInventory().markDirty();
    }

    // The audience never runs dry; the culprit gets a slow trickle to shoot back with
    private static void refillAmmo(MinecraftServer server) {
        for (ServerPlayerEntity player : onlineParticipants(server)) {
            PlayerInventory inventory = player.getInventory();
            ItemStack slot = inventory.getStack(0);

            if (isCulprit(player)) {
                if (ticks % CULPRIT_REFILL_TICKS != 0) continue;
                if (slot.isOf(Items.SNOWBALL)) {
                    if (slot.getCount() < CULPRIT_MAX_AMMO) {
                        slot.setCount(slot.getCount() + 1);
                        inventory.markDirty();
                    }
                } else if (slot.isEmpty()) {
                    inventory.setStack(0, new ItemStack(Items.SNOWBALL, 1));
                    inventory.markDirty();
                }
            } else if (!slot.isOf(Items.SNOWBALL) || slot.getCount() < AUDIENCE_STACK) {
                inventory.setStack(0, new ItemStack(Items.SNOWBALL, AUDIENCE_STACK));
                inventory.markDirty();
            }
        }
    }

    // The culprit stays in the pit and the audience stays on the ring, whatever happens
    private static void enforcePositions(MinecraftServer server, ServerWorld limbo) {
        for (ServerPlayerEntity player : onlineParticipants(server)) {
            if (player.getServerWorld() != limbo) continue;
            Vec3d pos = player.getPos();

            if (isCulprit(player)) {
                if (!ArenaBuilder.isInsidePit(pos)) {
                    Vec3d c = ArenaBuilder.PIT_CENTER;
                    player.teleport(limbo, c.x, c.y, c.z, player.getYaw(), player.getPitch());
                    player.sendMessage(Text.literal("§cNo hay escapatoria."), true);
                }
            } else if (ArenaBuilder.isInsidePit(pos) || !ArenaBuilder.isInsideArena(pos)) {
                Vec3d home = homeSpots.getOrDefault(player.getUuid(), ArenaBuilder.audienceSpot(0, 1));
                player.teleport(limbo, home.x, home.y, home.z, ArenaBuilder.yawTowards(home, ArenaBuilder.PIT_CENTER), 20.0f);
                player.sendMessage(Text.literal("§e¡Fuera del pozo!"), true);
            }
        }
    }

    private static void feed(MinecraftServer server) {
        for (ServerPlayerEntity player : onlineParticipants(server)) {
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20.0f);
        }
    }

    private static void removeStrayItems(ServerWorld limbo) {
        for (Entity entity : limbo.getOtherEntities(null, ArenaBuilder.BOUNDS, e -> e instanceof ItemEntity)) {
            entity.discard();
        }
    }

    // The Limbo dimension is not part of the reset, so the arena cleans up after itself
    private static void removeArenaEntities(ServerWorld limbo) {
        for (Entity entity : limbo.getOtherEntities(null, ArenaBuilder.BOUNDS, e -> !(e instanceof PlayerEntity))) {
            entity.discard();
        }
        effigy = null;
        wallText = null;
    }

    private static void announcePodium(MinecraftServer server) {
        broadcast(server, SEPARATOR);
        broadcast(server, culprit == null
                ? "§6★ Resultados de la guerra de bolas de nieve"
                : "§6★ Verdugos del intento §c#" + tryNumber);

        List<Map.Entry<UUID, Integer>> ranking = hits.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .limit(3)
                .toList();

        if (ranking.isEmpty()) {
            broadcast(server, "§7Nadie acertó ni un solo tiro. Vergonzoso.");
        } else {
            String[] medals = {"§e1.", "§72.", "§63."};
            for (int i = 0; i < ranking.size(); i++) {
                Map.Entry<UUID, Integer> entry = ranking.get(i);
                String name = names.getOrDefault(entry.getKey(), "?");
                broadcast(server, medals[i] + " §f" + name + " §7- " + entry.getValue() + (entry.getValue() == 1 ? " impacto" : " impactos"));
            }
        }

        if (culprit != null) {
            String line = "§c" + culprit.getName() + " §7recibió §c" + hitsOnCulprit + " §7" + (hitsOnCulprit == 1 ? "impacto" : "impactos");
            broadcast(server, culpritRevengeHits > 0 ? line + " §7y devolvió §e" + culpritRevengeHits + "§7." : line + "§7.");
        }
        broadcast(server, SEPARATOR);
    }

    private static void updateBossBar(MinecraftServer server) {
        if (bossBar == null || state != State.ACTIVE) return;

        int remaining = Math.max(0, minActiveTicks - activeTicks);
        String what = culprit == null ? "GUERRA DE BOLAS DE NIEVE" : "LINCHAMIENTO";

        if (remaining > 0) {
            bossBar.setColor(BossBar.Color.RED);
            bossBar.setName(Text.literal("§c" + what + " §7· quedan §e" + ceilSeconds(remaining) + "s"
                    + (worldReady ? " §7· §anuevo mundo listo" : " §7· generando el nuevo mundo")));
            bossBar.setPercent(minActiveTicks == 0 ? 1.0f : (float) remaining / minActiveTicks);
        } else if (!worldReady) {
            bossBar.setColor(BossBar.Color.YELLOW);
            bossBar.setName(Text.literal("§eEl nuevo mundo se está generando... §7¡sigan lanzando!"));
            bossBar.setPercent((ticks % 40) / 40.0f);
        }
    }

    private static void createSidebar(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        removeSidebar(server);
        Text title = culprit == null ? Text.literal("§b❄ Impactos ❄") : Text.literal("§c⚔ Verdugos ⚔");
        ScoreboardObjective objective = scoreboard.addObjective(
                SIDEBAR_OBJECTIVE, ScoreboardCriterion.DUMMY, title, ScoreboardCriterion.RenderType.INTEGER, true, null);
        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
    }

    private static void removeSidebar(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(SIDEBAR_OBJECTIVE);
        if (objective != null) {
            scoreboard.removeObjective(objective);
        }
    }

    private static void setSidebarScore(MinecraftServer server, ServerPlayerEntity player, int score) {
        ScoreboardObjective objective = server.getScoreboard().getNullableObjective(SIDEBAR_OBJECTIVE);
        if (objective != null) {
            server.getScoreboard().getOrCreateScore(player, objective).setScore(score);
        }
    }

    private static List<ServerPlayerEntity> onlineParticipants(MinecraftServer server) {
        List<ServerPlayerEntity> online = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (participants.contains(player.getUuid())) {
                online.add(player);
            }
        }
        return online;
    }

    private static void sendTitle(ServerPlayerEntity player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
    }

    private static void broadcast(MinecraftServer server, Text text) {
        server.getPlayerManager().broadcast(text, false);
    }

    private static void broadcast(MinecraftServer server, String text) {
        broadcast(server, Text.literal(text));
    }

    private static int ceilSeconds(int ticks) {
        return (ticks + 19) / 20;
    }
}
