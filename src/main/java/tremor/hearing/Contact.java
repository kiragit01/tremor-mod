package tremor.hearing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.world.LevelVoxelView;
import tremor.world.LevelVoxelView.ConductivityClass;

/**
 * Where a vibration enters the ground and how well it gets in (SPEC 7.2, "insulation under the feet").
 *
 * @param point   centre of the voxel the vibration enters through
 * @param footing conductivity of that voxel, the water factor, or 0
 * @param note    for the debug view when the source is not on the ground ({@code climbing}, {@code airborne},
 *                {@code in water}...), or null
 */
record Contact(Vec3 point, double footing, String note) {
    /** How far below the feet the ground is looked for when the entity's supporting block is not known. */
    private static final int GROUND_DEPTH = 2;
    /**
     * A stored climbable farther than this from the entity's position is stale (NeoForge's full-bounding-box ladders
     * find one anywhere in a player's box, up to about 2 blocks from the feet).
     */
    private static final double CLIMB_REACH = 3;

    /**
     * Contact of an entity. On the ground (always for a landing, and for a minecart on rails): the block its collision
     * rests on ({@code mainSupportingBlockPos}: a carpet or snow layer rather than the block under it) if known, else
     * the first block with a collision shape from its feet down ({@value #GROUND_DEPTH} blocks: a carpet at the feet,
     * the ground below, the block under a rail), else the block below its feet; the footing is the conductivity of
     * that block. Off the ground: climbing a ladder, vine or scaffolding, or wading in powder snow (vanilla posts
     * {@code step} there with the entity in the air), see {@link #ofClimbable}; else the water factor in water or in a
     * boat, else 0 (in the air).
     */
    static Contact ofEntity(LevelVoxelView view, Entity entity, boolean landing) {
        BlockPos feet = entity.blockPosition();
        if (landing || entity.onGround() || entity.isOnRails()) {
            BlockPos ground = entity.mainSupportingBlockPos.orElseGet(() -> groundBelow(view, feet));
            return new Contact(center(ground), view.conductivity(ground.getX(), ground.getY(), ground.getZ()), null);
        }
        if (entity instanceof LivingEntity living) {
            Contact climbing = ofClimbable(view, living);
            if (climbing != null) {
                return climbing;
            }
        }
        if (entity.getVehicle() instanceof Boat) {
            return new Contact(center(feet), TremorConfig.COMMON.waterFactor.get(), "in a boat");
        }
        if (entity.isInWater()) {
            return new Contact(center(feet), TremorConfig.COMMON.waterFactor.get(), "in water");
        }
        return new Contact(center(feet), 0, "airborne");
    }

    /**
     * Contact of an entity off the ground that holds on to something: the climbable block it is on
     * ({@link LivingEntity#onClimbable}, which also covers a spider on a wall) or the powder snow it wades in, with the
     * conductivity of that block (a ladder or scaffolding is wooden, powder snow sandy). A climbable that is neither
     * tagged nor solid (vines, cave, weeping and twisting vines; the air next to a spider) passes the vibration on to
     * what it hangs on: the best conductor among its four horizontal neighbours if that is better than the block
     * itself. Null when the entity holds on to nothing.
     */
    static Contact ofClimbable(LevelVoxelView view, LivingEntity entity) {
        BlockPos pos;
        String note;
        if (entity.onClimbable()) {
            // onClimbable() has just stored the climbable it found; an override that does not (a spider on a wall)
            // may leave an old one, so only a block next to the entity is taken, else its own block.
            pos = entity.getLastClimbablePos().filter(p -> p.closerToCenterThan(entity.position(), CLIMB_REACH))
                    .orElseGet(entity::blockPosition);
            note = "climbing";
        } else {
            // The block at the feet, or the one just below them (vanilla's getOnPos) when the feet are on its top.
            pos = entity.blockPosition();
            if (!isPowderSnow(view, pos)) {
                pos = entity.getOnPos();
                if (!isPowderSnow(view, pos)) {
                    return null;
                }
            }
            note = "in powder snow";
        }
        BlockPos best = pos;
        float footing = view.conductivity(pos.getX(), pos.getY(), pos.getZ());
        BlockState state = view.stateAt(pos.getX(), pos.getY(), pos.getZ());
        if (state != null && hangs(state)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos wall = pos.relative(side);
                float c = view.conductivity(wall.getX(), wall.getY(), wall.getZ());
                if (c > footing) {
                    best = wall;
                    footing = c;
                }
            }
        }
        return new Contact(center(best), footing, note);
    }

    /**
     * A block event (placed, broken, hit by a projectile) at {@code pos}: that voxel, with the conductivity of
     * {@code state} (the affected block) if given, else of the block there.
     */
    static Contact ofBlock(LevelVoxelView view, net.minecraft.world.phys.Vec3 pos, BlockState state) {
        BlockPos voxel = BlockPos.containing(pos);
        double footing = state != null ? LevelVoxelView.conductivity(state)
                : view.conductivity(voxel.getX(), voxel.getY(), voxel.getZ());
        return new Contact(center(voxel), footing, null);
    }

    /**
     * An explosion: of the voxel at its centre and the one below it, the better conductor (the blast of a TNT or a
     * creeper standing on the ground goes into that ground).
     */
    static Contact ofBlast(LevelVoxelView view, net.minecraft.world.phys.Vec3 pos) {
        BlockPos voxel = BlockPos.containing(pos);
        BlockPos below = voxel.below();
        float here = view.conductivity(voxel.getX(), voxel.getY(), voxel.getZ());
        float under = view.conductivity(below.getX(), below.getY(), below.getZ());
        return under > here ? new Contact(center(below), under, null) : new Contact(center(voxel), here, null);
    }

    private static boolean isPowderSnow(LevelVoxelView view, BlockPos pos) {
        BlockState state = view.stateAt(pos.getX(), pos.getY(), pos.getZ());
        return state != null && state.is(Blocks.POWDER_SNOW);
    }

    /** Untagged and without a collision shape: what conducts is whatever it is attached to. */
    private static boolean hangs(BlockState state) {
        ConductivityClass c = ConductivityClass.of(state);
        return c == ConductivityClass.AIR || c == ConductivityClass.FLUID;
    }

    private static BlockPos groundBelow(LevelVoxelView view, BlockPos feet) {
        for (int dy = 0; dy >= -GROUND_DEPTH; dy--) {
            BlockPos pos = feet.offset(0, dy, 0);
            BlockState state = view.stateAt(pos.getX(), pos.getY(), pos.getZ());
            if (state != null && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty()) {
                return pos;
            }
        }
        return feet.below();
    }

    private static Vec3 center(BlockPos pos) {
        return Vec3.voxelCenter(pos.getX(), pos.getY(), pos.getZ());
    }
}
