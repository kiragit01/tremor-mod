package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import tremor.core.behavior.DespawnClock;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.motion.Crawler;
import tremor.core.shape.BumpShape;

import java.util.Locale;

/**
 * Server-side state of the entity (SPEC 4). Not a vanilla {@code Entity}: it lives in {@link TremorSavedData} and is
 * driven by a {@link TremorRuntime}. Its position is a point on the skin of the world (the centre of a surface voxel
 * while standing). The route itself is not saved; an entity loaded with a target plans a new one.
 */
public final class TremorEntity {
    /** Highest anger (SPEC 8). */
    public static final float MAX_ANGER = 100;

    /**
     * The last sound the entity heard (SPEC 4 {@code lastHeard}, 7.3).
     *
     * @param position  where the vibration entered the ground
     * @param gameTime  when
     * @param perceived how loud it was at the entity
     * @param event     what made it ({@code step}, {@code fall}, {@code item_land}...)
     */
    public record Heard(Vec3 position, long gameTime, double perceived, String event) {
    }

    /** Who chose the target. */
    public enum TargetKind {
        /** {@code /tremor goto}: sounds do not replace it until it is reached or cancelled. */
        MANUAL,
        /** A heard sound (SPEC 7.3) the stage behaviour goes after (SPEC 8: investigate, creep, hunt). */
        SOUND,
        /** A wander leg (SPEC 5.6) or a search leg around a lost sound (SPEC 8 HUNTING). */
        ROAM
    }

    private final int instance;
    private final boolean natural;
    private final Crawler crawler;
    private final EntityParams params;
    private float anger;
    private Stage stage = Stage.DORMANT;
    private BlockPos target;
    private TargetKind targetKind;
    private Heard lastHeard;
    private boolean aiEnabled = true;
    /** In a frenzy over a dropped shard ({@link Frenzy}): not saved, the brain idle, much faster and taller. */
    private boolean frenzy;
    private boolean leaving;
    private boolean absorbed;
    private final DespawnClock despawnClock;

    /**
     * A new entity standing at {@code position} (a surface voxel centre) with the surface normal there.
     *
     * @param normal  a zero normal is replaced by straight up
     * @param natural spawned by the world rather than by a command; only such an entity despawns by itself (SPEC 11)
     */
    public TremorEntity(int instance, Vec3 position, Vec3 normal, boolean natural) {
        this(instance, natural, new Crawler(position, orUp(normal)), new EntityParams(), new DespawnClock());
    }

    private TremorEntity(int instance, boolean natural, Crawler crawler, EntityParams params,
                         DespawnClock despawnClock) {
        this.instance = instance;
        this.natural = natural;
        this.crawler = crawler;
        this.params = params;
        this.despawnClock = despawnClock;
    }

    /** Changes whenever an entity is (re)spawned in the dimension. */
    public int instance() {
        return instance;
    }

    /** Spawned by the world, not by {@code /tremor spawn}: it despawns by itself (SPEC 11). */
    public boolean natural() {
        return natural;
    }

    public Crawler crawler() {
        return crawler;
    }

    /** Per-entity shape and movement parameters. */
    public EntityParams params() {
        return params;
    }

    public float anger() {
        return anger;
    }

    /** Clamped to [0, {@link #MAX_ANGER}]. */
    public void setAnger(float anger) {
        this.anger = Math.max(0, Math.min(MAX_ANGER, anger));
    }

    public Stage stage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /** The surface node the entity is heading for, or null. */
    public BlockPos target() {
        return target;
    }

    /** Who chose the target; null when there is none. */
    public TargetKind targetKind() {
        return target == null ? null : targetKind;
    }

    /** Sets the target and who chose it (a null kind counts as manual); a null target clears both. */
    public void setTarget(BlockPos target, TargetKind kind) {
        this.target = target == null ? null : target.immutable();
        this.targetKind = target == null ? null : kind != null ? kind : TargetKind.MANUAL;
    }

    /** Moves the target (snapped again), keeping who chose it; null clears it. */
    public void setTarget(BlockPos target) {
        setTarget(target, targetKind);
    }

    /** Null until the entity has heard something. */
    public Heard lastHeard() {
        return lastHeard;
    }

    public void setLastHeard(Heard lastHeard) {
        this.lastHeard = lastHeard;
    }

    /**
     * Whether the stage behaviour decides where the entity goes (SPEC 8; on by default). Off ({@code /tremor ai off},
     * a debug switch for scripted tests), it only obeys {@code /tremor goto} and {@code /tremor stop}; anger, stages,
     * contact and despawning still run.
     */
    /** Whether it is in a frenzy over a dropped shard ({@link Frenzy}); not saved. */
    public boolean frenzy() {
        return frenzy;
    }

    public void setFrenzy(boolean frenzy) {
        this.frenzy = frenzy;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    /** The entity is leaving (SPEC 11: "ныряет глубже и исчезает"): its bump sinks, then it is removed. */
    public boolean leaving() {
        return leaving;
    }

    public void setLeaving(boolean leaving) {
        this.leaving = leaving;
    }

    /**
     * Taken by an Awakening (SPEC 9): the entity is the whole area rather than a bump until it is removed, which every
     * Awakening ends with. Saved, so that one loaded in AWAKENING can be told from one that was seeking a player then
     * ({@link TremorManager#onLevelLoad}).
     */
    public boolean absorbed() {
        return absorbed;
    }

    public void setAbsorbed(boolean absorbed) {
        this.absorbed = absorbed;
    }

    /** How long no player was near and nothing happened (SPEC 11); only used for a {@link #natural()} entity. */
    public DespawnClock despawnClock() {
        return despawnClock;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("instance", instance);
        tag.putBoolean("natural", natural);
        tag.put("position", saveVec(crawler.position()));
        tag.put("normal", saveVec(crawler.normal()));
        tag.put("forward", saveVec(crawler.forward()));
        tag.putDouble("amplitude", crawler.amplitude());
        tag.putDouble("phase", crawler.phase());
        tag.putFloat("anger", anger);
        tag.putString("stage", stage.name().toLowerCase(Locale.ROOT));
        if (target != null) {
            tag.putLong("target", target.asLong());
            tag.putString("targetKind", targetKind().name().toLowerCase(Locale.ROOT));
        }
        if (lastHeard != null) {
            CompoundTag heard = new CompoundTag();
            heard.put("position", saveVec(lastHeard.position()));
            heard.putLong("gameTime", lastHeard.gameTime());
            heard.putDouble("perceived", lastHeard.perceived());
            heard.putString("event", lastHeard.event());
            tag.put("lastHeard", heard);
        }
        tag.put("params", params.save());
        tag.putBoolean("ai", aiEnabled);
        tag.putBoolean("leaving", leaving);
        tag.putBoolean("absorbed", absorbed);
        tag.putDouble("despawnFar", despawnClock.farSeconds());
        tag.putDouble("despawnQuiet", despawnClock.quietSeconds());
        return tag;
    }

    /** @throws IllegalArgumentException if the tag has no usable position */
    public static TremorEntity load(CompoundTag tag) {
        Vec3 position = loadVec(tag, "position", null);
        if (position == null) {
            throw new IllegalArgumentException("tremor entity without a position: " + tag);
        }
        Vec3 normal = orUp(loadVec(tag, "normal", Vec3.UNIT_Y));
        Vec3 forward = loadVec(tag, "forward", Vec3.ZERO);
        Crawler crawler = new Crawler(position, normal, forward, finiteOr(tag.getDouble("amplitude"), 0),
                wrapPhase(finiteOr(tag.getDouble("phase"), 0)));
        TremorEntity entity = new TremorEntity(tag.getInt("instance"), tag.getBoolean("natural"), crawler,
                EntityParams.load(tag.getCompound("params")),
                new DespawnClock(tag.getDouble("despawnFar"), tag.getDouble("despawnQuiet")));
        // Saves before stage 3 have no switch: the stage behaviour is on.
        entity.aiEnabled = !tag.contains("ai", Tag.TAG_BYTE) || tag.getBoolean("ai");
        entity.leaving = tag.getBoolean("leaving");
        // Saves before stage 4d have no flag: one in AWAKENING then counts as seeking (TremorManager.onLevelLoad).
        entity.absorbed = tag.getBoolean("absorbed");
        entity.setAnger(Float.isFinite(tag.getFloat("anger")) ? tag.getFloat("anger") : 0);
        entity.stage = parseStage(tag.getString("stage"));
        if (tag.contains("target", Tag.TAG_LONG)) {
            // Stage 1 saves have no kind: their targets came from /tremor goto.
            entity.setTarget(BlockPos.of(tag.getLong("target")), parseKind(tag.getString("targetKind")));
        }
        if (tag.contains("lastHeard", Tag.TAG_COMPOUND)) {
            CompoundTag heard = tag.getCompound("lastHeard");
            Vec3 heardAt = loadVec(heard, "position", null);
            if (heardAt != null) {
                entity.lastHeard = new Heard(heardAt, heard.getLong("gameTime"),
                        finiteOr(heard.getDouble("perceived"), 0), heard.getString("event"));
            }
        }
        return entity;
    }

    /**
     * The animation phase (seconds) reduced to {@code [0, }{@link BumpShape#JITTER_PERIOD_SECONDS}{@code )}: the same
     * jitter, and small enough to keep its precision as a float. The crawler's phase grows for as long as the entity
     * lives (it is saved), so it is reduced where it leaves the server and when it is loaded.
     */
    static double wrapPhase(double phase) {
        double wrapped = phase % BumpShape.JITTER_PERIOD_SECONDS;
        if (wrapped < 0) {
            wrapped += BumpShape.JITTER_PERIOD_SECONDS;
            return wrapped < BumpShape.JITTER_PERIOD_SECONDS ? wrapped : 0; // a tiny negative value rounds up
        }
        return wrapped;
    }

    /** Unknown or missing: {@link TargetKind#MANUAL}. */
    private static TargetKind parseKind(String name) {
        for (TargetKind kind : TargetKind.values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return TargetKind.MANUAL;
    }

    private static Stage parseStage(String name) {
        for (Stage stage : Stage.values()) {
            if (stage.name().equalsIgnoreCase(name)) {
                return stage;
            }
        }
        return Stage.DORMANT;
    }

    private static Vec3 orUp(Vec3 normal) {
        return normal.isNearZero() ? Vec3.UNIT_Y : normal;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static ListTag saveVec(Vec3 v) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(v.x()));
        list.add(DoubleTag.valueOf(v.y()));
        list.add(DoubleTag.valueOf(v.z()));
        return list;
    }

    private static Vec3 loadVec(CompoundTag tag, String key, Vec3 fallback) {
        ListTag list = tag.getList(key, Tag.TAG_DOUBLE);
        if (list.size() != 3) {
            return fallback;
        }
        Vec3 v = new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()) ? v : fallback;
    }
}
