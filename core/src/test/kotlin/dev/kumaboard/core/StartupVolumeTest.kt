package dev.kumaboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupVolumeTest {
    @Test fun `40 percent of typical Android ranges`() {
        assertEquals(6, StartupVolume.level(0, 15))   // media 15 steps -> 6
        assertEquals(3, StartupVolume.level(0, 7))    // ring/notification/alarm 7 steps -> 2.8 -> 3
        assertEquals(2, StartupVolume.level(1, 5))    // call 1..5 -> 2
        assertEquals(1, StartupVolume.level(1, 1))
    }

    @Test fun `never below the stream minimum`() {
        assertEquals(1, StartupVolume.level(1, 2, percent = 10))
    }
}
