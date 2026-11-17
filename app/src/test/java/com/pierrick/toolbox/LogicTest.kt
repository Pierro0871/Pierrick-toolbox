package com.pierrick.toolbox

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class LogicTest {
    @Test fun gifWrite() {
        val dir = java.io.File(System.getProperty("gifdir") ?: "build/gif-test").apply { mkdirs() }
        for ((w, h) in listOf(1 to 1, 7 to 5, 64 to 48, 300 to 200)) {
            val bos = ByteArrayOutputStream()
            val enc = GifEncoder(bos, w, h, 10)
            val frames = List(3) { f -> ByteArray(w * h) { i -> if (f == 2) Random(i).nextInt(252).toByte() else ((i / 7 + f * 13) % 252).toByte() } }
            frames.forEach { enc.addIndexedFrame(it) }
            enc.finish()
            java.io.File(dir, "t_${w}x$h.gif").writeBytes(bos.toByteArray())
            java.io.File(dir, "t_${w}x$h.raw").writeBytes(frames.fold(ByteArray(0)) { acc, f -> acc + f })
        }
    }
    @Test fun easterDates() {
        assertEquals(LocalDate.of(2024, 3, 31), easter(2024)); assertEquals(LocalDate.of(2025, 4, 20), easter(2025)); assertEquals(LocalDate.of(2026, 4, 5), easter(2026))
        assertEquals(11, frenchHolidays(2025).size)
    }
    @Test fun sunParis() {
        val (rise, _, set) = sunTimes(LocalDate.of(2025, 6, 21), 48.8566, 2.3522)
        val z = ZoneId.of("Europe/Paris")
        fun t(j: Double) = java.time.Instant.ofEpochMilli(((j - 2440587.5) * 86400000).toLong()).atZone(z).toLocalTime()
        println("rise ${t(rise)} set ${t(set)}")
        assertTrue(t(rise).toString(), t(rise).hour == 5 && t(rise).minute in 40..55)
        assertTrue(t(set).toString(), t(set).hour == 21 && t(set).minute in 50..59)
    }
    @Test fun fractions() {
        assertEquals("17/12", (parseFrac("3/4")!! + parseFrac("2/3")!!).toString())
        assertEquals("3/2", parseFrac("1 1/2").toString()); assertEquals("3/4", parseFrac("0,75").toString())
        assertEquals("1 5/12", (parseFrac("3/4")!! + parseFrac("2/3")!!).mixed())
    }
    @Test fun sciParser() {
        assertEquals(1.0, SciParser("sin(90)", true).parse(), 1e-9)
        assertEquals(120.0, SciParser("5!", true).parse(), 1e-9)
        assertEquals(14.0, SciParser("2+3×4", true).parse(), 1e-9)
        assertEquals(512.0, SciParser("2^3^2", true).parse(), 1e-9)
        assertEquals(3.0, SciParser("√9", true).parse(), 1e-9)
        assertEquals(2.0, SciParser("log(100)", true).parse(), 1e-9)
        assertEquals(30.0, SciParser("sin⁻¹(0,5)", true).parse(), 1e-9)
    }
    @Test fun blocklist() {
        val s = setOf("youtube.com")
        assertTrue(BlockList.isBlocked("m.youtube.com", s)); assertTrue(BlockList.isBlocked("youtube.com.", s)); assertFalse(BlockList.isBlocked("notyoutube.com", s))
        assertEquals("youtube.com", BlockList.normalize("https://www.youtube.com/watch?v=1"))
    }
}
