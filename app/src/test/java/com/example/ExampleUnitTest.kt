package com.example

import com.example.data.bluetooth.FoundBtDevice
import com.example.data.bluetooth.TachographBluetoothManager
import com.example.data.model.WorkRestCompliance
import com.example.data.model.formatSecondsToHhMm
import com.example.data.model.formatSecondsToHhMmSs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testTimeFormatting() {
        assertEquals("04:30", formatSecondsToHhMm(16200))
        assertEquals("00:45", formatSecondsToHhMm(2700))
        assertEquals("09:00:00", formatSecondsToHhMmSs(32400))
    }

    @Test
    fun testComplianceCalculations() {
        val compliance = WorkRestCompliance(
            continuousDrivingSeconds = 14400, // 4 hours
            maxContinuousDrivingSeconds = 16200 // 4.5 hours
        )
        assertEquals(1800L, compliance.remainingContinuousSeconds) // 30 mins left
        assertFalse(compliance.isViolation)
    }

    @Test
    fun testViolationCalculation() {
        val compliance = WorkRestCompliance(
            continuousDrivingSeconds = 17000,
            maxContinuousDrivingSeconds = 16200,
            isViolation = true
        )
        assertEquals(0L, compliance.remainingContinuousSeconds)
        assertTrue(compliance.isViolation)
    }

    @Test
    fun testTachographVendorRecognition() {
        assertEquals("Continental VDO", TachographBluetoothManager.identifyTachographVendor("DTCO 1381 SmartLink"))
        assertEquals("Continental VDO", TachographBluetoothManager.identifyTachographVendor("VDO DTCO 4.1"))
        assertEquals("Stoneridge Electronics", TachographBluetoothManager.identifyTachographVendor("Stoneridge SE5000 Smart 2"))
        assertEquals("Stoneridge Electronics", TachographBluetoothManager.identifyTachographVendor("DigiFob BLE"))
        assertEquals("Intellic EFAS", TachographBluetoothManager.identifyTachographVendor("Intellic EFAS-4.8 BT"))
        assertEquals("Actia SmarTach", TachographBluetoothManager.identifyTachographVendor("Actia SmarTach DSRC"))
        assertNotNull(TachographBluetoothManager.identifyTachographVendor("Generic Tacho Link"))
    }

    @Test
    fun testSignalCalculation() {
        val deviceGood = FoundBtDevice(
            name = "Continental VDO DTCO",
            address = "00:11:22:33:44:55",
            isPaired = true,
            rssi = -60
        )
        assertTrue(deviceGood.signalPercent > 70)
        assertTrue(deviceGood.signalLabel.contains("Отличный") || deviceGood.signalLabel.contains("Хороший"))
    }
}
