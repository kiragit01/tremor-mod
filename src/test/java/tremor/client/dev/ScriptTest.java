package tremor.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ScriptTest {
    @Test
    void fpsAcceptsMultiplesOfTenWithinTheOptionRange() {
        for (int limit : new int[] {10, 60, 140, 250, 260}) {
            Script.Step step = Script.parse(1, "fps " + limit);
            assertEquals(Script.Kind.FPS, step.kind(), step.text());
            assertEquals(limit, step.number());
        }
    }

    @Test
    void fpsRejectsValuesTheOptionWouldRoundDown() {
        // Options.framerateLimit is IntRange(1, 26) mapped by *10 / /10: 15 would silently become 10, 255 -> 250.
        for (String limit : new String[] {"15", "59", "255", "259", "11"}) {
            Script.Step step = Script.parse(3, "fps " + limit);
            assertEquals(Script.Kind.INVALID, step.kind(), limit);
            assertTrue(step.text().contains("multiple of 10"), step.text());
            assertEquals(3, step.line());
        }
    }

    @Test
    void fpsRejectsValuesOutsideTheRange() {
        for (String limit : new String[] {"0", "5", "-10", "270", "1000", "abc", ""}) {
            Script.Step step = Script.parse(1, ("fps " + limit).strip());
            assertEquals(Script.Kind.INVALID, step.kind(), limit);
        }
    }

    @Test
    void holdParsesKeysAndTicks() {
        Script.Step step = Script.parse(1, "HOLD Sprint+forward 20");
        assertEquals(Script.Kind.HOLD, step.kind());
        assertEquals(Set.of(Script.HoldKey.FORWARD, Script.HoldKey.SPRINT), step.keys());
        assertEquals("forward+sprint", step.text());
        assertEquals(20, step.number());
        assertEquals(Script.Kind.INVALID, Script.parse(1, "hold forward+forward 20").kind());
        assertEquals(Script.Kind.INVALID, Script.parse(1, "hold forward 0").kind());
        assertEquals(Script.Kind.INVALID, Script.parse(1, "hold fly 20").kind());
    }

    @Test
    void configTakesThePathAndTheRestOfTheLineAsTheValue() {
        Script.Step step = Script.parse(4, "config spawn.baseChance 1.0");
        assertEquals(Script.Kind.CONFIG, step.kind(), step.text());
        assertEquals("spawn.baseChance", step.text());
        assertEquals("1.0", step.value());

        step = Script.parse(1, "CONFIG Client:render.style   warp");
        assertEquals(Script.Kind.CONFIG, step.kind(), step.text());
        assertEquals("client:render.style", step.text());
        assertEquals("warp", step.value());

        // common: is the default and dropped from the canonical form; a text value may contain spaces.
        step = Script.parse(1, "config common:a.b some  text # value");
        assertEquals("a.b", step.text());
        assertEquals("some  text # value", step.value());
        assertEquals("", Script.parse(1, "log x").value());
    }

    @Test
    void configRejectsMalformedPathsAndAMissingValue() {
        for (String line : new String[] {"config", "config spawn.baseChance", "config server:a.b 1",
                "config a..b 1", "config .a 1", "config a. 1", "config client: 1", "config : 1"}) {
            Script.Step step = Script.parse(2, line);
            assertEquals(Script.Kind.INVALID, step.kind(), line);
            assertEquals(2, step.line());
        }
        assertTrue(Script.parse(1, "config server:a.b 1").text().contains("common: or client:"));
    }

    @Test
    void configPathReadsItsCanonicalFormBack() {
        Script.ConfigPath path = Script.ConfigPath.parse("CLIENT:render.style");
        assertTrue(path.client());
        assertEquals(List.of("render", "style"), path.keys());
        assertEquals(path, Script.ConfigPath.parse(path.toString()));

        path = Script.ConfigPath.parse("hearing.conductivity.stony");
        assertFalse(path.client());
        assertEquals(List.of("hearing", "conductivity", "stony"), path.keys());
        assertEquals("hearing.conductivity.stony", path.toString());
        assertEquals(path, Script.ConfigPath.parse("common:hearing.conductivity.stony"));
    }

    private enum Mode { SLOW, FAST }

    @Test
    void configValuesAreReadByTheTypeOfTheCurrentValue() {
        assertEquals(7, Script.configValue(3, "7"));
        assertEquals(-7L, Script.configValue(3L, "-7"));
        assertEquals(1.0, Script.configValue(0.5, "1"));
        assertEquals(0.25, Script.configValue(0.5, "2.5e-1"));
        assertEquals(false, Script.configValue(true, "FALSE"));
        assertEquals(true, Script.configValue(false, "true"));
        assertSame(Mode.FAST, Script.configValue(Mode.SLOW, "fast"));
        assertEquals("a b", Script.configValue("x", "a b"));
    }

    @Test
    void configValuesOfAnotherTypeAreRejected() {
        Object[][] cases = {{3, "1.5"}, {3, "3000000000"}, {3L, "x"}, {0.5, "NaN"}, {0.5, "1e999"}, {0.5, "half"},
                {true, "yes"}, {Mode.SLOW, "medium"}, {List.of("a"), "b"}, {'c', "d"}};
        for (Object[] c : cases) {
            assertThrows(IllegalArgumentException.class, () -> Script.configValue(c[0], (String) c[1]),
                    c[0] + " <- " + c[1]);
        }
        assertEquals("expected one of SLOW, FAST: 'medium'", assertThrows(IllegalArgumentException.class,
                () -> Script.configValue(Mode.SLOW, "medium")).getMessage());
        String error = assertThrows(IllegalArgumentException.class,
                () -> Script.configValue(List.of("a"), "b")).getMessage();
        assertTrue(error.startsWith("lists cannot be set"), error);
    }

    @Test
    void waitforTakesTheLimitAndTheRestOfTheLineAsTheRegex() {
        Script.Step step = Script.parse(1, "WAITFOR 200 ^SYSTEM: Tremor .*spawned at \\d+");
        assertEquals(Script.Kind.WAITFOR, step.kind(), step.text());
        assertEquals(200, step.number());
        assertEquals("^SYSTEM: Tremor .*spawned at \\d+", step.text());
        assertTrue(Script.waitforPattern(step.text()).matcher("SYSTEM: Tremor 3 spawned at 12, 40").find());
        assertFalse(Script.waitforPattern(step.text()).matcher("OVERLAY: Tremor spawned at 12").find());
    }

    @Test
    void waitforRejectsABadLimitOrRegex() {
        for (String line : new String[] {"waitfor", "waitfor 20", "waitfor 0 x", "waitfor -5 x", "waitfor abc x"}) {
            assertEquals(Script.Kind.INVALID, Script.parse(1, line).kind(), line);
        }
        Script.Step step = Script.parse(1, "waitfor 20 spawned (at");
        assertEquals(Script.Kind.INVALID, step.kind());
        assertTrue(step.text().startsWith("invalid regex 'spawned (at'"), step.text());
        assertFalse(step.text().contains("\n"), step.text());
    }
}
