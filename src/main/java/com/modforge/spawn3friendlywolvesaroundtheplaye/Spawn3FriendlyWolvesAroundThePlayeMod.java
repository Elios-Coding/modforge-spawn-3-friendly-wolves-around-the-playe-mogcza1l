package com.modforge.spawn3friendlywolvesaroundtheplaye;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Spawn3FriendlyWolvesAroundThePlayeMod implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(Spawn3FriendlyWolvesAroundThePlayeMod.class);

	// Cooldown to prevent spawning every tick while crouching.
	private static final int COOLDOWN_TICKS = 20 * 10; // 10 seconds

	private final Map<UUID, Integer> cooldownTicksRemaining = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
		LOGGER.info("Spawn3FriendlyWolvesAroundThePlayeMod initialized");
	}

	private void onEndServerTick(MinecraftServer server) {
		try {
			// Tick down cooldowns.
			if (!cooldownTicksRemaining.isEmpty()) {
				cooldownTicksRemaining.replaceAll((uuid, ticks) -> Math.max(0, ticks - 1));
				cooldownTicksRemaining.values().removeIf(v -> v <= 0);
			}

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				try {
					if (player == null || !player.isAlive()) {
						continue;
					}

					// Only act when the player is sneaking and holding a bone in either hand.
					if (!player.isSneaking()) {
						continue;
					}

					if (!isHoldingBone(player)) {
						continue;
					}

					UUID uuid = player.getUuid();
					if (cooldownTicksRemaining.containsKey(uuid)) {
						continue;
					}

					ServerWorld world = player.getServerWorld();
					spawnThreeFriendlyWolves(world, player);
					cooldownTicksRemaining.put(uuid, COOLDOWN_TICKS);
				} catch (Throwable t) {
					LOGGER.error("Error while processing player tick for wolves spawning", t);
				}
			}
		} catch (Throwable t) {
			LOGGER.error("Error in END_SERVER_TICK handler", t);
		}
	}

	private boolean isHoldingBone(ServerPlayerEntity player) {
		try {
			ItemStack main = player.getMainHandStack();
			ItemStack off = player.getOffHandStack();
			return (main != null && main.isOf(Items.BONE)) || (off != null && off.isOf(Items.BONE));
		} catch (Throwable t) {
			LOGGER.error("Error checking if player is holding a bone", t);
			return false;
		}
	}

	private void spawnThreeFriendlyWolves(ServerWorld world, ServerPlayerEntity player) {
		try {
			Vec3d center = player.getPos();
			float baseYaw = player.getYaw();

			int spawned = 0;
			for (int i = 0; i < 3; i++) {
				WolfEntity wolf = null;
				try {
					double angleRad = Math.toRadians((baseYaw + (i * 120.0f)) % 360.0f);
					double radius = 2.0;
					double x = center.x + Math.cos(angleRad) * radius;
					double z = center.z + Math.sin(angleRad) * radius;
					double y = center.y;

					BlockPos pos = BlockPos.ofFloored(x, y, z);
					// Adjust Y to a sensible nearby height.
					pos = findReasonableSpawnPos(world, pos, 6);

					wolf = EntityType.WOLF.spawn(world, pos, SpawnReason.MOB_SUMMONED);
					if (wolf == null) {
						continue;
					}

					wolf.setOwner(player);
					wolf.setSitting(false);
					wolf.setHealth(wolf.getMaxHealth());

					// Nudge the wolf closer to desired position and face the player.
					wolf.refreshPositionAndAngles(
							pos.getX() + 0.5,
							pos.getY(),
							pos.getZ() + 0.5,
							wrapDegreesTowardPlayer(center, wolf.getPos()),
							wolf.getPitch()
					);

					spawned++;

					world.spawnParticles(ParticleTypes.HEART,
							wolf.getX(), wolf.getBodyY(0.6), wolf.getZ(),
							6,
							0.25, 0.25, 0.25,
							0.02);
				} catch (Throwable t) {
					LOGGER.error("Error spawning/taming wolf {}", i, t);
					if (wolf != null) {
						try {
							wolf.discard();
						} catch (Throwable ignored) {
							// ignore
						}
					}
				}
			}

			if (spawned > 0) {
				BlockPos soundPos = player.getBlockPos();
				world.playSound(null, soundPos, SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f);
				world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
						center.x, center.y + 1.0, center.z,
						10,
						0.6, 0.4, 0.6,
						0.01);
			}
		} catch (Throwable t) {
			LOGGER.error("Error spawning three friendly wolves", t);
		}
	}

	private BlockPos findReasonableSpawnPos(ServerWorld world, BlockPos start, int maxVerticalSearch) {
		try {
			// Prefer the first non-solid space above a solid block within a small vertical band.
			BlockPos.Mutable mut = start.mutableCopy();
			int baseY = start.getY();

			for (int dy = 0; dy <= maxVerticalSearch; dy++) {
				// Try from baseY up.
				mut.set(start.getX(), baseY + dy, start.getZ());
				if (isSpawnSpaceClear(world, mut) && isBlockBelowSolid(world, mut)) {
					return mut.toImmutable();
				}
				// Try from baseY down (skip dy=0 because already tested above).
				if (dy > 0) {
					mut.set(start.getX(), baseY - dy, start.getZ());
					if (isSpawnSpaceClear(world, mut) && isBlockBelowSolid(world, mut)) {
						return mut.toImmutable();
					}
				}
			}

			return start;
		} catch (Throwable t) {
			LOGGER.error("Error finding reasonable spawn position", t);
			return start;
		}
	}

	private boolean isSpawnSpaceClear(ServerWorld world, BlockPos pos) {
		try {
			// A simple check using collision; avoid relying on block state helper method names.
			// Use the entity collision shape check: if a generic wolf-sized AABB intersects blocks, it's not clear.
			// Since we do not have an entity yet, approximate with a 0.9x1.3 box.
			double x = pos.getX() + 0.5;
			double y = pos.getY();
			double z = pos.getZ() + 0.5;
			// If the position is out of world height, fail.
			if (y < world.getBottomY() || y >= world.getTopY()) {
				return false;
			}

			// Use a lightweight collision check by probing for any entities/blocks at the position.
			// This is conservative but avoids fragile mappings.
			return world.isSpaceEmpty((Entity) null, WolfEntity.createWolfBoundingBox(x, y, z));
		} catch (Throwable t) {
			// If anything goes wrong, allow spawn; vanilla spawn will still reject if invalid.
			LOGGER.error("Error checking spawn space clearance", t);
			return true;
		}
	}

	private boolean isBlockBelowSolid(ServerWorld world, BlockPos pos) {
		try {
			BlockPos below = pos.down();
			return world.getBlockState(below).isSolidBlock(world, below);
		} catch (Throwable t) {
			LOGGER.error("Error checking block below solidity", t);
			return true;
		}
	}

	private float wrapDegreesTowardPlayer(Vec3d playerPos, Vec3d wolfPos) {
		try {
			double dx = playerPos.x - wolfPos.x;
			double dz = playerPos.z - wolfPos.z;
			float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
			return MathHelper.wrapDegrees(yaw);
		} catch (Throwable t) {
			LOGGER.error("Error computing wolf yaw", t);
			return 0.0f;
		}
	}
}
