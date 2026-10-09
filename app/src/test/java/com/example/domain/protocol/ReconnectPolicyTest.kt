package com.example.domain.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun firstConnectionFailureGivesUpImmediately() {
        val policy = ReconnectPolicy()
        assertTrue(policy.onConnectFailed("timeout") is ReconnectPolicy.Decision.GiveUp)
    }

    @Test
    fun deviceThatDropsRightAwayIsGivenUpAfterThreeTries() {
        // The DTCO BLE case: connects, sends nothing, drops with status 257.
        val policy = ReconnectPolicy()
        policy.onConnected()
        val first = policy.onSessionEnded(2_000, 0, "status 257", fatal = false)
        val second = policy.onSessionEnded(2_000, 0, "status 257", fatal = false)
        val third = policy.onSessionEnded(2_000, 0, "status 257", fatal = false)
        assertTrue(first is ReconnectPolicy.Decision.Retry && !first.reportLoss)
        assertTrue(second is ReconnectPolicy.Decision.Retry)
        assertTrue(third is ReconnectPolicy.Decision.GiveUp)
    }

    @Test
    fun workingLinkIsRetriedAndLossReportedOnce() {
        val policy = ReconnectPolicy()
        policy.onConnected()
        val lost = policy.onSessionEnded(600_000, 5_000, "EOF", fatal = false) as ReconnectPolicy.Decision.Retry
        assertTrue(lost.reportLoss)
        assertEquals(5_000L, lost.delayMs)
        val retry = policy.onConnectFailed("busy") as ReconnectPolicy.Decision.Retry
        assertFalse(retry.reportLoss)
        assertEquals(10_000L, retry.delayMs)
    }

    @Test
    fun protocolErrorIsFatal() {
        val policy = ReconnectPolicy()
        policy.onConnected()
        assertTrue(policy.onSessionEnded(1_000, 0, "not ELM", fatal = true) is ReconnectPolicy.Decision.GiveUp)
    }
}
