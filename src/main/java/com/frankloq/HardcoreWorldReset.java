package com.frankloq;

import com.frankloq.reset.PlayerRespawner;
import com.frankloq.reset.WorldResetManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import static net.minecraft.server.command.CommandManager.literal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static net.minecraft.server.command.CommandManager.argument;
import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;

public class HardcoreWorldReset implements ModInitializer {

	public static final String MOD_ID = "hardcoreworldreset";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean resetInProgress = false;
	private static int limboCountdownTicks = -1;
	private static boolean modEnabled = true;
	public static boolean reuseSeed = false; // Reuse the same seed for each reset.
	public static int maxLives = 3; // Lives each player starts with. The world resets when someone loses their last one.
	private static boolean scheduledResetActive = false; // Flag to indicate if a reset is currently scheduled
	private static int scheduledResetTicks = -1; // scheduled reset
	private static int initialScheduledMinutes = -1;// Store the initial minutes for accurate time remaining display
	private static boolean alwaysShowActionBar = false;
	private static int actionBarDisplayTicks = 0; // Tracks the 5-second popup
	private static final java.util.Map<net.minecraft.server.network.ServerPlayerEntity, Integer> rescueQueue = new java.util.HashMap<>();

	public static boolean isModEnabled() { return modEnabled; }

	public static boolean cancelCountdown(MinecraftServer server) {
		boolean stopped = false;

		// Stop death countdown
		if (resetInProgress && limboCountdownTicks > 0) {
			resetInProgress = false;
			limboCountdownTicks = -1;
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
		if (!WorldResetManager.isResetting()) {
			WorldResetManager.unlockCountdown();
		}

		// Take the jumpscare back down with the countdown: red screen, pumpkin overlay and
		// song all have to go, otherwise an aborted reset leaves everyone staring at it.
		if (server != null) {
			ScareEffects.cancel(server);
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
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WorldResetManager.guaranteeLevelDatOnShutdown(server);
		});

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
					? "§cLast life! §7The next death erases the world."
					: "§7You have §c" + remaining + " §7lives remaining.";

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
											Text.literal("§a[Reset] §7Countdown aborted! The world is safe."), false
									);
								} else {
									context.getSource().sendError(Text.literal("§c[Reset] §7There is no active countdown to stop!"));
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
											Text.literal("§c[Reset] §7Mod disabled and active reset aborted."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§c[Reset] §7Mod disabled. Deaths will no longer reset the world."), false
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
										Text.literal("§a[Reset] §7Mod enabled. Hardcore resets are active!"), false
								);
								return 1;
							}))
					// 4. startTimer (Sets a timer in minutes)
					.then(literal("startTimer")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("minutes", integer(1))
									.executes(context -> {
										int mins = getInteger(context, "minutes");

										// Save the initial time into memory
										initialScheduledMinutes = mins;

										// 20 ticks = 1 second. 60 seconds = 1 minute.
										scheduledResetTicks = mins * 60 * 20;
										scheduledResetActive = true;
										actionBarDisplayTicks = 100;

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§e[Reset] §7World reset scheduled for§c " + mins + " §7minutes."), false
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
											Text.literal("§7Time until scheduled reset: §c" + mins + " §7minute(s) and §c" + secondsLeft + " §7second(s)."), false
									);
								} else {
									context.getSource().getServer().getPlayerManager().broadcast(
											Text.literal("§cThere isn't any currently active timer."), false
									);
								}
								return 1;
							}))

					// 6. reuseSameSeed (Toggles on/off the seed reuse feature in-game)
					.then(literal("reuseSameSeed")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("value", bool())
									.executes(context -> {
										boolean value = getBool(context, "value");

										// 1. Update the live RAM
										reuseSeed = value;

										// 2. Save it permanently to the physical file
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7reuse-same-seed set to: §e" + value), false
										);
										return 1;
									})))

					// 7. alwaysShowActionBar (Toggles the permanent action bar)
					.then(literal("alwaysShowActionBar")
							.requires(source -> source.hasPermissionLevel(2))
							.then(argument("value", bool())
									.executes(context -> {
										boolean value = getBool(context, "value");

										alwaysShowActionBar = value;
										saveConfig();

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7always-show-action-bar has been set to: §e" + value), false
										);
										return 1;
									})))

					// 8. lives (Shows everyone's remaining lives; no OP required)
					.then(literal("lives")
							.executes(context -> {
								context.getSource().sendFeedback(() -> Text.literal("§e[Reset] §7Remaining lives:"), false);
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
							.then(argument("value", integer(1, 100))
									.executes(context -> {
										int value = getInteger(context, "value");

										maxLives = value;
										saveConfig();
										LivesManager.resetAllLives(context.getSource().getServer());

										context.getSource().getServer().getPlayerManager().broadcast(
												Text.literal("§a[Reset] §7max-lives set to §c" + value + "§7. Everyone's lives have been refilled!"), false
										);
										return 1;
									})))

					// 10. testScare (Fires the final-death jumpscare + song without touching the world)
					.then(literal("testScare")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
								ScareEffects.startFinalScare(context.getSource().getServer(), player);
								context.getSource().sendFeedback(
										() -> Text.literal("§e[Reset] §7Jumpscare preview fired. §7Use §e/hwr stopScare §7to cut it short."), false);
								return 1;
							}))

					// 11. stopScare (Clears the scare and stops the song)
					.then(literal("stopScare")
							.requires(source -> source.hasPermissionLevel(2))
							.executes(context -> {
								ScareEffects.cancel(context.getSource().getServer());
								context.getSource().sendFeedback(() -> Text.literal("§a[Reset] §7Scare cleared."), false);
								return 1;
							}))
			);
		});

		// Fix for player getting stuck in the Limbo if they leave after the DELETING phase
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			net.minecraft.server.network.ServerPlayerEntity player = handler.player;

			// Check if the player logging in is trapped in Limbo
			if (player.getServerWorld().getRegistryKey() == com.frankloq.LimboDimension.LIMBO_KEY) {
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
					"§7You have §c" + remaining + " §7" + (remaining == 1 ? "life" : "lives")
							+ " §7remaining. Check the Tab list to see everyone's."), false);
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
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c10 §7minutes!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 300) {
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c5 §7minutes!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft == 60) {
					server.getPlayerManager().broadcast(Text.literal("§7The world will be erased in exactly §c1 §7minute!"), false);
					actionBarDisplayTicks = 120;
				} else if (secondsLeft <= 5 && secondsLeft > 0) {
					server.getPlayerManager().broadcast(Text.literal("§7Erasing in §c" + secondsLeft + "§7..."), false);
				}

				// Action bar timer logic
				if (secondsLeft > 5) {
					if (alwaysShowActionBar || actionBarDisplayTicks > 0) {
						// Calculate minutes and remaining seconds for a clean 00:00 format
						int displayMins = secondsLeft / 60;
						int displaySecs = secondsLeft % 60;
						String timerText = String.format("§eReset in: %02d:%02d", displayMins, displaySecs);

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

		// Advance the jumpscare timeline (it starts the song once the scream has landed)
		ScareEffects.tick(server);

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
							Text.literal("§7Erasing the world in §c"
									+ secondsLeft
									+ "§7 second"
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

		// A reset is already counting down or running: don't touch their lives
		// (they'll be refilled anyway), just freeze them until the Limbo trip
		if (WorldResetManager.isResetting() || WorldResetManager.isCountdownLocked()) {
			freezePlayerForReset(player);
			player.sendMessage(Text.literal("§eA world reset is already in progress. Joining The Limbo..."), false);
			return true;
		}

		int remaining = LivesManager.decrementLives(player);

		if (remaining > 0) {
			// Non-final death: let vanilla handle everything (death message, drops, respawn)
			LOGGER.info("Player {} lost a life. {} remaining.", player.getName().getString(), remaining);

			String warning = remaining == 1
					? "§c" + player.getName().getString() + " §7is down to their §cLAST §7life!"
					: "§7" + player.getName().getString() + " §7lost a life! §c" + remaining + " §7remaining.";
			server.getPlayerManager().broadcast(Text.literal(warning), false);
			ScareEffects.playDeathSound(server);

			return false;
		}

		// Final life spent: the world dies with them
		freezePlayerForReset(player);

		if (WorldResetManager.tryLockCountdown()) {

			// Broadcast vanilla death message only for the first player
			Text deathMessage = damageSource.getDeathMessage(player);
			server.getPlayerManager().broadcast(deathMessage, false);

			server.getPlayerManager().broadcast(
					Text.literal("§c" + player.getName().getString() + " §7lost their §cfinal §7life!"),
					false
			);

			// Jumpscare first; ScareEffects.tick() drops the song in a couple of seconds later,
			// well before the five second countdown drags everyone into Limbo.
			ScareEffects.startFinalScare(server, player);

			resetInProgress = true;
			limboCountdownTicks = 5 * 20;

			server.getPlayerManager().broadcast(
					Text.literal("§7Erasing the world in §c5 §7seconds..."),
					false
			);

			LOGGER.info("World reset sequence started. Countdown: 5 seconds.");

		} else {
			// Someone else locked the countdown in this same tick
			player.sendMessage(Text.literal("§eA world reset is already in progress. Joining The Limbo..."), false);
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

		int successCount = 0;
		int failCount = 0;

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			player.changeGameMode(GameMode.SPECTATOR);
			boolean success = LimboDimension.teleportToLimbo(player);

			if (success) {
				player.sendMessage(
						Text.literal("§7You have entered The Limbo. Please wait..."),
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
			server.getPlayerManager().broadcast(
					Text.literal("§cFailed to teleport all players to Limbo. Check logs."),
					false
			);
			resetInProgress = false;
			limboCountdownTicks = -1;
			ScareEffects.cancel(server);

			// Release the countdown lock. tryLockCountdown() took it when this attempt
			// started, and leaving it held makes every later reset fail silently for the
			// rest of the server's life -- not even /hwr stopCountdown could clear it.
			WorldResetManager.unlockCountdown();
			return;
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
				Text.literal("§7Everyone's lives restored to §c" + maxLives + "§7."),
				false
		);

		// Broadcasts the try counter
		server.getPlayerManager().broadcast(
				Text.literal("§7Try §c#" + WorldResetManager.getCurrentTry(server)),
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
					Text.literal("§e[Reset] §7The clock is ticking! Next reset in §c" + initialScheduledMinutes + "§7 minutes."),
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

					try {
						maxLives = Math.max(1, Integer.parseInt(props.getProperty("max-lives", "3").trim()));
					} catch (NumberFormatException e) {
						maxLives = 3;
						LOGGER.warn("Invalid max-lives value in config, falling back to 3.");
					}

					ScareEffects.deathSound = props.getProperty("death-sound", ScareEffects.deathSound);
					ScareEffects.finalDeathSound = props.getProperty("final-death-sound", ScareEffects.finalDeathSound);
					ScareEffects.finalDeathSong = props.getProperty("final-death-song", ScareEffects.finalDeathSong);
					ScareEffects.scareScreen = Boolean.parseBoolean(
							props.getProperty("scare-screen-effects", String.valueOf(ScareEffects.scareScreen)));

					try {
						ScareEffects.deathSoundPitch = Float.parseFloat(props.getProperty("death-sound-pitch", String.valueOf(ScareEffects.deathSoundPitch)).trim());
					} catch (NumberFormatException e) {
						LOGGER.warn("Invalid death-sound-pitch value in config, falling back to 0.7.");
					}

					try {
						ScareEffects.songDelayTicks = Math.max(0,
								Integer.parseInt(props.getProperty("final-song-delay-ticks", String.valueOf(ScareEffects.songDelayTicks)).trim()));
					} catch (NumberFormatException e) {
						LOGGER.warn("Invalid final-song-delay-ticks value in config, falling back to 40.");
					}

					LOGGER.info("Loaded config: reuse-same-seed = " + reuseSeed
							+ ", always-show-action-bar = " + alwaysShowActionBar
							+ ", max-lives = " + maxLives
							+ ", death-sound = " + ScareEffects.deathSound
							+ ", final-death-sound = " + ScareEffects.finalDeathSound
							+ ", final-death-song = " + ScareEffects.finalDeathSong);
				}
			} else {
				// If it doesn't exist, create it with the defaults
				props.setProperty("reuse-same-seed", "false");
				props.setProperty("always-show-action-bar", "false");
				props.setProperty("max-lives", "3");
				writeScareDefaults(props);
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
			writeScareDefaults(props);

			try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(configFile)) {
				props.store(out, "Hardcore World Reset Configuration");
				LOGGER.info("Saved config: reuse-same-seed = " + reuseSeed);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to save config file!", e);
		}
	}

	// Sound ids may be any minecraft:* event, or a custom one shipped in the server resource
	// pack (see resourcepack/README.md). Leave a value empty, or set it to "none", to mute it.
	private static void writeScareDefaults(java.util.Properties props) {
		props.setProperty("death-sound", ScareEffects.deathSound);
		props.setProperty("death-sound-pitch", String.valueOf(ScareEffects.deathSoundPitch));
		props.setProperty("final-death-sound", ScareEffects.finalDeathSound);
		props.setProperty("final-death-song", ScareEffects.finalDeathSong);
		props.setProperty("final-song-delay-ticks", String.valueOf(ScareEffects.songDelayTicks));
		props.setProperty("scare-screen-effects", String.valueOf(ScareEffects.scareScreen));
	}
}