package com.example.domain.protocol

/**
 * Decides what to do after a connection attempt fails or a session ends.
 *
 * A session that delivered data or lasted at least [healthySessionMs] counts as a real connection: losing it is
 * reported and retried. A device that drops the link right after connecting without sending anything usable
 * (wrong protocol, wrong Bluetooth service) is given up after [maxQuickDrops] such drops in a row instead of
 * flapping forever.
 */
class ReconnectPolicy(
    private val maxQuickDrops: Int = 3,
    private val healthySessionMs: Long = 60_000,
    private val maxAttempts: Int = 120
) {
    sealed interface Decision {
        /** [reportLoss]: a working link was lost, so the driver should be told once. */
        data class Retry(val delayMs: Long, val attempt: Int, val reportLoss: Boolean) : Decision
        data class GiveUp(val reason: String) : Decision
    }

    private var everConnected = false
    private var quickDrops = 0
    private var attempts = 0

    fun onConnected() {
        everConnected = true
    }

    fun onConnectFailed(reason: String): Decision =
        if (!everConnected) Decision.GiveUp(reason) else retry(reason, reportLoss = false)

    fun onSessionEnded(durationMs: Long, decodedMessages: Long, reason: String, fatal: Boolean): Decision {
        if (fatal) return Decision.GiveUp(reason)
        if (decodedMessages > 0 || durationMs >= healthySessionMs) {
            quickDrops = 0
            attempts = 0
            return retry(reason, reportLoss = true)
        }
        quickDrops++
        if (quickDrops >= maxQuickDrops) {
            return Decision.GiveUp("Устройство разрывает связь сразу после подключения ($quickDrops раза подряд, данных нет): $reason")
        }
        return retry(reason, reportLoss = false)
    }

    private fun retry(reason: String, reportLoss: Boolean): Decision {
        attempts++
        if (attempts > maxAttempts) return Decision.GiveUp("Не удалось восстановить связь: $reason")
        return Decision.Retry(minOf(5_000L * attempts, 30_000L), attempts, reportLoss)
    }
}
