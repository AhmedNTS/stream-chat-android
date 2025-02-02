package io.getstream.chat.android.ui.message.list.internal

import com.getstream.sdk.chat.adapter.MessageListItem
import io.getstream.chat.android.ui.common.extensions.isGiphyEphemeral
import io.getstream.chat.android.ui.message.list.MessageListView

internal object HiddenMessageListItemPredicate : MessageListView.MessageListItemPredicate {

    private val theirGiphyEphemeralMessagePredicate: (MessageListItem) -> Boolean = { item ->
        item is MessageListItem.MessageItem && item.message.isGiphyEphemeral() && item.isTheirs
    }

    override fun predicate(item: MessageListItem): Boolean {
        return theirGiphyEphemeralMessagePredicate(item).not()
    }
}
