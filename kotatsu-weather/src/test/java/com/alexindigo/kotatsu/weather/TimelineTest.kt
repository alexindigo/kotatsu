package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 16)
    private val home = Location("Home", "Portola Valley", 37.38, -122.23)
    private val hours = (0..47).map { hour ->
        Hour(today.atStartOfDay().plusHours(hour.toLong()), when (hour) {
            0 -> 1.0
            9 -> 40.0
            14 -> 80.0
            24 -> 55.0
            else -> 60.0
        }, 0, 0.0)
    }

    @Test fun axisIsUnionOfSpans() {
        assertEquals(Axis(2, 24), sharedAxis(listOf(home, home.copy(start = 2, end = 18))))
    }
    @Test fun numberHoursAndNextMidnight() {
        val numbers = temperatureNumbers(home, Forecast(hours, 1), today)
        assertEquals(55, numbers.single { it.kind == NumberKind.END }.value)
        assertEquals(24, numbers.single { it.kind == NumberKind.END }.hour)
        assertEquals(14, numbers.single { it.kind == NumberKind.HIGH }.hour)
        // The day's low is at 00:00, outside this span, so it is not inserted.
        assertFalse(numbers.any { it.kind == NumberKind.LOW })
    }
    @Test fun lowStrictlyInsideSpan() {
        val forecast = Forecast(hours.map { if (it.time.hour == 0) it.copy(temperature = 60.0) else it }, 0)
        assertEquals(9, temperatureNumbers(home, forecast, today).single { it.kind == NumberKind.LOW }.hour)
        assertFalse(temperatureNumbers(home.copy(start = 9), forecast, today).any { it.kind == NumberKind.LOW })
    }
    @Test fun missingEndOrInteriorMeansNoForecast() {
        assertTrue(temperatureNumbers(home, Forecast(hours.filter { it.time != today.plusDays(1).atStartOfDay() }, 0), today).isEmpty())
        assertTrue(temperatureNumbers(home, Forecast(hours.filter { it.time != today.atTime(10, 0) }, 0), today).isEmpty())
        assertTrue(temperatureNumbers(home, Forecast(hours, 0), today.plusDays(3)).isEmpty())
    }
    @Test fun priorityDropsOverlapsAndPreservesHourPlacement() {
        val numbers = listOf(
            TemperatureNumber(10, 30, NumberKind.END), TemperatureNumber(10, 40, NumberKind.START),
            TemperatureNumber(10, 20, NumberKind.LOW), TemperatureNumber(10, 50, NumberKind.HIGH),
            TemperatureNumber(20, 60, NumberKind.END),
        )
        val result = placeNumbers(numbers, Axis(0, 24), 0f, 240f, 4f) { 20f }
        assertEquals(listOf(NumberKind.HIGH, NumberKind.END), result.map { it.number.kind })
        assertEquals(100f, result.first().x, .001f)
    }
    @Test fun overlapUsesMeasuredWidthsAndGap() {
        val numbers = listOf(TemperatureNumber(10, -100, NumberKind.START), TemperatureNumber(12, 5, NumberKind.LOW))
        assertEquals(listOf(NumberKind.LOW), placeNumbers(numbers, Axis(0, 24), 0f, 240f, 5f) { it.length * 10f }.map { it.number.kind })
        assertEquals(2, placeNumbers(numbers, Axis(0, 24), 0f, 480f, 5f) { it.length * 10f }.size)
    }
    @Test fun lowBeatsStartAndStartBeatsEnd() {
        val same = listOf(TemperatureNumber(8, 1, NumberKind.END), TemperatureNumber(8, 2, NumberKind.START), TemperatureNumber(8, 3, NumberKind.LOW))
        assertEquals(NumberKind.LOW, placeNumbers(same, Axis(0, 24), 0f, 240f, 3f) { 10f }.single().number.kind)
        assertEquals(NumberKind.START, placeNumbers(same.take(2), Axis(0, 24), 0f, 240f, 3f) { 10f }.single().number.kind)
    }
    @Test fun nowHasMinutePrecisionAndIsAbsentOutsideAxis() {
        val axis = Axis(6, 24)
        assertEquals(65f, axis.nowX(12.5, 0f, 180f)!!, .001f)
        assertNull(axis.nowX(5.99, 0f, 180f))
        assertNull(axis.nowX(24.01, 0f, 180f))
    }
    @Test fun severityIncludesAllCodeGroups() {
        for (code in 95..99) assertEquals(Condition.THUNDERSTORM, condition(code, 90.0, true))
        for (code in (71..77) + (85..86)) assertEquals(Condition.SNOW, condition(code, 90.0, true))
        for (code in (51..67) + (80..82)) assertEquals(Condition.RAIN, condition(code, 90.0, true))
        for (code in listOf(45, 48)) assertEquals(Condition.FOG, condition(code, 90.0, true))
        assertEquals(Condition.CLOUDS, condition(3, 0.0, true))
        assertEquals(Condition.CLEAR, condition(1, 0.0, true))
        assertTrue(Condition.THUNDERSTORM > Condition.SNOW && Condition.SNOW > Condition.RAIN &&
            Condition.RAIN > Condition.FOG && Condition.FOG > Condition.WIND && Condition.WIND > Condition.CLOUDS)
    }
    @Test fun windThresholdBothUnits() {
        assertEquals(Condition.CLEAR, condition(0, 24.99, true))
        assertEquals(Condition.WIND, condition(0, 25.0, true))
        assertEquals(Condition.CLOUDS, condition(2, 39.99, false))
        assertEquals(Condition.WIND, condition(2, 40.0, false))
    }
    @Test fun conditionUsesOnlyTodaysSpan() {
        val forecast = Forecast(hours.map { it.copy(code = if (it.time.hour == 3) 99 else 0) }, 0)
        assertTrue(spanHours(home, forecast, today).all { it.code == 0 && it.time.toLocalDate() == today })
    }
    @Test fun staleIsStrictlyOverTwelveHours() {
        val twelveHours = 12 * 60 * 60 * 1000L
        assertFalse(isStale(100, 100 + twelveHours))
        assertTrue(isStale(100, 101 + twelveHours))
        assertFalse(isStale(100, 99))
    }
    @Test fun configRoundTripWithEscapesAndUnits() {
        val config = WidgetConfig(listOf(home.copy(label = "A\"\\\n", place = "日本\t\u0001"), home.copy(label = "School", start = 0, end = 12)), false)
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config)))
        assertEquals(WidgetConfig(emptyList()), ConfigJson.decode(ConfigJson.encode(WidgetConfig(emptyList()))))
    }
    @Test fun labelLimitCountsUnicodeCharacters() {
        assertEquals("Portola ", shortLabel("Portola Valley"))
        assertEquals("😀1234567", shortLabel("😀123456789"))
        assertEquals("Home", shortLabel("Home"))
        assertThrows(IllegalArgumentException::class.java) { home.copy(label = "123456789") }
    }
    @Test fun validatesSpanLocationCountAndCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { home.copy(start = 12, end = 12) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(end = 25) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(latitude = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { WidgetConfig(List(4) { home }) }
        assertEquals("37.3800,-122.2300", home.coordinateKey)
    }
}
