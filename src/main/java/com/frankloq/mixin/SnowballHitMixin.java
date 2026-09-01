package com.frankloq.mixin;

import com.frankloq.arena.LimboArena;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla snowballs deal zero damage to players, and PlayerEntity.damage() drops any zero-damage
// hit before the knockback code runs, so a snowball fight between players has no effect at all.
// The Limbo arena needs the hits to land, so it handles them itself.
@Mixin(SnowballEntity.class)
public abstract class SnowballHitMixin {

	@Inject(method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V", at = @At("TAIL"))
	private void hardcoreworldreset$onEntityHit(EntityHitResult hit, CallbackInfo ci) {
		SnowballEntity self = (SnowballEntity) (Object) this;
		if (self.getWorld().isClient()) {
			return;
		}
		LimboArena.onSnowballHit(self, hit.getEntity());
	}
}
