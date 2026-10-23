package de.eneriq.pilot

import kotlin.test.*

class VersionTest {
    @Test fun parsesTags() {
        assertEquals(listOf(1, 2), Version.parse("v1.2"))
        assertEquals(listOf(1, 2, 1), Version.parse("1.2.1"))
        assertEquals(listOf(1, 10), Version.parse("V1.10-beta"))
        assertNull(Version.parse("latest"))
    }

    @Test fun comparesNumerically() {
        assertTrue(Version.isNewer("v1.2", "1.1"))
        assertTrue(Version.isNewer("1.10", "1.9"))
        assertTrue(Version.isNewer("v1.1.1", "1.1"))
        assertFalse(Version.isNewer("v1.1", "1.1"))
        assertFalse(Version.isNewer("v1.1.0", "1.1"))
        assertFalse(Version.isNewer("v1.0", "1.1"))
        assertFalse(Version.isNewer("nightly", "1.1"))
    }
}
