package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.ui.JarvisScreen
import com.example.ui.JarvisUiState
import com.example.ui.theme.JarvisTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Jarvis", appName)
    }

    @Test
    fun `jarvis screen renders core controls`() {
        composeRule.setContent {
            JarvisTheme {
                JarvisScreen(
                    uiState = JarvisUiState(),
                    onPowerMicClick = {},
                    onToggleCameraVision = {},
                    onFlipCamera = {},
                    onToggleScreenVision = {},
                    onCameraFrameCaptured = {},
                    onCameraError = {},
                    onDismissError = {}
                )
            }
        }

        composeRule.onNodeWithTag("central_mic_button").assertIsDisplayed()
        composeRule.onNodeWithTag("camera_vision_button").assertIsDisplayed()
        composeRule.onNodeWithTag("screen_vision_button").assertIsDisplayed()
    }
}
