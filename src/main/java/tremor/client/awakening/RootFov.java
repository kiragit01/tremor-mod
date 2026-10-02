package tremor.client.awakening;

/**
 * Field of view of the rooted target of a swallowing ({@link ClientRoot}), as plain functions; no game classes.
 * <p>
 * Vanilla widens or narrows the view with the player's movement speed: its FOV modifier is a product of factors, one of
 * them {@code (speed / walkingSpeed + 1) / 2} ({@code AbstractClientPlayer.getFieldOfViewModifier}). The root holds the
 * speed at 0, which would make that factor 1/2 and halve the view for the whole swallow. Here that factor is
 * exchanged for the one of the speed the player would have without the root, and everything else (flying, a bow, speed
 * effects, other mods' changes, the player's FOV effects setting) is kept.
 */
final class RootFov {
    private RootFov() {
    }

    /**
     * Value of an attribute from its base and its modifiers, the way the game sums them: {@code (base + add) * (1 +
     * addMultipliedBase) * multipliedTotal} (before the attribute clamps it).
     *
     * @param add               sum of the {@code ADD_VALUE} amounts
     * @param addMultipliedBase sum of the {@code ADD_MULTIPLIED_BASE} amounts
     * @param multipliedTotal   product of {@code 1 + amount} over the {@code ADD_MULTIPLIED_TOTAL} modifiers
     */
    static double attributeValue(double base, double add, double addMultipliedBase, double multipliedTotal) {
        return (base + add) * (1 + addMultipliedBase) * multipliedTotal;
    }

    /** Vanilla's speed factor of the FOV modifier. */
    static double speedFactor(double speed, double walkingSpeed) {
        return (speed / walkingSpeed + 1) / 2;
    }

    /**
     * The FOV modifier vanilla would compute for the player without the root: {@code fovModifier} (computed with the
     * rooted speed) with its speed factor exchanged for that of {@code freeSpeed}. Unchanged where vanilla left the
     * speed out of it (a walking speed of 0, or a result that is not finite).
     */
    static double unrooted(double fovModifier, double rootedSpeed, double freeSpeed, double walkingSpeed) {
        if (walkingSpeed == 0 || !Double.isFinite(walkingSpeed)) {
            return fovModifier;
        }
        double rooted = speedFactor(rootedSpeed, walkingSpeed), free = speedFactor(freeSpeed, walkingSpeed);
        double result = fovModifier / rooted * free;
        return rooted != 0 && Double.isFinite(result) ? result : fovModifier;
    }

    /**
     * The modifier the view takes ({@code ComputeFovModifierEvent}'s new one) with the root's part taken out: the
     * event starts from {@code lerp(fovEffectScale, 1, fovModifier)}, so the difference of the modifiers counts scaled
     * by the setting; whatever other handlers did to it stays.
     *
     * @param newFovModifier the event's new modifier so far
     * @param fovModifier    vanilla's modifier, with the root
     * @param unrooted       vanilla's modifier without the root ({@link #unrooted})
     */
    static double corrected(double newFovModifier, double fovEffectScale, double fovModifier, double unrooted) {
        return newFovModifier + fovEffectScale * (unrooted - fovModifier);
    }
}
