package tremor.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import tremor.Tremor;
import tremor.core.math.Vec3;
import tremor.spawn.SpawnPauses;

/**
 * Per-dimension saved state ({@code data/tremor.dat} of the dimension): its entities (SPEC 4; several since 1.1.0,
 * one for each part of the world where players are, {@code spawn.spacing} apart), the counter their instance numbers
 * come from, which only ever grows so clients can tell a respawned entity from the old one, and where the natural
 * spawn pauses ({@link SpawnPauses}): around where a naturally spawned entity left (SPEC 11) and where one went deep
 * after an Awakening (the long cooldown of SPEC 9). A save of before 1.1.0 (one entity, pauses without a place) is
 * read as well.
 */
public final class TremorSavedData extends SavedData {
    public static final String NAME = Tremor.MODID;
    public static final SavedData.Factory<TremorSavedData> FACTORY =
            new SavedData.Factory<>(TremorSavedData::new, TremorSavedData::load);

    private int lastInstance;
    private final List<TremorEntity> entities = new ArrayList<>();
    private final SpawnPauses pauses = new SpawnPauses();

    private TremorSavedData() {
    }

    /** The level's data, created empty (and not written until something changes) if there is none yet. */
    public static TremorSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** The entities of the dimension, in the order they appeared. */
    public List<TremorEntity> entities() {
        return Collections.unmodifiableList(entities);
    }

    /** Instance number of the most recently spawned entity (0 if there never was one). */
    public int lastInstance() {
        return lastInstance;
    }

    /** Where natural spawns pause; changes go through {@link #remove} and {@link #awakeningEnded}. */
    public SpawnPauses pauses() {
        return pauses;
    }

    /** Forgets the pauses that are over at {@code now}, each kind lasting as long as given (ticks). */
    public void forgetPausesOver(long now, long leftTicks, long awakeningTicks) {
        if (pauses.forgetOver(SpawnPauses.Kind.LEFT, now, leftTicks)
                | pauses.forgetOver(SpawnPauses.Kind.AWAKENING, now, awakeningTicks)) {
            setDirty();
        }
    }

    /** An Awakening at {@code x, z} ended at {@code gameTime}: natural spawns around it pause from now on. */
    public void awakeningEnded(double x, double z, long gameTime) {
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.AWAKENING, x, z, gameTime));
        setDirty();
    }

    /**
     * A new entity with the next instance number.
     *
     * @param natural spawned by the world (SPEC 11) rather than by a command
     */
    public TremorEntity spawn(Vec3 position, Vec3 normal, boolean natural, long gameTime) {
        TremorEntity entity = new TremorEntity(++lastInstance, position, normal, natural);
        entities.add(entity);
        setDirty();
        return entity;
    }

    /**
     * Removes {@code entity} at {@code gameTime}; one that spawned naturally leaves a pause of natural spawns around
     * where it was. False if it was not one of the dimension's.
     */
    public boolean remove(TremorEntity entity, long gameTime) {
        if (!entities.remove(entity)) {
            return false;
        }
        if (entity.natural()) {
            Vec3 at = entity.crawler().position();
            pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, at.x(), at.z(), gameTime));
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("lastInstance", lastInstance);
        ListTag list = new ListTag();
        for (TremorEntity entity : entities) {
            list.add(entity.save());
        }
        tag.put("entities", list);
        ListTag paused = new ListTag();
        for (SpawnPauses.Pause pause : pauses.all()) {
            CompoundTag p = new CompoundTag();
            p.putString("kind", pause.kind().name());
            p.putDouble("x", pause.x());
            p.putDouble("z", pause.z());
            p.putLong("time", pause.gameTime());
            paused.add(p);
        }
        tag.put("pauses", paused);
        return tag;
    }

    private static TremorSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TremorSavedData data = new TremorSavedData();
        data.lastInstance = tag.getInt("lastInstance");
        // Before 1.1.0: one entity, and the two pauses without a place.
        if (tag.contains("lastNaturalDespawn", Tag.TAG_LONG)) {
            data.pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, Double.NaN, Double.NaN,
                    tag.getLong("lastNaturalDespawn")));
        }
        if (tag.contains("lastAwakeningEnd", Tag.TAG_LONG)) {
            data.pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.AWAKENING, Double.NaN, Double.NaN,
                    tag.getLong("lastAwakeningEnd")));
        }
        if (tag.contains("entity", Tag.TAG_COMPOUND)) {
            data.read(tag.getCompound("entity"));
        }
        ListTag list = tag.getList("entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.read(list.getCompound(i));
        }
        ListTag paused = tag.getList("pauses", Tag.TAG_COMPOUND);
        for (int i = 0; i < paused.size(); i++) {
            CompoundTag p = paused.getCompound(i);
            try {
                data.pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.valueOf(p.getString("kind")), p.getDouble("x"),
                        p.getDouble("z"), p.getLong("time")));
            } catch (IllegalArgumentException e) {
                Tremor.LOGGER.warn("Dropping an unreadable natural spawn pause {}", p);
            }
        }
        return data;
    }

    private void read(CompoundTag tag) {
        try {
            TremorEntity entity = TremorEntity.load(tag);
            entities.add(entity);
            lastInstance = Math.max(lastInstance, entity.instance());
        } catch (RuntimeException e) {
            Tremor.LOGGER.error("Dropping an unreadable tremor entity", e);
        }
    }
}
