package io.getstream.chat.android.compose.viewmodel.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getstream.sdk.chat.viewmodel.messages.getCreatedAtOrThrow
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.call.await
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.Reaction
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.compose.handlers.ClipboardHandler
import io.getstream.chat.android.compose.state.messages.MessageMode
import io.getstream.chat.android.compose.state.messages.MessagesState
import io.getstream.chat.android.compose.state.messages.MyOwn
import io.getstream.chat.android.compose.state.messages.NewMessageState
import io.getstream.chat.android.compose.state.messages.Normal
import io.getstream.chat.android.compose.state.messages.Other
import io.getstream.chat.android.compose.state.messages.Thread
import io.getstream.chat.android.compose.state.messages.items.DateSeparator
import io.getstream.chat.android.compose.state.messages.items.MessageItem
import io.getstream.chat.android.compose.state.messages.items.MessageItemGroupPosition.Bottom
import io.getstream.chat.android.compose.state.messages.items.MessageItemGroupPosition.Middle
import io.getstream.chat.android.compose.state.messages.items.MessageItemGroupPosition.None
import io.getstream.chat.android.compose.state.messages.items.MessageItemGroupPosition.Top
import io.getstream.chat.android.compose.state.messages.items.MessageListItem
import io.getstream.chat.android.compose.state.messages.list.Copy
import io.getstream.chat.android.compose.state.messages.list.Delete
import io.getstream.chat.android.compose.state.messages.list.Flag
import io.getstream.chat.android.compose.state.messages.list.MessageAction
import io.getstream.chat.android.compose.state.messages.list.MuteUser
import io.getstream.chat.android.compose.state.messages.list.React
import io.getstream.chat.android.compose.state.messages.list.Reply
import io.getstream.chat.android.compose.state.messages.list.ThreadReply
import io.getstream.chat.android.offline.ChatDomain
import io.getstream.chat.android.offline.channel.ChannelController
import io.getstream.chat.android.offline.model.ConnectionState
import io.getstream.chat.android.offline.thread.ThreadController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ViewModel responsible for handling all the business logic & state for the list of messages.
 */
public class MessageListViewModel(
    public val chatClient: ChatClient,
    public val chatDomain: ChatDomain,
    private val channelId: String,
    private val messageLimit: Int = 0,
    private val enforceUniqueReactions: Boolean = true,
    private val clipboardHandler: ClipboardHandler,
) : ViewModel() {

    /**
     * State handler for the UI, which holds all the information the UI needs to render messages.
     *
     * It chooses between [threadMessagesState] and [messagesState] based on if we're in a thread or not.
     */
    public val currentMessagesState: MessagesState
        get() = if (isInThread) threadMessagesState else messagesState

    /**
     * State of the screen, for the [Normal] [messageMode].
     */
    private var messagesState: MessagesState by mutableStateOf(MessagesState())

    /**
     * State of the screen, for the [Thread] [messageMode].
     */
    private var threadMessagesState: MessagesState by mutableStateOf(MessagesState())

    /**
     * Holds the current [MessageMode] that's used for the messages list. [Normal] by default.
     */
    public var messageMode: MessageMode by mutableStateOf(Normal)
        private set

    /**
     * The information for the current [Channel].
     */
    public var channel: Channel by mutableStateOf(Channel())
        private set

    /**
     * Set of currently active [MessageAction]s. Used to show things like edit, reply, delete and
     * similar actions.
     */
    public var messageActions: Set<MessageAction> by mutableStateOf(mutableSetOf())
        private set

    /**
     * Gives us information if we're currently in the [Thread] message mode.
     */
    public val isInThread: Boolean
        get() = messageMode is Thread

    /**
     * Gives us information if we're showing the selected message overlay.
     */
    public val isShowingOverlay: Boolean
        get() = messagesState.selectedMessage != null || threadMessagesState.selectedMessage != null

    /**
     * Gives us information about the online state of the device.
     */
    public val connectionState: StateFlow<ConnectionState> by chatDomain::connectionState

    /**
     * Gives us information about the online state of the device.
     */
    @Deprecated("Use connectionState instead")
    public val isOnline: StateFlow<Boolean>
        get() = chatDomain.online

    /**
     * Gives us information about the logged in user state.
     */
    public val user: StateFlow<User?>
        get() = chatDomain.user

    /**
     * [Job] that's used to keep the thread data loading operations. We cancel it when the user goes
     * out of the thread state.
     */
    private var threadJob: Job? = null

    /**
     * Represents the last loaded message in the list, for comparison when determining the
     * [NewMessageState] for the screen.
     */
    private var lastLoadedMessage: Message? = null

    /**
     * Represents the latest message we've seen in the channel.
     */
    private var lastSeenChannelMessage: Message? by mutableStateOf(null)

    /**
     * Represents the latest message we've seen in the active thread.
     */
    private var lastSeenThreadMessage: Message? by mutableStateOf(null)

    /**
     * Sets up the core data loading operations - such as observing the current channel and loading
     * messages and other pieces of information.
     */
    init {
        viewModelScope.launch {
            val result =
                chatDomain.watchChannel(channelId, messageLimit)
                    .await()

            if (result.isSuccess) {
                val controller = result.data()

                observeConversation(controller)
            } else {
                result.error().cause?.printStackTrace()
                showEmptyState()
            }
        }
    }

    /**
     * Starts observing the current conversation using the [controller]. We observe the
     * 'loadingOlderMessages', 'messagesState', 'user' and 'endOfOlderMessages' states from our
     * controller, as well as build the `newMessageState` using [getNewMessageState] and combine it
     * into a [MessagesState] that holds all the information required for the screen.
     *
     * @param controller The controller for the channel with the current [channelId].
     */
    private fun observeConversation(controller: ChannelController) {
        viewModelScope.launch {
            controller.messagesState
                .combine(user) { state, user ->
                    when (state) {
                        is ChannelController.MessagesState.NoQueryActive,
                        is ChannelController.MessagesState.Loading,
                        -> messagesState.copy(isLoading = true)
                        is ChannelController.MessagesState.OfflineNoResults -> messagesState.copy(
                            isLoading = false,
                            messageItems = emptyList()
                        )
                        is ChannelController.MessagesState.Result -> {
                            messagesState.copy(
                                isLoading = false,
                                messageItems = groupMessages(filterDeletedMessages(state.messages)),
                                isLoadingMore = false,
                                endOfMessages = controller.endOfOlderMessages.value,
                                currentUser = user
                            )
                        }
                    }
                }.collect { newState ->
                    val newLastMessage =
                        (newState.messageItems.firstOrNull { it is MessageItem } as? MessageItem)?.message

                    val hasNewMessage = lastLoadedMessage != null &&
                        messagesState.messageItems.isNotEmpty() &&
                        newLastMessage?.id != lastLoadedMessage?.id

                    messagesState = if (hasNewMessage) {
                        val newMessageState = getNewMessageState(newLastMessage)

                        newState.copy(
                            newMessageState = newMessageState,
                            unreadCount = getUnreadMessageCount(newMessageState)
                        )
                    } else {
                        newState
                    }
                    lastLoadedMessage = newLastMessage
                    controller.toChannel().let { channel ->
                        ChatClient.dismissChannelNotifications(channelType = channel.type, channelId = channel.id)
                        setCurrentChannel(channel)
                    }
                }
        }
    }

    /**
     * Sets the current channel, used to show info in the UI.
     */
    private fun setCurrentChannel(channel: Channel) {
        this.channel = channel
    }

    /**
     * Used to filter messages deleted by other users.
     *
     * @param messages List of all messages.
     * @return Filtered messages.
     */
    private fun filterDeletedMessages(messages: List<Message>): List<Message> {
        val currentUser = user.value

        return messages.filter { !(it.user.id != currentUser?.id && it.deletedAt != null) }
    }

    /**
     * Builds the [NewMessageState] for the UI, whenever the message state changes. This is used to
     * allow us to show the user a floating button giving them the option to scroll to the bottom
     * when needed.
     *
     * @param lastMessage Last message in the list, used for comparison.
     */
    private fun getNewMessageState(lastMessage: Message?): NewMessageState? {
        val lastLoadedMessage = lastLoadedMessage
        val currentUser = user.value

        return if (lastMessage != null && lastLoadedMessage != null && lastMessage.id != lastLoadedMessage.id) {
            if (lastMessage.user.id == currentUser?.id) {
                MyOwn
            } else {
                Other
            }
        } else {
            null
        }
    }

    /**
     * Counts how many messages the user hasn't read already. This is based on what the last message they've seen is,
     * and the current message state.
     *
     * @param newMessageState The state that tells us if there are new messages in the list.
     * @return [Int] which describes how many messages come after the last message we've seen in the list.
     */
    private fun getUnreadMessageCount(newMessageState: NewMessageState? = currentMessagesState.newMessageState): Int {
        if (newMessageState == null || newMessageState == MyOwn) return 0

        val messageItems = currentMessagesState.messageItems
        val lastSeenMessagePosition =
            getLastSeenMessagePosition(if (isInThread) lastSeenThreadMessage else lastSeenChannelMessage)
        var unreadCount = 0

        for (i in 0..lastSeenMessagePosition) {
            val messageItem = messageItems[i]

            if (messageItem is MessageItem && !messageItem.isMine && messageItem.message.deletedAt == null) {
                unreadCount++
            }
        }

        return unreadCount
    }

    /**
     * Gets the list position of the last seen message in the list.
     *
     * @param lastSeenMessage - The last message we saw in the list.
     * @return [Int] list position of the last message we've seen.
     */
    private fun getLastSeenMessagePosition(lastSeenMessage: Message?): Int {
        if (lastSeenMessage == null) return 0

        return currentMessagesState.messageItems.indexOfFirst {
            it is MessageItem && it.message.id == lastSeenMessage.id
        }
    }

    /**
     * Attempts to update the last seen message in the channel or thread. We only update the last seen message the first
     * time the data loads and whenever we see a message that's newer than the current last seen message.
     *
     * @param message The message that is currently seen by the user.
     */
    public fun updateLastSeenMessage(message: Message) {
        val lastSeenMessage = if (isInThread) lastSeenThreadMessage else lastSeenChannelMessage

        if (lastSeenMessage == null) {
            updateLastSeenMessageState(message)
            return
        }

        if (message.id == lastSeenMessage.id) return

        val lastSeenMessageDate = message.createdAt ?: Date()
        val currentMessageDate = message.createdAt ?: Date()

        if (currentMessageDate < lastSeenMessageDate) {
            return
        }
        val messages = currentMessagesState.messageItems

        val currentMessagePosition =
            messages.indexOfFirst { it is MessageItem && it.message.id == message.id }
        val lastSeenMessagePosition =
            messages.indexOfFirst { it is MessageItem && it.message.id == message.id }

        if (currentMessagePosition < lastSeenMessagePosition) {
            updateLastSeenMessageState(message)
        }
    }

    /**
     * Updates the state of the last seen message. Based on if we're [isInThread] or not, it updates corresponding state.
     *
     * @param currentMessage The current message the user sees.
     */
    private fun updateLastSeenMessageState(currentMessage: Message) {
        if (isInThread) {
            lastSeenThreadMessage = currentMessage

            threadMessagesState = threadMessagesState.copy(unreadCount = getUnreadMessageCount())
        } else {
            lastSeenChannelMessage = currentMessage

            messagesState = messagesState.copy(unreadCount = getUnreadMessageCount())
        }

        chatDomain.markRead(channelId).enqueue()
    }

    /**
     * If there's an error, we just set the current state to be empty - 'isLoading' as false and
     * 'messages' as an empty list.
     */
    private fun showEmptyState() {
        messagesState = messagesState.copy(isLoading = false, messageItems = emptyList())
    }

    /**
     * Triggered when the user loads more data by reaching the end of the current messages.
     */
    public fun loadMore() {
        val messageMode = messageMode

        if (messageMode is Thread) {
            threadMessagesState = threadMessagesState.copy(isLoadingMore = true)
            chatDomain.threadLoadMore(channelId, messageMode.parentMessage.id, messageLimit)
                .enqueue()
        } else {
            messagesState = messagesState.copy(isLoadingMore = true)
            chatDomain.loadOlderMessages(channelId, messageLimit).enqueue()
        }
    }

    /**
     * Triggered when the user long taps on and selects a message. This updates the internal state
     * and allows our UI to re-render it and show an overlay.
     *
     * @param message The selected message.
     */
    public fun selectMessage(message: Message?) {
        if (isInThread) {
            threadMessagesState = threadMessagesState.copy(selectedMessage = message)
        } else {
            messagesState = messagesState.copy(selectedMessage = message)
        }
    }

    /**
     * Triggered when the user taps on a message that has a thread active. This changes the current
     * [messageMode] to [Thread] and loads the thread data.
     *
     * @param message The selected message with a thread.
     */
    public fun openMessageThread(message: Message) {
        this.messageMode = Thread(message)

        loadThread(message)
    }

    /**
     * Used to dismiss a specific message action, such as delete, reply, edit or something similar.
     *
     * @param messageAction The action to dismiss.
     */
    public fun dismissMessageAction(messageAction: MessageAction) {
        this.messageActions = messageActions - messageAction
    }

    /**
     * Dismisses all message actions, when we cancel them in the rest of the UI.
     */
    public fun dismissAllMessageActions() {
        this.messageActions = emptySet()
    }

    /**
     * Triggered when the user selects a new message action, in the message overlay.
     *
     * We first remove the overlay, after which we consume the event and based on the type of the event,
     * we do different things, such as starting a thread & loading thread data, showing delete or flag
     * events and dialogs, copying the message, muting users and more.
     *
     * @param messageAction The action the user chose.
     */
    public fun performMessageAction(messageAction: MessageAction) {
        removeOverlay()

        when (messageAction) {
            is ThreadReply -> {
                messageActions = messageActions + Reply(messageAction.message)
                loadThread(messageAction.message)
            }
            is Delete, is Flag -> {
                messageActions = messageActions + messageAction
            }
            is Copy -> copyMessage(messageAction.message)
            is MuteUser -> muteUser(messageAction.message.user)
            is React -> reactToMessage(messageAction.reaction, messageAction.message)
            else -> {
                // no op, custom user action
            }
        }
    }

    /**
     * Loads the thread data and changes the current [messageMode] to be [Thread].
     *
     * The data is loaded by fetching the [ThreadController] first, based on the [parentMessage],
     * after which we observe specific data from the thread.
     *
     * @param parentMessage The message with the thread we want to observe.
     */
    private fun loadThread(parentMessage: Message) {
        messageMode = Thread(parentMessage)

        chatDomain.getThread(channelId, parentMessage.id).enqueue { result ->
            if (result.isSuccess) {
                val controller = result.data()

                observeThreadMessages(controller)
            } else {
                messageMode = Normal
            }
        }
    }

    /**
     * Observes the currently active thread data, based on our [ThreadController]. In process, this
     * creates a [threadJob] that we can cancel once we leave the thread.
     *
     * The data consists of the 'loadingOlderMessages', 'messages' and 'endOfOlderMessages' states,
     * that are combined into one [MessagesState].
     *
     * @param controller The controller for the active thread.
     */
    private fun observeThreadMessages(controller: ThreadController) {
        threadJob = viewModelScope.launch {
            controller.messages
                .combine(user) { messages, user -> messages to user }
                .combine(controller.endOfOlderMessages) { (messages, user), endOfOlderMessages ->
                    threadMessagesState.copy(
                        isLoading = false,
                        messageItems = groupMessages(filterDeletedMessages(messages)),
                        isLoadingMore = false,
                        endOfMessages = endOfOlderMessages,
                        currentUser = user,
                        parentMessageId = controller.threadId
                    )
                }.collect { newState -> threadMessagesState = newState }
        }
    }

    /**
     * Takes in the available messages for a [Channel] and groups them based on the sender ID. We put the message in a
     * group, where the positions can be [Top], [Middle], [Bottom] or [None] if the message isn't in a group.
     *
     * @param messages The messages we need to group.
     * @return A list of [MessageItem]s, each containing a position.
     */
    private fun groupMessages(messages: List<Message>): List<MessageListItem> {
        val parentMessageId = (messageMode as? Thread)?.parentMessage?.id
        val currentUser = user.value
        val groupedMessages = mutableListOf<MessageListItem>()

        messages.forEachIndexed { index, message ->
            val user = message.user
            val previousMessage = messages.getOrNull(index - 1)

            val previousUser = previousMessage?.user
            val nextUser = messages.getOrNull(index + 1)?.user

            val position = when {
                previousUser != user && nextUser == user -> Top
                previousUser == user && nextUser == user -> Middle
                previousUser == user && nextUser != user -> Bottom
                else -> None
            }

            if (shouldAddDateSeparator(previousMessage, message)) {
                groupedMessages.add(DateSeparator(message.getCreatedAtOrThrow()))
            }

            groupedMessages.add(
                MessageItem(
                    message = message,
                    groupPosition = position,
                    parentMessageId = parentMessageId,
                    isMine = user.id == currentUser?.id
                )
            )
        }

        return groupedMessages.reversed()
    }

    private fun shouldAddDateSeparator(previousMessage: Message?, message: Message): Boolean {
        return if (previousMessage == null) {
            true
        } else {
            val timeDifference = message.getCreatedAtOrThrow().time - previousMessage.getCreatedAtOrThrow().time

            return timeDifference > TimeUnit.HOURS.toMillis(4)
        }
    }

    /**
     * Removes the delete actions from our [messageActions], as well as the overlay, before deleting
     * the selected message.
     *
     * @param message Message to delete.
     */
    @JvmOverloads
    public fun deleteMessage(message: Message, hard: Boolean = false) {
        messageActions = messageActions - messageActions.filterIsInstance<Delete>()
        removeOverlay()

        chatDomain.deleteMessage(message, hard).enqueue()
    }

    /**
     * Copies the message content using the [ClipboardHandler] we provide. This can copy both
     * attachment and text messages.
     *
     * @param message Message with the content to copy.
     */
    private fun copyMessage(message: Message) {
        clipboardHandler.copyMessage(message)
    }

    /**
     * Mutes the user that sent a particular message.
     *
     * @param user The user to mute.
     */
    private fun muteUser(user: User) {
        chatClient.muteUser(user.id).enqueue()
    }

    /**
     * Triggered when the user chooses the [React] action for the currently selected message. If the
     * message already has that reaction, from the current user, we remove it. Otherwise we add a new
     * reaction.
     *
     * @param reaction The reaction to add or remove.
     * @param message The currently selected message.
     */
    private fun reactToMessage(reaction: Reaction, message: Message) {
        if (message.ownReactions.any { it.messageId == reaction.messageId && it.type == reaction.type }) {
            chatDomain.deleteReaction(channelId, reaction).enqueue()
        } else {
            chatDomain.sendReaction(channelId, reaction, enforceUniqueReactions).enqueue()
        }
    }

    /**
     * Leaves the thread we're in and resets the state of the [messageMode] and both of the [MessagesState]s.
     *
     * It also cancels the [threadJob] to clean up resources.
     */
    public fun leaveThread() {
        messageMode = Normal
        messagesState = messagesState.copy(selectedMessage = null)
        threadMessagesState = MessagesState()
        lastSeenThreadMessage = null
        threadJob?.cancel()
    }

    /**
     * Resets the [MessagesState]s, to remove the message overlay, by setting 'selectedMessage' to null.
     */
    public fun removeOverlay() {
        threadMessagesState = threadMessagesState.copy(selectedMessage = null)
        messagesState = messagesState.copy(selectedMessage = null)
    }

    /**
     * Clears the [NewMessageState] from our UI state, after the user taps on the "Scroll to bottom"
     * or "New Message" actions in the list or simply scrolls to the bottom.
     */
    public fun clearNewMessageState() {
        threadMessagesState = threadMessagesState.copy(newMessageState = null, unreadCount = 0)
        messagesState = messagesState.copy(newMessageState = null, unreadCount = 0)
    }

    /**
     * Sets the focused message to be the message with the given ID, after which it removes it from
     * focus with a delay.
     *
     * @param messageId The ID of the message.
     */
    public fun focusMessage(messageId: String) {
        val messages = currentMessagesState.messageItems.map {
            if (it is MessageItem && it.message.id == messageId) {
                it.copy(isFocused = true)
            } else {
                it
            }
        }

        viewModelScope.launch {
            updateMessages(messages)
            delay(2000)
            removeMessageFocus(messageId)
        }
    }

    /**
     * Removes the focus flag from the message with the given ID.
     *
     * @param messageId The ID of the message.
     */
    private fun removeMessageFocus(messageId: String) {
        val messages = currentMessagesState.messageItems.map {
            if (it is MessageItem && it.message.id == messageId) {
                it.copy(isFocused = false)
            } else {
                it
            }
        }

        updateMessages(messages)
    }

    /**
     * Updates the current message state with new messages.
     *
     * @param messages The list of new message items.
     */
    private fun updateMessages(messages: List<MessageListItem>) {
        if (isInThread) {
            this.threadMessagesState = threadMessagesState.copy(messageItems = messages)
        } else {
            this.messagesState = messagesState.copy(messageItems = messages)
        }
    }

    /**
     * Returns a message with the given ID from the [currentMessagesState].
     *
     * @param messageId The ID of the selected message.
     * @return The [Message] with the ID, if it exists.
     */
    public fun getMessageWithId(messageId: String): Message? {
        val messageItem =
            currentMessagesState.messageItems.firstOrNull { it is MessageItem && it.message.id == messageId }

        return (messageItem as? MessageItem)?.message
    }
}
