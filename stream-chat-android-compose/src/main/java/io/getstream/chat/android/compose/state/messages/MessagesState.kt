package io.getstream.chat.android.compose.state.messages

import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.compose.state.messages.items.MessageListItem

/**
 * UI representation of the Conversation/Messages screen. Holds all the data required to show messages.
 *
 * @param isLoading If we're loading (initial load).
 * @param isLoadingMore If we're loading more data (pagination).
 * @param endOfMessages If we're at the end of messages (to stop pagination).
 * @param messageItems Message items to represent in the list.
 * @param selectedMessage Currently selected message, to show the overlay.
 * @param currentUser The data of the current user, required for various UI states.
 * @param newMessageState The state that represents any new messages.
 * @param parentMessageId The id of the parent message - if we're in a thread.
 * @param unreadCount The count of messages we haven't read yet.
 */
public data class MessagesState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endOfMessages: Boolean = false,
    val messageItems: List<MessageListItem> = emptyList(),
    val selectedMessage: Message? = null,
    val currentUser: User? = null,
    val newMessageState: NewMessageState? = null,
    val parentMessageId: String? = null,
    val unreadCount: Int = 0,
)
