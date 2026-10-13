package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CrowdingTest {
    private static Crowding.Entity at(int instance, double x, double anger, boolean natural, boolean held,
                                      boolean leaving) {
        return new Crowding.Entity(instance, x, 0, anger, natural, held, leaving);
    }

    @Test
    void theCalmerGivesWayThenANaturalOneThenTheYounger() {
        assertEquals(List.of(new Crowding.Yield(2, 1)), Crowding.yields(List.of(
                at(1, 0, 50, true, false, false), at(2, 60, 10, true, false, false)), 100));
        assertEquals(List.of(new Crowding.Yield(1, 2)), Crowding.yields(List.of(
                at(1, 0, 10, true, false, false), at(2, 60, 10.5, false, false, false)), 100), "natural first");
        assertEquals(List.of(new Crowding.Yield(2, 1)), Crowding.yields(List.of(
                at(1, 0, 10, false, false, false), at(2, 60, 10, false, false, false)), 100), "the younger");
    }

    @Test
    void oneHeldBySomethingElseStays() {
        assertEquals(List.of(new Crowding.Yield(2, 1)), Crowding.yields(List.of(
                at(1, 0, 0, true, true, false), at(2, 60, 90, false, false, false)), 100));
        assertTrue(Crowding.yields(List.of(
                at(1, 0, 0, true, true, false), at(2, 60, 90, false, true, false)), 100).isEmpty(), "both held");
    }

    @Test
    void farApartLeavingOrSwitchedOffNobodyGivesWay() {
        List<Crowding.Entity> two = List.of(at(1, 0, 50, true, false, false), at(2, 150, 10, true, false, false));
        assertTrue(Crowding.yields(two, 100).isEmpty(), "150 blocks apart");
        assertTrue(Crowding.yields(two, 0).isEmpty(), "off");
        assertTrue(Crowding.yields(List.of(at(1, 0, 50, true, false, false), at(2, 60, 10, true, false, true)), 100)
                .isEmpty(), "leaving already");
    }

    @Test
    void ofThreeTogetherTheTwoCalmerGiveWay() {
        assertEquals(List.of(new Crowding.Yield(2, 1), new Crowding.Yield(3, 1)), Crowding.yields(List.of(
                at(1, 0, 50, true, false, false), at(2, 40, 10, true, false, false),
                at(3, 80, 5, true, false, false)), 100));
    }
}
