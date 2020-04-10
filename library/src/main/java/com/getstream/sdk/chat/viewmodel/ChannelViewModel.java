package com.getstream.sdk.chat.viewmodel;

import android.app.Application;

import com.getstream.sdk.chat.Chat;
import com.getstream.sdk.chat.LifecycleHandler;
import com.getstream.sdk.chat.StreamLifecycleObserver;
import com.getstream.sdk.chat.enums.GiphyAction;
import com.getstream.sdk.chat.enums.InputType;
import com.getstream.sdk.chat.model.ModelType;
import com.getstream.sdk.chat.utils.*;

import org.jetbrains.annotations.NotNull;

import java.util.*;

import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import io.getstream.chat.android.client.api.models.SendActionRequest;
import io.getstream.chat.android.client.call.Call;
import io.getstream.chat.android.client.logger.ChatLogger;
import io.getstream.chat.android.client.logger.TaggedLogger;
import io.getstream.chat.android.client.models.*;
import io.getstream.chat.android.client.utils.Result;
import io.getstream.chat.android.client.utils.observable.Subscription;
import io.getstream.chat.android.livedata.ChannelRepo;
import io.getstream.chat.android.livedata.ChatRepo;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class ChannelViewModel extends AndroidViewModel implements LifecycleHandler {

    protected static final String TAG = ChannelViewModel.class.getSimpleName();
    LiveData<Integer> unreadCount;

    /**
     * The A livedata object for the list of messages
     */
    protected LiveData<List<Message>> messages = new MutableLiveData<>();
    /**
     * The numbers of users currently watching this channel
     */
    protected LiveData<Integer> watcherCount;
    /**
     * The list of users currently typing
     */
    protected LiveData<List<User>> typingUsers = new MutableLiveData<>();
    /**
     * Mutable live data object for the current messageInputText
     */
    protected MutableLiveData<String> messageInputText = new MutableLiveData<>("");

    private Date lastKeystrokeAt;
    private Date lastStartTypingEvent;

    protected String channelId;
    protected String channelType;

    protected ChatRepo repo;
    protected ChannelRepo channelRepo;

    protected int threadParentPosition = 0;

    protected LiveData<Boolean> online = new MutableLiveData<>(false);
    public LiveData<Boolean> getOnline() {
        return online;
    }
    protected LiveData<Boolean> initialized = new MutableLiveData<>(false);
    protected LiveData<Boolean> reachedEndOfPagination;
    protected LiveData<Boolean> reachedEndOfPaginationThread;
    protected MutableLiveData<Number> currentUserUnreadMessageCount = new MutableLiveData<>();
    protected Integer lastCurrentUserUnreadMessageCount = 0;
    protected LiveData<Boolean> loading = new MutableLiveData<>(false);
    protected MutableLiveData<Boolean> messageListScrollUp = new MutableLiveData<>(false);
    protected LiveData<Boolean> loadingMore = new MutableLiveData<>(false);
    protected MutableLiveData<Boolean> failed = new MutableLiveData<>(false);
    protected MutableLiveData<Message> editMessage;
    protected MutableLiveData<Message> threadParentMessage = new MutableLiveData<>(null);
    protected MutableLiveData<Channel> channelState = new MutableLiveData<>();

    protected LiveData<List<Message>> threadMessages = new MutableLiveData<>();
    protected LiveData<Boolean> anyOtherUsersOnline;

    protected MutableLiveData<Boolean> hasNewMessages = new MutableLiveData<>(false);

    protected LiveData<List<ChannelUserRead>> reads = new MutableLiveData<>();
    protected MutableLiveData<InputType> inputType = new MutableLiveData<>(InputType.DEFAULT);
    protected MessageListItemLiveData entities;

    private List<Subscription> subscriptions = new ArrayList<>();
    private TaggedLogger logger = ChatLogger.Companion.get("ChannelViewModel");


    public ChannelViewModel(Application application, String channelType, String channelId) {
        super(application);

        this.channelType = channelType;
        this.channelId = channelId;

        repo = ChatRepo.instance();


        channelRepo = repo.channel(channelType, channelId);

        // connect livedata objects
        channelRepo.watch(30);
        initialized = repo.getInitialized();
        online = repo.getOnline();
        watcherCount = channelRepo.getWatcherCount();
        typingUsers = channelRepo.getTyping();
        reads = channelRepo.getReads();
        typingUsers = channelRepo.getTyping();
        messages = channelRepo.getMessages();
        loading = channelRepo.getLoading();
        loadingMore = channelRepo.getLoadingOlderMessages();
        reachedEndOfPagination = channelRepo.getEndOfOlderMessages();
        unreadCount = channelRepo.getUnreadCount();


        logger.logI("instance created");

        User currentUser = ChatRepo.instance().getCurrentUser();

        entities = new MessageListItemLiveData(currentUser, messages, threadMessages, typingUsers, reads);

        editMessage = new MutableLiveData<>();

        new StreamLifecycleObserver(this);

    }


    public MutableLiveData<Number> getCurrentUserUnreadMessageCount() {
        return currentUserUnreadMessageCount;
    }

    public Channel getChannel() {
        return channelRepo.toChannel();
    }

    public LiveData<Boolean> getInitialized() {
        return initialized;
    }

    public LiveData<Channel> getChannelState() {
        return channelState;
    }

    public LiveData<List<Message>> getMessages() {
        return isThread() ? threadMessages : messages;
    }

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<Boolean> getLoadingMore() {
        return loadingMore;
    }

    public LiveData<List<ChannelUserRead>> getReads() {
        return reads;
    }

    public LiveData<Integer> getWatcherCount() {
        return watcherCount;
    }

    public LiveData<Boolean> getFailed() {
        return failed;
    }

    public LiveData<Boolean> getAnyOtherUsersOnline() {
        return anyOtherUsersOnline;
    }

    public LiveData<InputType> getInputType() {
        return inputType;
    }

    public void setInputType(InputType inputType) {
        if (inputType == InputType.SELECT && isEditing()) {
            this.inputType.postValue(InputType.EDIT);
            return;
        }
        this.inputType.postValue(inputType);
    }

    public LiveData<Message> getEditMessage() {
        return editMessage;
    }

    public void setEditMessage(Message editMessage) {
        this.editMessage.postValue(editMessage);
    }

    public LiveData<Boolean> getMessageListScrollUp() {
        return messageListScrollUp;
    }

    public void setMessageListScrollUp(Boolean messageListScrollUp) {
        this.messageListScrollUp.postValue(messageListScrollUp);
    }

    public boolean isEditing() {
        return getEditMessage().getValue() != null;
    }

    // region Thread
    public LiveData<Message> getThreadParentMessage() {
        return threadParentMessage;
    }

    public void setThreadParentMessage(Message threadParentMessage_) {
        threadParentMessage.postValue(threadParentMessage_);
        configThread(threadParentMessage_);
    }

    public int getThreadParentPosition() {
        return threadParentPosition;
    }

    public void setThreadParentPosition(int threadParentPosition) {
        if (isThread()) return;
        this.threadParentPosition = threadParentPosition;
    }

    private boolean isChildOfCurrentThread(Message message) {
        if (!isThread()) return false;
        String parentId = message.getParentId();
        String type = message.getType();
        if (parentId == null || parentId.isEmpty()) return false;
        if (!type.equals(ModelType.message_reply)) return false;
        String currentParentId = threadParentMessage.getValue().getId();
        return parentId.equals(currentParentId);
    }

    public boolean isThread() {
        return threadParentMessage.getValue() != null;
    }

    protected void configThread(Message message) {
        String parentId = message.getId();

        if (message.getReplyCount() == 0) {
            threadMessages = channelRepo.getThread(parentId);
        } else {
            threadMessages = channelRepo.getThread(parentId);
            // TODO: support threadReachedEndOfPagination
            //reachedEndOfPaginationThread = channelRepo.getThreadReachedEnd(parentId);
            channelRepo.threadLoadOlderMessages(parentId, 30);



        }
    }

    public void initThread() {
        // TODO: this thread thing is a mess
        threadParentMessage.postValue(null);
        threadMessages = new MutableLiveData<>();
        reachedEndOfPaginationThread = new MutableLiveData<Boolean>(false);
    }
    // endregion

    // endregion

    protected String getThreadOldestMessageId() {
        List<Message> messages = threadMessages.getValue();
        if (messages != null && messages.size() > 1)
            return threadMessages.getValue().get(1).getId();
        return "";
    }

    /**
     * bans a user from this channel
     *
     * @param targetUserId the ID of the user to ban
     * @param reason       the reason the ban was created.
     * @param timeout      the timeout in minutes until the ban is automatically expired
     */
    public Call<Unit> banUser(@NotNull String targetUserId, @Nullable String reason,
                              @Nullable Integer timeout) {

        return Chat.getInstance().getClient().banUser(targetUserId, channelType, channelId, reason, timeout);
    }

    /**
     * removes the ban for a user on this channel
     *
     * @param targetUserId the ID of the user to remove the ban
     * @param callback     the result callback
     */
    public Call<Unit> unBanUser(@NotNull String targetUserId, @Nullable ResultCallback<Void, String> callback) {

        return Chat.getInstance().getClient().unBanUser(targetUserId, channelType, channelId);
    }



    public MessageListItemLiveData getEntities() {
        return entities;
    }


    // TODO: this is totally a UI thing, create from other observables
    public MutableLiveData<Boolean> getHasNewMessages() {
        return hasNewMessages;
    }

    public void setHasNewMessages(Boolean hasNewMessages) {
        this.hasNewMessages.postValue(hasNewMessages);
    }

    @Override
    public void resume() {
        logger.logI("resume");
    }

    @Override
    public void stopped() {
        logger.logI("stopped");
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        logger.logI("onCleared");

    }



    /**
     * watches channel
     */
    public void watchChannel() {
        int limit = 10; // Constant.DEFAULT_LIMIT

        channelRepo.watch(limit);

    }

    /**
     * loads more messages, use this to load a previous page
     */
    public void loadMore() {

        if (isThread()) {
            channelRepo.threadLoadOlderMessages(threadParentMessage.getValue().getId(), 30);
        } else {
            channelRepo.loadOlderMessages(Constant.DEFAULT_LIMIT);
        }
    }

    public void sendMessage(Message message) {

        if (isThread()) {
            String parentMessageId = getThreadParentMessage().getValue().getId();
            message.setParentId(parentMessageId);
        }

        stopTyping();

        channelRepo.sendMessage(message);

    }


    /**
     * Edit message
     *
     * @param message the Message sent
     */
    public void editMessage(Message message) {
        stopTyping();
        channelRepo.editMessage(message);
    }

    /**
     * sendGiphy - Send giphy with shuffle and cancel.
     *
     * @param message the message that has giphy attachment
     * @param action  the giphy send, shuffle and cancel
     */
    public void sendGiphy(Message message, GiphyAction action) {
        Map<Object, Object> map = new HashMap<>();
        switch (action) {
            case SEND:
                map.put("image_action", ModelType.action_send);
                break;
            case SHUFFLE:
                map.put("image_action", ModelType.action_shuffle);
                break;
            case CANCEL:
                channelRepo.upsertMessage(message);

                return;
        }

        SendActionRequest request = new SendActionRequest(
                channelId,
                message.getId(),
                ModelType.channel_messaging,
                map);

        Chat.getInstance().getClient().sendAction(request).enqueue(result -> {

            if (result.isSuccess()) {
                Message actionMessage = result.data();
                channelRepo.upsertMessage(actionMessage);
            }

            return null;
        });
    }

    /**
     * keystroke - First of the typing.start and typing.stop events based on the users keystrokes.
     * Call this on every keystroke
     */
    public synchronized void keystroke() {
        if (isThread()) return;

        channelRepo.keystroke();
    }

    /**
     * stopTyping - Sets last typing to null and sends the typing.stop event
     */

    public void stopTyping() {
        if (isThread()) return;

        channelRepo.stopTyping();
    }

    public MutableLiveData<String> getMessageInputText() {
        return messageInputText;
    }

    public void setMessageInputText(MutableLiveData<String> messageInputText) {
        this.messageInputText = messageInputText;
    }

    public void markLastMessageRead() {
        channelRepo.markRead();
    }


}