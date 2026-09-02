package com.frankloq;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderWarningBlocksChangedS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.border.WorldBorder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// The final death: the jumpscare sound plus two seconds of screen effects for everyone, timed
// so it is over before the five second countdown drags the players into the Limbo arena.
//
// The visuals are client-side tricks delivered as plain packets, so they need nothing installed
// on the client and never touch server-side state:
//  - A throwaway world border with an absurd warning distance makes the client paint its
//    red border vignette at full strength. The real border is never changed, and the client
//    gets the true value back when the scare ends.
//  - A carved pumpkin written into the client's own helmet slot (sync id -2 targets the
//    client inventory only) makes vanilla stretch the pumpkin blur over the screen.
//  - Nausea and darkness are real, short status effects; the arena clears them anyway.
public final class Jumpscare {

    private static final int CLIENT_INVENTORY_SYNC_ID = ScreenHandlerSlotUpdateS2CPacket.UPDATE_PLAYER_INVENTORY_SYNC_ID;
    private static final int HELMET_SLOT = 39;
    private static final int SCREEN_TICKS = 40;
    private static final WorldBorder SCARE_BORDER = new WorldBorder();

    static {
        SCARE_BORDER.setWarningBlocks(Integer.MAX_VALUE);
    }

    public static boolean screenEffects = true; // jumpscare-screen-effects in the config

    private static int ticksLeft = -1;
    private static final Set<UUID> scared = new HashSet<>();

    private Jumpscare() {
    }

    public static boolean isActive() {
        return ticksLeft >= 0;
    }

    // Sound now, screen effects for the next two seconds
    public static void trigger(MinecraftServer server, String victimName) {
        ModSounds.playToAll(server, ModSounds.JUMPSCARE);
        if (!screenEffects) return;

        clear(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            applyScreen(player, victimName);
            scared.add(player.getUuid());
        }
        ticksLeft = SCREEN_TICKS;
    }

    public static void tick(MinecraftServer server) {
        if (ticksLeft < 0) return;
        ticksLeft--;
        if (ticksLeft < 0) {
            clear(server);
        }
    }

    // Also called when a countdown is aborted, so nobody is left staring at a red screen
    public static void clear(MinecraftServer server) {
        ticksLeft = -1;
        for (UUID id : scared) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null) {
                restoreScreen(player);
            }
        }
        scared.clear();
    }

    private static void applyScreen(ServerPlayerEntity player, String victimName) {
        player.networkHandler.sendPacket(new WorldBorderWarningBlocksChangedS2CPacket(SCARE_BORDER));
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                CLIENT_INVENTORY_SYNC_ID, 0, HELMET_SLOT, new ItemStack(Items.CARVED_PUMPKIN)));

        // No fade in: the title has to land on the same frame as the scream
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 50, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(
                Text.literal("§c" + victimName + " §7perdió su última vida")));
        player.networkHandler.sendPacket(new TitleS2CPacket(
                Text.literal("§4§l☠ EL MUNDO MUERE ☠")));

        // Hidden particles and icons: just the screen warping and going dark
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, false, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false, false));
    }

    private static void restoreScreen(ServerPlayerEntity player) {
        // The real warning distance of the world the player is standing in
        player.networkHandler.sendPacket(
                new WorldBorderWarningBlocksChangedS2CPacket(player.getServerWorld().getWorldBorder()));

        // And the helmet they actually wear, which undoes the fake pumpkin
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                CLIENT_INVENTORY_SYNC_ID, 0, HELMET_SLOT, player.getEquippedStack(EquipmentSlot.HEAD)));

        player.removeStatusEffect(StatusEffects.NAUSEA);
        player.removeStatusEffect(StatusEffects.DARKNESS);
    }
}
