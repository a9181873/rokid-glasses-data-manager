package tw.dky.rokidfiles;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TouchpadNavigationTuningTest {
    @Test
    public void directionMappingPreservesExistingRokidSignConvention() {
        assertEquals(1, TouchpadNavigationTuning.directionForPrimary(-1f));
        assertEquals(-1, TouchpadNavigationTuning.directionForPrimary(1f));
        assertEquals(-1, TouchpadNavigationTuning.directionForPrimary(0f));
    }

    @Test
    public void flingSpeedMapsToBoundedSteps() {
        assertEquals(1, TouchpadNavigationTuning.flingSteps(900f));
        assertEquals(2, TouchpadNavigationTuning.flingSteps(1_800f));
        assertEquals(4, TouchpadNavigationTuning.flingSteps(3_200f));
        assertEquals(8, TouchpadNavigationTuning.flingSteps(20_000f));
    }

    @Test
    public void smallScrollDeltasAccumulateWithoutJumping() {
        TouchpadNavigationTuning.ScrollAccumulator accumulator =
                new TouchpadNavigationTuning.ScrollAccumulator();

        assertEquals(0, accumulator.consume(0.30f));
        assertEquals(0, accumulator.consume(0.30f));
        assertEquals(1, accumulator.consume(0.45f));
    }

    @Test
    public void reversingScrollDirectionDropsOldRemainder() {
        TouchpadNavigationTuning.ScrollAccumulator accumulator =
                new TouchpadNavigationTuning.ScrollAccumulator();

        assertEquals(0, accumulator.consume(0.80f));
        assertEquals(0, accumulator.consume(-0.30f));
        assertEquals(-1, accumulator.consume(-0.80f));
    }

    @Test
    public void oneScrollEventIsCappedAtThreeSteps() {
        TouchpadNavigationTuning.ScrollAccumulator accumulator =
                new TouchpadNavigationTuning.ScrollAccumulator();

        assertEquals(3, accumulator.consume(12f));
    }
}
