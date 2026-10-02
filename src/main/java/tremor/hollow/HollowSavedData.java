package tremor.hollow;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import tremor.Tremor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Saved state of the hollow, in the overworld's storage ({@code data/tremor_hollow.dat}): the events (SPEC 9), with
 * the blocks their players placed. The way back of a player inside is saved with the player instead
 * ({@link HollowManager}), so the two can never disagree after a crash.
 */
final class HollowSavedData extends SavedData {
    static final String NAME = Tremor.MODID + "_hollow";
    private static final SavedData.Factory<HollowSavedData> FACTORY =
            new SavedData.Factory<>(HollowSavedData::new, HollowSavedData::load);

    private final List<HollowEvent> events = new ArrayList<>();

    private HollowSavedData() {
    }

    /** The server's data, created empty (and not written until something changes) if there is none yet. */
    static HollowSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** All events, those whose slot is being cleared included. */
    List<HollowEvent> events() {
        return Collections.unmodifiableList(events);
    }

    void add(HollowEvent event) {
        events.add(event);
        setDirty();
    }

    void remove(HollowEvent event) {
        events.remove(event);
        setDirty();
    }

    /** The player's event that is not yet clearing its slot, or null. */
    HollowEvent active(UUID player) {
        for (HollowEvent event : events) {
            if (event.player().equals(player) && event.phase() != HollowEvent.Phase.CLEARING) {
                return event;
            }
        }
        return null;
    }

    /** Events that are not yet clearing their slot. */
    int activeCount() {
        int count = 0;
        for (HollowEvent event : events) {
            if (event.phase() != HollowEvent.Phase.CLEARING) {
                count++;
            }
        }
        return count;
    }

    /** The player's latest event, clearing ones included, or null. */
    HollowEvent latest(UUID player) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).player().equals(player)) {
                return events.get(i);
            }
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (HollowEvent event : events) {
            list.add(event.save());
        }
        tag.put("events", list);
        return tag;
    }

    private static HollowSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        HollowSavedData data = new HollowSavedData();
        for (Tag entry : tag.getList("events", Tag.TAG_COMPOUND)) {
            try {
                data.events.add(HollowEvent.load((CompoundTag) entry));
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Dropping an unreadable hollow event {}", entry, e);
            }
        }
        return data;
    }
}
