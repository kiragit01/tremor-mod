package tremor.hearing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * The block tags of the hearing (the conductivity classes in {@code data/tremor/tags/blocks/conductivity}, and
 * {@code #tremor:rustling}) against the game data: a required entry
 * that does not exist makes the whole tag fail to load, so every required {@code minecraft:} block and tag must exist
 * in the vanilla data, and every optional common ({@code c:}) tag in NeoForge's (a typo there would be ignored
 * silently). The data is read from the jars ModDevGradle puts under {@code build/moddev/artifacts}; without them the
 * check is skipped.
 */
class ConductivityTagsTest {
    /** The tags of the hearing: the conductivity classes and the rustling blocks under the feet. */
    private static final List<String> TAGS = List.of("conductivity/insulating", "conductivity/wooden",
            "conductivity/gravelly", "conductivity/sandy", "conductivity/stony", "rustling");
    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*}");
    private static final Pattern OBJECT_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    /** Entries of one tag file: plain strings are required, {@code {"id": ..., "required": false}} optional. */
    private record Entries(Set<String> required, Set<String> optional) {
    }

    /** Entries of the tag of conductivity class {@code tag}. */
    private static Entries entries(String tag) throws IOException {
        return tagEntries("conductivity/" + tag);
    }

    /** Entries of the block tag {@code tremor:<tag>}. */
    private static Entries tagEntries(String tag) throws IOException {
        String path = "/data/tremor/tags/blocks/" + tag + ".json";
        String json;
        try (InputStream in = ConductivityTagsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int open = json.indexOf('[');
        int close = json.lastIndexOf(']');
        assertTrue(json.contains("\"values\"") && open >= 0 && close > open, tag + ": no values list");
        String values = json.substring(open + 1, close);
        Set<String> optional = new LinkedHashSet<>();
        Matcher objects = OBJECT.matcher(values);
        while (objects.find()) {
            Matcher id = OBJECT_ID.matcher(objects.group());
            assertTrue(id.find(), tag + ": entry without an id: " + objects.group());
            assertTrue(objects.group().matches("(?s).*\"required\"\\s*:\\s*false.*"),
                    tag + ": object entries are for optional tags: " + objects.group());
            optional.add(id.group(1));
        }
        String rest = OBJECT.matcher(values).replaceAll("");
        Set<String> required = new LinkedHashSet<>();
        Matcher strings = STRING.matcher(rest);
        while (strings.find()) {
            assertTrue(required.add(strings.group(1)), tag + ": duplicate entry " + strings.group(1));
        }
        return new Entries(required, optional);
    }

    private static List<ZipFile> gameJars() throws IOException {
        Path artifacts = Paths.get(System.getProperty("user.dir")).resolve("build/moddev/artifacts");
        assumeTrue(Files.isDirectory(artifacts), "no game jars at " + artifacts);
        List<ZipFile> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(artifacts)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                jars.add(new ZipFile(jar.toFile()));
            }
        }
        return jars;
    }

    private static boolean exists(List<ZipFile> jars, String entry) {
        return jars.stream().anyMatch(jar -> jar.getEntry(entry) != null);
    }

    /** The data file that proves an entry exists: a block's blockstates file, or a tag's file. */
    private static String evidence(String entry) {
        boolean tag = entry.startsWith("#");
        String id = tag ? entry.substring(1) : entry;
        int colon = id.indexOf(':');
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        return tag ? "data/" + namespace + "/tags/blocks/" + path + ".json"
                : "assets/" + namespace + "/blockstates/" + path + ".json";
    }

    @Test
    void everyEntryExistsInTheGameData() throws IOException {
        List<ZipFile> jars = gameJars();
        try {
            assumeTrue(exists(jars, "assets/minecraft/blockstates/stone.json"), "no vanilla resources jar");
            assumeTrue(exists(jars, "data/c/tags/blocks/ores.json"), "no NeoForge jar");
            List<String> problems = new ArrayList<>();
            for (String tag : TAGS) {
                Entries entries = tagEntries(tag);
                for (String entry : entries.required()) {
                    if (!entry.startsWith("minecraft:") && !entry.startsWith("#minecraft:")) {
                        problems.add(tag + ": required entry outside minecraft: " + entry);
                    } else if (!exists(jars, evidence(entry))) {
                        problems.add(tag + ": no such block or tag: " + entry);
                    }
                }
                for (String entry : entries.optional()) {
                    if (entry.startsWith("#c:") && !exists(jars, evidence(entry))) {
                        problems.add(tag + ": no such common tag: " + entry);
                    }
                }
            }
            if (!problems.isEmpty()) {
                fail(String.join("\n", problems));
            }
        } finally {
            for (ZipFile jar : jars) {
                jar.close();
            }
        }
    }

    @Test
    void woodenClassHoldsTheWoodenSlabsAndStairsThatStonyTakesByTag() throws IOException {
        // WOODEN is checked before STONY, which takes all of #minecraft:slabs and #minecraft:stairs.
        Set<String> wooden = entries("wooden").required();
        assertTrue(wooden.contains("#minecraft:wooden_slabs"));
        assertTrue(wooden.contains("#minecraft:wooden_stairs"));
        assertTrue(wooden.contains("minecraft:bamboo_mosaic_slab"));
        assertTrue(wooden.contains("minecraft:bamboo_mosaic_stairs"));
        assertTrue(wooden.contains("minecraft:scaffolding"));
        assertTrue(wooden.contains("minecraft:ladder"));
        Set<String> stony = entries("stony").required();
        for (String tag : List.of("#minecraft:slabs", "#minecraft:stairs", "#minecraft:walls", "#minecraft:terracotta")) {
            assertTrue(stony.contains(tag), "stony lacks " + tag);
        }
    }

    @Test
    void leavesInsulate() throws IOException {
        assertTrue(entries("insulating").required().contains("#minecraft:leaves"));
    }

    @Test
    void leavesRustleUnderTheFeet() throws IOException {
        // SPEC 7.2 (2026-10-03): louder under the feet, while along the way they still insulate (above).
        assertTrue(tagEntries("rustling").required().contains("#minecraft:leaves"));
    }
}
