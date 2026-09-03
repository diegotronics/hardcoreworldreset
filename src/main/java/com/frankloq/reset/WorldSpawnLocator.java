package com.frankloq.reset;

import com.frankloq.HardcoreWorldReset;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

public class WorldSpawnLocator {

    public static BlockPos determineWorldSpawn(ServerWorld world) {
        BlockPos bestPos = null;
        int sampled = 0;
        int generated = 0;
        long startedAt = System.currentTimeMillis();

        for (int r = 0; r <= 384; r += 16) {

            // Calculate how many points we need to check to form a perfect circle at this radius
            int points = (r == 0) ? 1 : (int) ((2 * Math.PI * r) / 16);

            for (int i = 0; i < points; i++) {
                double angle = (2 * Math.PI / points) * i;
                int x = (int) (Math.cos(angle) * r);
                int z = (int) (Math.sin(angle) * r);

                // Generating a full chunk for every candidate is what used to hold the server
                // thread for tens of seconds when (0, 0) landed in an ocean: each one costs a
                // whole pyramid of neighbouring chunks at lower statuses. The noise column
                // alone tells water from land in a fraction of a millisecond, so only the
                // candidates that look like dry land get the real chunk.
                sampled++;
                if (!looksLikeDryLand(world, x, z)) {
                    continue;
                }

                generated++;
                BlockPos candidate = checkAndGetSafePos(world, x, z);
                if (candidate != null) {
                    bestPos = candidate;
                    break;
                }
            }
            if (bestPos != null) break;
        }

        HardcoreWorldReset.LOGGER.info("Spawn search: {} column(s) sampled, {} chunk(s) generated, {} ms, result {}.",
                sampled, generated, System.currentTimeMillis() - startedAt, bestPos);

        // Fallback if the entire 2048 radius is somehow ocean
        if (bestPos == null) {
            world.getChunkManager().getChunk(0, 0, ChunkStatus.FULL, true);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 0, 0);
            if (y <= world.getBottomY()) {
                y = 63;
            }

            BlockPos fallbackPos = new BlockPos(0, Math.max(y, 63), 0);

        return fallbackPos;
    }

    return bestPos;
}

    // A cheap look at the terrain noise for one column, without loading or generating a chunk:
    // true when the first thing under the sky is solid ground rather than water, lava or the
    // void. Generators without noise (flat, debug) get no cheap answer and always pass.
    private static boolean looksLikeDryLand(ServerWorld world, int x, int z) {
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        if (!(generator instanceof NoiseChunkGenerator)) {
            return true;
        }

        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();

        // The biome costs a handful of noise samples and rules out most of the water before
        // the column, which is a few milliseconds, gets sampled at all
        RegistryEntry<Biome> biome = generator.getBiomeSource()
                .getBiome(BiomeCoords.fromBlock(x), BiomeCoords.fromBlock(generator.getSeaLevel()), BiomeCoords.fromBlock(z), noiseConfig.getMultiNoiseSampler());
        if (biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_DEEP_OCEAN) || biome.isIn(BiomeTags.IS_RIVER)) {
            return false;
        }

        VerticalBlockSample column = generator.getColumnSample(x, z, world, noiseConfig);
        for (int y = world.getTopY() - 1; y >= world.getBottomY(); y--) {
            BlockState state = column.getState(y);
            if (state.isAir()) {
                continue;
            }
            return state.getFluidState().isEmpty();
        }
        return false;
    }

    public static BlockPos checkAndGetSafePos(ServerWorld world, int x, int z) {
        // We load the chunk at the target X, Z to scan it safely
        world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);

        // Calculate the starting coordinates of the chunk
        int chunkStartX = (x >> 4) << 4;
        int chunkStartZ = (z >> 4) << 4;

        // Scan multiple blocks inside the loaded chunk
        // We check a grid of points within the 16x16 chunk. If any of them are safe, we use it
        for (int dx = 2; dx < 16; dx += 4) {
            for (int dz = 2; dz < 16; dz += 4) {
                int checkX = chunkStartX + dx;
                int checkZ = chunkStartZ + dz;

                // The height has to come from the column being tested. Reading it at the
                // chunk's own x/z gave all sixteen candidates one shared height, so on any
                // sloped ground every one of them sat buried or floating and got rejected,
                // which is what kept pushing spawn selection into its 0,0 fallback.
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, checkX, checkZ);

                // Anti-void protection
                if (y <= world.getBottomY()) {
                    continue;
                }

                BlockPos fuzzyPos = new BlockPos(checkX, y, checkZ);

                if (isValidSpawnBlock(world, fuzzyPos)) {
                    return fuzzyPos; // Found a safe solid block!
                }
            }
        }

        return null; // The entire chunk grid was unsafe
    }

    // Definitive fix for the player spawning crawling or in dangerous blocks
    private static boolean isValidSpawnBlock(ServerWorld world, BlockPos pos) {
        BlockState under = world.getBlockState(pos.down());
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());

        // The floor must be a solid block and not water or lava
        if (!under.isSolidBlock(world, pos.down()) || !world.getFluidState(pos.down()).isEmpty()) {
            return false;
        }

        // Paranoid check to ensure the block the player is standing in is definitely not a fluid
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }

        // No dangerous blocks allowed. Reject powder snow, magma and cactus
        if (under.isOf(Blocks.POWDER_SNOW) || feet.isOf(Blocks.POWDER_SNOW) || head.isOf(Blocks.POWDER_SNOW)) return false;
        if (under.isOf(Blocks.MAGMA_BLOCK) || under.isOf(Blocks.CACTUS) || under.isOf(Blocks.CAMPFIRE)) return false;

        // The crawling prevention, the collision shape for the feet and head must be completely empty
        if (!feet.getCollisionShape(world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(world, pos.up()).isEmpty()) return false;

        return true;
    }
}
