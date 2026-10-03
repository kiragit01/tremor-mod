package tremor.hollow.level;

import java.util.function.IntFunction;

/**
 * Which neighbour a cell the level fills grows from (the plain part of {@link Materials#material}): the first one, in
 * the order the sides are tried, that may be copied; else stone, if any neighbour is solid; else nothing. A block that
 * hurts (magma) is solid but never copied: a floor laid where magma was would be magma again, and burn the player on
 * the way the planning counted as safe ground. Plain Java.
 */
final class MaterialChoice {
    /** What a neighbour is. */
    enum Kind {
        /** Nothing to grow from: air, a fluid, a plant, fire. */
        OPEN,
        /** May be copied: a plain full block (sand as sandstone). */
        COPY,
        /** Solid, but not to be copied: it hurts (magma), has a block entity, falls, is one of the mod's own... */
        OTHER
    }

    /** {@link #choose}: no neighbour to copy, but a solid one: stone. */
    static final int STONE = -1;
    /** {@link #choose}: every neighbour is open, nothing to grow from. */
    static final int NOTHING = -2;

    private MaterialChoice() {
    }

    /**
     * The side to copy of {@code sides} sides, tried in order ({@code kind} of each is asked for once, until one may be
     * copied): its number, or {@link #STONE} or {@link #NOTHING}.
     */
    static int choose(int sides, IntFunction<Kind> kind) {
        boolean solid = false;
        for (int i = 0; i < sides; i++) {
            Kind side = kind.apply(i);
            if (side == Kind.COPY) {
                return i;
            }
            solid |= side == Kind.OTHER;
        }
        return solid ? STONE : NOTHING;
    }
}
