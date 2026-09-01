package com.frankloq;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderWarningBlocksChangedS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.border.WorldBorder;

// Death audio and the "final death" jumpscare.
//
// Everything here is sent as raw packets, so a vanilla client hears and sees it with no
// mod installed. Custom sound ids (the ones that are not minecraft:*) only resolve if the
// client has the server resource pack -- see resourcepack/README.md. When a client is
// missing the pack it simply hears nothing, which never breaks the reset pipeline.
public class ScareEffects {

    // Client inventory slot 39 is the helmet. Sending it with sync id -2 writes straight
    // into the client's PlayerInventory without touching the server-side stack, so the
    // carved pumpkin exists only on screen (it is what draws textures/misc/pumpkinblur.png
    // full screen -- override that texture in the resource pack to get a real jumpscare image).
    private static final int CLIENT_INVENTORY_SYNC_ID = -2;
    private static final int HELMET_SLOT = 39;

    // A throwaway border whose only job is to carry an absurd warning distance to the
    // client. The vignette turns blood red when the player is "inside" the warning zone,
    // and this never touches the real world border.
    private static final WorldBorder SCARE_BORDER = new WorldBorder();

    static {
        SCARE_BORDER.setWarningBlocks(Integer.MAX_VALUE);
    }

    // Config (see HardcoreWorldReset#loadConfig). An empty value disables that sound.
    public static String deathSound = "minecraft:entity.ender_dragon.growl";
    public static float deathSoundPitch = 0.7f;
    public static String finalDeathSound = "minecraft:entity.wither.spawn";
    public static String finalDeathSong = "hardcoreworldreset:final_song";
    public static int songDelayTicks = 40;
    public static boolean scareScreen = true;

    // Ticks left before the song kicks in, or -1 when no scare is running.
    private static int songCountdown = -1;
    private static boolean screenScareActive = false;
    private static Identifier playingSong = null;

    // One life lost, but not the last one: a single ominous hit everyone on the server hears.
    public static void playDeathSound(MinecraftServer server) {
        Identifier sound = parseSound(deathSound, "death-sound");
        if (sound == null) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            playAt(player, sound, 1.0f, deathSoundPitch);
        }
    }

    // Last life gone. Jumpscare now, song a moment later (once the scream has landed).
    public static void startFinalScare(MinecraftServer server, ServerPlayerEntity victim) {
        Identifier sound = parseSound(finalDeathSound, "final-death-sound");
        String victimName = victim.getName().getString();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (sound != null) {
                playAt(player, sound, 1.0f, 1.0f);
            }
            if (scareScreen) {
                applyScreenScare(player, victimName);
            }
        }

        screenScareActive = scareScreen;
        songCountdown = Math.max(0, songDelayTicks);

        HardcoreWorldReset.LOGGER.info("Final-death scare started for {}.", victimName);
    }

    public static void tick(MinecraftServer server) {
        if (songCountdown < 0) return;

        if (songCountdown > 0) {
            songCountdown--;
            return;
        }

        songCountdown = -1;
        clearScreenScare(server);
        startSong(server);
    }

    // The reset was aborted (/hwr stopCountdown or /hwr off): undo everything the scare did.
    public static void cancel(MinecraftServer server) {
        songCountdown = -1;
        clearScreenScare(server);
        stopSong(server);
    }

    private static void startSong(MinecraftServer server) {
        Identifier song = parseSound(finalDeathSong, "final-death-song");
        if (song == null) return;

        // Stereo .ogg files are played by the client without any 3D attenuation, so the
        // track keeps playing at full volume through the Limbo trip and the respawn. The
        // huge volume is the fallback that keeps a mono file audible after the teleport.
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            playAt(player, song, 1000000.0f, 1.0f);
        }

        playingSong = song;
        HardcoreWorldReset.LOGGER.info("Playing final-death song: {}", song);
    }

    private static void stopSong(MinecraftServer server) {
        if (playingSong == null) return;

        StopSoundS2CPacket packet = new StopSoundS2CPacket(playingSong, SoundCategory.MASTER);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.networkHandler.sendPacket(packet);
        }
        playingSong = null;
    }

    private static void applyScreenScare(ServerPlayerEntity player, String victimName) {
        // Blood-red full-screen vignette.
        player.networkHandler.sendPacket(new WorldBorderWarningBlocksChangedS2CPacket(SCARE_BORDER));

        // Fake carved pumpkin -> full-screen pumpkinblur.png overlay.
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                CLIENT_INVENTORY_SYNC_ID, 0, HELMET_SLOT, new ItemStack(Items.CARVED_PUMPKIN)));

        // No fade in: the title has to hit on the same frame as the sound.
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 60, 10));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("§c" + victimName + " §7lost their §cfinal §7life")));
        player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal("§4§l☠ THE WORLD IS DYING ☠")));

        // Purely visual: no particles, no HUD icons, just the screen warping and going dark.
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false, false));
    }

    private static void clearScreenScare(MinecraftServer server) {
        if (!screenScareActive) return;
        screenScareActive = false;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Hand the client back the real warning distance of the world it is standing in.
            player.networkHandler.sendPacket(
                    new WorldBorderWarningBlocksChangedS2CPacket(player.getServerWorld().getWorldBorder()));

            // And the helmet it actually has, undoing the fake pumpkin.
            player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                    CLIENT_INVENTORY_SYNC_ID, 0, HELMET_SLOT, player.getEquippedStack(EquipmentSlot.HEAD)));

            player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
            player.removeStatusEffect(StatusEffects.NAUSEA);
            player.removeStatusEffect(StatusEffects.DARKNESS);
        }
    }

    // Sends the sound as a *direct* registry entry, meaning the identifier travels over the
    // wire by name instead of by numeric id. That is what lets a vanilla client resolve a
    // hardcoreworldreset:* sound out of the server resource pack.
    private static void playAt(ServerPlayerEntity player, Identifier sound, float volume, float pitch) {
        RegistryEntry<SoundEvent> entry = RegistryEntry.of(SoundEvent.of(sound));
        player.networkHandler.sendPacket(new PlaySoundS2CPacket(
                entry,
                SoundCategory.MASTER,
                player.getX(),
                player.getY(),
                player.getZ(),
                volume,
                pitch,
                player.getRandom().nextLong()
        ));
    }

    private static Identifier parseSound(String raw, String configKey) {
        if (raw == null) return null;

        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) return null;

        Identifier id = Identifier.tryParse(trimmed);
        if (id == null) {
            HardcoreWorldReset.LOGGER.warn("Invalid sound id '{}' for {}; skipping it.", trimmed, configKey);
        }
        return id;
    }
}
