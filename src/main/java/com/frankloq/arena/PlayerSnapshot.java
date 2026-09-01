package com.frankloq.arena;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

// Everything the arena changes about a player, so a test run can hand them back untouched.
// A real reset wipes all of this anyway, so snapshots are only taken in test mode.
public final class PlayerSnapshot {

    private final RegistryKey<World> dimension;
    private final Vec3d pos;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    private final List<ItemStack> inventory;
    private final float health;
    private final int food;
    private final float saturation;
    private final int xpLevel;
    private final float xpProgress;
    private final List<StatusEffectInstance> effects;

    private PlayerSnapshot(ServerPlayerEntity player) {
        this.dimension = player.getServerWorld().getRegistryKey();
        this.pos = player.getPos();
        this.yaw = player.getYaw();
        this.pitch = player.getPitch();
        this.gameMode = player.interactionManager.getGameMode();

        PlayerInventory inv = player.getInventory();
        this.inventory = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) {
            this.inventory.add(inv.getStack(i).copy());
        }

        this.health = player.getHealth();
        this.food = player.getHungerManager().getFoodLevel();
        this.saturation = player.getHungerManager().getSaturationLevel();
        this.xpLevel = player.experienceLevel;
        this.xpProgress = player.experienceProgress;

        this.effects = new ArrayList<>();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            this.effects.add(new StatusEffectInstance(effect));
        }
    }

    public static PlayerSnapshot capture(ServerPlayerEntity player) {
        return new PlayerSnapshot(player);
    }

    public void restore(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld world = server.getWorld(dimension);
        if (world == null) world = server.getOverworld();

        player.changeGameMode(gameMode);

        PlayerInventory inv = player.getInventory();
        inv.clear();
        for (int i = 0; i < inv.size() && i < inventory.size(); i++) {
            inv.setStack(i, inventory.get(i).copy());
        }
        inv.markDirty();

        player.clearStatusEffects();
        for (StatusEffectInstance effect : effects) {
            player.addStatusEffect(new StatusEffectInstance(effect));
        }

        player.setHealth(Math.max(1.0f, health));
        player.getHungerManager().setFoodLevel(food);
        player.getHungerManager().setSaturationLevel(saturation);
        player.setExperienceLevel(xpLevel);
        player.setExperiencePoints(Math.round(xpProgress * player.getNextLevelExperience()));

        player.teleport(world, pos.x, pos.y, pos.z, yaw, pitch);
    }
}
