package com.frankloq.mixin;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class PlayerDeathMixin {

	@Inject(
			method = "onDeath(Lnet/minecraft/entity/damage/DamageSource;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void onPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
		ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

		if (!HardcoreWorldReset.isModEnabled()) {
			return; // The mod is off, let the game handle the death normally.
		}

		HardcoreWorldReset.LOGGER.info(
				"MIXIN FIRED for player: {}",
				player.getName().getString()
		);

		// Only cancel the vanilla death when the mod takes over (last life -> world reset,
		// or a reset is already running). Deaths with lives to spare stay fully vanilla.
		if (HardcoreWorldReset.handlePlayerDeath(player, damageSource)) {
			ci.cancel();
		}
	}
}