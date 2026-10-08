package com.example.domain.protocol

import com.example.data.model.DriverActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class TachoSessionTest {

    /** Emulates an ELM327 v1.5 on a truck FMS bus that broadcasts TCO1 and CCVS. */
    private class FakeElm(private val supportsJ1939: Boolean = true) : ByteTransport {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        private val pending = StringBuilder()
        val commands = mutableListOf<String>()

        override val incoming: Flow<ByteArray> = out.receiveAsFlow()

        override suspend fun write(data: ByteArray) {
            pending.append(String(data, Charsets.US_ASCII))
            while (true) {
                val index = pending.indexOf("\r")
                if (index < 0) break
                val cmd = pending.substring(0, index).trim().uppercase()
                pending.delete(0, index + 1)
                if (cmd.isNotEmpty()) {
                    commands += cmd
                    out.trySend(respond(cmd).toByteArray(Charsets.US_ASCII))
                }
            }
        }

        private fun respond(cmd: String): String = when {
            cmd == "ATZ" -> "\r\rELM327 v1.5\r\r>"
            cmd == "ATI" -> "ELM327 v1.5\r\r>"
            cmd == "STI" -> "?\r\r>"
            cmd == "ATSP A" -> if (supportsJ1939) "OK\r\r>" else "?\r\r>"
            cmd.startsWith("AT") && !cmd.startsWith("ATMP") -> "OK\r\r>"
            cmd == "ATMP FE6C 1" -> "18 FE 6C 00 4B 11 00 00 FF FF 00 52\r\r>"
            cmd == "ATMP FEF1 1" -> "18 FE F1 00 FF 00 52 00 00 00 00 00\r\r>"
            cmd == "0100" -> "41 00 BE 3E B8 11\r\r>"
            cmd == "010D" -> "41 0D 32\r\r>"
            else -> "NO DATA\r\r>"
        }

        fun disconnect() = out.close()

        override fun close() {
            out.close()
        }
    }

    private class Recorder : TachoSession.Listener {
        val updates = mutableListOf<VehicleUpdate>()
        var mode: ProtocolMode? = null
        var adapter: String? = null
        val tco1 = CompletableDeferred<VehicleUpdate>()
        val anyUpdate = CompletableDeferred<VehicleUpdate>()

        override fun onUpdate(update: VehicleUpdate) {
            updates += update
            anyUpdate.complete(update)
            if (update.driver1Activity != null) tco1.complete(update)
        }

        override fun onLog(line: String) = Unit

        override fun onModeResolved(mode: ProtocolMode, adapterInfo: String?) {
            this.mode = mode
            adapter = adapterInfo
        }

        override fun onBytes(count: Int) = Unit
    }

    @Test
    fun autoDetectsElmJ1939AndDecodesTachographData() = runTest {
        val elm = FakeElm()
        val recorder = Recorder()
        val job = launch { TachoSession(elm, ProtocolMode.AUTO, recorder, Channel()).run() }

        val update = withTimeout(60_000) { recorder.tco1.await() }
        assertEquals(DriverActivity.DRIVING, update.driver1Activity)
        assertEquals(true, update.driver1CardPresent)
        assertEquals(82.0, update.tachographSpeedKmh!!, 0.001)
        withTimeout(60_000) { while (recorder.mode == null) kotlinx.coroutines.yield() }
        assertEquals(ProtocolMode.ELM_J1939, recorder.mode)
        assertEquals("ELM327 v1.5", recorder.adapter)
        assertTrue("monitor commands use the J1939 PGN of TCO1", elm.commands.contains("ATMP FE6C 1"))
        job.cancelAndJoin()
    }

    @Test
    fun fallsBackToObdWhenJ1939IsNotSupported() = runTest {
        val elm = FakeElm(supportsJ1939 = false)
        val recorder = Recorder()
        val job = launch { TachoSession(elm, ProtocolMode.AUTO, recorder, Channel()).run() }

        val update = withTimeout(60_000) { recorder.anyUpdate.await() }
        assertEquals(50.0, update.wheelSpeedKmh!!, 0.0)
        assertEquals(ProtocolMode.ELM_OBD2, recorder.mode)
        job.cancelAndJoin()
    }

    @Test
    fun deviceWithoutPromptIsReadAsTextGateway() = runTest {
        val out = Channel<ByteArray>(Channel.UNLIMITED)
        val transport = object : ByteTransport {
            override val incoming: Flow<ByteArray> = out.receiveAsFlow()
            override suspend fun write(data: ByteArray) = Unit
            override fun close() {
                out.close()
            }
        }
        val recorder = Recorder()
        val job = launch { TachoSession(transport, ProtocolMode.AUTO, recorder, Channel()).run() }
        out.send("ACT=R;SPD=0;CARD1=DF00000012345601\r\n".toByteArray())

        val update = withTimeout(60_000) { recorder.tco1.await() }
        assertEquals(DriverActivity.REST, update.driver1Activity)
        assertEquals(ProtocolMode.TEXT_GATEWAY, recorder.mode)
        job.cancelAndJoin()
    }

    @Test
    fun sessionEndsWithErrorWhenTheLinkCloses() = runTest {
        val elm = FakeElm()
        val recorder = Recorder()
        val result = async { runCatching { TachoSession(elm, ProtocolMode.ELM_J1939, recorder, Channel()).run() } }
        withTimeout(60_000) { recorder.tco1.await() }
        elm.disconnect()
        val error = result.await().exceptionOrNull()
        assertTrue("expected EOF, got $error", error is EOFException)
    }

    @Test
    fun userCommandsAreSentBetweenPolls() = runTest {
        val elm = FakeElm()
        val recorder = Recorder()
        val commands = Channel<String>(Channel.UNLIMITED)
        val job = launch { TachoSession(elm, ProtocolMode.ELM_J1939, recorder, commands).run() }
        withTimeout(60_000) { recorder.tco1.await() }
        commands.send("ATRV")
        withTimeout(60_000) { while ("ATRV" !in elm.commands) kotlinx.coroutines.yield() }
        job.cancelAndJoin()
    }
}
