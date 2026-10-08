package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import tremor.client.ClientBlackout;
import tremor.client.hollow.ClientHollow;
import tremor.client.hollow.HollowPulse;
import tremor.client.hollow.HollowSink;
import tremor.client.hollow.HollowWake;
import tremor.config.TremorConfig;
import tremor.hollow.HollowDimension;
import tremor.network.TremorHollowStatePayload;
import tremor.sound.TremorSounds;

/**
 * What a player hears inside the hollow (SPEC 9 phase 2):
 * <ul>
 *     <li>the node's heartbeat ("его ищут по сердцебиению (объёмный звук, громче ближе)") on every beat of its pulse
 *     ({@link HollowPulse}, which also runs its rings over the ground), from the node, so the player hears which way it
 *     is: heard all over the hollow, plainly louder the nearer the player is to it and the further the hollow has
 *     closed, quicker as the server's pace quickens; none while there is no node;</li>
 *     <li>a faint, deeper thump under the player as the ring of a beat runs under them ({@link HollowWake});</li>
 *     <li>the low {@linkplain HumSound hum} of the ground, swelling and rising as the hollow closes;</li>
 *     <li>while the soft ground pulls the player in ({@link HollowSink}): a squelch under the player again and again,
 *     more often, louder and deeper the deeper it has the player, and the heartbeat, the hum and the world muffled
 *     ({@link HollowTone#muffle}).</li>
 * </ul>
 * The world itself is silent there from the start ({@link WorldSilence}), from the moment the player is moved in
 * ({@link #arriving}): the server sends the first state of the hollow only once its level plays, after the screen has
 * been kept dark until the client has the terrain; meanwhile the hum starts as it is before the hollow closes. Loudness
 * and timing are {@link HollowTone}'s. Played by {@link AwakeningSounds} every client tick, in place of the build-up's
 * sounds, while the player is alive in a hollow ({@link #heard}) or arriving in one. The heartbeat and the hum die away
 * with the hollow (the player's way out changes the level, which stops every sound anyway). Main thread only.
 */
final class HollowSounds {
    /** Ticks from the moment the ground starts pulling to the first squelch. */
    private static final int FIRST_PULL_TICKS = 6;

    /** {@link HollowPulse#count} as of the last heartbeat played (or let pass). */
    private static int heardBeats;
    /** {@link HollowWake#passes} as of the last thump played (or let pass). */
    private static int heardPasses;
    /** Ticks to the next squelch; counts down only while the ground pulls. */
    private static int pullCountdown = FIRST_PULL_TICKS;

    private HollowSounds() {
    }

    /** The hollow the local player is alive in ({@link ClientHollow#state}), or null. */
    static TremorHollowStatePayload heard(Minecraft mc) {
        LocalPlayer player = mc.player;
        return mc.level == null || player == null || player.isDeadOrDying() ? null : ClientHollow.state();
    }

    /**
     * Whether the local player is being moved into a hollow in the dark: alive in the hollow's dimension while the
     * screen is (still) black ({@link ClientBlackout#dark}), with no state of a hollow yet since the move
     * ({@link ClientHollow#heardOf}).
     */
    static boolean arriving(Minecraft mc) {
        LocalPlayer player = mc.player;
        return mc.level != null && player != null && !player.isDeadOrDying()
                && mc.level.dimension() == HollowDimension.KEY && !ClientHollow.heardOf() && ClientBlackout.dark();
    }

    /**
     * One client tick of the move into a hollow ({@link #arriving}): the hum as it is before the hollow closes, so it
     * is there when the screen comes back; no heartbeat yet.
     */
    static void arrive(Minecraft mc) {
        idle();
        HumSound.update(mc, true, 0, 1);
    }

    /**
     * One client tick in the hollow: the hum, a heartbeat if the node beat, a thump if a ring ran under the player,
     * the squelch of the pulling ground.
     */
    static void tick(Minecraft mc, TremorHollowStatePayload state) {
        double closeness = ClientHollow.closeness(state);
        double sink = HollowSink.now();
        double muffle = HollowTone.muffle(sink);
        HumSound.update(mc, true, closeness, muffle);
        int beats = HollowPulse.count();
        boolean beat = beats != heardBeats;
        heardBeats = beats;
        int passes = HollowWake.passes();
        boolean passed = passes != heardPasses;
        heardPasses = passes;
        if (mc.isPaused()) {
            return;
        }
        if (beat && state.node() != null) {
            beat(mc, state.node(), closeness, muffle);
        }
        if (passed) {
            thump(mc, HollowWake.passStrength(), muffle);
        }
        if (!HollowTone.pulling(sink)) {
            pullCountdown = FIRST_PULL_TICKS;
        } else if (--pullCountdown <= 0) {
            squelch(mc, sink);
            pullCountdown = HollowTone.pullTicks(sink);
        }
    }

    /** Out of the hollow: lets the beats and the passing rings go unheard and starts the squelches over. */
    static void idle() {
        heardBeats = HollowPulse.count();
        heardPasses = HollowWake.passes();
        pullCountdown = FIRST_PULL_TICKS;
    }

    /**
     * One heartbeat from the node, without the engine's attenuation (which would let it die out a few blocks away):
     * its loudness tells near from far ({@link HollowTone#beatVolume}), its direction where the node is. The sound is
     * a mono one (the warden's heartbeat), so the engine places it: it comes from the node's side, and with the
     * Directional Audio setting from in front or behind as well.
     */
    private static void beat(Minecraft mc, BlockPos node, double closeness, double muffle) {
        double x = node.getX() + 0.5, y = node.getY() + 0.5, z = node.getZ() + 0.5;
        double distance = Math.sqrt(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z));
        double volume = HollowTone.beatVolume(TremorConfig.CLIENT.heartbeatVolume.get(), closeness, distance, muffle);
        if (volume > 0) {
            mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.PULSE.get().getLocation(),
                    AwakeningSounds.SOURCE, (float) volume, (float) HollowTone.beatPitch(closeness),
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, x, y, z, false));
        }
    }

    /**
     * The thump of a ring of {@code strength} running under the player: a deep thud of its own
     * ({@link TremorSounds#THUMP}, with its own subtitle), fainter than the heartbeat ({@link HollowTone#thumpVolume}),
     * right under the player (relative to the listener).
     */
    private static void thump(Minecraft mc, double strength, double muffle) {
        double volume = HollowTone.thumpVolume(TremorConfig.CLIENT.heartbeatVolume.get(), strength, muffle);
        if (volume > 0) {
            mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.THUMP.get().getLocation(),
                    AwakeningSounds.SOURCE, (float) volume, 1, SoundInstance.createUnseededRandom(), false, 0,
                    SoundInstance.Attenuation.NONE, 0, -1, 0, true));
        }
    }

    /** One squelch of the pulling ground, right under the player (relative to the listener). */
    private static void squelch(Minecraft mc, double sink) {
        double volume = HollowTone.pullVolume(TremorConfig.CLIENT.pullVolume.get(), sink);
        if (volume > 0) {
            mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.PULL.get().getLocation(),
                    AwakeningSounds.SOURCE, (float) volume, (float) HollowTone.pullPitch(sink),
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        }
    }
}
