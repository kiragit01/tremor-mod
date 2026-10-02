package tremor.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Architecture rule (SPEC 3.2): the core is independent of Minecraft. Every source file under {@code tremor.core}
 * may import only {@code java.*} and {@code tremor.core.*}.
 */
class CoreIsolationTest {
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    /** Fully qualified references that would sneak a game dependency in without an import. */
    private static final Pattern FORBIDDEN_REFERENCE =
            Pattern.compile("\\b(net\\.minecraft|net\\.neoforged|com\\.mojang|org\\.joml|org\\.lwjgl)\\.");

    private static List<Path> coreSources() throws IOException {
        // Gradle runs tests with the project root as working directory.
        Path root = Paths.get(System.getProperty("user.dir")).resolve("src/main/java/tremor/core");
        if (!Files.isDirectory(root)) {
            fail("Core source directory not found: " + root.toAbsolutePath()
                    + " (tests must run with the project root as working directory)");
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void coreImportsOnlyJavaAndCore() throws IOException {
        List<Path> sources = coreSources();
        assertFalse(sources.isEmpty(), "no core sources found");
        List<String> violations = new ArrayList<>();
        for (Path file : sources) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = IMPORT.matcher(lines.get(i));
                if (m.find()) {
                    String imported = m.group(2);
                    if (!imported.startsWith("java.") && !imported.startsWith("tremor.core.")) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " imports " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "tremor.core must import only java.* and tremor.core.*:\n"
                + String.join("\n", violations));
    }

    @Test
    void coreHasNoFullyQualifiedGameReferences() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : coreSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = FORBIDDEN_REFERENCE.matcher(lines.get(i));
                if (m.find()) {
                    violations.add(file.getFileName() + ":" + (i + 1) + " references " + m.group(1));
                }
            }
        }
        assertTrue(violations.isEmpty(), "tremor.core must not reference game classes:\n"
                + String.join("\n", violations));
    }

    @Test
    void corePackagesAreUnderTremorCore() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : coreSources()) {
            String pkg = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(PACKAGE::matcher).filter(Matcher::find).map(m -> m.group(1)).findFirst().orElse("<none>");
            if (!pkg.equals("tremor.core") && !pkg.startsWith("tremor.core.")) {
                violations.add(file.getFileName() + " is in package " + pkg);
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
