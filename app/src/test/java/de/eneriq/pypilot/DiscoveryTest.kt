package de.eneriq.pypilot

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    /** Format wie in pypilot/server.py (ServerValues.get_msg): values={"name": info, ...} */
    private val values = """{"ap.enabled":{"type":"BooleanProperty","writable":true},
        "ap.pilot.basic.P":{"type":"RangeProperty","min":0,"max":0.03,"persistent":true,"profiled":true,"AutopilotGain":true},
        "ap.pilot.basic.PR":{"type":"RangeProperty","min":0,"max":0.02,"AutopilotGain":true},
        "ap.pilot.basic.Pgain":{"type":"SensorValue"},
        "ap.pilot.simple.D":{"type":"RangeProperty","min":0,"max":1,"AutopilotGain":true},
        "ap.pilot":{"type":"EnumProperty","choices":["basic","simple"]}}"""

    @Test fun findsGainsWithLimitsAndNames() {
        val g = Discovery.gains(values)
        assertEquals(setOf("ap.pilot.basic.P", "ap.pilot.basic.PR", "ap.pilot.simple.D"), g.map { it.valueName }.toSet())
        val p = g.first { it.name == "P" }
        assertEquals(0.03, p.max, 1e-12)
        assertEquals("ap.pilot.basic.Pgain", p.termName)   // pypilot: Name + "gain"
    }

    @Test fun badInputNeverThrows() {
        assertTrue(Discovery.gains("kein json").isEmpty())
        assertTrue(Discovery.gains("{}").isEmpty())
        assertTrue(Discovery.stringList("kaputt").isEmpty())
        assertTrue(Discovery.stringList("").isEmpty())
    }

    @Test fun readsStringLists() {
        assertEquals(listOf("default", "segeln"), Discovery.stringList("[\"default\",\"segeln\"]"))
        assertEquals(listOf("compass", "wind"), Discovery.stringList("[\"compass\",\"wind\"]"))
    }
}
