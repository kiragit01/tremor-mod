package tremor.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import tremor.client.awakening.ClientAwakening;
import tremor.hollow.HollowDimension;

import java.util.List;

/**
 * The debug screen (F3) cannot tell where the player is in the hollow, nor while an Awakening has them (the user's
 * wish: where am I is part of the fear): the coordinates and what gives the place away are drawn obfuscated, ever
 * changing characters ({@link ChatFormatting#OBFUSCATED}, as vanilla draws it).
 */
public final class DebugScramble {
    /** Lines (without their formatting) whose value is scrambled. */
    private static final String[] LABELS = {"XYZ:", "Block:", "Chunk:", "Facing:", "Biome:", "Targeted Block:",
            "Targeted Fluid:"};

    private DebugScramble() {
    }

    public static void onDebugText(net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(HollowDimension.is(mc.level) || ClientAwakening.isTarget())) {
            return;
        }
        scramble(event.getLeft());
        scramble(event.getRight());
    }

    private static void scramble(List<String> lines) {
        String hollow = HollowDimension.KEY.location().toString();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String plain = line == null ? null : ChatFormatting.stripFormatting(line);
            if (plain == null) {
                continue;
            }
            if (plain.contains(hollow)) {
                lines.set(i, ChatFormatting.OBFUSCATED + plain);
                continue;
            }
            for (String label : LABELS) {
                if (plain.startsWith(label)) {
                    lines.set(i, label + " " + ChatFormatting.OBFUSCATED + plain.substring(label.length()).trim());
                    break;
                }
            }
        }
    }
}
