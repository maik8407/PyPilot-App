package de.eneriq.pilot

import kotlin.test.*

class ServoStatusTest {
    @Test fun normalStatesAreNoFaults() {
        assertEquals(emptyList(), ServoStatus.faults("SYNC ENGAGED"))
        assertEquals(emptyList(), ServoStatus.faults(""))
    }

    @Test fun findsFaultFlags() {
        assertEquals(listOf("OVERTEMP_FAULT", "PORT_OVERCURRENT_FAULT", "DRIVER_TIMEOUT"),
            ServoStatus.faults("SYNC OVERTEMP_FAULT PORT_OVERCURRENT_FAULT DRIVER_TIMEOUT"))
        assertEquals(listOf("BAD_FUSES", "INVALID"), ServoStatus.faults("BAD_FUSES INVALID"))
        assertEquals(listOf("SATURATED"), ServoStatus.notes("ENGAGED SATURATED"))
    }

    @Test fun missingValuesGiveNoStatement() {
        assertNull(ServoStatus.faults(null))
        assertNull(ServoStatus.temperature("false"))     // pypilot: false, solange keine Temperatur gemeldet wird
        assertNull(ServoStatus.temperature(null))
        assertEquals(38.5, ServoStatus.temperature("38.500"))
    }
}
