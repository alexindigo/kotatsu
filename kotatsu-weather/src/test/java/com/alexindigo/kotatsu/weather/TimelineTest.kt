package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 16)
    private val home = Location("Home", "Portola Valley", 37.38, -122.23)
    // Base 60; mark blocks (h-1, h, h+1): 1 → 50, 9 → 55, 13 → 70, 23 → 57, every other mark 60.
    private val hours = (0..47).map { hour ->
        Hour(today.atStartOfDay().plusHours(hour.toLong()), when (hour) {
            0 -> 30.0
            9 -> 45.0
            13 -> 90.0
            24 -> 51.0
            else -> 60.0
        }, 0, 0.0)
    }

    @Test fun axisIsUnionOfSpans() {
        assertEquals(Axis(3, 23), sharedAxis(listOf(home, home.copy(start = 3, end = 17))))
    }
    @Test fun markValuesAverageTheHourBeforeTheMarkAndTheHourAfter() {
        val byTime = hours.associateBy { it.time }
        assertEquals(50.0, markTemperature(byTime, today, 1)!!, 1e-9)
        assertEquals(55.0, markTemperature(byTime, today, 9)!!, 1e-9)
        // Hour 24 is the next day's 00:00.
        assertEquals(57.0, markTemperature(byTime, today, 23)!!, 1e-9)
        assertNull(markTemperature(byTime - today.atTime(10, 0), today, 11))
    }
    @Test fun numbersComeFromMarkAverages() {
        val numbers = temperatureNumbers(home, Forecast(hours, 1), today)
        assertEquals(TemperatureNumber(7, 60, NumberKind.START), numbers.single { it.kind == NumberKind.START })
        assertEquals(TemperatureNumber(23, 57, NumberKind.END), numbers.single { it.kind == NumberKind.END })
        assertEquals(TemperatureNumber(13, 70, NumberKind.HIGH), numbers.single { it.kind == NumberKind.HIGH })
        // The day's lowest block is the 01:00 mark, outside this span, so it is not inserted.
        assertFalse(numbers.any { it.kind == NumberKind.LOW })
    }
    @Test fun lowStrictlyInsideSpan() {
        val forecast = Forecast(hours.map { if (it.time.hour == 0) it.copy(temperature = 60.0) else it }, 0)
        assertEquals(TemperatureNumber(9, 55, NumberKind.LOW), temperatureNumbers(home, forecast, today).single { it.kind == NumberKind.LOW })
        assertFalse(temperatureNumbers(home.copy(start = 9), forecast, today).any { it.kind == NumberKind.LOW })
    }
    @Test fun missingHourOfAnySpanBlockMeansNoForecast() {
        for (missing in listOf(today.plusDays(1).atStartOfDay(), today.atTime(10, 0), today.atTime(6, 0))) {
            assertTrue(temperatureNumbers(home, Forecast(hours.filter { it.time != missing }, 0), today).isEmpty())
        }
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
        val axis = Axis(7, 23)
        assertEquals(55f, axis.nowX(12.5, 0f, 160f)!!, .001f)
        assertNull(axis.nowX(6.99, 0f, 160f))
        assertNull(axis.nowX(23.01, 0f, 160f))
    }
    @Test fun everyMarkIsADotExceptShownNumbers() {
        val axis = Axis(7, 23)
        val placed = placeNumbers(listOf(TemperatureNumber(7, 60, NumberKind.START),
            TemperatureNumber(13, 70, NumberKind.HIGH), TemperatureNumber(23, 57, NumberKind.END)), axis, 0f, 160f, 2f) { 12f }
        assertEquals(listOf(9, 11, 15, 17, 19, 21), hourDots(axis, placed))
        // A row with a shorter span still dots the whole shared axis.
        assertEquals(listOf(3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23), hourDots(Axis(3, 23), emptyList()))
    }
    @Test fun numbersOnNeighbouringMarksKeepTheirGap() {
        val step = 30f; val gap = 2f
        assertEquals(step, maxNumberWidth(step, gap) + gap, .001f)
        assertEquals(0f, maxNumberWidth(1f, 2f), 0f)
    }
    @Test fun rowsStackFromTheBottomWithEqualGaps() {
        // Row height 10, gap 4: the last row's bottom is the bottom edge, each row above is 14 higher.
        assertEquals(listOf(72f, 86f, 100f), stackedRowBottoms(3, 10f, 4f, 100f))
        assertEquals(listOf(100f), stackedRowBottoms(1, 10f, 4f, 100f))
        assertEquals(38f, stackHeight(3, 10f, 4f), 0f)
        assertEquals(10f, stackHeight(1, 10f, 4f), 0f)
    }
    @Test fun fourLinesUseCondensedSpacing() {
        assertEquals(24f, rowGapDp(1), 0f)
        assertEquals(24f, rowGapDp(3), 0f)
        assertEquals(12f, rowGapDp(4), 0f)
    }
    @Test fun currentLocationIsRoundedToAbout100Metres() {
        val here = currentLocation(37.3841127, -122.2352488)
        assertEquals("Here", here.label)
        assertEquals(37.384, here.latitude, 0.0)
        assertEquals(-122.235, here.longitude, 0.0)
        assertEquals("Current location, 37.384, -122.235", here.place)
        assertEquals(7, here.start); assertEquals(23, here.end)
    }
    @Test fun largestFittingSizeIsSolvedNotGuessed() {
        assertEquals(20f, largestFitting(20f, 1f) { true }, 0f)
        assertEquals(13.5f, largestFitting(20f, 1f) { it <= 13.5f }, .01f)
        assertEquals(1f, largestFitting(20f, 1f) { false }, 0f)
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
    @Test fun missingConditionsAreIgnoredAndMissingGustIsNotWindy() {
        assertNull(condition(null, 90.0, true))
        assertEquals(Condition.CLEAR, condition(0, null, true))
        assertEquals(Condition.CLOUDS, condition(2, null, false))
        assertEquals(Condition.RAIN, condition(61, null, true))
        val samples = listOf(hours[7].copy(code = null, gust = 90.0), hours[8].copy(code = 2, gust = null))
        assertEquals(Condition.CLOUDS, samples.mapNotNull { condition(it.code, it.gust, true) }.maxOrNull())
        assertNull(samples.take(1).mapNotNull { condition(it.code, it.gust, true) }.maxOrNull())
    }
    @Test fun cacheReplacementRequiresPositiveNonDecreasingUsefulnessUnlessAbsent() {
        val good = Forecast(hours, 1)
        val fewer = good.copy(hours = hours.take(24))
        val zero = good.copy(hours = emptyList())
        assertTrue(shouldReplaceForecast(null, good, today))
        assertTrue(shouldReplaceForecast(null, zero, today))
        assertTrue(shouldReplaceForecast(fewer, good, today))
        assertTrue(shouldReplaceForecast(good, good.copy(fetchedAt = 2), today))
        assertFalse(shouldReplaceForecast(good, fewer, today))
        assertFalse(shouldReplaceForecast(good, zero, today))
        assertFalse(shouldReplaceForecast(zero, zero, today))
    }
    @Test fun usefulnessStartsAtLocalMidnightNotNowOrFetchTime() {
        val old = Forecast(listOf(hours[0], hours[1]), 999)
        val past = hours[0].copy(time = today.minusDays(1).atTime(23, 0))
        assertFalse(shouldReplaceForecast(old, Forecast(listOf(past, hours[0]), 1000), today))
        assertTrue(shouldReplaceForecast(old, Forecast(listOf(past, hours[0], hours[24]), 0), today))
        assertFalse(shouldReplaceForecast(old, old, today.plusDays(1)))
        assertTrue(shouldReplaceForecast(old, Forecast(listOf(hours[24]), 0), today.plusDays(1)))
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
        val config = WidgetConfig(listOf(home.copy(label = "A\"\\\n", place = "日本\t\u0001"), home.copy(label = "School", start = 1, end = 11, id = "school")), false)
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
        assertEquals(7, home.start); assertEquals(23, home.end)
        // Spans start and end on marks: odd hours from 1 to 23, start before end.
        for ((start, end) in listOf(13 to 13, 15 to 13, 6 to 23, 7 to 24, 0 to 23, 7 to 25, -1 to 23)) {
            assertThrows("$start–$end", IllegalArgumentException::class.java) { home.copy(start = start, end = end) }
        }
        home.copy(start = 1, end = 3); home.copy(start = 21, end = 23)
        assertThrows(IllegalArgumentException::class.java) { home.copy(latitude = Double.NaN) }
        WidgetConfig(List(4) { home })
        assertThrows(IllegalArgumentException::class.java) { WidgetConfig(List(5) { home }) }
        assertEquals("37.3800,-122.2300", home.coordinateKey)
    }
}
