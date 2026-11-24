package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface LanChatRoute {
    @Serializable data object Onboarding : LanChatRoute
    @Serializable data object Chats : LanChatRoute
    @Serializable data object MeshRadar : LanChatRoute
    @Serializable data object Contacts : LanChatRoute
    @Serializable data object Settings : LanChatRoute
    @Serializable data class Chat(val conversationId: String) : LanChatRoute
    @Serializable data class VerifyPeer(val targetNodeId: String? = null) : LanChatRoute
    @Serializable data object CreateGroup : LanChatRoute
}
