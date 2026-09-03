package com.frankloq.mixin;

import com.frankloq.HardcoreWorldReset;
import com.frankloq.reset.PlayerRespawner;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla ignores a respawn request from a player whose health is above zero. That is exactly
// the state of someone whose client is on the death screen while the reset has them alive on
// the server (revived, frozen or moved after a death), and it leaves them stuck there for good:
// the "Respawn" button does nothing. While the mod owns the player, the request is honoured
// with a respawn that keeps their state, which is the one thing that closes that screen.
@Mixin(ServerPlayNetworkHandler.class)
public abstract class RespawnRequestMixin {

	@Inject(method = "onClientStatus", at = @At("HEAD"), cancellable = true)
	private void hardcoreworldreset$onClientStatus(ClientStatusC2SPacket packet, CallbackInfo ci) {
		if (packet.getMode() != ClientStatusC2SPacket.Mode.PERFORM_RESPAWN) {
			return;
		}

		ServerPlayerEntity player = ((ServerPlayNetworkHandler) (Object) this).player;
		MinecraftServer server = player.getServer();

		// The packet arrives on the network thread first; vanilla reschedules the whole
		// handler onto the server thread, where this runs again
		if (server == null || !server.isOnThread()) {
			return;
		}

		// A real death, or the End credits: vanilla handles both
		if (player.getHealth() <= 0.0F || player.notInAnyWorld) {
			return;
		}

		if (!HardcoreWorldReset.managesPlayer(player)) {
			return;
		}

		ci.cancel();
		PlayerRespawner.refreshClient(server, player);
	}
}
