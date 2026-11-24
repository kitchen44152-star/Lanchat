package com.example

import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.example.mesh.router.MeshRouter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanChatRobolectricUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun ensureOnboarded(displayName: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("onboarding_name_input").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("nav_chats").fetchSemanticsNodes().isNotEmpty()
        }
        val hasOnboarding = composeRule.onAllNodesWithTag("onboarding_name_input")
            .fetchSemanticsNodes().isNotEmpty()
        if (hasOnboarding) {
            composeRule.onNodeWithTag("onboarding_name_input").performTextInput(displayName)
            composeRule.onNodeWithTag("onboarding_submit_button").performScrollTo().performClick()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("nav_chats").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `bundled hero image, launcher icon, and typography fonts decode validly`() {
        val context = ApplicationProvider.getApplicationContext<LanChatApplication>()

        val heroBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.img_mesh_hero)
        assertNotNull("img_mesh_hero failed to decode", heroBitmap)
        assertTrue(heroBitmap.width > 100 && heroBitmap.height > 100)

        val iconBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.img_lanchat_icon)
        assertNotNull("img_lanchat_icon failed to decode", iconBitmap)
        assertTrue(iconBitmap.width > 100 && iconBitmap.height > 100)

        assertNotNull(ResourcesCompat.getFont(context, R.font.plus_jakarta_sans))
        assertNotNull(ResourcesCompat.getFont(context, R.font.inter))
        assertNotNull(ResourcesCompat.getFont(context, R.font.jetbrains_mono))
    }

    @Test
    fun `onboarding creates identity and user can send message in nearby broadcast channel`() {
        ensureOnboarded("TrailLeader")

        composeRule.onNodeWithTag("nav_chats").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("conversation_row_${MeshRouter.BROADCAST_CONVERSATION_ID}")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("conversation_row_${MeshRouter.BROADCAST_CONVERSATION_ID}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("chat_message_input").performTextInput("All clear at Summit Ridge")
        composeRule.onNodeWithTag("chat_send_button").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("All clear at Summit Ridge")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All clear at Summit Ridge").assertIsDisplayed()
    }

    @Test
    fun `user can navigate across Mesh Radar, QR verification, Contacts, Settings, and Create Group`() {
        ensureOnboarded("MeshTester")

        // Open QR verification screen from Chats toolbar
        composeRule.onNodeWithTag("chats_qr_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("my_qr_card").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_back_button").performClick()
        composeRule.waitForIdle()

        // Open Mesh Radar tab
        composeRule.onNodeWithTag("nav_mesh").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Mesh Radar").assertIsDisplayed()
        composeRule.onNodeWithTag("mesh_radar_canvas_card").assertIsDisplayed()

        // Open Contacts tab
        composeRule.onNodeWithTag("nav_contacts").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Contacts & Keys").assertIsDisplayed()

        // Open Settings tab, scroll to theme chip, and switch to Dark
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Mesh Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Local Mesh Identity").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_theme_dark"))
        composeRule.onNodeWithTag("settings_theme_dark").performClick()
        composeRule.waitForIdle()

        // Return to Chats tab and create an encrypted group
        composeRule.onNodeWithTag("nav_chats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chats_create_group_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_group_name_input").performTextInput("Alpine Team")
        composeRule.onNodeWithTag("create_group_submit_button").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("AES-256-GCM Encrypted Group").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alpine Team").assertIsDisplayed()
        composeRule.onNodeWithText("AES-256-GCM Encrypted Group").assertIsDisplayed()
    }

    @Test
    fun `opening Mesh Radar via status pill or bottom nav cleanly switches back to Chats`() {
        ensureOnboarded("NavTester")

        // Navigate to Mesh tab via status pill in top bar
        composeRule.onNodeWithTag("mesh_status_pill").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mesh_radar_canvas_card").assertIsDisplayed()

        // Clicking Chats tab MUST return directly to Chats screen (no tab trap)
        composeRule.onNodeWithTag("nav_chats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chats_search_input").assertIsDisplayed()

        // Navigate back to Mesh and Settings, update display name, and return cleanly to Chats
        composeRule.onNodeWithTag("nav_mesh").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mesh_radar_canvas_card").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_display_name_input").performTextInput("_Renamed")
        composeRule.onNodeWithTag("settings_save_name_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_chats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chats_search_input").assertIsDisplayed()
    }

    @Test
    fun `interactive Mesh Radar zoom, recenter, and grid controls respond smoothly`() {
        ensureOnboarded("RadarTester")

        composeRule.onNodeWithTag("nav_mesh").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mesh_radar_canvas_card").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("radar_zoom_in").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("radar_zoom_out").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("radar_grid_toggle").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("radar_grid_toggle").performClick()
        composeRule.onNodeWithTag("radar_recenter").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("mesh_radar_canvas_card").assertIsDisplayed()
    }
}
