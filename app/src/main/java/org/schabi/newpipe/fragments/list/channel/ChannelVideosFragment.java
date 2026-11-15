package org.schabi.newpipe.fragments.list.channel;

import static org.schabi.newpipe.ktx.TextViewUtils.animateTextColor;
import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateBackgroundColor;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.jakewharton.rxbinding4.view.RxView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.databinding.ChannelHeaderBinding;
import org.schabi.newpipe.databinding.FragmentChannelVideosBinding;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelTabInfo;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.filter.Filter;
import org.schabi.newpipe.extractor.search.filter.FilterGroup;
import org.schabi.newpipe.extractor.search.filter.FilterItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.ktx.AnimationType;
import org.schabi.newpipe.local.feed.notifications.NotificationHelper;
import org.schabi.newpipe.local.subscription.SubscriptionManager;
import org.schabi.newpipe.local.subscription.dialog.FeedGroupSelectionDialog;
import org.schabi.newpipe.player.playqueue.ChannelPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ChannelVideosFragment extends BaseListInfoFragment<StreamInfoItem, ChannelInfo>
        implements View.OnClickListener, BackPressable {

    private static final int BUTTON_DEBOUNCE_INTERVAL = 100;
    private static final String PICASSO_CHANNEL_TAG = "PICASSO_CHANNEL_TAG";

    private final CompositeDisposable disposables = new CompositeDisposable();
    private Disposable subscribeButtonMonitor;
    private MenuItem menuSortButton;
    private MenuItem menuFilterButton;

    protected int selectedSortFilterId = Filter.ITEM_IDENTIFIER_UNKNOWN;

    private boolean channelContentNotSupported = false;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private SubscriptionManager subscriptionManager;

    private FragmentChannelVideosBinding channelBinding;
    private ChannelHeaderBinding headerBinding;
    private PlaylistControlBinding playlistControlBinding;
    private ImageButton addToGroupButton;

    public static ChannelVideosFragment getInstance(@NonNull final ChannelInfo channelInfo) {
        final ChannelVideosFragment instance = new ChannelVideosFragment();
        instance.setInitialData(channelInfo.getServiceId(), channelInfo.getUrl(),
                channelInfo.getName());
        instance.currentInfo = channelInfo;
        instance.currentNextPage = channelInfo.getNextPage();
        return instance;
    }

    public static ChannelVideosFragment getInstance(
            final int serviceId, final String url, final String name) {
        final ChannelVideosFragment instance = new ChannelVideosFragment();
        instance.setInitialData(serviceId, url, name);
        return instance;
    }

    public ChannelVideosFragment() {
        super(UserAction.REQUESTED_CHANNEL);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("selectedSortFilterId", selectedSortFilterId);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        selectedSortFilterId = savedInstanceState.getInt("selectedSortFilterId", Filter.ITEM_IDENTIFIER_UNKNOWN);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity != null && useAsFrontPage) {
            setTitle(currentInfo != null ? currentInfo.getName() : name);
        }
        // PipeVR: back from following on the site's page: show the real state
        if (recheckSubscriptionOnResume && currentInfo != null) {
            recheckSubscriptionOnResume = false;
            syncAccountSubscription(currentInfo);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_channel_videos, menu);
        menuSortButton = menu.findItem(R.id.menu_item_sort);
        menuFilterButton = menu.findItem(R.id.action_search_local);
        if (menuFilterButton != null) {
            // inside ChannelFragment the (remote) search button is used instead
            menuFilterButton.setVisible(useAsFrontPage);
        }
        updateSortButton();
    }

    @Override
    public boolean onBackPressed() {
        return closeLocalFilterOnBack();
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        updateSortButton();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.menu_item_sort) {
            showSortDialog();
            return true;
        } else if (item.getItemId() == R.id.action_search_local) {
            openLocalFilter();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        subscriptionManager = new SubscriptionManager(activity);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        channelBinding = FragmentChannelVideosBinding.inflate(inflater, container, false);
        return channelBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View rootView, final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        showContentNotSupportedIfNeeded();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
        if (subscribeButtonMonitor != null) {
            subscribeButtonMonitor.dispose();
        }
        channelBinding = null;
        headerBinding = null;
        playlistControlBinding = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        headerBinding = ChannelHeaderBinding
                .inflate(activity.getLayoutInflater(), itemsList, false);
        playlistControlBinding = headerBinding.playlistControl;
        playlistControlBinding.getRoot().configure(getString(R.string.list_control_page_channel_videos));

        return headerBinding::getRoot;
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        headerBinding.subChannelTitleView.setOnClickListener(this);
        headerBinding.subChannelAvatarView.setOnClickListener(this);

        addToGroupButton = headerBinding.channelAddToGroupButton;
        addToGroupButton.setOnClickListener(this);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Channel Subscription
    //////////////////////////////////////////////////////////////////////////*/

    private void monitorSubscription(final ChannelInfo info) {
        final Consumer<Throwable> onError = (Throwable throwable) -> {
            animate(headerBinding.channelSubscribeButton, false, 100);
            showSnackBarError(new ErrorInfo(throwable, UserAction.SUBSCRIPTION_GET,
                    "Get subscription status", currentInfo));
        };

        final Observable<List<SubscriptionEntity>> observable = subscriptionManager
                .subscriptionTable()
                .getSubscriptionFlowable(info.getServiceId(), info.getUrl())
                .toObservable();

        disposables.add(observable
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getSubscribeUpdateMonitor(info), onError));

        disposables.add(observable
                .map(List::isEmpty)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isEmpty -> updateSubscribeButton(!isEmpty), onError));

        disposables.add(observable
                .map(List::isEmpty)
                .distinctUntilChanged()
                .skip(1) // channel has just been opened
                .filter(x -> NotificationHelper.areNewStreamsNotificationsEnabled(requireContext()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isEmpty -> {
                    if (!isEmpty) {
                        showNotifySnackbar();
                    }
                }, onError));
    }

    /**
     * PipeVR: Twitch (and Kick) may only allow following on their website: open the channel's
     * page in the app, signed in, so the change can be made there. The button re-checks when
     * this page comes back.
     */
    private void openSiteForFollow(final int serviceId, final String url, final String name) {
        if (serviceId != org.schabi.newpipe.extractor.ServiceList.Twitch.getServiceId()
                && serviceId != org.schabi.newpipe.extractor.ServiceList.Kick.getServiceId()) {
            return;
        }
        recheckSubscriptionOnResume = true;
        AndroidSchedulers.mainThread().scheduleDirect(() -> {
            if (getContext() == null) {
                return;
            }
            android.widget.Toast.makeText(getContext(),
                    R.string.pipevr_follow_on_site, android.widget.Toast.LENGTH_LONG).show();
            org.schabi.newpipe.pipevr.PipeVrWebPageActivity.open(requireContext(), url, name);
        });
    }

    private boolean recheckSubscriptionOnResume = false;

    /**
     * PipeVR: change the subscription on the site's account. Returns false when the change is
     * made on the site's own page instead (Twitch only allows that), so nothing changes here
     * until the page comes back and the real state is read.
     */
    private boolean changeOnAccount(final int serviceId, final String channelId,
                                    final String url, final String name,
                                    final boolean subscribe) throws Exception {
        if (!org.schabi.newpipe.pipevr.PipeVrAccountSubscriptions.handles(serviceId)) {
            return true;
        }
        if (serviceId == org.schabi.newpipe.extractor.ServiceList.Twitch.getServiceId()) {
            openSiteForFollow(serviceId, url, name);
            return false;
        }
        final boolean ok;
        try {
            ok = org.schabi.newpipe.pipevr.PipeVrAccountSubscriptions.set(
                    serviceId, channelId, subscribe);
        } catch (final Exception e) {
            openSiteForFollow(serviceId, url, name);
            throw e;
        }
        if (!ok) {
            openSiteForFollow(serviceId, url, name);
            throw new Exception(org.schabi.newpipe.extractor.NewPipe.getNameOfService(serviceId)
                    + " didn't accept the change");
        }
        return true;
    }

    private Function<Object, Object> mapOnSubscribe(final SubscriptionEntity subscription,
                                                    final ChannelInfo info) {
        return (@NonNull Object o) -> {
            // PipeVR: subscribe on the signed-in account too
            if (changeOnAccount(info.getServiceId(), info.getId(), info.getUrl(),
                    info.getName(), true)) {
                subscriptionManager.insertSubscription(subscription, info);
            }
            return o;
        };
    }

    private Function<Object, Object> mapOnUnsubscribe(final SubscriptionEntity subscription) {
        return (@NonNull Object o) -> {
            // PipeVR: unsubscribe on the signed-in account too
            if (currentInfo == null || changeOnAccount(currentInfo.getServiceId(),
                    currentInfo.getId(), currentInfo.getUrl(), currentInfo.getName(), false)) {
                subscriptionManager.deleteSubscription(subscription);
            }
            return o;
        };
    }

    /**
     * PipeVR: make the subscribe button show the YouTube account's real subscription (the
     * channel may have been subscribed to on the phone) by syncing the local subscription.
     */
    private void syncAccountSubscription(final ChannelInfo info) {
        if (!org.schabi.newpipe.pipevr.PipeVrAccountSubscriptions.handles(info.getServiceId())) {
            return;
        }
        disposables.add(io.reactivex.rxjava3.core.Single.fromCallable(() -> {
            final Boolean subscribed = org.schabi.newpipe.pipevr.PipeVrAccountSubscriptions
                    .get(info.getServiceId(), info.getId());
            if (subscribed == null) {
                return false;
            }
            final SubscriptionEntity local = subscriptionManager.subscriptionTable()
                    .getSubscription(info.getServiceId(), info.getUrl()).blockingGet();
            if (subscribed && local == null) {
                final SubscriptionEntity channel = new SubscriptionEntity();
                channel.setServiceId(info.getServiceId());
                channel.setUrl(info.getUrl());
                channel.setData(info.getName(), info.getAvatarUrl(), info.getDescription(),
                        info.getSubscriberCount());
                subscriptionManager.insertSubscription(channel, info);
            } else if (!subscribed && local != null) {
                subscriptionManager.deleteSubscription(local);
            }
            return true;
        }).subscribeOn(Schedulers.io()).subscribe(ok -> { }, error -> { }));
    }

    private void updateSubscription(final ChannelInfo info) {
        if (DEBUG) {
            Log.d(TAG, "updateSubscription() called with: info = [" + info + "]");
        }
        final Action onComplete = () -> {
            if (DEBUG) {
                Log.d(TAG, "Updated subscription: " + info.getUrl());
            }
        };

        final Consumer<Throwable> onError = (@NonNull Throwable throwable) ->
                showSnackBarError(new ErrorInfo(throwable, UserAction.SUBSCRIPTION_UPDATE,
                        "Updating subscription for " + info.getUrl(), info));

        disposables.add(subscriptionManager.updateChannelInfo(info)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(onComplete, onError));
    }

    private Disposable monitorSubscribeButton(final Button subscribeButton,
                                              final Function<Object, Object> action) {
        final Consumer<Object> onNext = (@NonNull Object o) -> {
            if (DEBUG) {
                Log.d(TAG, "Changed subscription status to this channel!");
            }
        };

        final Consumer<Throwable> onError = (@NonNull Throwable throwable) ->
                showSnackBarError(new ErrorInfo(throwable, UserAction.SUBSCRIPTION_CHANGE,
                        "Changing subscription for " + currentInfo.getUrl(), currentInfo));

        /* Emit clicks from main thread unto io thread */
        return RxView.clicks(subscribeButton)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(Schedulers.io())
                .debounce(BUTTON_DEBOUNCE_INTERVAL, TimeUnit.MILLISECONDS) // Ignore rapid clicks
                // PipeVR: a failed change shows its error but must not stop the button
                .map(o -> {
                    try {
                        return action.apply(o);
                    } catch (final Throwable t) {
                        Log.w(TAG, "subscription change failed", t);
                        AndroidSchedulers.mainThread().scheduleDirect(() -> {
                            try {
                                onError.accept(t);
                            } catch (final Throwable ignored) {
                                // nothing more to do
                            }
                        });
                        return o;
                    }
                })
                .subscribe(onNext, onError);
    }

    private Consumer<List<SubscriptionEntity>> getSubscribeUpdateMonitor(final ChannelInfo info) {
        return (List<SubscriptionEntity> subscriptionEntities) -> {
            if (DEBUG) {
                Log.d(TAG, "subscriptionManager.subscriptionTable.doOnNext() called with: "
                        + "subscriptionEntities = [" + subscriptionEntities + "]");
            }
            if (subscribeButtonMonitor != null) {
                subscribeButtonMonitor.dispose();
            }

            if (subscriptionEntities.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "No subscription to this channel!");
                }
                final SubscriptionEntity channel = new SubscriptionEntity();
                channel.setServiceId(info.getServiceId());
                channel.setUrl(info.getUrl());
                channel.setData(info.getName(),
                        info.getAvatarUrl(),
                        info.getDescription(),
                        info.getSubscriberCount());
                subscribeButtonMonitor = monitorSubscribeButton(
                        headerBinding.channelSubscribeButton, mapOnSubscribe(channel, info));
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Found subscription to this channel!");
                }
                final SubscriptionEntity subscription = subscriptionEntities.get(0);
                subscribeButtonMonitor = monitorSubscribeButton(
                        headerBinding.channelSubscribeButton, mapOnUnsubscribe(subscription));
            }
        };
    }

    private void updateSubscribeButton(final boolean isSubscribed) {
        if (DEBUG) {
            Log.d(TAG, "updateSubscribeButton() called with: "
                    + "isSubscribed = [" + isSubscribed + "]");
        }

        final boolean isButtonVisible = headerBinding.channelSubscribeButton.getVisibility()
                == View.VISIBLE;
        final int backgroundDuration = isButtonVisible ? 300 : 0;
        final int textDuration = isButtonVisible ? 200 : 0;

        final int subscribeBackground = ThemeHelper
                .resolveColorFromAttr(activity, R.attr.colorPrimary);
        final int subscribeText = ContextCompat.getColor(activity, R.color.subscribe_text_color);
        final int subscribedBackground = ContextCompat
                .getColor(activity, R.color.subscribed_background_color);
        final int subscribedText = ContextCompat.getColor(activity, R.color.subscribed_text_color);

        final boolean follows = currentInfo != null
                && (currentInfo.getServiceId()
                        == org.schabi.newpipe.extractor.ServiceList.Twitch.getServiceId()
                    || currentInfo.getServiceId()
                        == org.schabi.newpipe.extractor.ServiceList.Kick.getServiceId());
        if (!isSubscribed) {
            headerBinding.channelSubscribeButton.setText(
                    follows ? R.string.pipevr_follow : R.string.subscribe_button_title);
            animateBackgroundColor(headerBinding.channelSubscribeButton, backgroundDuration,
                    subscribedBackground, subscribeBackground);
            animateTextColor(headerBinding.channelSubscribeButton, textDuration, subscribedText,
                    subscribeText);
        } else {
            headerBinding.channelSubscribeButton.setText(
                    follows ? R.string.pipevr_following : R.string.subscribed_button_title);
            animateBackgroundColor(headerBinding.channelSubscribeButton, backgroundDuration,
                    subscribeBackground, subscribedBackground);
            animateTextColor(headerBinding.channelSubscribeButton, textDuration, subscribeText,
                    subscribedText);
        }

        animate(headerBinding.channelSubscribeButton, true, 100,
                AnimationType.LIGHT_SCALE_AND_ALPHA);

        updateAddToGroupButton(isSubscribed);
    }

    /**
     * Show a snackbar with the option to enable notifications on new streams for this channel.
     */
    private void showNotifySnackbar() {
        Snackbar.make(itemsList, R.string.you_successfully_subscribed, Snackbar.LENGTH_LONG)
                .setAction(R.string.get_notified, v -> setNotify(true))
                .setActionTextColor(Color.YELLOW)
                .show();
    }

    private void setNotify(final boolean isEnabled) {
        disposables.add(
                subscriptionManager
                        .updateNotificationMode(
                                currentInfo.getServiceId(),
                                currentInfo.getUrl(),
                                isEnabled ? NotificationMode.ENABLED : NotificationMode.DISABLED)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe()
        );
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected Single<ListExtractor.InfoItemsPage<StreamInfoItem>> loadMoreItemsLogic() {
        if (selectedSortFilterId != Filter.ITEM_IDENTIFIER_UNKNOWN && currentInfo != null) {
            return Single.fromCallable(() -> getSortedVideosTabHandler(currentInfo))
                    .flatMap(handler -> handler == null
                            ? ExtractorHelper.getMoreChannelItems(serviceId, url, currentNextPage)
                            : ExtractorHelper.getMoreChannelTabItems(serviceId, handler,
                                    currentNextPage).map(this::toStreamItemsPage));
        }
        return ExtractorHelper.getMoreChannelItems(serviceId, url, currentNextPage);
    }

    @Override
    protected Single<ChannelInfo> loadResult(final boolean forceLoad) {
        if (selectedSortFilterId == Filter.ITEM_IDENTIFIER_UNKNOWN) {
            return ExtractorHelper.getChannelInfo(serviceId, url, forceLoad);
        }

        return Single.fromCallable(() -> ChannelInfo.getInfo(NewPipe.getService(serviceId), url))
                .flatMap(channelInfo -> loadSortedChannelInfo(channelInfo, forceLoad));
    }

    private Single<ChannelInfo> loadSortedChannelInfo(final ChannelInfo channelInfo,
                                                      final boolean forceLoad) {
        return Single.fromCallable(() -> getSortedVideosTabHandler(channelInfo))
                .flatMap(handler -> handler == null
                        ? Single.just(channelInfo)
                        : ExtractorHelper.getChannelTab(serviceId, handler, forceLoad)
                                .map(channelTabInfo -> applyChannelTabInfo(
                                        channelInfo, channelTabInfo)));
    }

    private ChannelInfo applyChannelTabInfo(final ChannelInfo channelInfo,
                                            final ChannelTabInfo channelTabInfo) {
        channelInfo.setRelatedItems(channelTabInfo.getRelatedItems().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList()));
        channelInfo.setNextPage(channelTabInfo.getNextPage());
        return channelInfo;
    }

    private ListExtractor.InfoItemsPage<StreamInfoItem> toStreamItemsPage(
            final ListExtractor.InfoItemsPage<InfoItem> infoItemsPage) {
        final List<StreamInfoItem> streamItems = infoItemsPage.getItems().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());

        return new ListExtractor.InfoItemsPage<>(streamItems,
                infoItemsPage.getNextPage(), infoItemsPage.getErrors());
    }

    @Nullable
    private ListLinkHandler getSortedVideosTabHandler(final ChannelInfo channelInfo)
            throws Exception {
        final ListLinkHandler videosTabHandler = getVideosTabHandler(channelInfo);
        final FilterItem selectedSortFilter = getSelectedSortFilterItem();
        if (videosTabHandler == null || selectedSortFilter == null) {
            return null;
        }

        return NewPipe.getService(serviceId).getChannelTabLHFactory().fromQuery(
                videosTabHandler.getId(),
                videosTabHandler.getContentFilters(),
                Collections.singletonList(selectedSortFilter));
    }

    @Nullable
    private ListLinkHandler getVideosTabHandler(@Nullable final ChannelInfo channelInfo) {
        if (channelInfo == null) {
            return null;
        }

        for (final ListLinkHandler linkHandler : channelInfo.getTabs()) {
            if (!linkHandler.getContentFilters().isEmpty()
                    && ChannelTabs.VIDEOS.equals(linkHandler.getContentFilters().get(0).getName())) {
                return linkHandler;
            }
        }

        return null;
    }

    @Nullable
    private FilterItem getSelectedSortFilterItem() throws Exception {
        for (final FilterItem sortFilterItem : getChannelTabSortFilterItems()) {
            if (sortFilterItem.getIdentifier() == selectedSortFilterId) {
                return sortFilterItem;
            }
        }

        return null;
    }

    private List<FilterItem> getChannelTabSortFilterItems() {
        try {
            final Filter sortFilter = NewPipe.getService(serviceId)
                    .getChannelTabLHFactory()
                    .getAvailableSortFilter();
            if (sortFilter == null || sortFilter.getFilterGroups() == null) {
                return Collections.emptyList();
            }

            final List<FilterItem> sortFilterItems = new ArrayList<>();
            for (final FilterGroup filterGroup : sortFilter.getFilterGroups()) {
                Collections.addAll(sortFilterItems, filterGroup.filterItems);
            }
            return sortFilterItems;
        } catch (final Exception e) {
            return Collections.emptyList();
        }
    }

    private void showSortDialog() {
        final List<FilterItem> sortFilterItems = getChannelTabSortFilterItems();
        if (sortFilterItems.isEmpty()) {
            return;
        }

        final String[] sortFilterLabels = sortFilterItems.stream()
                .map(filterItem -> ServiceHelper.getTranslatedFilterString(
                        filterItem.getName(), requireContext()))
                .toArray(String[]::new);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sort)
                .setSingleChoiceItems(sortFilterLabels, getSelectedSortFilterIndex(sortFilterItems),
                        (dialog, which) -> {
                            final int selectedFilterId = getNormalizedSortFilterId(
                                    sortFilterItems.get(which));
                            dialog.dismiss();
                            if (selectedFilterId == selectedSortFilterId) {
                                return;
                            }
                            selectedSortFilterId = selectedFilterId;
                            startLoading(true);
                        })
                .show();
    }

    private int getNormalizedSortFilterId(final FilterItem sortFilterItem) {
        return "latest".equals(sortFilterItem.getName())
                ? Filter.ITEM_IDENTIFIER_UNKNOWN
                : sortFilterItem.getIdentifier();
    }

    private int getSelectedSortFilterIndex(final List<FilterItem> sortFilterItems) {
        for (int i = 0; i < sortFilterItems.size(); i++) {
            if (getNormalizedSortFilterId(sortFilterItems.get(i)) == selectedSortFilterId) {
                return i;
            }
        }
        return 0;
    }

    private void updateSortButton() {
        if (menuSortButton != null) {
            menuSortButton.setVisible(getVideosTabHandler(currentInfo) != null
                    && !getChannelTabSortFilterItems().isEmpty());
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OnClick
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onClick(final View v) {
        if (isLoading.get() || currentInfo == null) {
            return;
        }

        if (v.getId() == R.id.sub_channel_avatar_view || v.getId() == R.id.sub_channel_title_view) {
            if (!TextUtils.isEmpty(currentInfo.getParentChannelUrl())) {
                try {
                    NavigationHelper.openChannelFragment(getFM(), currentInfo.getServiceId(),
                            currentInfo.getParentChannelUrl(),
                            currentInfo.getParentChannelName());
                } catch (final Exception e) {
                    ErrorUtil.showUiErrorSnackbar(this, "Opening channel fragment", e);
                }
            } else if (DEBUG) {
                Log.i(TAG, "Can't open parent channel because we got no channel URL");
            }
        } else if (v.getId() == R.id.channel_add_to_group_button) {
            showAddToGroupDialog();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();
        PicassoHelper.cancelTag(PICASSO_CHANNEL_TAG);
        animate(headerBinding.channelSubscribeButton, false, 100);
    }

    @Override
    public void handleResult(@NonNull final ChannelInfo result) {
        super.handleResult(result);

        headerBinding.getRoot().setVisibility(View.VISIBLE);
        updateSortButton();
        activity.invalidateOptionsMenu();
        PicassoHelper.loadBanner(result.getBannerUrl()).tag(PICASSO_CHANNEL_TAG)
                .into(headerBinding.channelBannerImage);
        PicassoHelper.loadAvatar(result.getAvatarUrl()).tag(PICASSO_CHANNEL_TAG)
                .into(headerBinding.channelAvatarView);
        PicassoHelper.loadAvatar(result.getParentChannelAvatarUrl()).tag(PICASSO_CHANNEL_TAG)
                .into(headerBinding.subChannelAvatarView);

        // PipeVR: "followers" / "patrons" where the site says so; nothing when unknown
        if (result.getSubscriberCount() >= 0) {
            headerBinding.channelSubscriberView.setVisibility(View.VISIBLE);
            headerBinding.channelSubscriberView.setText(Localization.shortAudienceCount(
                    activity, result.getServiceId(), result.getSubscriberCount()));
        } else {
            headerBinding.channelSubscriberView.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(currentInfo.getParentChannelName())) {
            headerBinding.subChannelTitleView.setText(String.format(
                    getString(R.string.channel_created_by),
                    currentInfo.getParentChannelName())
            );
            headerBinding.subChannelTitleView.setVisibility(View.VISIBLE);
            headerBinding.subChannelAvatarView.setVisibility(View.VISIBLE);
        } else {
            headerBinding.subChannelTitleView.setVisibility(View.GONE);
        }

        // PlaylistControls should be visible only if there is some item in
        // infoListAdapter other than header
        if (infoListAdapter.getItemCount() != 1) {
            playlistControlBinding.getRoot().setVisibility(View.VISIBLE);
        } else {
            playlistControlBinding.getRoot().setVisibility(View.GONE);
        }

        channelContentNotSupported = false;
        for (final Throwable throwable : result.getErrors()) {
            if (throwable instanceof ContentNotSupportedException) {
                channelContentNotSupported = true;
                showContentNotSupportedIfNeeded();
                break;
            }
        }

        disposables.clear();
        if (subscribeButtonMonitor != null) {
            subscribeButtonMonitor.dispose();
        }
        updateSubscription(result);
        monitorSubscription(result);
        syncAccountSubscription(result);

        playlistControlBinding.playlistCtrlPlayAllButton
                .setOnClickListener(view -> NavigationHelper
                        .playOnMainPlayer(activity, getPlayQueue()));
        playlistControlBinding.playlistCtrlPlayPopupButton
                .setOnClickListener(view -> NavigationHelper
                        .playOnPopupPlayer(activity, getPlayQueue(), false));
        playlistControlBinding.playlistCtrlPlayBgButton
                .setOnClickListener(view -> NavigationHelper
                        .playOnBackgroundPlayer(activity, getPlayQueue(), false));
    }

    private void showContentNotSupportedIfNeeded() {
        // channelBinding might not be initialized when handleResult() is called
        // (e.g. after rotating the screen, #6696)
        if (!channelContentNotSupported || channelBinding == null) {
            return;
        }

        channelBinding.errorContentNotSupported.setVisibility(View.VISIBLE);
        channelBinding.channelKaomoji.setText("(︶︹︺)");
        channelBinding.channelKaomoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 45f);
        channelBinding.channelNoVideos.setVisibility(View.GONE);
    }

    private PlayQueue getPlayQueue() {
        final List<StreamInfoItem> streamItems = infoListAdapter.getItemsList().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());

        return new ChannelPlayQueue(currentInfo.getServiceId(), currentInfo.getUrl(),
                currentInfo.getNextPage(), streamItems, 0);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void setTitle(final String title) {
        super.setTitle(title);
        headerBinding.channelTitleView.setText(title);
    }

    private void showAddToGroupDialog() {
        // Check if user is subscribed first
        disposables.add(subscriptionManager
                .subscriptionTable()
                .getSubscription(currentInfo.getServiceId(), currentInfo.getUrl())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        subscriptionEntity -> {
                            FeedGroupSelectionDialog.newInstance(subscriptionEntity.getUid())
                                    .show(getParentFragmentManager(), "GROUP_SELECTION");
                        },
                        throwable -> showSnackBarError(new ErrorInfo(throwable,
                                UserAction.SUBSCRIPTION_GET,
                                "Get subscription status", currentInfo))
                ));
    }


    private void updateAddToGroupButton(final boolean isSubscribed) {
        if (DEBUG) {
            Log.d(TAG, "updateAddToGroupButton() called with: "
                    + "isSubscribed = [" + isSubscribed + "]");
        }

        if (isSubscribed) {
            animate(addToGroupButton, true, 100, AnimationType.LIGHT_SCALE_AND_ALPHA);
        } else {
            animate(addToGroupButton, false, 100);
        }
    }

    @Override
    protected boolean supportsMultiSelect() {
        return true;
    }
}
