package com.frankloq;

import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// The mod's own sound effects. The .ogg files ship inside the jar under
// assets/hardcoreworldreset/sounds, and ./gradlew build also packs them as a server resource
// pack for clients that do not have the mod (see resourcepack/README.md).
//
// None of them is registered in the sound event registry, on purpose. A registered entry has
// to be synced to every client and would stop vanilla clients from joining, while an
// unregistered SoundEvent travels in the play-sound packet as a direct entry that carries its
// identifier, which any client resolves from whatever resource pack it has loaded.
public final class ModSounds {

    public static final SoundEvent DEAD = of("dead");              // a life lost, not the last one
    public static final SoundEvent JUMPSCARE = of("jumpscare");    // the last life: the world dies with it
    public static final SoundEvent LIMBO = of("limbo");            // background track while in the Limbo
    public static final SoundEvent RESET_WORLD = of("resetworld"); // arriving in the freshly generated world

    public static final List<String> NAMES = List.of("dead", "jumpscare", "limbo", "resetworld");

    // limbo.ogg lasts 3:23. It starts again after this many ticks for anyone still down there.
    private static final int LIMBO_LOOP_TICKS = 203 * 20 + 20;
    // The Limbo uses the End's sky, so the client may decide to start the End's own music on
    // top of ours at any moment. It gets cut every few seconds while the track plays.
    private static final Identifier VANILLA_END_MUSIC = Identifier.of("minecraft", "music.end");

    public static boolean enabled = true; // sounds-enabled in the config

    // Server tick at which each player's Limbo track has to start over
    private static final Map<UUID, Integer> limboTrack = new HashMap<>();

    private ModSounds() {
    }

    private static SoundEvent of(String name) {
        return SoundEvent.of(Identifier.of(HardcoreWorldReset.MOD_ID, name));
    }

    public static SoundEvent byName(String name) {
        return switch (name) {
            case "dead" -> DEAD;
            case "jumpscare" -> JUMPSCARE;
            case "limbo" -> LIMBO;
            case "resetworld" -> RESET_WORLD;
            default -> null;
        };
    }

    // The track is music; the rest are stingers everyone must hear regardless of the sliders
    public static SoundCategory categoryOf(SoundEvent sound) {
        return sound == LIMBO ? SoundCategory.MUSIC : SoundCategory.MASTER;
    }

    // Event sounds are heard by everybody at full volume, wherever they are
    public static void playToAll(MinecraftServer server, SoundEvent sound) {
        if (!enabled) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            play(player, sound);
        }
    }

    public static void play(ServerPlayerEntity player, SoundEvent sound) {
        // RegistryEntry.of() is a direct entry: the packet carries the identifier itself
        // instead of a registry id these events do not have. Played at the player's own
        // position, so a stereo file comes through at full volume wherever they stand.
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                RegistryEntry.of(sound), categoryOf(sound),
                player.getX(), player.getY(), player.getZ(),
                1.0f, 1.0f, player.getRandom().nextLong()
        ));
    }

    public static void stop(ServerPlayerEntity player, SoundEvent sound) {
        player.networkHandler.sendPacket(new StopSoundS2CPacket(sound.getId(), categoryOf(sound)));
    }

    // /hwr sounds stop: silences every mod sound for everyone
    public static void stopAll(MinecraftServer server) {
        int now = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            for (String name : NAMES) {
                stop(player, byName(name));
            }
            // Someone still in the Limbo would get the track back on the next tick otherwise
            if (limboTrack.containsKey(player.getUuid())) {
                limboTrack.put(player.getUuid(), now + LIMBO_LOOP_TICKS);
            }
        }
    }

    // Keeps the Limbo track playing for whoever is in the Limbo dimension and stops it the
    // moment they leave, whether through the reset, an arena test or the login rescue.
    public static void tick(MinecraftServer server) {
        int now = server.getTicks();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            boolean inLimbo = player.getServerWorld().getRegistryKey() == LimboDimension.LIMBO_KEY;
            Integer restartAt = limboTrack.get(id);

            if (inLimbo && enabled) {
                if (restartAt == null || now >= restartAt) {
                    player.networkHandler.sendPacket(new StopSoundS2CPacket(VANILLA_END_MUSIC, SoundCategory.MUSIC));
                    play(player, LIMBO);
                    limboTrack.put(id, now + LIMBO_LOOP_TICKS);
                } else if (now % 40 == 0) {
                    player.networkHandler.sendPacket(new StopSoundS2CPacket(VANILLA_END_MUSIC, SoundCategory.MUSIC));
                }
            } else if (restartAt != null) {
                limboTrack.remove(id);
                stop(player, LIMBO);
            }
        }

        if (now % 200 == 0) {
            limboTrack.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
        }
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        limboTrack.remove(player.getUuid());
    }
}
