package com.example.domain.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItsProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun vuFrame(sid: Int, trtp: Int, data: ByteArray, target: Int = 0x01, cc: Int = 0xFF, cm: Int = 0xFF) =
        ItsFrame(target, Its.VU_ID, sid, trtp, cc, cm, data).encode()

    @Test
    fun requestDataIsEncodedPerTable2() {
        val frame = ItsFrame(Its.VU_ID, Its.DEFAULT_UNIT_ID, Its.SID_REQUEST_DATA, 0x02, 0xFF, 0xFF, ByteArray(0)).encode()
        // EE A0 00 08 02 FF FF + checksum (sum mod 256)
        val body = bytes(0xEE, 0xA0, 0x00, 0x08, 0x02, 0xFF, 0xFF)
        assertArrayEquals(body + Its.checksum(body, 0, body.size).toByte(), frame)
        assertEquals((0xEE + 0xA0 + 0x08 + 0x02 + 0xFF + 0xFF) and 0xFF, frame.last().toInt() and 0xFF)
    }

    @Test
    fun parserAcceptsAllLayoutsAndSplitChunks() {
        val parser = ItsFrameParser()
        val withCounters = vuFrame(Its.SID_SEND_ITS_ID, 0xFF, bytes(0x05))
        // Table 3 style without CC/CM
        val noCounters = run {
            val b = bytes(0x05, 0xEE, 0x01, Its.SID_PAIRING_RESULT, 0xFF, 0x01)
            b + Its.checksum(b, 0, b.size).toByte()
        }
        val stream = bytes(0x00, 0x13) + withCounters + noCounters // leading garbage
        val first = parser.feed(stream.copyOfRange(0, 6))
        val rest = parser.feed(stream.copyOfRange(6, stream.size))
        val frames = first + rest
        assertEquals(2, frames.size)
        assertEquals(Its.SID_SEND_ITS_ID, frames[0].sid)
        assertEquals(5, frames[0].data[0].toInt())
        assertEquals(Its.SID_PAIRING_RESULT, frames[1].sid)
        assertEquals(1, frames[1].data[0].toInt())
    }

    @Test
    fun splitMessagesAreReassembled() {
        val parser = ItsFrameParser()
        val part1 = vuFrame(Its.SID_REQUEST_ACCEPTED, 0x01, "WDB96340".toByteArray(), cc = 1, cm = 2)
        val part2 = vuFrame(Its.SID_REQUEST_ACCEPTED, 0x01, "31L894102".toByteArray(), cc = 2, cm = 2)
        assertTrue(parser.feed(part1).isEmpty())
        val frames = parser.feed(part2)
        assertEquals(1, frames.size)
        assertEquals("WDB9634031L894102", String(frames[0].data))
        assertEquals("WDB9634031L894102", ItsDataDecoder.decode(Its.DataType.STANDARD_TACH, frames[0].data)?.vin)
    }

    /** Vehicle unit that assigns ID 0x07, asks for PIN 1234 and then answers data requests. */
    private class FakeVu : ByteTransport {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        private val parser = ItsFrameParserForVu()
        var whitelisted = false
        val received = mutableListOf<ItsFrame>()
        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        override suspend fun write(data: ByteArray) {
            for (frame in parser.feed(data)) {
                received += frame
                when {
                    frame.source == Its.DEFAULT_UNIT_ID -> {
                        send(Its.SID_SEND_ITS_ID, 0xFF, byteArrayOf(0x07), target = Its.DEFAULT_UNIT_ID)
                        send(Its.SID_REQUEST_PIN, 0xFF, ByteArray(0))
                    }
                    frame.sid == Its.SID_SEND_PIN -> {
                        whitelisted = frame.data.contentEquals(byteArrayOf(1, 2, 3, 4))
                        send(Its.SID_PAIRING_RESULT, 0xFF, byteArrayOf(if (whitelisted) 1 else 0))
                    }
                    frame.sid == Its.SID_REQUEST_DATA && !whitelisted -> send(Its.SID_REQUEST_PIN, 0xFF, ByteArray(0))
                    frame.sid == Its.SID_REQUEST_DATA && frame.trtp == 0x01 ->
                        send(Its.SID_REQUEST_ACCEPTED, 0x01, byteArrayOf(0x30, 0x11) + "WDB9634031L894102".toByteArray() + byteArrayOf(0))
                    frame.sid == Its.SID_REQUEST_DATA -> send(Its.SID_DATA_UNAVAILABLE, frame.trtp, byteArrayOf(Its.CODE_PERSONAL_NOT_SHARED.toByte()))
                }
            }
        }

        private fun send(sid: Int, trtp: Int, data: ByteArray, target: Int = 0x07) {
            out.trySend(ItsFrame(target, Its.VU_ID, sid, trtp, 0xFF, 0xFF, data).encode())
        }

        override fun close() {
            out.close()
        }
    }

    /** Parses frames sent by the phone (SRC = phone, TGT = 0xEE). */
    private class ItsFrameParserForVu {
        fun feed(bytes: ByteArray): List<ItsFrame> {
            if (bytes.size < 8) return emptyList()
            val len = bytes[2].toInt() and 0xFF
            return listOf(
                ItsFrame(
                    bytes[0].toInt() and 0xFF, bytes[1].toInt() and 0xFF, bytes[3].toInt() and 0xFF, bytes[4].toInt() and 0xFF,
                    bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF, bytes.copyOfRange(7, 7 + len)
                )
            )
        }
    }

    @Test
    fun sessionGetsIdSendsPinAndReadsData() = runTest {
        val vu = FakeVu()
        val pins = Channel<String>(Channel.CONFLATED)
        val pinAsked = CompletableDeferred<Int>()
        val vin = CompletableDeferred<String>()
        val notices = mutableListOf<String>()
        val listener = object : TachoSession.Listener {
            override fun onUpdate(update: VehicleUpdate) {
                update.vin?.let { vin.complete(it) }
            }
            override fun onLog(line: String) = Unit
            override fun onModeResolved(mode: ProtocolMode, adapterInfo: String?) = Unit
            override fun onBytes(count: Int) = Unit
            override fun onPinRequired(failedAttempts: Int) {
                pinAsked.complete(failedAttempts)
            }
            override fun onNotice(message: String) {
                notices += message
            }
        }
        val job = launch { TachoSession(vu, ProtocolMode.ITS_TACHOGRAPH, listener, Channel(), pins).run() }

        assertEquals(0, withTimeout(60_000) { pinAsked.await() })
        pins.send("1234")
        assertEquals("WDB9634031L894102", withTimeout(60_000) { vin.await() })
        assertTrue(vu.whitelisted)
        // After the ID is assigned the phone uses it as its source address.
        assertTrue(vu.received.drop(1).all { it.source == 0x07 })
        assertTrue(notices.any { it.contains("PIN принят") })
        job.cancelAndJoin()
    }
}
