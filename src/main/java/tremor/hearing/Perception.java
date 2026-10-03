package tremor.hearing;

import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;

/**
 * How the entity perceived a {@link Vibration} (SPEC 7.2, 7.3) and what came of it (SPEC 8).
 *
 * @param listener    where the entity was
 * @param distance    from the source
 * @param perceived   loudness at the entity
 * @param heard       {@code perceived >= threshold}: it updated {@code lastHeard}, the anger and the brain
 * @param angerAdded  how much the anger grew (0 if not heard)
 * @param anger       the anger afterwards
 * @param stageBefore the stage before
 * @param stage       the stage afterwards
 * @param reaction    what the entity makes of it (see {@code TremorMind.heard}: {@code ignores it},
 *                    {@code investigates}, {@code freezes}, {@code hunts}, {@code seeks}, {@code ai off}...); null if
 *                    not heard
 */
public record Perception(Vec3 listener, double distance, double perceived, boolean heard, double angerAdded,
                         double anger, Stage stageBefore, Stage stage, String reaction) {
}
