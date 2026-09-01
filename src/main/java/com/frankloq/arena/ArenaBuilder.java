package com.frankloq.arena;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Builds the Limbo arena out of plain blocks, so it works on any server without shipping a
 * structure file. Everything is centred on the block column (0, 0) of the Limbo dimension.
 *
 * <pre>
 *  y=73      barrier ceiling (invisible) over the whole arena
 *  y=68..72  audience headroom, enclosed by the outer wall (radius 10)
 *  y=67      ring floor, radius 5..9; at radius 5 it doubles as the top of the pit wall
 *  y=65..66  pit wall, radius 5
 *  y=64      pit floor, radius 0..4 (9x9); the culprit stands on it with their feet at y=65
 * </pre>
 *
 * Seen from the pit floor the wall is three blocks tall, more than a jump plus the small hop a
 * snowball knockback gives, so the culprit cannot climb out. Radii are "square" radii, that is
 * max(|x|, |z|). The arena is rebuilt from scratch every time it is used, which also repairs it.
 */
public final class ArenaBuilder {

    public static final int PIT_FLOOR_Y = 64;
    public static final int PIT_RADIUS = 4;
    public static final int RING_FLOOR_Y = 67;
    public static final int RING_OUTER_RADIUS = 9;
    public static final int WALL_RADIUS = 10;
    public static final int WALL_TOP_Y = 72;
    public static final int CEILING_Y = 73;

    private static final int CLEAR_RADIUS = 12;
    private static final int CLEAR_MIN_Y = 62;
    private static final int CLEAR_MAX_Y = 76;

    // The audience stands on the middle of the ring
    private static final int AUDIENCE_RADIUS = 7;
    public static final double AUDIENCE_Y = RING_FLOOR_Y + 1;

    // Where the culprit stands: the centre block on top of the pit floor
    public static final Vec3d PIT_CENTER = new Vec3d(0.5, PIT_FLOOR_Y + 1, 0.5);

    // Everything the arena can contain, used to sweep stray entities
    public static final Box BOUNDS = new Box(
            -CLEAR_RADIUS, CLEAR_MIN_Y, -CLEAR_RADIUS,
            CLEAR_RADIUS + 1, CLEAR_MAX_Y + 1, CLEAR_RADIUS + 1
    );

    // Chunk coordinates covered by the arena, kept force-loaded while it runs
    public static final int[] CHUNK_RANGE = {-1, 0};

    private ArenaBuilder() {
    }

    public static void build(ServerWorld world) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = -CLEAR_RADIUS; x <= CLEAR_RADIUS; x++) {
            for (int z = -CLEAR_RADIUS; z <= CLEAR_RADIUS; z++) {
                for (int y = CLEAR_MIN_Y; y <= CLEAR_MAX_Y; y++) {
                    pos.set(x, y, z);
                    BlockState wanted = blockAt(x, y, z);
                    if (!world.getBlockState(pos).equals(wanted)) {
                        world.setBlockState(pos, wanted, Block.NOTIFY_LISTENERS);
                    }
                }
            }
        }
    }

    private static BlockState blockAt(int x, int y, int z) {
        int r = Math.max(Math.abs(x), Math.abs(z));

        // Invisible lid so nothing (and nobody) leaves the arena upwards
        if (y == CEILING_Y && r <= WALL_RADIUS) {
            return Blocks.BARRIER.getDefaultState();
        }

        // Outer wall around the audience ring
        if (r == WALL_RADIUS && y >= RING_FLOOR_Y && y <= WALL_TOP_Y) {
            if (y == WALL_TOP_Y) return Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState();
            boolean lanternColumn = (Math.abs(x) == WALL_RADIUS && z % 3 == 0) || (Math.abs(z) == WALL_RADIUS && x % 3 == 0);
            if (y == RING_FLOOR_Y + 3 && lanternColumn) return Blocks.SEA_LANTERN.getDefaultState();
            return Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        }

        // Ring floor (the audience walks on top of it)
        if (y == RING_FLOOR_Y && r >= PIT_RADIUS + 1 && r <= RING_OUTER_RADIUS) {
            if (r == PIT_RADIUS + 1) return Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState(); // pit rim
            if (Math.abs(x) == 8 && Math.abs(z) == 8) return Blocks.SEA_LANTERN.getDefaultState();
            return Math.floorMod(x * 31 + z * 17, 6) == 0
                    ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.getDefaultState()
                    : Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        }

        // Pit wall, lit from the middle of each side
        if (r == PIT_RADIUS + 1 && y >= PIT_FLOOR_Y && y < RING_FLOOR_Y) {
            boolean midWall = x == 0 || z == 0;
            if (y == PIT_FLOOR_Y + 2 && midWall) return Blocks.SEA_LANTERN.getDefaultState();
            return Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        }

        // Pit floor
        if (y == PIT_FLOOR_Y && r <= PIT_RADIUS) {
            if (r == PIT_RADIUS && Math.abs(x) == Math.abs(z)) return Blocks.SEA_LANTERN.getDefaultState(); // corners
            if (x == 0 && z == 0) return Blocks.CRYING_OBSIDIAN.getDefaultState();
            return Math.floorMod(x * 31 + z * 17, 5) == 0
                    ? Blocks.CRACKED_DEEPSLATE_TILES.getDefaultState()
                    : Blocks.DEEPSLATE_TILES.getDefaultState();
        }

        return Blocks.AIR.getDefaultState();
    }

    // Spreads the audience evenly along the ring, walking the perimeter of the square of
    // "radius" AUDIENCE_RADIUS. Returns the centre of a block on the ring floor.
    public static Vec3d audienceSpot(int index, int count) {
        int side = 2 * AUDIENCE_RADIUS;
        double perimeter = 4.0 * side;
        double t = ((index + 0.5) / Math.max(1, count)) * perimeter;

        double x;
        double z;
        if (t < side) {
            x = -AUDIENCE_RADIUS + t;
            z = -AUDIENCE_RADIUS;
        } else if (t < 2 * side) {
            x = AUDIENCE_RADIUS;
            z = -AUDIENCE_RADIUS + (t - side);
        } else if (t < 3 * side) {
            x = AUDIENCE_RADIUS - (t - 2 * side);
            z = AUDIENCE_RADIUS;
        } else {
            x = -AUDIENCE_RADIUS;
            z = AUDIENCE_RADIUS - (t - 3 * side);
        }
        return new Vec3d(Math.floor(x) + 0.5, AUDIENCE_Y, Math.floor(z) + 0.5);
    }

    // Yaw that makes an entity standing at "from" look at "to"
    public static float yawTowards(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < 1.0E-6) return 0.0f;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    public static boolean isInsidePit(Vec3d pos) {
        return Math.abs(pos.x - 0.5) <= PIT_RADIUS + 0.5
                && Math.abs(pos.z - 0.5) <= PIT_RADIUS + 0.5
                && pos.y < RING_FLOOR_Y + 1;
    }

    public static boolean isInsideArena(Vec3d pos) {
        return Math.abs(pos.x - 0.5) <= WALL_RADIUS - 0.5
                && Math.abs(pos.z - 0.5) <= WALL_RADIUS - 0.5
                && pos.y >= PIT_FLOOR_Y
                && pos.y <= CEILING_Y;
    }
}
