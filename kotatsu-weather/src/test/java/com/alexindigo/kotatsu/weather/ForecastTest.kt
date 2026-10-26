package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ForecastTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val start = Instant.parse("2026-11-01T07:00:00Z")
    private val location = Location("Home", "Fixture", 37.0, -122.0, 1, 23)
    private fun payload(
        times: List<Any?> = (0..25).map { start.epochSecond + it * 3600 },
        temperatures: List<Any?> = times.indices.map { it * 10.0 },
        codes: List<Any?> = times.map { 0 }, gusts: List<Any?> = times.map { 0 }, offset: Int = -25200,
    ): String {
        fun array(values: List<Any?>) = values.joinToString(",", "[", "]") { if (it is String) "\"$it\"" else "$it" }
        return """{"utc_offset_seconds":$offset,"hourly":{"time":${array(times)},"temperature_2m":${array(temperatures)},"weather_code":${array(codes)},"wind_gusts_10m":${array(gusts)}}}"""
    }
    private fun valid(json: String) = (OpenMeteoParser.parse(json) as Parsed.Valid).series

    @Test fun fieldsAreIndependentlyNullableAndTemperatureIsRequired() {
        val samples = valid(payload(temperatures = listOf(null, 12, 13), times = listOf(start.epochSecond, start.epochSecond + 3600, start.epochSecond + 7200),
            codes = listOf(0, null, 2), gusts = listOf(null, 90, null))).samples
        assertEquals(2, samples.size)
        assertEquals(12.0, samples[0].temperature, 0.0)
        assertNull(samples[0].code)
        assertEquals(90.0, samples[0].gust!!, 0.0)
        assertNull(samples[1].gust)
        assertEquals(Instant.EPOCH, valid(payload()).fetchedAt)
    }

    @Test fun everyArrayLengthMustAgreeEvenWithExtraElements() {
        for (json in listOf(payload(temperatures = listOf(1)), payload(codes = (0..26).toList()), payload(gusts = emptyList()))) {
            assertTrue(json, OpenMeteoParser.parse(json) is Parsed.Malformed)
        }
    }

    @Test fun rejectsMissingHourlyBadJsonInvalidFieldsAndNonHourlyTimes() {
        val t = start.epochSecond
        for (json in listOf("{}", "null", "{", payload().dropLast(1), payload() + "x",
            payload(times = listOf(t, t)), payload(times = listOf(t, t - 3600)), payload(times = listOf(t, t + 60)),
            payload(times = listOf(t + 1)), payload(times = listOf(null)), payload(times = listOf("2026-11-01T00:00")),
            payload(times = listOf(t + 0.5)), payload(codes = List(26) { 1.5 }), payload(gusts = List(26) { "wind" }),
            payload(temperatures = List(26) { true }))) {
            assertTrue(json, OpenMeteoParser.parse(json) is Parsed.Malformed)
        }
        assertTrue(OpenMeteoParser.parse(payload(times = emptyList())) is Parsed.Valid)
    }

    @Test fun unixTimesAlsoSupportFractionalOffsetDeviceZones() {
        val first = Instant.parse("2026-11-01T18:15:00Z").epochSecond
        assertEquals(first, valid(payload(times = listOf(first, first + 3600), offset = 20700)).samples.first().instant.epochSecond)
    }

    @Test fun legacyConversionUsesFixedResponseOffsetNotZoneRules() {
        val legacy = payload(times = listOf("2026-11-01T00:00", "2026-11-01T01:00", "2026-11-01T02:00", "2026-11-01T03:00"))
        val series = (OpenMeteoParser.parseLegacy(legacy) as Parsed.Valid).series
        assertEquals(listOf(0, 1, 1, 2), series.samples.map { it.instant.atZone(zone).hour })
        assertEquals(start.plusSeconds(3 * 3600), series.samples.last().instant)
        assertTrue(OpenMeteoParser.parseLegacy(legacy.replace("\"utc_offset_seconds\":-25200,", "")) is Parsed.Malformed)
        assertTrue(OpenMeteoParser.parseLegacy(legacy.replace("T01:00", "T00:00")) is Parsed.Malformed)
    }

    @Test fun fallbackKeepsBothOneOClockInstantsAndAveragesFourActualHours() {
        val series = valid(payload())
        val day = DayWindow.of(LocalDate.of(2026, 11, 1), zone)
        assertEquals(26, day.instants.size) // 25 real hours, plus the next midnight.
        assertEquals(listOf(0, 1, 1, 2, 3), series.samples.take(5).map { it.instant.atZone(zone).hour })
        assertEquals(4, day.block(1).instants.size)
        assertEquals(15.0, markTemperature(series.samples.associateBy { it.instant }, day, 1)!!, 0.0)
        val numbers = temperatureNumbers(location, series, day)
        assertEquals(15, numbers.single { it.kind == NumberKind.START }.value)
        assertEquals(240, numbers.single { it.kind == NumberKind.END }.value)
        assertEquals(240, numbers.single { it.kind == NumberKind.HIGH }.value)
    }

    @Test fun springGapDoesNotInventAnHourButMissingRealSampleEmptiesTheRow() {
        val day = DayWindow.of(LocalDate.of(2026, 3, 8), zone)
        val series = ForecastSeries(day.instants.mapIndexed { i, instant -> HourSample(instant, i * 10.0, 0, null) }, Instant.EPOCH)
        val byTime = series.samples.associateBy { it.instant }
        assertEquals(24, day.instants.size)
        assertEquals(2, day.block(1).instants.size)
        assertEquals(5.0, markTemperature(byTime, day, 1)!!, 0.0)
        assertEquals(25.0, markTemperature(byTime, day, 3)!!, 0.0)
        assertFalse(temperatureNumbers(location, series, day).isEmpty())
        assertTrue(temperatureNumbers(location, series.copy(samples = series.samples.drop(1)), day).isEmpty())
    }

    @Test fun threeRequestedDaysIncludeTomorrowWindowHaloAfterSaturdayEveningFetch() {
        val saturday = LocalDate.of(2026, 10, 17)
        val evening = saturday.atTime(23, 50).atZone(zone)
        assertEquals(3, DayWindow.forecastDays)
        val first = saturday.atStartOfDay(zone).toInstant()
        val series = ForecastSeries((0 until DayWindow.forecastDays * 24).map {
            HourSample(first.plusSeconds(it * 3600L), 60.0, 0, 0.0)
        }, evening.toInstant())
        for (time in listOf(saturday.plusDays(1).atTime(0, 5), saturday.plusDays(1).atTime(23, 30))) {
            val day = DayWindow.of(time.toLocalDate(), zone)
            assertTrue(series.samples.map { it.instant }.containsAll(day.instants))
            assertFalse(temperatureNumbers(location.copy(start = 7), series, day).isEmpty())
            // Two calendar days stop at Sunday 23:00; its last block also needs Monday 00:00.
            assertTrue(temperatureNumbers(location.copy(start = 7), series.copy(samples = series.samples.take(48)), day).isEmpty())
        }
    }

    @Test fun conditionStillExcludesTheEndMarkAndHaloWhileNumbersUseThem() {
        val date = LocalDate.of(2026, 10, 17)
        val day = DayWindow.of(date, ZoneOffset.UTC)
        val row = location.copy(start = 7, end = 23)
        val samples = day.instants.map { HourSample(it, if (it.atZone(ZoneOffset.UTC).hour == 23) 90.0 else 60.0,
            if (it == date.atTime(23, 0).toInstant(ZoneOffset.UTC) || it == day.instants.last()) 99 else 0, 0.0) }
        val series = ForecastSeries(samples, Instant.EPOCH)
        assertEquals(70, temperatureNumbers(row, series, day).single { it.kind == NumberKind.END }.value)
        assertEquals(Condition.CLEAR, spanHours(row, series, day).mapNotNull { condition(it.code, it.gust, true) }.maxOrNull())
        assertEquals((7 until 23).toList(), spanHours(row, series, day).map { it.instant.atZone(ZoneOffset.UTC).hour })
    }

    @Test fun usefulnessUsesDeviceMidnightAcrossDstAndRetainsBetterTemperatureCoverage() {
        val series = valid(payload())
        val day = DayWindow.of(LocalDate.of(2026, 11, 1), zone)
        assertTrue(shouldReplaceForecast(series, series, day))
        assertFalse(shouldReplaceForecast(series, series.copy(samples = series.samples.drop(1)), day))
        assertFalse(shouldReplaceForecast(series, series.copy(samples = emptyList()), day))
        assertTrue(shouldReplaceForecast(series.copy(samples = series.samples.take(1)), series, day))
    }
}
