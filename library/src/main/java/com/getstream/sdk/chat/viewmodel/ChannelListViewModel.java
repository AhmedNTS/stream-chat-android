package com.getstream.sdk.chat.viewmodel;

import android.app.Application;
import android.os.Handler;

import com.getstream.sdk.chat.Chat;
import com.getstream.sdk.chat.LifecycleHandler;
import com.getstream.sdk.chat.StreamLifecycleObserver;
import com.getstream.sdk.chat.utils.LlcMigrationUtils;
import com.getstream.sdk.chat.utils.RetryPolicy;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import io.getstream.chat.android.client.ChatClient;
import io.getstream.chat.android.client.api.models.QueryChannelsRequest;
import io.getstream.chat.android.client.api.models.QuerySort;
import io.getstream.chat.android.client.call.Call;
import io.getstream.chat.android.client.events.ConnectedEvent;
import io.getstream.chat.android.client.events.MessageReadEvent;
import io.getstream.chat.android.client.events.NewMessageEvent;
import io.getstream.chat.android.client.logger.ChatLogger;
import io.getstream.chat.android.client.logger.TaggedLogger;
import io.getstream.chat.android.client.models.Channel;
import io.getstream.chat.android.client.models.Message;
import io.getstream.chat.android.client.models.User;
import io.getstream.chat.android.client.utils.FilterObject;
import io.getstream.chat.android.client.utils.Result;
import io.getstream.chat.android.client.utils.observable.Subscription;
import io.getstream.chat.android.livedata.ChannelRepo;
import io.getstream.chat.android.livedata.ChatRepo;
import io.getstream.chat.android.livedata.QueryChannelsRepo;
import io.getstream.chat.android.livedata.entity.QueryChannelsEntity;
import io.getstream.chat.android.livedata.requests.QueryChannelsPaginationRequest;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import static com.getstream.sdk.chat.utils.Utils.removeIf;

public class ChannelListViewModel extends AndroidViewModel implements LifecycleHandler {

    private TaggedLogger logger = ChatLogger.Companion.get("ChannelListViewModel");

    protected LiveData<Boolean> loading;
    protected LiveData<Boolean> loadingMore;

    protected FilterObject filter;
    protected QuerySort sort;

    private boolean reachedEndOfPagination;
    protected AtomicBoolean initialized;
    protected AtomicBoolean isLoading;
    protected AtomicBoolean isLoadingMore;
    protected boolean queryChannelDone;
    protected int pageSize;
    protected Handler retryLooper;
    protected ChatRepo repo;
    protected QueryChannelsRepo queryRepo;
    private LiveData<List<ChannelRepo>> channelRepos;


    public ChannelListViewModel(@NonNull Application application) {
        super(application);

        logger.logI("instance created");

        repo = ChatRepo.instance();

        isLoading = new AtomicBoolean(false);
        isLoadingMore = new AtomicBoolean(false);
        initialized = new AtomicBoolean(false);

        reachedEndOfPagination = false;
        pageSize = 25;

        loading = new MutableLiveData<>(true);
        loadingMore = new MutableLiveData<>(false);

        //channels = new LazyQueryChannelLiveData<>();
        //channels.viewModel = this;
        sort = new QuerySort().desc("last_message_at");

        new StreamLifecycleObserver(this);
        retryLooper = new Handler();

    }

    public void setQuery(FilterObject filter, QuerySort sort) {
        this.filter = filter;
        this.sort = sort;
        this.queryRepo = repo.queryChannels(filter, sort);
        if (initialized.get()) {
            logger.logI("setChannelFilter on an already initialized channel will reload the view model");
            reload();
        }

        // connect the livedata objects
        this.channelRepos = queryRepo.getChannels();
        this.loading = queryRepo.getLoading();
        this.loadingMore = queryRepo.getLoadingMore();
    }

    public LiveData<List<ChannelRepo>> getChannelRepos() {
        return channelRepos;
    }


    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<Boolean> getLoadingMore() {
        return loadingMore;
    }

    public void setChannelsPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        logger.logI("onCleared");
    }




    /**
     * hides the channel from queryChannels for the user until a message is added and remove from the current list of channels
     */
    public Call<Unit> hideChannel(@NonNull String channelType, @NonNull String channelId, boolean clearHistory) {

        return Chat.getInstance().getClient().hideChannel(channelType, channelId, clearHistory).map(unit -> {
            //deleteChannel(channelType + ":" + channelId);
            return null;
        });
    }

    /**
     * removes the hidden status for a channel and remove from the current list of channels
     *
     * @param channelId
     * @param channelType
     */
    public Call<Unit> showChannel(@NonNull String channelType, @NonNull String channelId) {

        return Chat.getInstance().getClient().showChannel(channelType, channelId).map(unit -> {
            //deleteChannel(channelType + ":" + channelId);
            return null;
        });
    }

    @Override
    public void resume() {
        logger.logI("resume");
    }

    @Override
    public void stopped() {
        logger.logI("stopped");
    }

    /**
     * query channels
     */
    public void queryChannels() {
        QueryChannelsPaginationRequest request = new QueryChannelsPaginationRequest(0, pageSize, 20);
        queryRepo.query(request);
    }

    /**
     * loads more channels, use this to load a previous page
     */
    public void loadMore() {
        if (!Chat.getInstance().getClient().isSocketConnected()) return;

        if (loadingMore.getValue() || loading.getValue()) {
            return;
        }
        if (reachedEndOfPagination) {
            return;
        }

        queryRepo.loadMore(pageSize);

    }

    protected void clean() {
        retryLooper.removeCallbacksAndMessages(null);
        initialized.set(true);
        reachedEndOfPagination = false;
    }

    /**
     * Reloads the state of the view model
     */
    public void reload() {
        clean();
        queryChannels();

    }

}