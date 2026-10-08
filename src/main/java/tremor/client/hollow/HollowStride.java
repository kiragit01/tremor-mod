package tremor.client.hollow;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.TickEvent;
import tremor.Tremor;
import tremor.hollow.SprintLock;

import java.lang.reflect.Field;

/**
 * The local player cannot run in the hollow (SPEC 9 phase 2: "бег невозможен"), as under blindness: no sprint by the
 * sprint key, by toggled sprint or by a double tap forward, and none kept from before. Creative and spectator players
 * run as they like ({@link SprintLock#locks}, which the server enforces as well).
 * <p>
 * Vanilla starts a sprint inside the player's movement step, where no event reaches, so the lock takes away what that
 * step starts one from, before the player's tick ({@link #onPlayerTickPre}):
 * <ul>
 *     <li>the sprint key is let go: a held one is released, a toggled one toggled off, also when it is bound with a
 *     modifier key that is not held (a press would not toggle it then);</li>
 *     <li>the window of the double tap forward ({@code LocalPlayer.sprintTriggerTime}) is closed, so a tap only opens
 *     it again and never starts a sprint;</li>
 *     <li>a sprint kept from before stops.</li>
 * </ul>
 * Should a sprint start in the tick all the same (another mod, or the player's fields out of reach), it is taken back
 * as soon as it shows: a jump with it loses the sprint's push at once ({@link #onJump}), and after the tick
 * ({@link #onPlayerTickPost}) the sprint stops and the walking speed is taken back (the player keeps the speed of a
 * tick for the next one). Either way the server never hears of it: the client sends its sprint only after the tick.
 * <p>
 * A held key is down again after the hollow once it is pressed again (or the keyboard repeats the press of a key held
 * all along). A toggled sprint comes back as it is to be once the lock lets go ({@link SprintToggle}): on if it was on
 * when the lock took it, turned over by every press of the key in the hollow (the press is the toggle it always is;
 * only the sprint does not start), and off after a death there, as vanilla's respawn turns every toggle off.
 * <p>
 * The key's state and the double tap's window are fields of vanilla's that are not public: they are reached by
 * reflection on their Mojang names (the runtime names in NeoForge 1.21.1). If that is not possible, it is logged once
 * and only the backstops are left: without the key's field a toggled sprint bound with a modifier is toggled off only
 * while the modifier is held (until then it starts a sprint in every tick, which keeps a sprint's quicker steering in
 * the air), without the window's field a double tap starts one. Main thread only.
 */
public final class HollowStride {
    /** Field of {@link KeyMapping}: whether the key is down; for a toggled key, whether it is toggled on. */
    private static final String KEY_DOWN_FIELD = "isDown";
    /** Field of {@link LocalPlayer}: ticks left of the window in which a second tap forward starts a sprint. */
    private static final String TRIGGER_FIELD = "sprintTriggerTime";
    /** Push (blocks per tick) vanilla adds to a jump while sprinting, along the yaw ({@code jumpFromGround}). */
    private static final double SPRINT_JUMP_PUSH = 0.2;

    private static boolean fieldsResolved;
    /** {@link #KEY_DOWN_FIELD}, null if it cannot be reached. */
    private static Field keyDown;
    /** {@link #TRIGGER_FIELD}, null if it cannot be reached. */
    private static Field trigger;

    /** What a toggled sprint is to be once the lock lets go. */
    private static final SprintToggle TOGGLE = new SprintToggle();

    private HollowStride() {
    }

    /**
     * Lets the sprint key go, closes the double tap's window and stops a sprint before the local player's tick, while
     * the lock holds; gives a toggled sprint back once it no longer does.
     */
    public static void onPlayerTickPre(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (!(event.player instanceof LocalPlayer player)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        KeyMapping sprint = mc.options.keySprint;
        boolean toggle = mc.options.toggleSprint().get();
        if (!SprintLock.locks(player)) {
            if (TOGGLE.letGo(toggle, sprint.isDown())) {
                setToggled(sprint, true);
            }
            return;
        }
        boolean turnedOff = false;
        if (sprint.isDown()) {
            if (toggle) {
                setToggled(sprint, false);
                // On as the lock takes it, or pressed since: either way it turns over what is to come back.
                turnedOff = !sprint.isDown();
            } else {
                sprint.setDown(false);
            }
        }
        TOGGLE.held(turnedOff, player.isDeadOrDying());
        closeDoubleTap(player);
        stop(player);
    }

    /** Stops a sprint that started in the local player's tick all the same, while the lock holds. */
    public static void onPlayerTickPost(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof LocalPlayer player && SprintLock.locks(player)) {
            stop(player);
        }
    }

    /**
     * Takes the sprint's push back off a jump the local player makes while sprinting where the lock holds, and stops
     * the sprint. The event comes at the end of the jump, after vanilla added the push in the jump's direction.
     */
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        if (event.getEntity() instanceof LocalPlayer player && player.isSprinting() && SprintLock.locks(player)) {
            float yaw = player.getYRot() * Mth.DEG_TO_RAD;
            player.addDeltaMovement(new Vec3(Mth.sin(yaw) * SPRINT_JUMP_PUSH, 0, -Mth.cos(yaw) * SPRINT_JUMP_PUSH));
            stop(player);
        }
    }

    private static void stop(LocalPlayer player) {
        if (player.isSprinting()) {
            player.setSprinting(false);
            player.setSpeed((float) player.getAttributeValue(Attributes.MOVEMENT_SPEED));
        }
    }

    /**
     * Toggles the toggled {@code key} on or off, whatever modifier key it is bound with. Without the field, it is given
     * a press if it is not as wanted, which toggles it unless it is bound with a modifier that is not held.
     */
    private static void setToggled(KeyMapping key, boolean on) {
        resolveFields();
        Field field = keyDown;
        if (field != null) {
            try {
                field.setBoolean(key, on);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                keyDown = null;
                unreachable(KEY_DOWN_FIELD, e);
            }
        }
        if (key.isDown() != on) {
            key.setDown(true);
        }
    }

    /** Closes the window of the double tap forward, so that a tap in this tick only opens it again. */
    private static void closeDoubleTap(LocalPlayer player) {
        resolveFields();
        Field field = trigger;
        if (field == null) {
            return;
        }
        try {
            field.setInt(player, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            trigger = null;
            unreachable(TRIGGER_FIELD, e);
        }
    }

    private static void resolveFields() {
        if (fieldsResolved) {
            return;
        }
        fieldsResolved = true;
        keyDown = field(KeyMapping.class, KEY_DOWN_FIELD, "f_90817_", boolean.class);
        trigger = field(LocalPlayer.class, TRIGGER_FIELD, "f_108583_", int.class);
    }

    /** The field by its Mojang name (development) or its SRG name (a release: Forge 1.20.1 runs on SRG names). */
    private static Field field(Class<?> owner, String name, String srg, Class<?> type) {
        try {
            Field field;
            try {
                field = owner.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                field = owner.getDeclaredField(srg);
            }
            if (field.getType() != type) {
                throw new NoSuchFieldException(name + " is a " + field.getType().getName());
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            unreachable(name, e);
            return null;
        }
    }

    private static void unreachable(String name, Exception e) {
        Tremor.LOGGER.warn("Hollow: cannot reach the sprint's {} ({}); the lock on running in the hollow falls back on "
                + "stopping a sprint once it shows", name, e.toString());
    }
}
