package tremor.client;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.shape.BumpParams;
import tremor.debug.DebugReport;
import tremor.network.TremorReportPayload;

/**
 * A report for a bug report, copied to the clipboard with the debug probe (its menu, or Alt + a right click): the
 * server's part ({@link TremorReportPayload}: the world and its seed, the settings changed, the probe's lines and the
 * tremor's exact numbers) and the client's, taken at the moment it was asked for: the client and its settings, every
 * tremor it sees, the hill of the one commanded as drawn, column by column with the height drawn, the height it is
 * heading to and why ({@code DeformationRenderer#report}), and the ground around it block by block, enough to lay the
 * same place again (the user: text instead of screenshots, for a full analysis, into the clipboard). Main thread only.
 */
public final class ProbeReport {
    /** Blocks of ground reported around the tremor, to each side and up and down. */
    private static final int AROUND = 7, BELOW = 6, ABOVE = 6;
    /** Columns of the hill reported within this of its centre (blocks). */
    private static final double HILL_RADIUS = 7;
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** The client's part, taken when the report was asked for; null while none is awaited. */
    private static String clientPart;
    /** When it was asked for ({@link System#nanoTime}): an answer that comes later than this waits is dropped. */
    private static long askedAt;
    private static final long WAIT_NANOS = 10_000_000_000L;

    private ProbeReport() {
    }

    /** The report was just asked for: what the client sees is taken now, the server's part comes after. */
    static void asked() {
        clientPart = client();
        askedAt = System.nanoTime();
    }

    /** The server's part came: the whole goes to the clipboard. */
    public static void accept(TremorReportPayload payload) {
        // Only the answer to a report this player has just asked for: a server may not put text into the clipboard
        // unasked (the security review).
        String own = clientPart;
        clientPart = null;
        if (own == null || System.nanoTime() - askedAt > WAIT_NANOS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String text = "# Tremor report, " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\n" + payload.text() + own;
        mc.keyboardHandler.setClipboard(text);
        Tremor.LOGGER.info("Tremor report copied to the clipboard:\n{}", text);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("tremor.probe.report.copied",
                    text.split("\n", -1).length, text.length()), true);
        }
    }

    /** What the client sees now. */
    static String client() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder out = new StringBuilder();
        out.append("## client\n");
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 eye = camera.getPosition();
        out.append(String.format(Locale.ROOT, "mod tremor %s, fps %d, render distance %d, graphics %s, gui scale %d, "
                        + "shader mods %s%n", DebugReport.version(Tremor.MODID), mc.getFps(),
                mc.options.getEffectiveRenderDistance(),
                mc.options.graphicsMode().get().name().toLowerCase(Locale.ROOT),
                (int) mc.getWindow().getGuiScale(), shaders()));
        out.append(String.format(Locale.ROOT, "camera at %.3f %.3f %.3f, yaw %.1f, pitch %.1f, %s%s%n", eye.x, eye.y,
                eye.z, camera.getYRot(), camera.getXRot(), mc.options.getCameraType() == CameraType.FIRST_PERSON
                        ? "first person" : "third person", mc.isPaused() ? ", paused" : ""));
        StringBuilder changed = new StringBuilder();
        DebugReport.changed(changed, TremorConfig.CLIENT_SPEC.getValues(), "");
        out.append("client settings not at their default: ").append(changed.length() == 0 ? "none" : changed)
                .append('\n');
        List<ClientTremor.RenderState> states = ClientTremor.lastStates();
        out.append("## tremors seen (client), nearest first\n");
        if (states.isEmpty()) {
            out.append("none\n");
        }
        for (ClientTremor.RenderState state : states) {
            BumpParams p = state.params();
            out.append(String.format(Locale.ROOT, "#%d %s: centre %s, normal %s, forward %s, amplitude %.3f of %.3f, "
                            + "sigma front %.3f back %.3f side %.3f, ripple %s%n", state.instance(),
                    state.stage().name().toLowerCase(Locale.ROOT), DebugReport.vec(state.frame().center()),
                    DebugReport.vec(state.frame().normal()), DebugReport.vec(state.frame().forward()), p.amplitude(),
                    state.fullAmplitude(), p.sigmaFront(), p.sigmaBack(), p.sigmaSide(),
                    Double.isNaN(state.rippleAgeSeconds()) ? "none"
                            : String.format(Locale.ROOT, "%.2f s old", state.rippleAgeSeconds())));
        }
        ClientTremor.RenderState chosen = chosen(states);
        if (chosen != null && mc.level != null) {
            out.append("## hill of #").append(chosen.instance()).append(" (client)\n");
            tremor.client.render.DeformationRenderer.report(out, chosen.instance(), HILL_RADIUS,
                    mc.level.getGameTime());
            terrain(out, mc, BlockPos.containing(chosen.frame().center().x(), chosen.frame().center().y(),
                    chosen.frame().center().z()));
        } else if (mc.player != null) {
            terrain(out, mc, mc.player.blockPosition());
        }
        return out.toString();
    }

    /** The tremor the probe commands: the one chosen, else the nearest seen. */
    private static ClientTremor.RenderState chosen(List<ClientTremor.RenderState> states) {
        for (ClientTremor.RenderState state : states) {
            if (state.instance() == ProbeTool.selected) {
                return state;
            }
        }
        return states.isEmpty() ? null : states.get(0);
    }

    /**
     * The ground around {@code centre} block by block: a palette of the states found, then a layer per height from the
     * bottom up, a row per z, a character per x ({@code .} air).
     */
    private static void terrain(StringBuilder out, Minecraft mc, BlockPos centre) {
        int x0 = centre.getX() - AROUND, y0 = centre.getY() - BELOW, z0 = centre.getZ() - AROUND;
        int size = 2 * AROUND + 1, height = BELOW + ABOVE + 1;
        Map<BlockState, Character> palette = new LinkedHashMap<>();
        List<String> layers = new ArrayList<>();
        List<Character> uniform = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < height; dy++) {
            StringBuilder layer = new StringBuilder();
            char only = 0;
            boolean same = true;
            for (int dz = 0; dz < size; dz++) {
                layer.append(String.format(Locale.ROOT, "%6d ", z0 + dz));
                for (int dx = 0; dx < size; dx++) {
                    BlockState state = mc.level.getBlockState(at.set(x0 + dx, y0 + dy, z0 + dz));
                    char ch;
                    if (state.isAir()) {
                        ch = '.';
                    } else {
                        Character known = palette.get(state);
                        if (known == null) {
                            known = palette.size() < CHARS.length() ? CHARS.charAt(palette.size()) : '?';
                            palette.put(state, known);
                        }
                        ch = known;
                    }
                    layer.append(ch);
                    if (only == 0) {
                        only = ch;
                    } else if (ch != only) {
                        same = false;
                    }
                }
                layer.append('\n');
            }
            layers.add(layer.toString());
            uniform.add(same ? only : null);
        }
        out.append(String.format(Locale.ROOT, "## ground (client): %d x %d x %d blocks from %d %d %d, x east along a "
                + "row, a row per z, a layer per y from the bottom up%n", size, height, size, x0, y0, z0));
        out.append("palette: .=air");
        for (Map.Entry<BlockState, Character> entry : palette.entrySet()) {
            out.append(' ').append(entry.getValue()).append('=').append(BlockStateParser.serialize(entry.getKey()));
        }
        out.append('\n');
        // A layer of one block alone is told in a line, and a run of them in one.
        for (int dy = 0; dy < height; ) {
            Character only = uniform.get(dy);
            if (only == null) {
                out.append("y ").append(y0 + dy).append('\n').append(layers.get(dy));
                dy++;
                continue;
            }
            int end = dy;
            while (end + 1 < height && only.equals(uniform.get(end + 1))) {
                end++;
            }
            out.append("y ").append(y0 + dy).append(end > dy ? ".." + (y0 + end) : "").append(" all ").append(only)
                    .append('\n');
            dy = end + 1;
        }
    }

    private static String shaders() {
        StringBuilder found = new StringBuilder();
        for (String mod : new String[]{"iris", "oculus", "sodium", "embeddium", "rubidium"}) {
            if (ModList.get().isLoaded(mod)) {
                found.append(found.length() == 0 ? "" : " ").append(mod);
            }
        }
        return found.length() == 0 ? "none" : found.toString();
    }
}
