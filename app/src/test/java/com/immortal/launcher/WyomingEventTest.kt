package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wyoming wire format: header line, optional extra data, optional payload. */
class WyomingEventTest {

  private fun roundTrip(e: WyomingEvent): WyomingEvent? {
    val out = ByteArrayOutputStream()
    e.write(out)
    return WyomingEvent.read(ByteArrayInputStream(out.toByteArray()))
  }

  @Test
  fun roundTrip_keepsTypeDataAndPayload() {
    val pcm = ByteArray(2048) { (it % 251).toByte() }
    val e = WyomingEvent("audio-chunk", WyomingEvent.audioFormat(16000).put("timestamp", 64), pcm)
    val back = roundTrip(e)!!
    assertEquals("audio-chunk", back.type)
    assertEquals(16000, back.data.getInt("rate"))
    assertEquals(64, back.data.getInt("timestamp"))
    assertArrayEquals(pcm, back.payload)
  }

  @Test
  fun roundTrip_eventWithoutDataOrPayload() {
    val back = roundTrip(WyomingEvent("run-satellite"))!!
    assertEquals("run-satellite", back.type)
    assertEquals(0, back.data.length())
    assertNull(back.payload)
  }

  @Test
  fun read_mergesSeparateDataBlockOverHeaderData() {
    // Python's wyoming writes data as a separate block after the header (data_length).
    val extra = """{"text":"turn on the kitchen"}""".toByteArray()
    val header =
        JSONObject()
            .put("type", "transcript")
            .put("data", JSONObject().put("language", "es"))
            .put("data_length", extra.size)
    val wire = (header.toString() + "\n").toByteArray() + extra
    val e = WyomingEvent.read(ByteArrayInputStream(wire))!!
    assertEquals("turn on the kitchen", e.data.getString("text"))
    assertEquals("es", e.data.getString("language"))
  }

  @Test
  fun read_consecutiveEventsFromOneStream() {
    val out = ByteArrayOutputStream()
    WyomingEvent("ping", JSONObject().put("text", "a")).write(out)
    WyomingEvent("audio-chunk", WyomingEvent.audioFormat(22050), ByteArray(10) { 7 }).write(out)
    WyomingEvent("audio-stop").write(out)
    val input = ByteArrayInputStream(out.toByteArray())
    assertEquals("ping", WyomingEvent.read(input)!!.type)
    assertEquals(10, WyomingEvent.read(input)!!.payload!!.size)
    assertEquals("audio-stop", WyomingEvent.read(input)!!.type)
    assertNull(WyomingEvent.read(input)) // clean end of stream
  }

  @Test
  fun read_truncatedPayloadThrows() {
    val wire = """{"type":"audio-chunk","payload_length":100}""" + "\n" + "short"
    val thrown = runCatching { WyomingEvent.read(ByteArrayInputStream(wire.toByteArray())) }
    assertTrue(thrown.exceptionOrNull() is IOException)
  }

  @Test
  fun read_garbageHeaderThrows() {
    val thrown = runCatching { WyomingEvent.read(ByteArrayInputStream("not json\n".toByteArray())) }
    assertTrue(thrown.exceptionOrNull() is IOException)
  }
}
