package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appNameIsLocalised() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Тахограф Про", context.getString(R.string.app_name))
    }

    @Test
    fun appStartsWithoutInventedDataAndDemoCanBeStartedAndStopped() {
        compose.onNodeWithText("ТАХОГРАФ ПРО").assertIsDisplayed()
        compose.onNodeWithText("ОТКЛ.").assertIsDisplayed()
        // No demo driver is shown before anything is connected.
        assertTrue(compose.onAllNodesWithText("SCHMIDT HANS-PETER").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Данных о карте нет").assertIsDisplayed()

        compose.onNodeWithTag("nav_tab_devices").performClick()
        compose.onNodeWithText("Демо").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("ДЕМО").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("nav_tab_timers").performClick()
        compose.onNodeWithText("Непрерывное вождение").assertIsDisplayed()

        compose.onNodeWithTag("nav_tab_devices").performClick()
        compose.onNodeWithText("Выйти из демо").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("ОТКЛ.").fetchSemanticsNodes().isNotEmpty() }
    }
}
