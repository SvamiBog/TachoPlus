package com.example.domain.protocol

import com.example.data.model.DriverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class J1939Test {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun pgnIsExtractedFrom29BitIdentifier() {
        val frame = J1939.frameFromId(0x0CFE6C17, ByteArray(8))
        assertEquals(J1939.PGN_TCO1, frame.pgn)
        assertEquals(3, frame.priority)
        assertEquals(0x17, frame.sourceAddress)

        // PDU1 (PF < 240): the PS byte is a destination address and not part of the PGN.
        assertEquals(J1939.PGN_TP_CM, J1939.frameFromId(0x1CECFF00, ByteArray(8)).pgn)
    }

    @Test
    fun vehicleIdentificationIsNotTachograph() {
        // 0xFEEC is VI (VIN); TCO1 is 0xFE6C.
        val vin = "WDB9634031L894102*".toByteArray()
        val update = J1939.decode(J1939.frameFromId(0x18FEEC00, vin))
        assertEquals("WDB9634031L894102", update?.vin)
        assertNull(update?.driver1Activity)
    }

    @Test
    fun tco1IsDecodedPerJ1939_71() {
        // byte1: driver1 = drive (3), driver2 = available (1), motion = detected (01)
        // byte2: time state 1, card driver1 present (01), no overspeed
        // byte3: driver2 card not present
        // bytes 7-8: 82 km/h × 256 = 0x5200
        val data = bytes(0x03 or (0x01 shl 3) or (0x01 shl 6), 0x11, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x52)
        val update = J1939.decodeTco1(J1939.frameFromId(0x0CFE6C00, data))!!
        assertEquals(DriverActivity.DRIVING, update.driver1Activity)
        assertEquals(DriverActivity.AVAILABLE, update.driver2Activity)
        assertEquals(true, update.vehicleMotion)
        assertEquals(true, update.driver1CardPresent)
        assertEquals(false, update.driver2CardPresent)
        assertEquals(false, update.overspeed)
        assertEquals(TimeRelatedState.BEFORE_4H30, update.driver1TimeState)
        assertEquals(82.0, update.tachographSpeedKmh!!, 0.001)
    }

    @Test
    fun tco1NotAvailableValuesAreIgnored() {
        val data = bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        val update = J1939.decodeTco1(J1939.frameFromId(0x0CFE6C00, data))!!
        assertNull(update.driver1Activity)
        assertNull(update.driver1CardPresent)
        assertNull(update.tachographSpeedKmh)
    }

    @Test
    fun speedRpmAndDistance() {
        val ccvs = J1939.decode(J1939.frameFromId(0x18FEF100, bytes(0xFF, 0x80, 0x50, 0, 0, 0, 0, 0)))
        assertEquals(0x5080 / 256.0, ccvs?.wheelSpeedKmh!!, 0.001)

        val eec1 = J1939.decode(J1939.frameFromId(0x0CF00400, bytes(0, 0, 0, 0x40, 0x2F, 0, 0, 0)))
        assertEquals(0x2F40 * 0.125, eec1?.engineRpm!!, 0.001)

        // VDHR: 5 m per bit → 56 838 000 × 0.005 = 284 190 km
        val vdhrRaw = 56_838_000L
        val vdhr = J1939.decode(
            J1939.frameFromId(
                0x18FEC100,
                bytes(
                    (vdhrRaw and 0xFF).toInt(), ((vdhrRaw shr 8) and 0xFF).toInt(),
                    ((vdhrRaw shr 16) and 0xFF).toInt(), ((vdhrRaw shr 24) and 0xFF).toInt(), 0, 0, 0, 0
                )
            )
        )
        assertEquals(284_190.0, vdhr?.odometerKm!!, 0.001)

        // VD: total distance is in bytes 5-8, 0.125 km per bit
        val vdRaw = 2_273_520L // 284 190 km
        val vd = J1939.decode(
            J1939.frameFromId(
                0x18FEE000,
                bytes(
                    1, 2, 3, 4,
                    (vdRaw and 0xFF).toInt(), ((vdRaw shr 8) and 0xFF).toInt(),
                    ((vdRaw shr 16) and 0xFF).toInt(), ((vdRaw shr 24) and 0xFF).toInt()
                )
            )
        )
        assertEquals(284_190.0, vd?.odometerKm!!, 0.001)
    }

    @Test
    fun broadcastMultiPacketDriverIdentificationIsReassembled() {
        val payload = "DF00000012345601*DE00000098765401*".toByteArray()
        val packets = (payload.size + 6) / 7
        val assembler = J1939TransportAssembler()
        val cm = J1939.frameFromId(
            0x1CECFF00,
            bytes(0x20, payload.size, 0, packets, 0xFF, 0x6B, 0xFE, 0x00)
        )
        assertNull(assembler.accept(cm))
        var result: J1939Frame? = null
        for (seq in 1..packets) {
            val chunk = ByteArray(8) { 0xFF.toByte() }
            chunk[0] = seq.toByte()
            for (i in 0 until 7) {
                val index = (seq - 1) * 7 + i
                if (index < payload.size) chunk[i + 1] = payload[index]
            }
            result = assembler.accept(J1939.frameFromId(0x1CEBFF00, chunk))
        }
        assertNotNull(result)
        assertEquals(J1939.PGN_DI, result!!.pgn)
        val update = J1939.decode(result)!!
        assertEquals("DF00000012345601", update.driver1Id)
        assertEquals("DE00000098765401", update.driver2Id)
    }

    @Test
    fun elmLineFormatsAreParsed() {
        val spaced = ElmResponses.parseCanLine("18 FE 6C 00 C3 41 00 C0 FF FF 00 50", expectExtended = true)!!
        assertEquals(0x18FE6C00L, spaced.id)
        assertEquals(8, spaced.data.size)

        val compact = ElmResponses.parseCanLine("18FE6C00C34100C0FFFF0050", expectExtended = true)!!
        assertEquals(0x18FE6C00L, compact.id)

        val formatted = ElmResponses.parseCanLine("6 FE6C 00 C3 41 00 C0 FF FF 00 50", expectExtended = true)!!
        assertEquals(J1939.PGN_TCO1, J1939.frameFromId(formatted.id, formatted.data).pgn)

        val obd = ElmResponses.parseCanLine("7E8 03 41 0D 52", expectExtended = false)!!
        assertFalse(obd.extended)
        assertEquals(0x7E8L, obd.id)

        assertNull(ElmResponses.parseCanLine("NO DATA", expectExtended = true))
        assertNull(ElmResponses.parseCanLine("SEARCHING...", expectExtended = true))
        assertNull(ElmResponses.parseCanLine("STOPPED", expectExtended = true))
    }

    @Test
    fun obdResponsesWithAndWithoutSpaces() {
        assertEquals(82.0, ObdDecoder.decodeMode01(ElmResponses.parseObdBytes("41 0D 52")!!, 0x0D)?.wheelSpeedKmh!!, 0.0)
        assertEquals(82.0, ObdDecoder.decodeMode01(ElmResponses.parseObdBytes("410D52")!!, 0x0D)?.wheelSpeedKmh!!, 0.0)
        assertEquals(1726.0, ObdDecoder.decodeMode01(ElmResponses.parseObdBytes("41 0C 1A F8")!!, 0x0C)?.engineRpm!!, 0.0)
        assertNull(ElmResponses.parseObdBytes("NO DATA"))

        val vin = ObdDecoder.decodeVin(
            listOf("014", "0: 49 02 01 57 44 42", "1: 39 36 33 34 30 33 31", "2: 4C 38 39 34 31 30 32")
        )
        assertEquals("WDB9634031L894102", vin)
    }

    @Test
    fun textGatewayKeysAreMatchedExactly() {
        val update = TextGatewayParser.parse("ACT=D;SPD=82;CARD1=DF00000012345601;NAME1=SCHMIDT HANS;ODO=284190.5")!!
        assertEquals(DriverActivity.DRIVING, update.driver1Activity)
        assertEquals(82.0, update.tachographSpeedKmh!!, 0.0)
        assertEquals(true, update.driver1CardPresent)
        assertEquals("DF00000012345601", update.driver1Id)
        assertEquals("SCHMIDT HANS", update.driver1Name)
        assertEquals(284_190.5, update.odometerKm!!, 0.0)

        // A timer-like key must not switch the activity, and a VIN is never taken for a card number.
        val other = TextGatewayParser.parse("BREAK=00:15;VIN=WDB9634031L894102")!!
        assertNull(other.driver1Activity)
        assertNull(other.driver1Id)
        assertEquals("WDB9634031L894102", other.vin)

        assertEquals(false, TextGatewayParser.parse("CARD1=NONE")?.driver1CardPresent)
        assertNull(TextGatewayParser.parse("hello world"))
    }

    @Test
    fun candumpLinesAreRecognised() {
        val compact = TextGatewayParser.parseCandump("18FE6C00#C34100C0FFFF0050")!!
        assertTrue(compact.extended)
        assertEquals(8, compact.data.size)

        val spaced = TextGatewayParser.parseCandump("can0  18FE6C00   [8]  C3 41 00 C0 FF FF 00 50")!!
        assertEquals(0x18FE6C00L, spaced.id)
        assertEquals(8, spaced.data.size)
    }
}
