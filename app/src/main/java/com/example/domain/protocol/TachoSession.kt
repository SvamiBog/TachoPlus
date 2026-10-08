package com.example.domain.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** A bidirectional byte link (Bluetooth SPP, BLE UART, or a fake in tests). */
interface ByteTransport {
    /** Received chunks. Completes when the peer closes the link and throws on I/O errors. */
    val incoming: Flow<ByteArray>

    suspend fun write(data: ByteArray)

    fun close()
}

class ProtocolException(message: String) : IOException(message)

sealed interface AdapterLine {
    data class Text(val text: String) : AdapterLine
    data object Prompt : AdapterLine
}

/** Splits a byte stream into lines on CR/LF and reports the ELM327 '>' prompt separately. */
class LineSplitter(private val maxLineBytes: Int = 1024) {
    private val buffer = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, emit: (AdapterLine) -> Unit) {
        for (b in bytes) {
            when (b.toInt()) {
                '\r'.code, '\n'.code -> flush(emit)
                '>'.code -> {
                    flush(emit)
                    emit(AdapterLine.Prompt)
                }
                0 -> Unit
                else -> {
                    buffer.write(b.toInt())
                    if (buffer.size() >= maxLineBytes) flush(emit)
                }
            }
        }
    }

    private fun flush(emit: (AdapterLine) -> Unit) {
        if (buffer.size() == 0) return
        val text = buffer.toString(Charsets.UTF_8.name()).trim()
        buffer.reset()
        if (text.isNotEmpty()) emit(AdapterLine.Text(text))
    }
}

data class ElmReply(val lines: List<String>, val prompt: Boolean) {
    val rejected: Boolean get() = lines.any { it.trim() == "?" }
    val ok: Boolean get() = prompt && !rejected
}

/** Command/response client for ELM327-compatible adapters (ELM327, STN11xx/STN2xxx, OBDLink). */
class ElmClient(
    private val lines: ReceiveChannel<AdapterLine>,
    private val writer: suspend (ByteArray) -> Unit,
    private val log: (String) -> Unit
) {
    private fun drainStale() {
        while (true) {
            val result = lines.tryReceive()
            if (result.isClosed) throw result.exceptionOrNull() ?: EOFException("Соединение закрыто")
            val line = result.getOrNull() ?: return
            if (line is AdapterLine.Text) log("RX ${line.text}")
        }
    }

    private suspend fun send(cmd: String) {
        drainStale()
        log("TX $cmd")
        writer((cmd + "\r").toByteArray(Charsets.US_ASCII))
    }

    private suspend fun collect(timeoutMs: Long, echo: String?, maxFrames: Int?): Pair<List<String>, Boolean> {
        val out = ArrayList<String>()
        var frames = 0
        val prompt = withTimeoutOrNull(timeoutMs) {
            while (true) {
                when (val line = lines.receive()) {
                    AdapterLine.Prompt -> return@withTimeoutOrNull true
                    is AdapterLine.Text -> {
                        if (echo != null && line.text.equals(echo, ignoreCase = true)) continue
                        out += line.text
                        log("RX ${line.text}")
                        if (maxFrames != null && !ElmResponses.isStatusLine(line.text)) {
                            frames++
                            if (frames >= maxFrames) return@withTimeoutOrNull false
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        return out to prompt
    }

    suspend fun command(cmd: String, timeoutMs: Long = 2_000): ElmReply {
        send(cmd)
        val (out, prompt) = collect(timeoutMs, cmd, null)
        return ElmReply(out, prompt)
    }

    /**
     * Runs a monitor command (ATMP/ATMA) until [maxFrames] frames arrive, the adapter returns to the prompt, or
     * [timeoutMs] passes. Monitoring is stopped by sending a character, as the ELM327 datasheet describes.
     */
    suspend fun monitor(cmd: String, timeoutMs: Long, maxFrames: Int): ElmReply {
        send(cmd)
        val (out, prompt) = collect(timeoutMs, cmd, maxFrames)
        if (prompt) return ElmReply(out, true)
        // Adapters that honour the message count ("ATMP hhhh 1") return to the prompt on their own. A bare CR
        // sent at the prompt would repeat the last command, so only interrupt when monitoring is still running.
        val (after, promptAfter) = collect(300, null, null)
        if (promptAfter) return ElmReply(out + after, true)
        writer("\r".toByteArray(Charsets.US_ASCII))
        val (rest, _) = collect(1_500, null, null)
        return ElmReply(out + after + rest, true)
    }
}

/**
 * Talks to a Bluetooth adapter over [transport]: detects an ELM327/STN adapter, configures it for J1939 (FMS)
 * or OBD-II, and polls the messages that carry tachograph and vehicle data. Without an ELM327 the stream is
 * read as the text gateway protocol. Runs until the link fails; throws on disconnect.
 */
class TachoSession(
    private val transport: ByteTransport,
    private val requestedMode: ProtocolMode,
    private val listener: Listener,
    private val userCommands: ReceiveChannel<String>
) {
    interface Listener {
        fun onUpdate(update: VehicleUpdate)
        fun onLog(line: String)
        fun onModeResolved(mode: ProtocolMode, adapterInfo: String?)
        fun onBytes(count: Int)
    }

    private val assembler = J1939TransportAssembler()
    private var monitorWithCount: Boolean? = null

    suspend fun run() = coroutineScope {
        val lines = Channel<AdapterLine>(Channel.UNLIMITED)
        val splitter = LineSplitter()
        val reader = launch {
            try {
                transport.incoming.collect { chunk ->
                    listener.onBytes(chunk.size)
                    splitter.feed(chunk) { lines.trySend(it) }
                }
                lines.close(EOFException("Соединение закрыто устройством"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lines.close(e)
            }
        }
        try {
            runProtocol(lines)
        } finally {
            reader.cancel()
        }
    }

    private suspend fun runProtocol(lines: Channel<AdapterLine>) {
        if (requestedMode == ProtocolMode.TEXT_GATEWAY) {
            runTextGateway(lines, emptyList())
            return
        }
        val elm = ElmClient(lines, transport::write, listener::onLog)
        val reset = elm.command("ATZ", 4_000)
        val looksLikeElm = reset.prompt || reset.lines.any {
            val u = it.uppercase()
            u.contains("ELM") || u.contains("STN") || u.contains("OBD")
        }
        if (!looksLikeElm) {
            if (requestedMode == ProtocolMode.AUTO) {
                listener.onLog("[SYS] Нет ответа ELM327 — читаю поток как текстовый шлюз")
                runTextGateway(lines, reset.lines)
                return
            }
            throw ProtocolException("Адаптер не отвечает как ELM327 (нет приглашения «>»)")
        }

        val adapterInfo = initElm(elm)
        when (requestedMode) {
            ProtocolMode.ELM_J1939 -> {
                if (!selectJ1939(elm)) throw ProtocolException("Адаптер не поддерживает J1939 (ATSP A)")
                runJ1939(elm, adapterInfo)
            }
            ProtocolMode.ELM_OBD2 -> {
                if (!selectObd(elm)) listener.onLog("[SYS] ЭБУ не ответил на 0100 — продолжаю опрос")
                runObd(elm, adapterInfo)
            }
            else -> {
                if (selectJ1939(elm) && probeJ1939(elm)) {
                    runJ1939(elm, adapterInfo)
                } else if (selectObd(elm)) {
                    runObd(elm, adapterInfo)
                } else {
                    listener.onLog("[SYS] Данных шины нет (зажигание выключено?). Жду J1939…")
                    selectJ1939(elm)
                    runJ1939(elm, adapterInfo)
                }
            }
        }
    }

    private suspend fun initElm(elm: ElmClient): String {
        elm.command("ATE0")
        elm.command("ATL0")
        elm.command("ATS1")
        elm.command("ATAT1")
        val version = elm.command("ATI").lines.firstOrNull { !ElmResponses.isStatusLine(it) || it.uppercase().startsWith("ELM") }
        val stn = elm.command("STI").takeIf { it.ok }?.lines?.firstOrNull { it.isNotBlank() && it.trim() != "?" }
        return listOfNotNull(version?.trim(), stn?.trim()).joinToString(" · ").ifEmpty { "ELM327" }
    }

    private suspend fun selectJ1939(elm: ElmClient): Boolean {
        val reply = elm.command("ATSP A")
        if (!reply.ok) return false
        elm.command("ATH1")
        elm.command("ATJHF0") // raw 29-bit headers where supported; other formats are parsed too
        elm.command("ATCAF1")
        return true
    }

    private suspend fun probeJ1939(elm: ElmClient): Boolean =
        listOf(J1939.PGN_TCO1, J1939.PGN_CCVS, J1939.PGN_EEC1).any { pgn ->
            monitorPgn(elm, pgn, timeoutMs = 2_500) > 0
        }

    private suspend fun selectObd(elm: ElmClient): Boolean {
        if (!elm.command("ATSP 0").ok) return false
        elm.command("ATH0")
        val reply = elm.command("0100", 8_000)
        return reply.lines.any { it.replace(" ", "").uppercase().contains("4100") }
    }

    /** Returns the number of CAN frames received for [pgn]. */
    private suspend fun monitorPgn(elm: ElmClient, pgn: Int, timeoutMs: Long = 1_500): Int {
        val hex = "%04X".format(pgn)
        var reply: ElmReply? = null
        if (monitorWithCount != false) {
            val withCount = elm.monitor("ATMP $hex 1", timeoutMs, 1)
            if (withCount.rejected) monitorWithCount = false else {
                monitorWithCount = true
                reply = withCount
            }
        }
        if (reply == null) reply = elm.monitor("ATMP $hex", timeoutMs, 1)
        var frames = 0
        for (line in reply.lines) {
            val can = ElmResponses.parseCanLine(line, expectExtended = true) ?: continue
            if (!can.extended) continue
            frames++
            handleJ1939(J1939.frameFromId(can.id, can.data))
        }
        return frames
    }

    private fun handleJ1939(frame: J1939Frame) {
        val complete = if (frame.pgn == J1939.PGN_TP_CM || frame.pgn == J1939.PGN_TP_DT) assembler.accept(frame) else frame
        complete?.let { J1939.decode(it)?.let(listener::onUpdate) }
    }

    private suspend fun runJ1939(elm: ElmClient, adapterInfo: String) {
        listener.onModeResolved(ProtocolMode.ELM_J1939, adapterInfo)
        // Best effort: ask for the VIN (request PGN 0xEA00 for PGN 0xFEEC, LSB first).
        elm.command("ECFE00", 2_500).lines.forEach { line ->
            ElmResponses.parseCanLine(line, expectExtended = true)?.takeIf { it.extended }?.let {
                handleJ1939(J1939.frameFromId(it.id, it.data))
            }
        }
        val schedule = listOf(
            J1939.PGN_TCO1, J1939.PGN_CCVS, J1939.PGN_TCO1, J1939.PGN_EEC1, J1939.PGN_TCO1,
            J1939.PGN_VDHR, J1939.PGN_TCO1, J1939.PGN_VD, J1939.PGN_TCO1, J1939.PGN_DI
        )
        var step = 0
        var tco1Misses = 0
        while (true) {
            runUserCommands(elm)
            val pgn = schedule[step % schedule.size]
            step++
            val frames = monitorPgn(elm, pgn)
            if (pgn == J1939.PGN_TCO1) {
                tco1Misses = if (frames > 0) 0 else tco1Misses + 1
                if (tco1Misses == 3) {
                    listener.onLog("[SYS] TCO1 не приходит: тахограф не передаёт данные в шину FMS. Режим водителя — вручную.")
                }
            }
        }
    }

    private suspend fun runObd(elm: ElmClient, adapterInfo: String) {
        listener.onModeResolved(ProtocolMode.ELM_OBD2, adapterInfo)
        ObdDecoder.decodeVin(elm.command("0902", 5_000).lines)?.let { listener.onUpdate(VehicleUpdate(vin = it)) }
        var cycle = 0
        var odometerSupported = true
        while (true) {
            runUserCommands(elm)
            pollObd(elm, "010D", 0x0D)
            pollObd(elm, "010C", 0x0C)
            if (odometerSupported && cycle % 30 == 0) odometerSupported = pollObd(elm, "01A6", 0xA6)
            cycle++
            delay(300)
        }
    }

    private suspend fun pollObd(elm: ElmClient, cmd: String, pid: Int): Boolean {
        val reply = elm.command(cmd, 1_500)
        val update = reply.lines.firstNotNullOfOrNull { line ->
            ElmResponses.parseObdBytes(line)?.let { ObdDecoder.decodeMode01(it, pid) }
        }
        update?.let(listener::onUpdate)
        return update != null
    }

    private suspend fun runUserCommands(elm: ElmClient) {
        while (true) {
            val cmd = userCommands.tryReceive().getOrNull() ?: return
            elm.command(cmd.trim(), 3_000)
        }
    }

    private suspend fun runTextGateway(lines: Channel<AdapterLine>, alreadyReceived: List<String>) {
        listener.onModeResolved(ProtocolMode.TEXT_GATEWAY, null)
        alreadyReceived.forEach(::handleTextLine)
        while (true) {
            while (true) {
                val cmd = userCommands.tryReceive().getOrNull() ?: break
                listener.onLog("TX $cmd")
                transport.write((cmd.trimEnd() + "\r\n").toByteArray(Charsets.UTF_8))
            }
            val line = withTimeoutOrNull(500) { lines.receive() } ?: continue
            if (line is AdapterLine.Text) {
                listener.onLog("RX ${line.text}")
                handleTextLine(line.text)
            }
        }
    }

    private fun handleTextLine(text: String) {
        val can = TextGatewayParser.parseCandump(text)
        if (can != null) {
            if (can.extended) handleJ1939(J1939.frameFromId(can.id, can.data))
            return
        }
        TextGatewayParser.parse(text)?.let(listener::onUpdate)
    }
}
