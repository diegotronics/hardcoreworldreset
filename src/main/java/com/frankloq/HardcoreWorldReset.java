package com.frankloq;

import com.frankloq.arena.HallOfShame;
import com.frankloq.arena.LimboArena;
import com.frankloq.reset.PlayerRespawner;
import com.frankloq.reset.WorldResetManager;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class HardcoreWorldReset implements ModInitializer {

	public static final String MOD_ID = "hardcoreworldreset";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean resetInProgress = false;
	private static int limboCountdownTicks = -1;
	private static boolean modEnabled = true;
	public static boolean reuseSeed = false; // Reuse the same seed for each reset.
	public static int maxLives = 3; // Lives each player starts with. The world resets when someone loses their last one.
	public static boolean arenaEnabled = true; // Lock everyone in the Limbo arena while the world regenerates.
	public static int arenaMinSeconds = 30; // The arena never ends before this many seconds of throwing.
	private static boolean scheduledResetActive = false; // Flag to indicate if a reset is currently scheduled
	private static int scheduledResetTicks = -1; // scheduled reset
	private static int initialScheduledMinutes = -1;// Store the initial minutes for accurate time remaining display
	private static boolean alwaysShowActionBar = false;
	private static int actionBarDisplayTicks = 0; // Tracks the 5-second popup
	private static final java.util.Map<net.minecraft.server.network.ServerPlayerEntity, Integer> rescueQueue = new java.util.HashMap<>();

	// Who spent the final life, remembered from the death until the Limbo trip five seconds later
	private static GameProfile pendingCulprit = null;
	private static Text pendingDeathCause = null;

	public static boolean isModEnabled() { return modEnabled; }

	public static boolean cancelCountdown(MinecraftServer server) {
		boolean stopped = false;

		// Stop death countdown
		if (resetInProgress && limboCountdownTicks > 0) {
			resetInProgress = false;
			limboCountdownTicks = -1;
			pendingCulprit = null;
			pendingDeathCause = null;
			WorldResetManager.unlockCountdown();
			stopped = true;
		}

		// Stop scheduled countdown
		if (scheduledResetActive || initialScheduledMinutes > 0) {
			scheduledResetActive = false;
			scheduledResetTicks = -1;
			initialScheduledMinutes = -1;
			stopped = true;
		}

		// Whenever no reset pipeline is actually running, make sure the lock is free. This
		// is the manual escape hatch if an aborted attempt ever leaves it held: without it
		// the mod would look enabled while quietly refusing to reset again.
		// The arena keeps the lock on purpose until it hands the new world over.
		if (!WorldResetManager.isResetting() && !LimboArena.isRunning()) {
			WorldResetManager.unlockCountdown();
		}

		return stopped;
	}

	// True from the moment a reset is committed to until the new world is handed back,
	// including the five second countdown before players are moved to Limbo.
	public static boolean isResetImminent() {
		return resetInProgress || WorldResetManager.isCountdownLocked();
	}

	@Override
	public void onInitialize() {
		LOGGER.info("HardcoreWorldReset initialized.");
		loadConfig();
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LivesManager.load(server);
			LivesManager.initScoreboard(server);
			LimboArena.clearStaleSidebar(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(LimboArena::onServerStopping);

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WorldResetManager.guaranteeLevelDatOnShutdown(server);
		});

		// Inside the arena nobody can be hurt or killed, whatever the source
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(LimboArena::allowDamage);
		ServerLivingEntityEvents.ALLOW_DEATH.register(LimboArena::allowDeath);

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LimboArena.onPlayerDisconnect(handler.player));

		// After a non-final death the player respawns through the vanilla flow;
		// greet them with how many lives they have left.
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive) return; // Ignore dimension-change "respawns" (e.g. leaving the End)

			// Safety net for servers running hardcore=true, where vanilla respawns players as spectators
			MinecraftServer server = newPlayer.getServer();
			if (server != null && server.isHardcore() && newPlayer.isSpectator()) {
				newPlayer.changeGameMode(GameMode.SURVIVAL);
			}

			LivesManager.syncPlayer(newPlayer);

			int remaining = LivesManager.getLives(newPlayer);
			String hearts = remaining <= 10 ? "§c❤".repeat(Math.max(remaining, 0)) : "§c" + remaining + " ❤";
			String subtitle = remaining == 1
					? "§c¡Última vida! §7La próxima muerte borra el mundo."
					: "§7Te quedan §c" + remaining + " §7vidas.";

			newPlayer.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(10, 60, 10));
			newPlayer.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal(subtitle)));
			newPlayer.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal(hearts)));
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("hwr")

					// stopCountdown (Aborts any active reset without turning off the mod)
					.then(literal("stopCountdown")
							.requires(source -> source.hasPermissionLevel(2)) // Makes command require op status
							.executes(context -> {
								if (cancelCountdown(context.getSource().getServer())) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§a[Reset] §7¡Cuenta atrás cancelada! El mundo está a salvo."), false
									);
								} else {
									context.getSource().sendError(Text.literal("§c[Reset] §7¡No hay ninguna cuenta atrás activa que detener!"));
								}
								return 1;
							}))

					// 2. off (Disables the mod and aborts any active reset)
					.then(literal("off")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								modEnabled = false;
								boolean stopped = cancelCountdown(context.getSource().getServer());

								if (stopped) {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§c[Reset] §7Mod desactivado y reset en curso cancelado."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§c[Reset] §7Mod desactivado. Las muertes ya no reinician el mundo."), false
									);
								}
								return 1;
							}))

					// 3. on (Enables the mod)
					.then(literal("on")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								modEnabled = true;
								context.getSource().getServer().getPlayerManager().broadcast(
										Text.literal("§a[Reset] §7Mod activado. ¡Los resets hardcore están activos!"), false
								);
								return 1;
							}))
					// 4. startTimer (Sets a timer in minutes)
					.then(literal("startTimer")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("minutos", integer(1))
									.executes(context -> {
										int mins = getInteger(context, "minutos");

										// Save the initial time into memory
										initialScheduledMinutes = mins;

										// 20 ticks = 1 second. 60 seconds = 1 minute.
										scheduledResetTicks = mins * 60 * 20;
										scheduledResetActive = true;
										actionBarDisplayTicks = 100;

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§e[Reset] §7Reset del mundo programado en §c" + mins + " §7minutos."), false
										);
										return 1;
									})))
					// 5. Show time remaining on scheduled reset
					.then(literal("timeRemaining")
							.executes(context -> {
								if (scheduledResetActive) {
									int secondsLeft = scheduledResetTicks / 20;
									int mins = secondsLeft / 60;
									secondsLeft = secondsLeft % 60;
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§7Tiempo hasta el reset programado: §c" + mins + " §7minuto(s) y §c" + secondsLeft + " §7segundo(s)."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§cNo hay ningún temporizador activo."), false
									);
								}
								return 1;
							}))

					// 6. reuseSameSeed (Toggles on/off the seed reuse feature in-game)
					.then(literal("reuseSameSeed")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("valor", bool())
									.executes(context -> {
										boolean value = getBool(context, "valor");

										// 1. Update the live RAM
										reuseSeed = value;

										// 2. Save it permanently to the physical file
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7reuse-same-seed establecido en: §e" + value), false
										);
										return 1;
									})))

					// 7. alwaysShowActionBar (Toggles the permanent action bar)
					.then(literal("alwaysShowActionBar")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("valor", bool())
									.executes(context -> {
										boolean value = getBool(context, "valor");

										alwaysShowActionBar = value;
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7always-show-action-bar establecido en: §e" + value), false
										);
										return 1;
									})))

					// 8. lives (Shows everyone's remaining lives; no OP required)
					.then(literal("lives")
							.executes(context -> {
								context.getSource().sendFeedback(() -> Text.literal("§e[Reset] §7Vidas restantes:"), false);
								for (ServerPlayerEntity p : context.getSource().getServer().getPlayerManager().getPlayerList()) {
									int remaining = LivesManager.getLives(p);
									context.getSource().sendFeedback(() -> Text.literal(
											"§7- " + p.getName().getString() + ": §c" + remaining), false);
								}
								return 1;
							}))

					// 9. maxLives (Changes how many lives everyone gets and refills them)
					.then(literal("maxLives")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("valor", integer(1, 100))
									.executes(context -> {
										int value = getInteger(context, "valor");

										maxLives = value;
										saveConfig();
										LivesManager.resetAllLives(context.getSource().getServer());

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7max-lives establecido en §c" + value + "§7. ¡Se han recargado las vidas de todos!"), false
										);
										return 1;
									})))

					// 10. arena (The Limbo arena: test it, end it early, configure it)
					.then(literal("arena")
							.then(literal("test")
									.requires(source -> source.hasPermissionLevel(2))
									.executes(context -> startArenaTest(context.getSource(), null))
									.then(argument("culpable", EntityArgumentType.player())
											.executes(context -> startArenaTest(context.getSource(), EntityArgumentType.getPlayer(context, "culpable")))))
							.then(literal("skip")
									.requires(source -> source.hasPermissionLevel(2))
									.executes(context -> {
										Text feedback = LimboArena.skip(context.getSource().getServer());
										context.getSource().sendFeedback(() -> feedback, true);
										return 1;
									}))
							.then(literal("enabled")
									.requires(source -> source.hasPermissionLevel(2))
									.then(argument("valor", bool())
											.executes(context -> {
												arenaEnabled = getBool(context, "valor");
												saveConfig();
												context.getSource().getServer().getPlayerManager().broadcast(
														Text.literal("§a[Arena] §7arena-enabled establecido en: §e" + arenaEnabled), false
												);
												return 1;
											})))
							.then(literal("minSeconds")
									.requires(source -> source.hasPermissionLevel(2))
									.then(argument("segundos", integer(0, 600))
											.executes(context -> {
												arenaMinSeconds = getInteger(context, "segundos");
												saveConfig();
												context.getSource().getServer().getPlayerManager().broadcast(
														Text.literal("§a[Arena] §7arena-min-seconds establecido en: §e" + arenaMinSeconds), false
												);
												return 1;
											}))))

					// 11. culpables (The hall of shame; no OP required)
					.then(literal("culpables")
							.executes(context -> {
								java.util.List<HallOfShame.Entry> entries = HallOfShame.load(context.getSource().getServer(), 10);
								if (entries.isEmpty()) {
									context.getSource().sendFeedback(() -> Text.literal("§e[Reset] §7Aún no hay culpables registrados."), false);
									return 1;
								}
								context.getSource().sendFeedback(() -> Text.literal("§e[Reset] §7Muro de la vergüenza (últimos " + entries.size() + "):"), false);
								for (int i = entries.size() - 1; i >= 0; i--) {
									HallOfShame.Entry entry = entries.get(i);
									context.getSource().sendFeedback(() -> Text.literal("§7#" + entry.tryNumber() + " §e" + entry.name() + " §7· §f").append(entry.cause()), false);
								}
								return 1;
							}))
			);
		});

		// Fix for player getting stuck in the Limbo if they leave after the DELETING phase
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			net.minecraft.server.network.ServerPlayerEntity player = handler.player;

			if (LimboArena.restoreSnapshot(player)) {
				// A test arena participant coming back: they were put back where they were
				LOGGER.info("Restored {} from an arena test snapshot.", player.getName().getString());
			} else if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {
				// Check if the player logging in is trapped in Limbo
				// Give blindness so they don't see the void
				player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
						net.minecraft.entity.effect.StatusEffects.BLINDNESS, 40, 1, false, false, false
				));

				// Add them to the rescue queue with a 20 tick delay
				rescueQueue.put(player, 20);
				HardcoreWorldReset.LOGGER.info("Player " + player.getName().getString() + " detected in Limbo. Rescue arriving in 1 second...");
			}

			// Show the joining player their lives on the Tab list and in chat
			LivesManager.syncPlayer(player);
			int remaining = LivesManager.getLives(player);
			player.sendMessage(Text.literal(
					"§7Te queda" + (remaining == 1 ? "" : "n") + " §c" + remaining + " §7" + (remaining == 1 ? "vida" : "vidas")
							+ "§7. Mira la lista Tab para ver las de todos."), false);
		});
	}

	private void onServerTick(MinecraftServer server) {
		// Scheduled reset logic
		if (scheduledResetActive && scheduledResetTicks > 0) {
			scheduledResetTicks--;

			// Handle action bar tick countdown (runs every tick)
			if (!alwaysShowActionBar && actionBarDisplayTicks > 0) {
				actionBarDisplayTicks--;
			}

			// Only run checks once per second (every 20 ticks) to save performance
			if ((scheduledResetTicks + 1) % 20 == 0) {
				int secondsLeft = scheduledResetTicks / 20;

				// Warnings
				if (secondsLeft == 600) {
					server.getPlayerManager().broadcast(Text.literal("§7¡El mundo se borrará en exactamente §c10 §7minutos!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 300) {
					server.getPlayerManager().broadcast(Text.literal("§7¡El mundo se borrará en exactamente §c5 §7minutos!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 60) {
					server.getPlayerManager().broadcast(Text.literal("§7¡El mundo se borrará en exactamente §c1 §7minuto!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft <= 5 && secondsLeft > 0) {
					server.getPlayerManager().broadcast(Text.literal("§7Borrando en §c" + secondsLeft + "§7..."), false);
				}

				// Action bar timer logic
				if (secondsLeft > 5) {
					if (alwaysShowActionBar || actionBarDisplayTicks > 0) {
						// Calculate minutes and remaining seconds for a clean 00:00 format
						int displayMins = secondsLeft / 60;
						int displaySecs = secondsLeft % 60;
						String timerText = String.format("§eReset en: %02d:%02d", displayMins, displaySecs);

						// Send to every player's Action Bar (the 'true' makes it go above the hotbar)
						for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
							p.sendMessage(Text.literal(timerText), true);
						}
					}
				}
			}

			// Trigger Reset
			if (scheduledResetTicks == 0) {
				scheduledResetActive = false;

				// Try to lock the reset (prevents double-resets if someone dies at the exact same millisecond)
				if (WorldResetManager.tryLockCountdown()) {
					// A scheduled reset has no culprit: the arena becomes a snowball free-for-all
					pendingCulprit = null;
					pendingDeathCause = null;
					executeLimboTeleport(server);
				}
			}
		}

		// Process rescue queue
		if (!rescueQueue.isEmpty()) {
			java.util.Iterator<java.util.Map.Entry<net.minecraft.server.network.ServerPlayerEntity, Integer>> iterator = rescueQueue.entrySet().iterator();

			while (iterator.hasNext()) {
				java.util.Map.Entry<net.minecraft.server.network.ServerPlayerEntity, Integer> entry = iterator.next();
				net.minecraft.server.network.ServerPlayerEntity p = entry.getKey();

				// Decrease timer
				entry.setValue(entry.getValue() - 1);

				if (entry.getValue() <= 0) {
					iterator.remove(); // Remove them from the queue

					if (!p.isDisconnected()) {
						ServerWorld overworld = server.getWorld(World.OVERWORLD);
						if (overworld != null) {
							net.minecraft.util.math.BlockPos spawnPos = overworld.getSpawnPos();

							// Wipe the player cache
							com.frankloq.reset.WorldInjectionUtils.wipePlayerState(p);

							// Teleport the player
							p.teleport(
									overworld,
									spawnPos.getX() + 0.5,
									spawnPos.getY() + 1.0,
									spawnPos.getZ() + 0.5,
									0.0f,
									0.0f
							);

							HardcoreWorldReset.LOGGER.info("Successfully rescued & wiped offline player: " + p.getName().getString());
						}
					}
				}
			}
		}

		// The Limbo arena runs alongside the reset pipeline and hands the new world over itself
		LimboArena.tick(server);

		// Advance the world reset pipeline if it is running
		WorldResetManager.tick(server);

		if (!resetInProgress) {
			return;
		}

		if (limboCountdownTicks > 0) {
			limboCountdownTicks--;

			if (limboCountdownTicks % 20 == 0) {
				int secondsLeft = limboCountdownTicks / 20;
				if (secondsLeft > 0) {
					server.getPlayerManager().broadcast(
							Text.literal("§7Borrando el mundo en §c"
									+ secondsLeft
									+ "§7 segundo"
									+ (secondsLeft == 1 ? "" : "s")
									+ "..."),
							false
					);
				}
			}

			if (limboCountdownTicks == 0) {
				executeLimboTeleport(server);
			}
		}
	}

	// Returns true when the mod takes over the death (vanilla death must be cancelled):
	// either this was the player's last life and the reset begins, or a reset is already running.
	// Returns false for deaths with lives to spare, which stay fully vanilla (drops, death screen, respawn).
	public static boolean handlePlayerDeath(
			ServerPlayerEntity player,
			DamageSource damageSource) {

		LOGGER.info("Intercepting death for player: {}",
				player.getName().getString());

		MinecraftServer server = player.getServer();
		if (server == null) return false;

		// Nothing in the arena can kill anyone (the arena blocks all damage before it gets
		// this far); this is only a safety net so a death never leaks into the reset logic.
		if (LimboArena.isParticipant(player)) {
			LimboArena.revive(player);
			return true;
		}

		// A reset is already counting down or running: don't touch their lives
		// (they'll be refilled anyway), just freeze them until the Limbo trip
		if (WorldResetManager.isResetting() || WorldResetManager.isCountdownLocked()) {
			freezePlayerForReset(player);
			player.sendMessage(Text.literal("§eYa hay un reset del mundo en curso. Entrando al Limbo..."), false);
			return true;
		}

		int remaining = LivesManager.decrementLives(player);

		if (remaining > 0) {
			// Non-final death: let vanilla handle everything (death message, drops, respawn)
			LOGGER.info("Player {} lost a life. {} remaining.", player.getName().getString(), remaining);

			String warning = remaining == 1
					? "§7¡§c" + player.getName().getString() + " §7está en su §cÚLTIMA §7vida!"
					: "§7¡" + player.getName().getString() + " perdió una vida! Le quedan §c" + remaining + "§7.";
			server.getPlayerManager().broadcast(Text.literal(warning), false);

			return false;
		}

		// Final life spent: the world dies with them
		freezePlayerForReset(player);

		if (WorldResetManager.tryLockCountdown()) {

			// Broadcast vanilla death message only for the first player
			Text deathMessage = damageSource.getDeathMessage(player);
			server.getPlayerManager().broadcast(deathMessage, false);

			// Remember who to blame: the arena needs the name, the skin and the cause of death
			pendingCulprit = player.getGameProfile();
			pendingDeathCause = deathMessage;

			server.getPlayerManager().broadcast(
					Text.literal("§7¡§c" + player.getName().getString() + " §7perdió su §cúltima §7vida!"),
					false
			);

			resetInProgress = true;
			limboCountdownTicks = 5 * 20;

			server.getPlayerManager().broadcast(
					Text.literal("§7Borrando el mundo en §c5 §7segundos..."),
					false
			);

			LOGGER.info("World reset sequence started. Countdown: 5 seconds.");

		} else {
			// Someone else locked the countdown in this same tick
			player.sendMessage(Text.literal("§eYa hay un reset del mundo en curso. Entrando al Limbo..."), false);
		}

		return true;
	}

	// Keeps a "dead" player alive and parked in spectator while the reset takes care of the rest
	private static void freezePlayerForReset(ServerPlayerEntity player) {
		// Restore health server-side
		player.setHealth(20.0f);
		player.getHungerManager().setFoodLevel(20);
		player.getHungerManager().setSaturationLevel(5.0f);

		// Sync health to client
		player.networkHandler.sendPacket(
				new HealthUpdateS2CPacket(
						20.0f,
						player.getHungerManager().getFoodLevel(),
						player.getHungerManager().getSaturationLevel()
				)
		);

		// Switch to spectator
		player.changeGameMode(GameMode.SPECTATOR);
		player.networkHandler.sendPacket(
				new GameStateChangeS2CPacket(
						GameStateChangeS2CPacket.GAME_MODE_CHANGED,
						3.0f
				)
		);
	}

	private static void executeLimboTeleport(MinecraftServer server) {
		LOGGER.info("Teleporting all players to Limbo...");

		// A test arena still running would collide with the real one: hand its players back first
		if (LimboArena.isTestRunning()) {
			LimboArena.abortTest(server);
		}

		GameProfile culprit = pendingCulprit;
		Text deathCause = pendingDeathCause;
		pendingCulprit = null;
		pendingDeathCause = null;

		if (server.getWorld(LimboDimension.LIMBO_KEY) == null) {
			LOGGER.error("Limbo world not found in server world list! Make sure the dimension JSON files are in the correct location.");
			abortLimboTeleport(server);
			return;
		}

		// The arena locks everyone in and takes care of the players until the new world is
		// ready. When it cannot run (nobody to throw, nobody to throw at) everyone floats in
		// the plain Limbo as a spectator, exactly like before.
		boolean arenaStarted = arenaEnabled
				&& LimboArena.start(server, culprit, deathCause, WorldResetManager.getCurrentTry(server), false);

		if (!arenaStarted) {
			int successCount = 0;
			int failCount = 0;

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				player.changeGameMode(GameMode.SPECTATOR);
				boolean success = LimboDimension.teleportToLimbo(player);

				if (success) {
					player.sendMessage(
							Text.literal("§7Has entrado al Limbo. Espera un momento..."),
							false
					);
					successCount++;
				} else {
					failCount++;
				}
			}

			LOGGER.info("Limbo teleport complete. Success: {}, Failed: {}",
					successCount, failCount);

			if (failCount > 0) {
				abortLimboTeleport(server);
				return;
			}
		}

		// All players are in Limbo, now begin the actual world reset
		limboCountdownTicks = -1;

		// Clear entities safely
		LOGGER.info("Clearing overworld entities to prevent chunk-holder crash...");
		ServerWorld overworld = server.getWorld(World.OVERWORLD);
		if (overworld != null) {
			// Create a safe temporary list
			java.util.List<net.minecraft.entity.Entity> entitiesToRemove = new java.util.ArrayList<>();

			// Gather everything that isn't a player
			for (net.minecraft.entity.Entity entity : overworld.iterateEntities()) {
				if (entity != null && !(entity instanceof ServerPlayerEntity)) {
					entitiesToRemove.add(entity);
				}
			}

			// Delete them safely outside the main iteration loop
			for (net.minecraft.entity.Entity entity : entitiesToRemove) {
				entity.discard();
			}
		}

		WorldResetManager.beginReset(server);
	}

	// The trip to Limbo failed, so the reset cannot go on: put everything back the way it was
	private static void abortLimboTeleport(MinecraftServer server) {
		server.getPlayerManager().broadcast(
				Text.literal("§cNo se pudo teletransportar a todos los jugadores al Limbo. Revisa los logs."),
				false
		);
		resetInProgress = false;
		limboCountdownTicks = -1;

		// Release the countdown lock. tryLockCountdown() took it when this attempt
		// started, and leaving it held makes every later reset fail silently for the
		// rest of the server's life -- not even /hwr stopCountdown could clear it.
		WorldResetManager.unlockCountdown();
	}

	// /hwr arena test [culpable]: runs the arena without touching the world and restores everyone after
	private static int startArenaTest(ServerCommandSource source, ServerPlayerEntity culprit) {
		MinecraftServer server = source.getServer();

		if (isResetImminent() || WorldResetManager.isResetting()) {
			source.sendError(Text.literal("§c[Arena] §7No se puede probar la arena mientras hay un reset en curso."));
			return 0;
		}
		if (LimboArena.isRunning()) {
			source.sendError(Text.literal("§c[Arena] §7Ya hay una arena activa. Usa §e/hwr arena skip §7para terminarla."));
			return 0;
		}

		if (server.getPlayerManager().getPlayerList().isEmpty()) {
			source.sendError(Text.literal("§c[Arena] §7No hay jugadores conectados para la prueba."));
			return 0;
		}
		if (server.getWorld(LimboDimension.LIMBO_KEY) == null) {
			source.sendError(Text.literal("§c[Arena] §7No existe la dimensión del Limbo. Revisa los logs."));
			return 0;
		}

		GameProfile profile = culprit == null ? null : culprit.getGameProfile();
		Text cause = culprit == null ? null : Text.translatable("death.attack.generic", culprit.getDisplayName());

		if (!LimboArena.start(server, profile, cause, WorldResetManager.getCurrentTry(server), true)) {
			source.sendError(Text.literal("§c[Arena] §7No se pudo iniciar la prueba. Revisa los logs."));
			return 0;
		}

		server.getPlayerManager().broadcast(
				Text.literal("§e[Arena] §7Prueba de la arena iniciada por §f" + source.getName()
						+ "§7. Termina sola a los §e" + arenaMinSeconds + "s §7o con §e/hwr arena skip§7."),
				false
		);
		return 1;
	}

	// Called by WorldResetManager when all phases are complete
	public static void onResetComplete(MinecraftServer server) {
		LOGGER.info("Reset complete. Respawning all players.");

		// Reset the flag so future deaths trigger a new reset
		resetInProgress = false;

		// Respawn all players into the fresh world
		PlayerRespawner.respawnAllPlayers(server);

		// Fresh world, fresh lives for everyone
		LivesManager.resetAllLives(server);
		server.getPlayerManager().broadcast(
				Text.literal("§7Vidas de todos restauradas a §c" + maxLives + "§7."),
				false
		);

		// Broadcasts the try counter
		server.getPlayerManager().broadcast(
				Text.literal("§7Intento §c#" + WorldResetManager.getCurrentTry(server)),
				false
		);

		// Restarting the timer
		if (initialScheduledMinutes > 0) {
			// Reset the clock to the maximum time
			scheduledResetTicks = initialScheduledMinutes * 60 * 20;
			scheduledResetActive = true;

			// Show the action bar for 5 seconds when the new loop starts
			actionBarDisplayTicks = 100;

			// Let the players know the clock has started
			server.getPlayerManager().broadcast(
					Text.literal("§e[Reset] §7¡El reloj corre! Próximo reset en §c" + initialScheduledMinutes + "§7 minutos."),
					false
			);
		}
	}

	public static void loadConfig() {
		try {
			// This gets the standard .minecraft/config folder
			java.nio.file.Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
			java.nio.file.Path configFile = configDir.resolve("hardcoreworldreset.properties");
			java.util.Properties props = new java.util.Properties();

			if (java.nio.file.Files.exists(configFile)) {
				// If config exists, read it
				try (java.io.InputStream in = java.nio.file.Files.newInputStream(configFile)) {
					props.load(in);
					String reuse = props.getProperty("reuse-same-seed", "false");
					reuseSeed = Boolean.parseBoolean(reuse);
					String showBar = props.getProperty("always-show-action-bar", "false");
					alwaysShowActionBar = Boolean.parseBoolean(showBar);
					arenaEnabled = Boolean.parseBoolean(props.getProperty("arena-enabled", "true"));

					try {
						maxLives = Math.max(1, Integer.parseInt(props.getProperty("max-lives", "3").trim()));
					} catch (NumberFormatException e) {
						maxLives = 3;
						LOGGER.warn("Invalid max-lives value in config, falling back to 3.");
					}

					try {
						arenaMinSeconds = Math.max(0, Integer.parseInt(props.getProperty("arena-min-seconds", "30").trim()));
					} catch (NumberFormatException e) {
						arenaMinSeconds = 30;
						LOGGER.warn("Invalid arena-min-seconds value in config, falling back to 30.");
					}

					LOGGER.info("Loaded config: reuse-same-seed = " + reuseSeed
							+ ", always-show-action-bar = " + alwaysShowActionBar
							+ ", max-lives = " + maxLives
							+ ", arena-enabled = " + arenaEnabled
							+ ", arena-min-seconds = " + arenaMinSeconds);
				}

				// Older config files predate the arena keys: write them out so they can be edited
				if (!props.containsKey("arena-enabled") || !props.containsKey("arena-min-seconds")) {
					saveConfig();
				}
			} else {
				// If it doesn't exist, create it with the defaults
				props.setProperty("reuse-same-seed", "false");
				props.setProperty("always-show-action-bar", "false");
				props.setProperty("max-lives", "3");
				props.setProperty("arena-enabled", "true");
				props.setProperty("arena-min-seconds", "30");
				try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(configFile)) {
					props.store(out, "Hardcore World Reset Configuration");
					LOGGER.info("Generated default config file.");
				}
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load or generate config file", e);
		}
	}

	public static void saveConfig() {
		try {
			java.nio.file.Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
			java.nio.file.Path configFile = configDir.resolve("hardcoreworldreset.properties");
			java.util.Properties props = new java.util.Properties();

			props.setProperty("reuse-same-seed", String.valueOf(reuseSeed));
			props.setProperty("always-show-action-bar", String.valueOf(alwaysShowActionBar));
			props.setProperty("max-lives", String.valueOf(maxLives));
			props.setProperty("arena-enabled", String.valueOf(arenaEnabled));
			props.setProperty("arena-min-seconds", String.valueOf(arenaMinSeconds));

			try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(configFile)) {
				props.store(out, "Hardcore World Reset Configuration");
				LOGGER.info("Saved config: reuse-same-seed = " + reuseSeed
						+ ", always-show-action-bar = " + alwaysShowActionBar
						+ ", max-lives = " + maxLives
						+ ", arena-enabled = " + arenaEnabled
						+ ", arena-min-seconds = " + arenaMinSeconds);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to save config file!", e);
		}
	}
}
