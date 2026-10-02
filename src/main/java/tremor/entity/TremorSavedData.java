package tremor.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import tremor.Tremor;
import tremor.core.math.Vec3;

/**
 * Per-dimension saved state ({@code data/tremor.dat} of the dimension): at most one entity (SPEC 4), the counter its
 * instance numbers come from, which only ever grows so clients can tell a respawned entity from the old one, and when a
 * naturally spawned entity was last removed (for the natural spawn cooldown, SPEC 11).
 */
public final class TremorSavedData extends SavedData {
    public static final String NAME = Tremor.MODID;
    public static final SavedData.Factory<TremorSavedData> FACTORY =
            new SavedData.Factory<>(TremorSavedData::new, TremorSavedData::load);

    private int lastInstance;
    private TremorEntity entity;
    private long lastNaturalDespawn = Long.MIN_VALUE;

    private TremorSavedData() {
    }

    /** The level's data, created empty (and not written until something changes) if there is none yet. */
    public static TremorSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** The entity of the dimension, or null. */
    public TremorEntity entity() {
        return entity;
    }

    /** Instance number of the most recently spawned entity (0 if there never was one). */
    public int lastInstance() {
        return lastInstance;
    }

    /**
     * Game time of the last removal of a naturally spawned entity (it left, was despawned or replaced), or
     * {@link Long#MIN_VALUE} if there never was one.
     */
    public long lastNaturalDespawn() {
        return lastNaturalDespawn;
    }

    /**
     * Replaces the entity (if any; a removal at {@code gameTime}) by a new one with the next instance number.
     *
     * @param natural spawned by the world (SPEC 11) rather than by a command
     */
    public TremorEntity spawn(Vec3 position, Vec3 normal, boolean natural, long gameTime) {
        remove(gameTime);
        entity = new TremorEntity(++lastInstance, position, normal, natural);
        return entity;
    }

    /** Removes the entity at {@code gameTime}; returns it, or null if there was none. */
    public TremorEntity remove(long gameTime) {
        TremorEntity removed = entity;
        if (removed != null && removed.natural()) {
            lastNaturalDespawn = gameTime;
        }
        entity = null;
        setDirty();
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("lastInstance", lastInstance);
        if (lastNaturalDespawn != Long.MIN_VALUE) {
            tag.putLong("lastNaturalDespawn", lastNaturalDespawn);
        }
        if (entity != null) {
            tag.put("entity", entity.save());
        }
        return tag;
    }

    private static TremorSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TremorSavedData data = new TremorSavedData();
        data.lastInstance = tag.getInt("lastInstance");
        if (tag.contains("lastNaturalDespawn", Tag.TAG_LONG)) {
            data.lastNaturalDespawn = tag.getLong("lastNaturalDespawn");
        }
        if (tag.contains("entity", Tag.TAG_COMPOUND)) {
            try {
                data.entity = TremorEntity.load(tag.getCompound("entity"));
                data.lastInstance = Math.max(data.lastInstance, data.entity.instance());
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Dropping an unreadable tremor entity", e);
            }
        }
        return data;
    }
}
