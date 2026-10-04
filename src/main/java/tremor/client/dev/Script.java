package tremor.client.dev;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * A parsed autotest script (UTF-8, an optional BOM is skipped, malformed input fails the load): one step per line,
 * surrounding whitespace trimmed, blank lines and full-line {@code #} comments ignored. Keywords are case-insensitive.
 * <pre>
 * wait &lt;ticks&gt;                 wait that many client ticks
 * waitchunks [timeoutTicks]    wait until chunks stopped arriving and visible sections are meshed (default 600)
 * cmd &lt;command&gt;               run a command as the player (a leading slash is optional)
 * hud &lt;on|off&gt;                show / hide the GUI (F1)
 * view &lt;first|back|front&gt;     camera: first person, third person from behind or from the front (F5)
 * look &lt;yaw&gt; &lt;pitch&gt;          set the camera rotation, pitch in [-90, 90]
 * hold &lt;keys&gt; &lt;ticks&gt;          hold movement keys for that many client ticks (at least 1), then release them; keys
 *                              are forward back left right jump sneak sprint attack joined with '+', e.g.
 *                              forward+sprint (vanilla rules: sprint only works together with forward and not while
 *                              sneaking); attack keeps mining the block under the crosshair like a held left button
 * lookat &lt;x&gt; &lt;y&gt; &lt;z&gt;           turn the camera toward a point (from the player's eyes)
 * release                      release all movement keys now and stop sprinting
 * graphics &lt;fast|fancy|fabulous&gt;  switch the graphics mode (rebuilds the level renderer, follow with waitchunks)
 * fps &lt;limit&gt;                  frame rate limit: a multiple of 10 in 10..260, 260 = unlimited (vanilla's option only
 *                              stores steps of 10 and would silently round anything else down)
 * bench &lt;label&gt; &lt;ticks&gt;       measure frame times over that many client ticks
 * screenshot &lt;name&gt;            save the next frame as screenshots/&lt;name&gt;.png in the report directory
 * log &lt;text&gt;                   write a line to the report
 * config &lt;path&gt; &lt;value&gt;        set a value of the mod's config until the run ends, see below
 * waitfor &lt;maxTicks&gt; &lt;regex&gt;   wait until a chat line received after this step started matches the regex, at most
 *                              maxTicks client ticks (at least 1); a timeout is a FAIL that counts as an error, and
 *                              the script goes on either way
 * quit                         write the report, save the world and close the game (also at the end of the script)
 * </pre>
 * {@code config}: the path is the dotted key of a value in the common config ({@code spawn.baseChance}) or, with the
 * prefix {@code client:}, in the client config ({@code client:render.style}); {@code common:} is accepted too. The
 * value is the rest of the line, read by the type of the current value: an integer, a number, {@code true} or
 * {@code false}, an enum constant by name in any case, or text; lists cannot be set. An unknown path or a value the
 * config spec rejects is a script error. The value is applied the way the config screen applies an edit (config file
 * written, reload event fired), and every changed value is put back when the run ends, however it ends.
 * <p>
 * {@code waitfor}: the regex (Java syntax, the rest of the line) is searched ({@code Matcher.find}) in every chat,
 * system and overlay message as the report lists it after the tick prefix, e.g. {@code SYSTEM: <text>} or
 * {@code CHAT (canceled): <text>}, so {@code ^OVERLAY: } picks a kind. Only messages that arrive after the step
 * started count, so the reply to a {@code cmd} on the line before can be missed: the integrated server often answers
 * before the next step starts.
 * <p>
 * Malformed lines are kept as {@link Kind#INVALID} steps so that the error shows up in the report at the point
 * where the line would have run. Comments are full-line only, because commands may legitimately contain {@code #}
 * (block tags, selectors).
 */
record Script(Path path, List<Step> steps) {
    static final int DEFAULT_WAITCHUNKS_TIMEOUT = 600;
    /** {@code fps} limits: vanilla's {@code Options.framerateLimit} is {@code IntRange(1, 26)} times 10. */
    static final int FPS_MIN = 10;
    static final int FPS_STEP = 10;
    /** {@code Options.UNLIMITED_FRAMERATE_CUTOFF}: at this value the game does not limit the frame rate. */
    static final int FPS_UNLIMITED = 260;

    enum Kind {
        WAIT, WAITCHUNKS, CMD, HUD, VIEW, LOOK, LOOKAT, HOLD, RELEASE, GRAPHICS, FPS, BENCH, SCREENSHOT, LOG, CONFIG, WAITFOR, QUIT,
        INVALID
    }

    /** Movement keys for {@code hold}, named in scripts by their lower-case names. */
    enum HoldKey {
        FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, DROP;

        final String id = name().toLowerCase(Locale.ROOT);

        /** Parses {@code key+key+...}, case-insensitive, without duplicates. */
        static Set<HoldKey> parseAll(String raw) {
            EnumSet<HoldKey> keys = EnumSet.noneOf(HoldKey.class);
            for (String part : raw.split("\\+", -1)) {
                HoldKey key = Arrays.stream(values())
                        .filter(k -> k.id.equalsIgnoreCase(part))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown key '" + part + "' in '" + raw
                                + "', expected " + Arrays.stream(values()).map(k -> k.id).collect(Collectors.joining(" "))
                                + " joined with '+'"));
                if (!keys.add(key)) {
                    throw new IllegalArgumentException("duplicate key '" + key.id + "' in '" + raw + "'");
                }
            }
            return Collections.unmodifiableSet(keys);
        }

        /** The keys in declaration order, joined with {@code +}. */
        static String join(Set<HoldKey> keys) {
            return keys.stream().sorted().map(k -> k.id).collect(Collectors.joining("+"));
        }
    }

    /**
     * Where a {@code config} step writes: a dotted path in the common config, or in the client config if
     * {@code client}.
     */
    record ConfigPath(boolean client, List<String> keys) {
        ConfigPath {
            keys = List.copyOf(keys);
        }

        /** Parses {@code [common:|client:]key.key...}; the prefix is case-insensitive, the keys are not. */
        static ConfigPath parse(String raw) {
            boolean client = false;
            String path = raw;
            int colon = raw.indexOf(':');
            if (colon >= 0) {
                String file = raw.substring(0, colon);
                if (file.equalsIgnoreCase("client")) {
                    client = true;
                } else if (!file.equalsIgnoreCase("common")) {
                    throw new IllegalArgumentException("unknown config '" + file + "' in '" + raw
                            + "', expected the prefix common: or client:");
                }
                path = raw.substring(colon + 1);
            }
            List<String> keys = List.of(path.split("\\.", -1));
            if (keys.stream().anyMatch(String::isEmpty)) {
                throw new IllegalArgumentException("invalid config path '" + raw
                        + "', expected dotted keys such as spawn.baseChance or client:render.style");
            }
            return new ConfigPath(client, keys);
        }

        /** The path in its canonical form, which {@link #parse} reads back: {@code client:} only for the client. */
        @Override
        public String toString() {
            return (client ? "client:" : "") + String.join(".", keys);
        }
    }

    /**
     * @param line   1-based line number in the script file
     * @param source the trimmed line as written
     * @param text   command / label / screenshot name / log text / hold keys joined with {@code +} / config path in its
     *               canonical {@link ConfigPath#toString() form} / waitfor regex, or the error message of an invalid
     *               step
     * @param number ticks (wait, waitchunks timeout, hold, bench, waitfor limit), {@code 1}/{@code 0} for hud on/off, the camera for view (0 first, 1 back, 2 front)
     * @param keys   the keys of a hold, empty for every other step
     * @param value  the value of a config step as written, empty for every other step
     */
    record Step(int line, String source, Kind kind, String text, int number, float yaw, float pitch, Set<HoldKey> keys,
                String value) {
        static Step of(int line, String source, Kind kind, String text, int number) {
            return new Step(line, source, kind, text, number, 0, 0, Set.of(), "");
        }
    }

    static Script load(Path path) throws IOException {
        String content = decode(path, Files.readAllBytes(path));
        List<Step> steps = new ArrayList<>();
        String[] lines = content.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String source = lines[i].strip();
            if (source.isEmpty() || source.startsWith("#")) {
                continue;
            }
            steps.add(parse(i + 1, source));
        }
        return new Script(path, List.copyOf(steps));
    }

    long errorCount() {
        return steps.stream().filter(s -> s.kind() == Kind.INVALID).count();
    }

    /** Strict UTF-8 with an optional BOM; malformed input is an {@link IOException} naming the line and byte offset. */
    private static String decode(Path path, byte[] bytes) throws IOException {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer in = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(in)
                    .toString();
        } catch (CharacterCodingException e) {
            // On a coding error the buffer is positioned at the first bad byte.
            int offset = Math.min(in.position(), bytes.length);
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (bytes[i] == '\n') {
                    line++;
                }
            }
            boolean utf16 = bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE
                    || (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF);
            String found = offset < bytes.length ? String.format(Locale.ROOT, " 0x%02X", bytes[offset] & 0xFF) : "";
            throw new IOException("script " + path + " is not valid UTF-8" + (utf16 ? " (it looks like UTF-16)" : "")
                    + ": malformed byte" + found + " at line " + line + ", byte offset " + offset
                    + "; save the file as UTF-8", e);
        }
    }

    static Step parse(int line, String source) {
        int space = indexOfWhitespace(source);
        String keyword = (space < 0 ? source : source.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : source.substring(space + 1).strip();
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        try {
            return switch (keyword) {
                case "wait" -> {
                    expectArgs(args, 1, 1, "wait <ticks>");
                    yield Step.of(line, source, Kind.WAIT, "", parseInt(args[0], 0));
                }
                case "waitchunks" -> {
                    expectArgs(args, 0, 1, "waitchunks [timeoutTicks]");
                    int timeout = args.length == 0 ? DEFAULT_WAITCHUNKS_TIMEOUT : parseInt(args[0], 1);
                    yield Step.of(line, source, Kind.WAITCHUNKS, "", timeout);
                }
                case "cmd" -> {
                    String command = rest.startsWith("/") ? rest.substring(1).strip() : rest;
                    if (command.isEmpty()) {
                        throw new IllegalArgumentException("usage: cmd <command>");
                    }
                    yield Step.of(line, source, Kind.CMD, command, 0);
                }
                case "hud" -> {
                    expectArgs(args, 1, 1, "hud <on|off>");
                    String value = args[0].toLowerCase(Locale.ROOT);
                    if (!value.equals("on") && !value.equals("off")) {
                        throw new IllegalArgumentException("usage: hud <on|off>");
                    }
                    yield Step.of(line, source, Kind.HUD, value, value.equals("on") ? 1 : 0);
                }
                case "view" -> {
                    expectArgs(args, 1, 1, "view <first|back|front>");
                    String value = args[0].toLowerCase(Locale.ROOT);
                    int camera = switch (value) {
                        case "first" -> 0;
                        case "back" -> 1;
                        case "front" -> 2;
                        default -> throw new IllegalArgumentException("usage: view <first|back|front>");
                    };
                    yield Step.of(line, source, Kind.VIEW, value, camera);
                }
                case "look" -> {
                    expectArgs(args, 2, 2, "look <yaw> <pitch>");
                    float yaw = parseFloat(args[0]);
                    float pitch = parseFloat(args[1]);
                    if (pitch < -90 || pitch > 90) {
                        throw new IllegalArgumentException("pitch must be within [-90, 90]: " + args[1]);
                    }
                    yield new Step(line, source, Kind.LOOK, "", 0, yaw, pitch, Set.of(), "");
                }
                case "lookat" -> {
                    expectArgs(args, 3, 3, "lookat <x> <y> <z>");
                    for (String a : args) {
                        parseDouble(a);
                    }
                    yield Step.of(line, source, Kind.LOOKAT, String.join(" ", args), 0);
                }
                case "hold" -> {
                    expectArgs(args, 2, 2, "hold <keys> <ticks>");
                    Set<HoldKey> keys = HoldKey.parseAll(args[0]);
                    yield new Step(line, source, Kind.HOLD, HoldKey.join(keys), parseInt(args[1], 1), 0, 0, keys, "");
                }
                case "release" -> {
                    expectArgs(args, 0, 0, "release");
                    yield Step.of(line, source, Kind.RELEASE, "", 0);
                }
                case "graphics" -> {
                    expectArgs(args, 1, 1, "graphics <fast|fancy|fabulous>");
                    String value = args[0].toLowerCase(Locale.ROOT);
                    if (!value.equals("fast") && !value.equals("fancy") && !value.equals("fabulous")) {
                        throw new IllegalArgumentException("usage: graphics <fast|fancy|fabulous>");
                    }
                    yield Step.of(line, source, Kind.GRAPHICS, value, 0);
                }
                case "fps" -> {
                    expectArgs(args, 1, 1, "fps <limit: a multiple of 10 in 10..260, 260 = unlimited>");
                    int limit = parseInt(args[0], FPS_MIN);
                    if (limit > FPS_UNLIMITED || limit % FPS_STEP != 0) {
                        throw new IllegalArgumentException("fps limit must be a multiple of " + FPS_STEP + " within ["
                                + FPS_MIN + ", " + FPS_UNLIMITED + "] (the game stores it in steps of " + FPS_STEP
                                + "): " + args[0]);
                    }
                    yield Step.of(line, source, Kind.FPS, "", limit);
                }
                case "bench" -> {
                    expectArgs(args, 2, 2, "bench <label> <ticks>");
                    yield Step.of(line, source, Kind.BENCH, args[0], parseInt(args[1], 1));
                }
                case "screenshot" -> {
                    expectArgs(args, 1, 1, "screenshot <name>");
                    yield Step.of(line, source, Kind.SCREENSHOT, screenshotName(args[0]), 0);
                }
                case "log" -> Step.of(line, source, Kind.LOG, rest, 0);
                case "config" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException("usage: config <path> <value>");
                    }
                    ConfigPath path = ConfigPath.parse(args[0]);
                    String value = rest.substring(args[0].length()).strip();
                    yield new Step(line, source, Kind.CONFIG, path.toString(), 0, 0, 0, Set.of(), value);
                }
                case "waitfor" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException("usage: waitfor <maxTicks> <regex>");
                    }
                    int maxTicks = parseInt(args[0], 1);
                    String regex = rest.substring(args[0].length()).strip();
                    waitforPattern(regex);
                    yield Step.of(line, source, Kind.WAITFOR, regex, maxTicks);
                }
                case "quit" -> {
                    expectArgs(args, 0, 0, "quit");
                    yield Step.of(line, source, Kind.QUIT, "", 0);
                }
                default -> throw new IllegalArgumentException("unknown command '" + keyword + "'");
            };
        } catch (IllegalArgumentException e) {
            return Step.of(line, source, Kind.INVALID, e.getMessage(), 0);
        }
    }

    /** File-system safe screenshot name without the extension. */
    static String screenshotName(String raw) {
        String name = raw.toLowerCase(Locale.ROOT).endsWith(".png") ? raw.substring(0, raw.length() - 4) : raw;
        name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.isEmpty() || name.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException("invalid screenshot name '" + raw + "'");
        }
        return name;
    }

    /**
     * Reads the value of a {@code config} step by the type of the current value: Integer, Long, Double (finite),
     * Boolean ({@code true} or {@code false} in any case), an enum constant by name in any case, or a String as is.
     *
     * @throws IllegalArgumentException if {@code raw} is no such value, or for any other type (lists included)
     */
    static Object configValue(Object current, String raw) {
        if (current instanceof Integer) {
            return parseNumber(raw, Integer::valueOf, "an integer");
        }
        if (current instanceof Long) {
            return parseNumber(raw, Long::valueOf, "an integer");
        }
        if (current instanceof Double) {
            Double value = parseNumber(raw, Double::valueOf, "a number");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("not a finite number: '" + raw + "'");
            }
            return value;
        }
        if (current instanceof Boolean) {
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                return Boolean.valueOf(raw);
            }
            throw new IllegalArgumentException("expected true or false: '" + raw + "'");
        }
        if (current instanceof Enum<?> constant) {
            Enum<?>[] constants = constant.getDeclaringClass().getEnumConstants();
            for (Enum<?> candidate : constants) {
                if (candidate.name().equalsIgnoreCase(raw)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("expected one of " + Arrays.stream(constants).map(Enum::name)
                    .collect(Collectors.joining(", ")) + ": '" + raw + "'");
        }
        if (current instanceof String) {
            return raw;
        }
        if (current instanceof List) {
            throw new IllegalArgumentException("lists cannot be set by a config step, edit the config file instead");
        }
        throw new IllegalArgumentException("values of type " + (current == null ? "null" : current.getClass().getName())
                + " cannot be set by a config step");
    }

    /** Compiles the regex of a {@code waitfor} step; a syntax error becomes a one-line message. */
    static Pattern waitforPattern(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex '" + regex + "': " + e.getDescription()
                    + (e.getIndex() >= 0 ? " at index " + e.getIndex() : ""));
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void expectArgs(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static int parseInt(String s, int min) {
        int value;
        try {
            value = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an integer: '" + s + "'");
        }
        if (value < min) {
            throw new IllegalArgumentException("value must be >= " + min + ": " + value);
        }
        return value;
    }

    private static <T extends Number> T parseNumber(String raw, Function<String, T> parser, String what) {
        try {
            return parser.apply(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not " + what + ": '" + raw + "'");
        }
    }

    private static double parseDouble(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + raw);
        }
    }

    private static float parseFloat(String s) {
        try {
            float value = Float.parseFloat(s);
            if (!Float.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: '" + s + "'");
        }
    }
}
