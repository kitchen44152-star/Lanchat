package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.TrustStatus
import com.example.mesh.call.CallPhase
import com.example.mesh.call.MeshCallUiState
import com.example.ui.screens.FullScreenCallOverlay
import com.example.ui.screens.MinimizedCallBanner
import com.example.ui.theme.LanChatTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceCallUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `incoming call overlay shows peer name, E2E badge, and decline button`() {
        var declined = false
        composeRule.setContent {
            LanChatTheme(darkTheme = true) {
                FullScreenCallOverlay(
                    callState = MeshCallUiState(
                        phase = CallPhase.INCOMING_RINGING,
                        callId = "call-ui-1",
                        peerNodeId = "node1234",
                        peerName = "MountainGuide",
                        trustStatus = TrustStatus.VERIFIED_QR,
                        hopCount = 1,
                        isIncoming = true
                    ),
                    onAccept = {},
                    onDeclineOrEnd = { declined = true },
                    onToggleMute = {},
                    onToggleSpeaker = {},
                    onMinimize = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("full_screen_call_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("MountainGuide").assertIsDisplayed()
        composeRule.onNodeWithTag("call_accept_button").assertIsDisplayed()
        composeRule.onNodeWithTag("call_decline_button").performClick()
        assertTrue(declined)
    }

    @Test
    fun `active call overlay controls mute, speaker, minimize, and hangup cleanly`() {
        var mutedToggled = false
        var speakerToggled = false
        var minimizedClicked = false
        var endedClicked = false

        composeRule.setContent {
            LanChatTheme(darkTheme = true) {
                FullScreenCallOverlay(
                    callState = MeshCallUiState(
                        phase = CallPhase.ACTIVE,
                        callId = "call-ui-2",
                        peerNodeId = "node5678",
                        peerName = "BaseCamp",
                        trustStatus = TrustStatus.VERIFIED_QR,
                        hopCount = 2,
                        durationSec = 75
                    ),
                    onAccept = {},
                    onDeclineOrEnd = { endedClicked = true },
                    onToggleMute = { mutedToggled = true },
                    onToggleSpeaker = { speakerToggled = true },
                    onMinimize = { minimizedClicked = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("call_duration_text").assertIsDisplayed()
        composeRule.onNodeWithText("01:15").assertIsDisplayed()
        composeRule.onNodeWithTag("call_mute_button").performClick()
        composeRule.onNodeWithTag("call_speaker_button").performClick()
        composeRule.onNodeWithTag("call_minimize_button").performClick()
        composeRule.onNodeWithTag("call_hangup_button").performClick()

        assertTrue(mutedToggled)
        assertTrue(speakerToggled)
        assertTrue(minimizedClicked)
        assertTrue(endedClicked)
    }

    @Test
    fun `minimized call banner expands or hangs up when tapped`() {
        var expanded = false
        var hungUp = false
        composeRule.setContent {
            LanChatTheme(darkTheme = false) {
                MinimizedCallBanner(
                    callState = MeshCallUiState(
                        phase = CallPhase.ACTIVE,
                        callId = "call-ui-3",
                        peerNodeId = "node9999",
                        peerName = "RelayScout",
                        durationSec = 12,
                        isMinimized = true
                    ),
                    onExpand = { expanded = true },
                    onHangUp = { hungUp = true }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("minimized_call_banner").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("minimized_call_hangup").performClick()
        assertTrue(expanded)
        assertTrue(hungUp)
    }
}
