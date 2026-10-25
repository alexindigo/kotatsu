package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test

class ConfigJsonTest {
    private val legacy = """{"fahrenheit":true,"locations":[{"label":"First","place":"Fixture","latitude":10,"longitude":20,"start":7,"end":23}]}"""

    @Test fun legacyIdsAreStableAndPersistOnTheFirstOperation() {
        val first = ConfigJson.decode(legacy)
        assertEquals(first, ConfigJson.decode(legacy))
        assertEquals(first, ConfigJson.decode(ConfigJson.encode(first)))
        assertTrue(ConfigJson.encode(first).contains("\"version\":1"))
    }

    @Test fun rejectsUnsupportedVersionsInvalidSpansAndDuplicateIdsWithoutDroppingRows() {
        for (raw in listOf(legacy.replaceFirst("{", "{\"version\":9,"),
            legacy.replaceFirst("{", "{\"futureData\":42,"),
            legacy.replaceFirst("{", "{\"fahrenheit\":false,"),
            legacy.replace("\"start\":7", "\"start\":6"), legacy.replace("\"end\":23", "\"end\":23.9"))) {
            assertThrows(Exception::class.java) { ConfigJson.decode(raw) }
        }
        val place = Location("First", "Fixture", 10.0, 20.0)
        assertThrows(Exception::class.java) { ConfigJson.decode(ConfigJson.encode(WidgetConfig(listOf(place, place)))) }
    }
}
