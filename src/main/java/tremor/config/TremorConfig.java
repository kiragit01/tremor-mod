package tremor.config;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;
import tremor.core.behavior.BehaviorParams;
import tremor.core.behavior.Stage;
import tremor.core.hearing.HearingParams;
import tremor.core.shape.BumpParams;
import tremor.core.shape.RippleParams;
import tremor.hearing.LoudnessTable;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** All tunables (SPEC 14.2). COMMON is read by the server logic, CLIENT only by rendering. */
public final class TremorConfig {
    /** Translation key prefix of the config screen entries (assets/tremor/lang). */
    private static final String KEY = "tremor.configuration.";

    private TremorConfig() {
    }

    public static final class Common {
        public final ModConfigSpec.DoubleValue amplitude;
        public final ModConfigSpec.DoubleValue sigmaFront;
        public final ModConfigSpec.DoubleValue sigmaBack;
        public final ModConfigSpec.DoubleValue sigmaSide;
        public final ModConfigSpec.DoubleValue trailLag;
        public final ModConfigSpec.DoubleValue trailSigma;
        public final ModConfigSpec.DoubleValue trailDepth;
        public final ModConfigSpec.DoubleValue jitter;

        public final ModConfigSpec.IntValue maxDiveDepth;
        public final ModConfigSpec.DoubleValue diveCost;
        public final ModConfigSpec.IntValue pathMaxNodes;
        public final ModConfigSpec.IntValue pathNodesPerTick;
        public final ModConfigSpec.DoubleValue speed;
        public final ModConfigSpec.DoubleValue acceleration;
        public final ModConfigSpec.DoubleValue normalSmoothing;
        public final ModConfigSpec.DoubleValue amplitudeSmoothing;
        public final ModConfigSpec.IntValue syncInterval;
        public final ModConfigSpec.IntValue syncRange;
        public final ModConfigSpec.IntValue cacheMaxAge;

        public final ModConfigSpec.DoubleValue hearingThreshold;
        public final ModConfigSpec.DoubleValue hearingMaxDistance;
        public final ModConfigSpec.DoubleValue hearingSampleStep;
        public final ModConfigSpec.DoubleValue minConductivity;
        public final ModConfigSpec.DoubleValue angerPerLoudness;
        public final ModConfigSpec.DoubleValue explosionAngerBonus;
        public final ModConfigSpec.IntValue retargetCooldownTicks;
        public final ModConfigSpec.ConfigValue<List<? extends String>> loudness;
        public final ModConfigSpec.DoubleValue sprintStepLoudness;
        public final ModConfigSpec.DoubleValue mountStepLoudness;
        public final ModConfigSpec.DoubleValue itemLandLoudness;
        public final ModConfigSpec.DoubleValue fallLoudness;
        public final ModConfigSpec.DoubleValue mobLoudnessFactor;
        public final ModConfigSpec.DoubleValue waterFactor;
        public final ModConfigSpec.DoubleValue rustlingFactor;
        public final ModConfigSpec.DoubleValue conductivityInsulating;
        public final ModConfigSpec.DoubleValue conductivityWooden;
        public final ModConfigSpec.DoubleValue conductivityGravelly;
        public final ModConfigSpec.DoubleValue conductivitySandy;
        public final ModConfigSpec.DoubleValue conductivityEarth;
        public final ModConfigSpec.DoubleValue conductivityStony;
        public final ModConfigSpec.DoubleValue conductivityFluid;
        public final ModConfigSpec.DoubleValue conductivityAir;

        public final ModConfigSpec.DoubleValue alertAt;
        public final ModConfigSpec.DoubleValue huntAt;
        public final ModConfigSpec.DoubleValue awakenAt;
        public final ModConfigSpec.DoubleValue hysteresis;
        public final ModConfigSpec.DoubleValue decayPerSecond;
        public final ModConfigSpec.DoubleValue quietAfterSeconds;
        public final ModConfigSpec.DoubleValue quietDecayFactor;
        public final ModConfigSpec.DoubleValue dormantReactLoudness;
        public final ModConfigSpec.DoubleValue alertFreezeSeconds;
        public final ModConfigSpec.DoubleValue alertLoseInterestSeconds;
        public final ModConfigSpec.DoubleValue huntSearchRadius;
        public final ModConfigSpec.DoubleValue huntSearchSeconds;
        public final ModConfigSpec.DoubleValue wanderPauseSeconds;
        public final ModConfigSpec.DoubleValue wanderMinRadius;
        public final ModConfigSpec.DoubleValue wanderMaxRadius;
        public final ModConfigSpec.DoubleValue minWanderDistance;
        public final ModConfigSpec.DoubleValue transitionVolume;
        private final EnumMap<Stage, ModConfigSpec.DoubleValue> stageSpeedFactors = new EnumMap<>(Stage.class);
        private final EnumMap<Stage, ModConfigSpec.DoubleValue> stageAmplitudeFactors = new EnumMap<>(Stage.class);
        public final ModConfigSpec.DoubleValue contactRadius;
        public final ModConfigSpec.IntValue contactCooldownTicks;
        public final ModConfigSpec.DoubleValue contactDamage;
        public final ModConfigSpec.DoubleValue contactKnockback;
        public final ModConfigSpec.DoubleValue contactLift;
        public final ModConfigSpec.DoubleValue contactAnger;
        public final ModConfigSpec.DoubleValue despawnPlayerDistance;
        public final ModConfigSpec.DoubleValue despawnFarSeconds;
        public final ModConfigSpec.DoubleValue despawnQuietSeconds;

        public final Spawn spawn;
        public final Hollow hollow;
        public final Awakening awakening;

        Common(ModConfigSpec.Builder b) {
            BumpParams d = BumpParams.defaults();
            b.comment("Shape of the bump the entity pushes through the ground (SPEC 6.1). Lengths are in blocks.")
                    .translation(KEY + "shape").push("shape");
            amplitude = b.comment("A: height of the bump")
                    .translation(KEY + "shape.amplitude").defineInRange("amplitude", d.amplitude(), 0.1, 8.0);
            sigmaFront = b.comment("Half-length ahead of the bump (smaller = steeper front)")
                    .translation(KEY + "shape.sigmaFront").defineInRange("sigmaFront", d.sigmaFront(), 0.25, 16.0);
            sigmaBack = b.comment("Half-length behind the bump")
                    .translation(KEY + "shape.sigmaBack").defineInRange("sigmaBack", d.sigmaBack(), 0.25, 16.0);
            sigmaSide = b.comment("Half-width of the bump")
                    .translation(KEY + "shape.sigmaSide").defineInRange("sigmaSide", d.sigmaSide(), 0.25, 16.0);
            trailLag = b.comment("L: how far behind the bump the trailing depression sits")
                    .translation(KEY + "shape.trailLag").defineInRange("trailLag", d.trailLag(), 0.0, 32.0);
            trailSigma = b.comment("Length of the trailing depression")
                    .translation(KEY + "shape.trailSigma").defineInRange("trailSigma", d.trailSigma(), 0.25, 16.0);
            trailDepth = b.comment("k: depth of the trailing depression relative to A")
                    .translation(KEY + "shape.trailDepth").defineInRange("trailDepth", d.trailDepth(), 0.0, 1.0);
            jitter = b.comment("epsilon: amplitude of the fine tremble relative to A")
                    .translation(KEY + "shape.jitter").defineInRange("jitter", d.jitter(), 0.0, 0.5);
            b.pop();

            b.comment("Movement over the surface of the world (SPEC 5)").translation(KEY + "movement").push("movement");
            maxDiveDepth = b.comment("How far (blocks) the entity can travel straight through rock to another surface")
                    .translation(KEY + "movement.maxDiveDepth").defineInRange("maxDiveDepth", 4, 0, 16);
            diveCost = b.comment("Path cost of a block travelled through rock, relative to a block on the surface")
                    .translation(KEY + "movement.diveCost").defineInRange("diveCost", 2.0, 1.0, 20.0);
            pathMaxNodes = b.comment("A* gives up after expanding this many nodes and heads for the best one found")
                    .translation(KEY + "movement.pathMaxNodes").defineInRange("pathMaxNodes", 4000, 100, 100000);
            pathNodesPerTick = b.comment("A* nodes expanded per server tick (the search is spread over ticks)")
                    .translation(KEY + "movement.pathNodesPerTick").defineInRange("pathNodesPerTick", 500, 10, 100000);
            speed = b.comment("Crawling speed, blocks per second")
                    .translation(KEY + "movement.speed").defineInRange("speed", 3.0, 0.1, 30.0);
            acceleration = b.comment("Speed change limit, blocks per second squared")
                    .translation(KEY + "movement.acceleration").defineInRange("acceleration", 3.0, 0.1, 100.0);
            normalSmoothing = b.comment("Time constant (s) of the normal smoothing; a floor-to-wall turn takes ~3x this")
                    .translation(KEY + "movement.normalSmoothing").defineInRange("normalSmoothing", 0.25, 0.0, 5.0);
            amplitudeSmoothing = b.comment("Time constant (s) of the bump rising and sinking (surfacing / diving)")
                    .translation(KEY + "movement.amplitudeSmoothing")
                    .defineInRange("amplitudeSmoothing", 0.35, 0.0, 5.0);
            b.pop();

            b.comment("Server internals").translation(KEY + "server").push("server");
            syncInterval = b.comment("Server ticks between state packets to nearby players (2 = 10 per second)")
                    .translation(KEY + "server.syncInterval").defineInRange("syncInterval", 2, 1, 20);
            syncRange = b.comment("Players within this many blocks of the entity receive its state")
                    .translation(KEY + "server.syncRange").defineInRange("syncRange", 160, 16, 1024);
            cacheMaxAge = b.comment("Ticks after which cached terrain is re-read even without a block change event")
                    .translation(KEY + "server.cacheMaxAge").defineInRange("cacheMaxAge", 600, 20, 72000);
            b.pop();

            HearingParams h = HearingParams.defaults();
            b.comment("Hearing: vibrations through the ground (SPEC 7).",
                            "perceived = loudness * footing / (1 + distance * average resistance along the way)")
                    .translation(KEY + "hearing").push("hearing");
            hearingThreshold = b.comment("Perceived loudness from which the entity hears a vibration")
                    .translation(KEY + "hearing.threshold").defineInRange("threshold", h.threshold(), 0.001, 100.0);
            hearingMaxDistance = b.comment("Vibrations further away than this (blocks) are never heard")
                    .translation(KEY + "hearing.maxDistance").defineInRange("maxDistance", h.maxDistance(), 4.0, 256.0);
            hearingSampleStep = b.comment("Spacing (blocks) of the conductivity samples between source and entity;",
                            "raised automatically so that one vibration reads at most " + MAX_HEARING_SAMPLES + " blocks")
                    .translation(KEY + "hearing.sampleStep").defineInRange("sampleStep", h.sampleStep(), 0.1, 8.0);
            minConductivity = b.comment("Conductivities are clamped to at least this before taking the resistance 1/c")
                    .translation(KEY + "hearing.minConductivity")
                    .defineInRange("minConductivity", h.minConductivity(), 0.001, 1.0);
            angerPerLoudness = b.comment("Anger added per unit of perceived loudness of a heard vibration")
                    .translation(KEY + "hearing.angerPerLoudness").defineInRange("angerPerLoudness", 6.0, 0.0, 100.0);
            explosionAngerBonus = b.comment("Anger added on top when an explosion is heard")
                    .translation(KEY + "hearing.explosionAngerBonus")
                    .defineInRange("explosionAngerBonus", 20.0, 0.0, 100.0);
            retargetCooldownTicks = b.comment("A heard sound replaces the one being followed at most this often (ticks),",
                            "unless it is at least 1.5 times louder")
                    .translation(KEY + "hearing.retargetCooldownTicks")
                    .defineInRange("retargetCooldownTicks", 10, 0, 1200);
            loudness = b.comment("Base loudness of vanilla game events, \"namespace:event=loudness\"; events not listed",
                            "(or 0) are ignored. Steps of sneaking players are never heard.")
                    .translation(KEY + "hearing.loudness")
                    .defineListAllowEmpty("loudness", LoudnessTable.DEFAULTS, () -> "minecraft:step=0",
                            LoudnessTable::isValid);
            sprintStepLoudness = b.comment("Loudness of a sprinting player's step (replaces the step loudness)")
                    .translation(KEY + "hearing.sprintStepLoudness")
                    .defineInRange("sprintStepLoudness", 4.0, 0.0, 1000.0);
            mountStepLoudness = b.comment("Loudness of a step of a mount ridden by a player, and of a moving minecart")
                    .translation(KEY + "hearing.mountStepLoudness")
                    .defineInRange("mountStepLoudness", 6.0, 0.0, 1000.0);
            itemLandLoudness = b.comment("Loudness of a dropped item hitting the ground (up to 2 more for fast impacts)")
                    .translation(KEY + "hearing.itemLandLoudness").defineInRange("itemLandLoudness", 3.0, 0.0, 1000.0);
            fallLoudness = b.comment("Loudness of a fall with fall damage, plus the fall height in blocks")
                    .translation(KEY + "hearing.fallLoudness").defineInRange("fallLoudness", 10.0, 0.0, 1000.0);
            mobLoudnessFactor = b.comment("Loudness factor for living non-player sources (0: the entity listens for",
                            "players, not cows); explosions always count fully")
                    .translation(KEY + "hearing.mobLoudnessFactor").defineInRange("mobLoudnessFactor", 0.0, 0.0, 10.0);
            waterFactor = b.comment("Footing of a source that is not on the ground but in water or in a boat",
                            "(a source in the air makes no vibration)")
                    .translation(KEY + "hearing.waterFactor").defineInRange("waterFactor", 0.2, 0.0, 10.0);
            rustlingFactor = b.comment("Footing of a source on a rustling block (block tag #tremor:rustling: leaves),",
                            "which rustles under the feet: louder than stone. The leaves it rustles in carry the",
                            "rustle at this factor too; leaves farther along the way conduct as their class below",
                            "(leaves: insulating)")
                    .translation(KEY + "hearing.rustlingFactor").defineInRange("rustlingFactor", 1.5, 0.0, 10.0);

            b.comment("Vibration conductivity of blocks by class (block tags #tremor:conductivity/<class>,",
                            "first match in this order); 1 = baseline")
                    .translation(KEY + "hearing.conductivity").push("conductivity");
            conductivityInsulating = conductivity(b, "insulating", "Wool, carpets", 0.1);
            conductivityWooden = conductivity(b, "wooden", "Planks, logs and other wooden blocks", 0.4);
            conductivityGravelly = conductivity(b, "gravelly", "Gravel", 0.4);
            conductivitySandy = conductivity(b, "sandy", "Sand, soul sand and soil, snow", 0.7);
            conductivityStony = conductivity(b, "stony", "Stone, deepslate, ores, bedrock, obsidian", 1.2);
            conductivityEarth = conductivity(b, "earth", "Any other block with a collision shape (dirt, grass...)", 1.0);
            conductivityFluid = conductivity(b, "fluid", "Water and other fluids", 0.3);
            conductivityAir = conductivity(b, "air", "Air and other open blocks; also unloaded terrain", 0.05);
            b.pop(2);

            BehaviorParams bp = BehaviorParams.defaults();
            b.comment("Anger and stage behaviour (SPEC 8). The anger runs from 0 to awakenAt. Times are in seconds,",
                            "distances in blocks, loudness in perceived units (see hearing). Needs",
                            "0 < alertAt < huntAt < awakenAt and hysteresis < alertAt, else the defaults are used.")
                    .translation(KEY + "behavior").push("behavior");
            alertAt = b.comment("Anger from which the entity is ALERT")
                    .translation(KEY + "behavior.alertAt").defineInRange("alertAt", bp.alertAt(), 1.0, 100.0);
            huntAt = b.comment("Anger from which the entity is HUNTING")
                    .translation(KEY + "behavior.huntAt").defineInRange("huntAt", bp.huntAt(), 1.0, 100.0);
            awakenAt = b.comment("Anger of the AWAKENING (the entity seeks a player; the Awakening starts once it has",
                            "reached one, see the awakening section); also the top of the scale")
                    .translation(KEY + "behavior.awakenAt").defineInRange("awakenAt", bp.awakenAt(), 1.0, 100.0);
            hysteresis = b.comment("A stage is left downwards only once the anger is this far below its threshold")
                    .translation(KEY + "behavior.hysteresis").defineInRange("hysteresis", bp.hysteresis(), 0.0, 50.0);
            decayPerSecond = b.comment("Anger lost per second")
                    .translation(KEY + "behavior.decayPerSecond")
                    .defineInRange("decayPerSecond", bp.decayPerSecond(), 0.0, 20.0);
            quietAfterSeconds = b.comment("After this long without a heard sound...")
                    .translation(KEY + "behavior.quietAfterSeconds")
                    .defineInRange("quietAfterSeconds", bp.quietAfterSeconds(), 0.0, 3600.0);
            quietDecayFactor = b.comment("...the anger decays this many times faster")
                    .translation(KEY + "behavior.quietDecayFactor")
                    .defineInRange("quietDecayFactor", bp.quietDecayFactor(), 1.0, 50.0);
            dormantReactLoudness = b.comment("A DORMANT entity goes after a sound only if it is at least this loud",
                            "(quieter heard sounds still add anger)")
                    .translation(KEY + "behavior.dormantReactLoudness")
                    .defineInRange("dormantReactLoudness", bp.dormantReactLoudness(), 0.0, 100.0);
            alertFreezeSeconds = b.comment("An ALERT entity freezes and turns toward each heard sound for this long,",
                            "then creeps toward it")
                    .translation(KEY + "behavior.alertFreezeSeconds")
                    .defineInRange("alertFreezeSeconds", bp.alertFreezeSeconds(), 0.0, 60.0);
            alertLoseInterestSeconds = b.comment("An ALERT entity that hears nothing for this long wanders again")
                    .translation(KEY + "behavior.alertLoseInterestSeconds")
                    .defineInRange("alertLoseInterestSeconds", bp.alertLoseInterestSeconds(), 0.0, 600.0);
            huntSearchRadius = b.comment("A HUNTING entity that finds nobody at the sound searches this far around it...")
                    .translation(KEY + "behavior.huntSearchRadius")
                    .defineInRange("huntSearchRadius", bp.huntSearchRadius(), 0.0, 64.0);
            huntSearchSeconds = b.comment("...until this long after the sound, then wanders")
                    .translation(KEY + "behavior.huntSearchSeconds")
                    .defineInRange("huntSearchSeconds", bp.huntSearchSeconds(), 0.0, 600.0);
            wanderPauseSeconds = b.comment("Mean pause between two legs of wandering (SPEC 5.6)")
                    .translation(KEY + "behavior.wanderPauseSeconds")
                    .defineInRange("wanderPauseSeconds", bp.wanderPauseSeconds(), 0.0, 120.0);
            wanderMinRadius = b.comment("A wander leg ends at least this far away...")
                    .translation(KEY + "behavior.wanderMinRadius").defineInRange("wanderMinRadius", 16.0, 1.0, 64.0);
            wanderMaxRadius = b.comment("...and at most this far (a smaller value than wanderMinRadius counts as it)")
                    .translation(KEY + "behavior.wanderMaxRadius").defineInRange("wanderMaxRadius", 32.0, 1.0, 64.0);
            minWanderDistance = b.comment("A DORMANT entity wanders no closer than this to any player; nor does one",
                            "seeking at the top of its anger (AWAKENING) with no sound to go for, to any player it",
                            "could take (alive, in survival mode), and its way there passes none of them within",
                            "awakening.reachDistance")
                    .translation(KEY + "behavior.minWanderDistance")
                    .defineInRange("minWanderDistance", bp.minWanderDistance(), 0.0, 128.0);
            transitionVolume = b.comment("Volume of the stage change sounds; heard up to 16 blocks times this away")
                    .translation(KEY + "behavior.transitionVolume")
                    .defineInRange("transitionVolume", 3.0, 0.0, 16.0);

            b.comment("Movement per stage (SPEC 5.5, 8): factors on movement.speed and on the bump height")
                    .translation(KEY + "behavior.stages").push("stages");
            stage(b, Stage.DORMANT, "Lazy wandering: slow, a low bump", 0.6, 0.6);
            stage(b, Stage.ALERT, "Freezing and creeping toward sounds", 0.5, 0.85);
            stage(b, Stage.HUNTING, "Going for sounds: fast, a higher bump", 1.6, 1.25);
            stage(b, Stage.AWAKENING, "Seeking a player at the top of the anger (see the awakening section): faster and"
                    + " higher than hunting", 2.1, 1.4);
            b.pop();

            b.comment("Contact of the bump with a player while HUNTING or AWAKENING (SPEC 8), with the bump at least",
                            "half up; not in creative or spectator mode, and never through rock")
                    .translation(KEY + "behavior.contact").push("contact");
            contactRadius = b.comment("Radius of the zone around the axis of the bump, from the middle of its block",
                            "to 1.5 blocks above its top: players with feet, middle or head in it are struck")
                    .translation(KEY + "behavior.contact.radius").defineInRange("radius", 1.6, 0.1, 8.0);
            contactCooldownTicks = b.comment("Each player is struck at most this often (ticks)")
                    .translation(KEY + "behavior.contact.cooldownTicks").defineInRange("cooldownTicks", 20, 1, 1200);
            contactDamage = b.comment("Damage of a strike (2 = one heart); a shield does not block it")
                    .translation(KEY + "behavior.contact.damage").defineInRange("damage", 4.0, 0.0, 100.0);
            contactKnockback = b.comment("Horizontal speed a strike throws the player away with, blocks per tick, as",
                            "the player's client gets it; times 1 - the player's knockback resistance")
                    .translation(KEY + "behavior.contact.knockback").defineInRange("knockback", 0.9, 0.0, 3.9);
            contactLift = b.comment("Upward speed of that throw, blocks per tick; also times 1 - the knockback",
                            "resistance")
                    .translation(KEY + "behavior.contact.lift").defineInRange("lift", 0.45, 0.0, 3.0);
            contactAnger = b.comment("Anger added by a strike")
                    .translation(KEY + "behavior.contact.anger").defineInRange("anger", 15.0, 0.0, 100.0);
            b.pop();

            b.comment("A naturally spawned entity leaves (sinks and disappears, SPEC 11) when no player was near for",
                            "farSeconds, or nothing happened (no sound heard while DORMANT) for quietSeconds; while",
                            "its chunk is not loaded the times run on, and it disappears at once")
                    .translation(KEY + "behavior.despawn").push("despawn");
            despawnPlayerDistance = b.comment("A player within this distance counts as near")
                    .translation(KEY + "behavior.despawn.playerDistance")
                    .defineInRange("playerDistance", 96.0, 8.0, 1024.0);
            despawnFarSeconds = b.comment("Seconds without a player near")
                    .translation(KEY + "behavior.despawn.farSeconds").defineInRange("farSeconds", 120.0, 1.0, 86400.0);
            despawnQuietSeconds = b.comment("Seconds without anything happening")
                    .translation(KEY + "behavior.despawn.quietSeconds")
                    .defineInRange("quietSeconds", 600.0, 1.0, 86400.0);
            b.pop(2);

            spawn = new Spawn(b);
            hollow = new Hollow(b);
            awakening = new Awakening(b);
        }

        private static ModConfigSpec.DoubleValue conductivity(ModConfigSpec.Builder b, String name, String comment,
                                                              double value) {
            return b.comment(comment).translation(KEY + "hearing.conductivity." + name)
                    .defineInRange(name, value, 0.0, 10.0);
        }

        /** The subsection {@code behavior.stages.<stage>}: its speed and amplitude factors. */
        private void stage(ModConfigSpec.Builder b, Stage stage, String comment, double speed, double amplitude) {
            String name = stage.name().toLowerCase(Locale.ROOT);
            b.comment(comment).translation(KEY + "behavior.stages." + name).push(name);
            stageSpeedFactors.put(stage, b.comment("Factor on movement.speed")
                    .translation(KEY + "behavior.stages.speedFactor").defineInRange("speedFactor", speed, 0.05, 10.0));
            stageAmplitudeFactors.put(stage, b.comment("Factor on the bump height (shape.amplitude)")
                    .translation(KEY + "behavior.stages.amplitudeFactor")
                    .defineInRange("amplitudeFactor", amplitude, 0.0, 4.0));
            b.pop();
        }

        /**
         * Stage behaviour (SPEC 8) from the {@code behavior} section, and the search radius of the seeking from the
         * {@code awakening} one.
         *
         * @throws IllegalArgumentException if the thresholds contradict each other ({@link BehaviorParams})
         */
        public BehaviorParams behaviorParams() {
            return new BehaviorParams(alertAt.get(), huntAt.get(), awakenAt.get(), hysteresis.get(),
                    decayPerSecond.get(), quietAfterSeconds.get(), quietDecayFactor.get(), dormantReactLoudness.get(),
                    alertFreezeSeconds.get(), alertLoseInterestSeconds.get(), huntSearchRadius.get(),
                    huntSearchSeconds.get(), wanderPauseSeconds.get(), minWanderDistance.get(),
                    awakening.searchRadius.get());
        }

        /** Factor on the crawling speed in {@code stage} (SPEC 5.5, 8). */
        public double speedFactor(Stage stage) {
            return stageSpeedFactors.get(stage).get();
        }

        /** Factor on the bump height in {@code stage} (SPEC 8). */
        public double amplitudeFactor(Stage stage) {
            return stageAmplitudeFactors.get(stage).get();
        }

        public BumpParams bumpParams() {
            return new BumpParams(amplitude.get(), sigmaFront.get(), sigmaBack.get(), sigmaSide.get(),
                    trailLag.get(), trailSigma.get(), trailDepth.get(), jitter.get());
        }

        /**
         * How vibrations travel (SPEC 7.2). The sample step is raised where needed so that one vibration reads at
         * most {@value TremorConfig#MAX_HEARING_SAMPLES} voxels.
         */
        public HearingParams hearingParams() {
            double maxDistance = hearingMaxDistance.get();
            return new HearingParams(hearingThreshold.get(), maxDistance,
                    Math.max(hearingSampleStep.get(), maxDistance / MAX_HEARING_SAMPLES), minConductivity.get());
        }
    }

    /** Natural spawn (SPEC 11), section {@code spawn} of COMMON; read by {@link tremor.spawn.NaturalSpawner}. */
    public static final class Spawn {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.ConfigValue<List<? extends String>> dimensions;
        public final ModConfigSpec.IntValue checkIntervalSeconds;
        public final ModConfigSpec.DoubleValue baseChance;
        public final ModConfigSpec.DoubleValue caveMultiplier;
        public final ModConfigSpec.DoubleValue darkMultiplier;
        public final ModConfigSpec.DoubleValue deepMultiplier;
        public final ModConfigSpec.DoubleValue nightMultiplier;
        public final ModConfigSpec.DoubleValue minDistance;
        public final ModConfigSpec.DoubleValue maxDistance;
        public final ModConfigSpec.DoubleValue routeHalfWidth;
        public final ModConfigSpec.IntValue verticalRange;
        public final ModConfigSpec.IntValue attempts;
        public final ModConfigSpec.IntValue cooldownSeconds;
        public final ModConfigSpec.IntValue graceSeconds;
        public final ModConfigSpec.IntValue protectionRadius;
        public final ModConfigSpec.BooleanValue allowPeaceful;

        Spawn(ModConfigSpec.Builder b) {
            b.comment("Natural spawn: the entity appears by itself in the distance (SPEC 11)")
                    .translation(KEY + "spawn").push("spawn");
            enabled = b.comment("Whether the entity appears by itself")
                    .translation(KEY + "spawn.enabled").define("enabled", true);
            dimensions = b.comment("Dimensions it appears in, ids like minecraft:overworld")
                    .translation(KEY + "spawn.dimensions")
                    .defineListAllowEmpty("dimensions", List.of("minecraft:overworld"), () -> "minecraft:overworld",
                            Spawn::isDimensionId);
            checkIntervalSeconds = b.comment("Every player in such a dimension gets a spawn check this often",
                            "(seconds); the checks of different players are spread over this time")
                    .translation(KEY + "spawn.checkIntervalSeconds")
                    .defineInRange("checkIntervalSeconds", 30, 1, 3600);
            baseChance = b.comment("Chance that a check spawns the entity, before the multipliers below")
                    .translation(KEY + "spawn.baseChance").defineInRange("baseChance", 0.04, 0.0, 1.0);
            caveMultiplier = b.comment("Chance multiplier when the player cannot see the sky (in a cave or under a",
                            "roof; leaves and water are no roof)")
                    .translation(KEY + "spawn.caveMultiplier").defineInRange("caveMultiplier", 2.5, 0.0, 100.0);
            darkMultiplier = b.comment("Chance multiplier when the light at the player's eyes is below 7 (block light,",
                            "or sky light dimmed by night and weather)")
                    .translation(KEY + "spawn.darkMultiplier").defineInRange("darkMultiplier", 2.0, 0.0, 100.0);
            deepMultiplier = b.comment("Chance multiplier when the player is below y = 0")
                    .translation(KEY + "spawn.deepMultiplier").defineInRange("deepMultiplier", 1.5, 0.0, 100.0);
            nightMultiplier = b.comment("Chance multiplier at night (a thunderstorm counts as night, as for beds)")
                    .translation(KEY + "spawn.nightMultiplier").defineInRange("nightMultiplier", 2.0, 0.0, 100.0);
            minDistance = b.comment("Nearest spawn point to the player (blocks)")
                    .translation(KEY + "spawn.minDistance").defineInRange("minDistance", 40.0, 0.0, 256.0);
            maxDistance = b.comment("Farthest spawn point from the player (blocks; at least minDistance + 1 is used).",
                            "Only loaded terrain is searched; a point the player sees but will not pass must also",
                            "lie within the player's view distance")
                    .translation(KEY + "spawn.maxDistance").defineInRange("maxDistance", 80.0, 1.0, 256.0);
            routeHalfWidth = b.comment("A spawn point the player does not see must lie within this distance (blocks,",
                            "sideways) of the player's way ahead, which may climb or fall up to 45 degrees")
                    .translation(KEY + "spawn.routeHalfWidth").defineInRange("routeHalfWidth", 10.0, 0.0, 64.0);
            verticalRange = b.comment("Spawn points are looked for this many blocks above and below the player's feet")
                    .translation(KEY + "spawn.verticalRange").defineInRange("verticalRange", 24, 0, 128);
            attempts = b.comment("Candidate points tried per spawn (each costs a column scan and a line of sight)")
                    .translation(KEY + "spawn.attempts").defineInRange("attempts", 48, 1, 1024);
            cooldownSeconds = b.comment("No natural spawn in a dimension for this long after a naturally spawned",
                            "entity left it (seconds)")
                    .translation(KEY + "spawn.cooldownSeconds").defineInRange("cooldownSeconds", 600, 0, 86400);
            graceSeconds = b.comment("A player's first spawn check comes this long after the player enters the",
                            "dimension: joining, the server starting, changing dimension (seconds)")
                    .translation(KEY + "spawn.graceSeconds").defineInRange("graceSeconds", 60, 0, 3600);
            protectionRadius = b.comment("No spawn this close (blocks, horizontally) to the world spawn point and to",
                            "the beds and respawn anchors of the players online; 0 = off")
                    .translation(KEY + "spawn.protectionRadius").defineInRange("protectionRadius", 32, 0, 1024);
            allowPeaceful = b.comment("Whether the entity appears by itself in peaceful difficulty too")
                    .translation(KEY + "spawn.allowPeaceful").define("allowPeaceful", false);
            b.pop();
        }

        private static boolean isDimensionId(Object entry) {
            return entry instanceof String s && ResourceLocation.tryParse(s) != null;
        }
    }

    /** The hollow (SPEC 9, 12), section {@code hollow} of COMMON; read by {@link tremor.hollow.HollowManager}. */
    public static final class Hollow {
        public final ModConfigSpec.IntValue radius;
        public final ModConfigSpec.IntValue below;
        public final ModConfigSpec.IntValue above;
        public final ModConfigSpec.DoubleValue budgetMillis;
        public final ModConfigSpec.IntValue fadeTicks;
        public final ModConfigSpec.IntValue settleTicks;
        public final ModConfigSpec.IntValue maxEvents;
        public final HollowLevel level;

        Hollow(ModConfigSpec.Builder b) {
            b.comment("The hollow: a copy of the terrain around a swallowed player in the dimension tremor:hollow,",
                            "where the Awakening is played out (SPEC 9)")
                    .translation(KEY + "hollow").push("hollow");
            radius = b.comment("Blocks copied around the player horizontally (the copy is 2 * radius + 1 wide)")
                    .translation(KEY + "hollow.radius").defineInRange("radius", 32, 8, 96);
            below = b.comment("Blocks copied below the player's feet (cut at the bottom of the world)")
                    .translation(KEY + "hollow.below").defineInRange("below", 24, 4, 128);
            above = b.comment("Blocks copied above the player's feet (cut at the top of the world)")
                    .translation(KEY + "hollow.above").defineInRange("above", 24, 4, 128);
            budgetMillis = b.comment("Server time per tick for copying the terrain, preparing the level inside it and",
                            "clearing it again (milliseconds); the work is spread over as many ticks as it needs")
                    .translation(KEY + "hollow.budgetMillis").defineInRange("budgetMillis", 5.0, 0.5, 50.0);
            fadeTicks = b.comment("Length of the fade to black before a move into or out of the hollow, and of the",
                            "fade back (ticks)")
                    .translation(KEY + "hollow.fadeTicks").defineInRange("fadeTicks", 20, 0, 200);
            settleTicks = b.comment("After a move the screen stays black until the client has had the terrain around",
                            "the player for this many ticks")
                    .translation(KEY + "hollow.settleTicks").defineInRange("settleTicks", 10, 0, 200);
            maxEvents = b.comment("Events in the hollow at the same time (one player each)")
                    .translation(KEY + "hollow.maxEvents").defineInRange("maxEvents", 8, 1, 64);
            level = new HollowLevel(b);
            b.pop();
        }
    }

    /**
     * The level inside the hollow (SPEC 9, phase 2: the widened cave, the node, the closing, the moving walls, the soft
     * ground), section {@code hollow.level} of COMMON; read by {@link tremor.hollow.level.HollowLevels}.
     */
    public static final class HollowLevel {
        public final ModConfigSpec.DoubleValue budgetMillis;
        public final ModConfigSpec.IntValue widenBelow;
        public final ModConfigSpec.IntValue nodeMinDistance;
        public final ModConfigSpec.IntValue nodeMaxDistance;
        public final ModConfigSpec.DoubleValue nodeMinStraight;
        public final ModConfigSpec.IntValue minDeadEnds;
        public final ModConfigSpec.IntValue maxDeadEnds;
        public final ModConfigSpec.IntValue graceSeconds;
        public final ModConfigSpec.DoubleValue closeSpeed;
        public final ModConfigSpec.DoubleValue noiseFactor;
        public final ModConfigSpec.DoubleValue noiseSeconds;
        public final ModConfigSpec.DoubleValue minRadius;
        public final ModConfigSpec.DoubleValue lurePauseSeconds;
        public final ModConfigSpec.DoubleValue lureCooldownSeconds;
        public final ModConfigSpec.DoubleValue lureMinDistance;
        public final ModConfigSpec.IntValue fillsPerTick;
        public final ModConfigSpec.DoubleValue wallShiftSeconds;
        public final ModConfigSpec.IntValue wallShifts;
        public final ModConfigSpec.DoubleValue stillSeconds;
        public final ModConfigSpec.DoubleValue sinkSeconds;
        public final ModConfigSpec.DoubleValue recoverSeconds;
        public final ModConfigSpec.IntValue beatSlowTicks;
        public final ModConfigSpec.IntValue beatFastTicks;

        HollowLevel(ModConfigSpec.Builder b) {
            b.comment("The level inside the hollow (SPEC 9): the node to destroy, the edges closing in, walls that",
                            "move, ground that pulls in a player who stands still")
                    .translation(KEY + "hollow.level").push("level");
            budgetMillis = b.comment("Server time per tick for the level of one player while the player is inside",
                            "(milliseconds; it is prepared within hollow.budgetMillis); the closing gets what the rest",
                            "leaves of it")
                    .translation(KEY + "hollow.level.budgetMillis").defineInRange("budgetMillis", 2.0, 0.2, 20.0);
            widenBelow = b.comment("A place with fewer open blocks than this connected to the player within 8 blocks",
                            "(a tunnel up to some 4 blocks across, a small room) is widened into a cave some 16 blocks",
                            "across before the player arrives (0 = never)")
                    .translation(KEY + "hollow.level.widenBelow").defineInRange("widenBelow", 400, 0, 2000);
            nodeMinDistance = b.comment("The node is put at least this many steps from the player along the way",
                            "there, a tunnel dug through the copy to a chamber under the ground (where the copy leaves",
                            "no room for that, the best way found is taken: /tremor hollow status and the log say SHORT)")
                    .translation(KEY + "hollow.level.nodeMinDistance").defineInRange("nodeMinDistance", 40, 2, 120);
            nodeMaxDistance = b.comment("...and at most this many")
                    .translation(KEY + "hollow.level.nodeMaxDistance").defineInRange("nodeMaxDistance", 60, 2, 120);
            nodeMinStraight = b.comment("...and at least this far from where the player arrived in a straight line",
                            "(blocks)")
                    .translation(KEY + "hollow.level.nodeMinStraight")
                    .defineInRange("nodeMinStraight", 12.0, 0.0, 64.0);
            minDeadEnds = b.comment("Dead ends dug besides the way to the node, at least: out of the start (or decoy",
                            "throats on open ground) and forking off the way")
                    .translation(KEY + "hollow.level.minDeadEnds").defineInRange("minDeadEnds", 3, 0, 12);
            maxDeadEnds = b.comment("...and at most")
                    .translation(KEY + "hollow.level.maxDeadEnds").defineInRange("maxDeadEnds", 5, 0, 12);
            graceSeconds = b.comment("The edges start closing in this long after the player arrives (seconds)")
                    .translation(KEY + "hollow.level.graceSeconds").defineInRange("graceSeconds", 10, 0, 600);
            closeSpeed = b.comment("How fast the edges close in while the player is silent (blocks per second)")
                    .translation(KEY + "hollow.level.closeSpeed").defineInRange("closeSpeed", 0.08, 0.0, 10.0);
            noiseFactor = b.comment("Extra closing speed per unit of noise: blocks per second for each loudness per",
                            "second the ground gets from the player (walking on stone makes about 6, sprinting",
                            "about 16, sneaking nothing)")
                    .translation(KEY + "hollow.level.noiseFactor").defineInRange("noiseFactor", 0.02, 0.0, 10.0);
            noiseSeconds = b.comment("The noise of the player fades away over this time (seconds)")
                    .translation(KEY + "hollow.level.noiseSeconds").defineInRange("noiseSeconds", 3.0, 0.1, 60.0);
            minRadius = b.comment("The edges stop closing at this distance from where the player arrived (blocks);",
                            "the way to the node always stays open")
                    .translation(KEY + "hollow.level.minRadius").defineInRange("minRadius", 6.0, 2.0, 96.0);
            lurePauseSeconds = b.comment("A lure (a thrown item or projectile landing in the hollow away from the",
                            "player) stops the closing for this long (seconds)")
                    .translation(KEY + "hollow.level.lurePauseSeconds")
                    .defineInRange("lurePauseSeconds", 4.0, 0.0, 60.0);
            lureCooldownSeconds = b.comment("A lure within this long after the last one does nothing (seconds)")
                    .translation(KEY + "hollow.level.lureCooldownSeconds")
                    .defineInRange("lureCooldownSeconds", 8.0, 0.0, 600.0);
            lureMinDistance = b.comment("A landing closer to the player than this is no lure (blocks)")
                    .translation(KEY + "hollow.level.lureMinDistance")
                    .defineInRange("lureMinDistance", 4.0, 0.0, 64.0);
            fillsPerTick = b.comment("Blocks the closing fills per tick at most")
                    .translation(KEY + "hollow.level.fillsPerTick").defineInRange("fillsPerTick", 96, 1, 4096);
            wallShiftSeconds = b.comment("Walls 5 to 14 blocks from the player bulge and recede this often (seconds)")
                    .translation(KEY + "hollow.level.wallShiftSeconds")
                    .defineInRange("wallShiftSeconds", 2.0, 0.1, 60.0);
            wallShifts = b.comment("...this many blocks each time (0 = the walls stand still)")
                    .translation(KEY + "hollow.level.wallShifts").defineInRange("wallShifts", 3, 0, 64);
            stillSeconds = b.comment("A player who stays within a quarter of a block for this long is standing still,",
                            "and the ground under the player starts to soften (seconds)")
                    .translation(KEY + "hollow.level.stillSeconds").defineInRange("stillSeconds", 3.0, 0.5, 60.0);
            sinkSeconds = b.comment("How long the softening takes to pull a player who keeps standing on it in over",
                            "the eyes: the defeat (seconds)")
                    .translation(KEY + "hollow.level.sinkSeconds").defineInRange("sinkSeconds", 10.0, 1.0, 120.0);
            recoverSeconds = b.comment("Soft ground sets again this long after the player got off it (seconds)")
                    .translation(KEY + "hollow.level.recoverSeconds")
                    .defineInRange("recoverSeconds", 4.0, 0.5, 120.0);
            beatSlowTicks = b.comment("Ticks between two beats of the node when the player arrives")
                    .translation(KEY + "hollow.level.beatSlowTicks").defineInRange("beatSlowTicks", 30, 4, 200);
            beatFastTicks = b.comment("Ticks between two beats of the node once the hollow has closed")
                    .translation(KEY + "hollow.level.beatFastTicks").defineInRange("beatFastTicks", 12, 4, 200);
            b.pop();
        }
    }

    /**
     * The Awakening (SPEC 9 phase 1 and the outcomes), section {@code awakening} of COMMON; read by
     * {@link tremor.awakening.AwakeningManager}, {@link tremor.awakening.Outcomes} and
     * {@link tremor.awakening.Craters}, and the seeking before it (SPEC 8 AWAKENING) by the entity's mind
     * ({@link tremor.entity.TremorMind}).
     */
    public static final class Awakening {
        public final ModConfigSpec.IntValue seekSeconds;
        public final ModConfigSpec.DoubleValue reachDistance;
        public final ModConfigSpec.DoubleValue searchRadius;
        public final ModConfigSpec.DoubleValue radius;
        public final ModConfigSpec.IntValue buildupSeconds;
        public final ModConfigSpec.IntValue swallowTicks;
        public final ModConfigSpec.IntValue cooldownSeconds;
        public final ModConfigSpec.IntValue emergeTicks;
        public final ModConfigSpec.IntValue craterRadius;
        public final ModConfigSpec.IntValue craterDepth;
        public final ModConfigSpec.IntValue craterBlocksPerTick;
        public final ModConfigSpec.DoubleValue craterBudgetMillis;
        public final ModConfigSpec.BooleanValue lethal;

        Awakening(ModConfigSpec.Builder b) {
            b.comment("The Awakening (SPEC 9): at the top of its anger the entity seeks a player, and once it has",
                            "reached one it becomes the whole area around that player; the player escapes by leaving",
                            "the zone in time, or the ground swallows the player into the hollow")
                    .translation(KEY + "awakening").push("awakening");
            seekSeconds = b.comment("At the top of its anger (AWAKENING) the entity goes for the sounds it hears,",
                            "faster than hunting, its anger held at the top; if it has reached nobody this long after",
                            "it got there, it calms down to HUNTING (halfway between behavior.huntAt and awakenAt) and",
                            "the anger decays again (seconds)")
                    .translation(KEY + "awakening.seekSeconds").defineInRange("seekSeconds", 35, 1, 600);
            reachDistance = b.comment("The Awakening starts once the bump of the seeking entity is this close to a",
                            "player in survival mode (blocks, horizontally, and at most 5 blocks above or below the",
                            "feet); the zone is around that player")
                    .translation(KEY + "awakening.reachDistance").defineInRange("reachDistance", 8.0, 1.0, 32.0);
            searchRadius = b.comment("The seeking entity searches only this far around the last sound it heard",
                            "(blocks; behavior.huntSearchRadius applies while hunting). So a player who makes a noise,",
                            "then gets quietly a little more than searchRadius + reachDistance (12) blocks away from",
                            "it before the entity is there, not toward the entity coming for it, and keeps still, is",
                            "not found; with nothing to go for the entity roams away from the players",
                            "(behavior.minWanderDistance)")
                    .translation(KEY + "awakening.searchRadius")
                    .defineInRange("searchRadius", BehaviorParams.defaults().seekSearchRadius(), 0.0, 64.0);
            radius = b.comment("Radius of the zone (blocks, horizontally) around where the player stood at the start")
                    .translation(KEY + "awakening.radius").defineInRange("radius", 30.0, 4.0, 128.0);
            buildupSeconds = b.comment("Time to get out of the zone before it closes (seconds); the last third is dark")
                    .translation(KEY + "awakening.buildupSeconds").defineInRange("buildupSeconds", 30, 1, 600);
            swallowTicks = b.comment("How long the hill rises under the rooted player before the screen goes dark",
                            "(ticks)")
                    .translation(KEY + "awakening.swallowTicks").defineInRange("swallowTicks", 50, 1, 600);
            cooldownSeconds = b.comment("After an Awakening the entity has gone deep: no natural spawn in the",
                            "dimension for this long (seconds)")
                    .translation(KEY + "awakening.cooldownSeconds")
                    .defineInRange("cooldownSeconds", 3600, 0, 604800);
            emergeTicks = b.comment("After a victory: how long the hill at the swallow point rises, lets the player",
                            "out and settles again (ticks)")
                    .translation(KEY + "awakening.emergeTicks").defineInRange("emergeTicks", 80, 1, 600);
            craterRadius = b.comment("After a defeat, and after an escape through the edge of the hollow, a real",
                            "crater opens where the player was swallowed: an irregular funnel with steep walls; its",
                            "radius (blocks, the rim up to a quarter nearer or farther by direction, so it reaches up",
                            "to 1.25 x the radius; 0 = no crater). It never takes blocks with a block entity, blocks of",
                            "#tremor:protected, unbreakable blocks or the spawn protection, nor lets a fluid in (the",
                            "fluid is plugged where it touches the crater)")
                    .translation(KEY + "awakening.craterRadius").defineInRange("craterRadius", 12, 0, 24);
            craterDepth = b.comment("Depth of the crater at its middle (blocks). At the surface it also takes all that",
                            "stands over it up to the top of the terrain (a hill, trees; 48 blocks over the swallow",
                            "point at most); deep under a roof (a cave, a mine) it cuts this far up, under the rest")
                    .translation(KEY + "awakening.craterDepth").defineInRange("craterDepth", 20, 1, 40);
            craterBlocksPerTick = b.comment("The crater caves in gradually, from the top down: at most this many blocks",
                            "are changed per tick")
                    .translation(KEY + "awakening.craterBlocksPerTick")
                    .defineInRange("craterBlocksPerTick", 120, 1, 10000);
            craterBudgetMillis = b.comment("Server time per tick for digging the craters, however many blocks that",
                            "is (milliseconds)")
                    .translation(KEY + "awakening.craterBudgetMillis")
                    .defineInRange("craterBudgetMillis", 2.0, 0.1, 50.0);
            lethal = b.comment("A defeat kills the player, whose things are hidden on the bottom of the crater (in",
                            "caches of rubble, some of them buried); false: the player comes out there alive,",
                            "keeping everything, but badly weakened")
                    .translation(KEY + "awakening.lethal").define("lethal", true);
            b.pop();
        }
    }

    /** Upper bound of the conductivity samples per vibration (performance, SPEC 16). */
    public static final int MAX_HEARING_SAMPLES = 128;

    /** How the deformation is drawn. */
    public enum Style implements TranslatableEnum {
        /** SPEC 6.3: whole block copies shifted along the normal, the gap filled with more copies. */
        BLOCKS,
        /** Every vertex shifted by the height at that vertex: one smooth continuous mound. */
        WARP;

        @Override
        public Component getTranslatedName() {
            return Component.translatable(KEY + "render.style." + name().toLowerCase(Locale.ROOT));
        }
    }

    public static final class Client {
        public final ModConfigSpec.IntValue maxDeformedBlocks;
        public final ModConfigSpec.IntValue awakeningMaxBlocks;
        public final ModConfigSpec.IntValue renderDistance;
        public final ModConfigSpec.BooleanValue jitter;
        public final ModConfigSpec.EnumValue<Style> style;
        public final ModConfigSpec.BooleanValue ripple;
        public final ModConfigSpec.DoubleValue rippleAmplitude;
        public final ModConfigSpec.BooleanValue rippleDust;
        public final ModConfigSpec.DoubleValue hollowFogDistance;
        public final ModConfigSpec.DoubleValue rustleVolume;
        public final ModConfigSpec.DoubleValue silenceFloor;
        public final ModConfigSpec.DoubleValue heartbeatVolume;
        public final ModConfigSpec.DoubleValue humVolume;
        public final ModConfigSpec.DoubleValue pullVolume;

        Client(ModConfigSpec.Builder b) {
            b.comment("Rendering quality of the ground deformation").translation(KEY + "render").push("render");
            maxDeformedBlocks = b.comment("Upper bound of block copies drawn per frame; the rest is skipped")
                    .translation(KEY + "render.maxDeformedBlocks").defineInRange("maxDeformedBlocks", 1500, 0, 20000);
            awakeningMaxBlocks = b.comment("The same bound while the ground of an Awakening zone (SPEC 9) is drawn,",
                            "where all of it in view breathes; the nearest is kept, and it lowers smoothly towards",
                            "where the bound cuts it off")
                    .translation(KEY + "render.awakeningMaxBlocks")
                    .defineInRange("awakeningMaxBlocks", 4000, 0, 40000);
            renderDistance = b.comment("Deformation further than this from the camera (blocks) is not drawn")
                    .translation(KEY + "render.renderDistance").defineInRange("renderDistance", 128, 16, 512);
            jitter = b.comment("Draw the fine tremble of the ground (noise term of the shape)")
                    .translation(KEY + "render.jitter").define("jitter", true);
            style = b.comment("blocks: whole block copies pushed out, the gap filled with more copies;",
                            "warp: every vertex moved by the height at that vertex, a smooth mound")
                    .translation(KEY + "render.style").defineEnum("style", Style.BLOCKS);
            b.pop();

            b.comment("Visible effects of the entity's behaviour (SPEC 8)")
                    .translation(KEY + "effects").push("effects");
            ripple = b.comment("Draw the ripple that runs over the ground around an alerted entity")
                    .translation(KEY + "effects.ripple").define("ripple", true);
            rippleAmplitude = b.comment("Height of the ripple around a bump of full height (shape.amplitude), blocks",
                            "(0 = off); it is lower around a lower bump and gone while the bump dives")
                    .translation(KEY + "effects.rippleAmplitude")
                    .defineInRange("rippleAmplitude", RippleParams.defaults().amplitude(), 0.0, 1.0);
            rippleDust = b.comment("Kick up a little dust of the ground along the front of the ripples while they are",
                            "drawn (around an alerted entity, the rings of steps in an Awakening and the rings of the",
                            "node in the hollow, there with a few faint glints where it is too dark to see dust); half",
                            "as much with the Particles video setting at Decreased, none at Minimal")
                    .translation(KEY + "effects.rippleDust").define("rippleDust", true);
            hollowFogDistance = b.comment("In the hollow (SPEC 9) a black fog hides everything further than this",
                            "(blocks); it starts about a tenth of the way out and closes in a little as the hollow",
                            "closes. Night vision does not lift it. The rings of the node and the heaving ground are",
                            "drawn only about as far as this (at most 20 blocks)")
                    .translation(KEY + "effects.hollowFogDistance")
                    .defineInRange("hollowFogDistance", 5.5, 2.0, 32.0);
            b.pop();

            b.comment("Sounds of the entity (SPEC 13)").translation(KEY + "sound").push("sound");
            rustleVolume = b.comment("Volume of the rustle of the moving bump (1 = normal, 0 = silent). Above 1 it is",
                            "louder than the other hostile sounds, but at most at full volume: that only counts while",
                            "the Hostile Creatures volume is below 100%")
                    .translation(KEY + "sound.rustleVolume").defineInRange("rustleVolume", 1.0, 0.0, 2.0);
            silenceFloor = b.comment("Inside the zone of an Awakening every sound of the world (mobs, weather, blocks,",
                            "music) fades to this share of its volume over 3 s, and comes back once you are out",
                            "(SPEC 9); the music plays on through it. 0 = complete silence, 1 = no silence; the menu",
                            "and the mod's own sounds stay, and so may sound loops of other mods")
                    .translation(KEY + "sound.silenceFloor").defineInRange("silenceFloor", 0.05, 0.0, 1.0);
            heartbeatVolume = b.comment("Volume of the heartbeat heard inside the zone of an Awakening; it starts at",
                            "40% of this and quickens and grows to all of it as the zone closes (0 = off). In the",
                            "hollow the heartbeat comes from the node: louder near it and as the hollow closes, and",
                            "never quieter than about a fifth of this far off; the ring of a beat thumps faintly",
                            "under you as it passes")
                    .translation(KEY + "sound.heartbeatVolume").defineInRange("heartbeatVolume", 0.8, 0.0, 1.0);
            humVolume = b.comment("Volume of the low hum of the ground inside the zone of an Awakening and in the",
                            "hollow; it starts at 35% of this and swells to all of it as the zone (the hollow) closes",
                            "(0 = off)")
                    .translation(KEY + "sound.humVolume").defineInRange("humVolume", 0.7, 0.0, 1.0);
            pullVolume = b.comment("Volume of the squelch of the soft ground of the hollow pulling you in; it starts",
                            "at 40% of this and grows to all of it the deeper you sink (0 = off)")
                    .translation(KEY + "sound.pullVolume").defineInRange("pullVolume", 0.8, 0.0, 1.0);
            b.pop();
        }
    }

    public static final Common COMMON;
    public static final ModConfigSpec COMMON_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec CLIENT_SPEC;

    static {
        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        COMMON = new Common(common);
        COMMON_SPEC = common.build();
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        CLIENT = new Client(client);
        CLIENT_SPEC = client.build();
    }
}
