package com.frankloq.arena;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

// The decorative entities of the arena: floating text, the culprit's effigy and cosmetic lightning.
public final class ArenaDisplays {

    private static final int TEXT_BACKGROUND = 0x99000000; // ARGB, translucent black
    private static final byte SHADOW_FLAG = 1;

    private ArenaDisplays() {
    }

    // A floating text that always faces the viewer. Its bottom edge sits at "pos".
    public static DisplayEntity.TextDisplayEntity spawnText(ServerWorld world, Vec3d pos, Text text, float scale, int lineWidth) {
        DisplayEntity.TextDisplayEntity display = EntityType.TEXT_DISPLAY.create(world);
        if (display == null) return null;

        display.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        display.setText(text);
        display.setLineWidth(lineWidth);
        display.setBackground(TEXT_BACKGROUND);
        display.setDisplayFlags(SHADOW_FLAG);
        display.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        display.setBrightness(new Brightness(15, 15));
        display.setTransformation(new AffineTransformation(
                new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()
        ));
        world.spawnEntity(display);
        return display;
    }

    // An armor stand wearing the culprit's head, for when the culprit logs out to dodge the punishment
    public static ArmorStandEntity spawnEffigy(ServerWorld world, Vec3d pos, GameProfile profile, Text label) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) return null;

        stand.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setShowArms(true);
        stand.setCustomName(label);
        stand.setCustomNameVisible(true);
        stand.disabledSlots = 0xFFFFFF; // nobody takes anything off it

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));
        stand.equipStack(EquipmentSlot.HEAD, head);
        stand.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        stand.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
        stand.equipStack(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));

        world.spawnEntity(stand);
        return stand;
    }

    // Thunder and flash without fire or damage
    public static void strikeLightning(ServerWorld world, Vec3d pos) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
        if (bolt == null) return;
        bolt.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        bolt.setCosmetic(true);
        world.spawnEntity(bolt);
    }
}
