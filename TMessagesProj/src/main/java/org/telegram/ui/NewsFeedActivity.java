package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.NewsFeedAdapter;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NewsFeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private NewsFeedAdapter adapter;

    private final List<Long> channelIds = new ArrayList<>();
    private final List<MessageObject> feedMessages = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {

        fragmentView = new FrameLayout(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));

        adapter = new NewsFeedAdapter(context, this, new ArrayList<>(feedMessages), currentAccount);
        listView.setAdapter(adapter);

        ((FrameLayout) fragmentView).addView(listView);

        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getDialogs(0);

        for (TLRPC.Dialog d : dialogs) {
            if (DialogObject.isChannel(d)) {
                if (!channelIds.contains(d.id)) {
                    channelIds.add(d.id);
                }
            }
        }

        reloadFeed();

        return fragmentView;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {

            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> newMessages = (ArrayList<MessageObject>) args[1];

            boolean changed = false;

            for (MessageObject msg : newMessages) {
                long dialogId = msg.getDialogId();

                TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);

                if (dialog != null && DialogObject.isChannel(dialog)) {
                    if (!channelIds.contains(dialogId)) {
                        channelIds.add(dialogId);
                        changed = true;
                    }
                }
            }

            if (changed) {
                reloadFeed();
            }
        }
    }

    private void reloadFeed() {
        loadNewsFeedMessages(currentAccount, channelIds, 500, messages -> {
            feedMessages.clear();
            feedMessages.addAll(messages);
            adapter.notifyDataSetChanged();
        });
    }

    public void loadNewsFeedMessages(int currentAccount, List<Long> channelIds, int limit, Consumer<List<MessageObject>> callback) {

        if (channelIds == null || channelIds.isEmpty()) {
            callback.accept(new ArrayList<>());
            return;
        }

        String channelIdsSql = channelIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {

            List<MessageObject> result = new ArrayList<>();

            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(currentAccount).getDatabase().queryFinalized(
                        "SELECT mid, data, date, uid FROM messages_v2 WHERE uid IN (" +
                                channelIdsSql +
                                ") ORDER BY date DESC LIMIT " + limit
                );

                while (cursor.next()) {
                    int mid = cursor.intValue(0);
                    NativeByteBuffer data = cursor.byteBufferValue(1);
                    int date = cursor.intValue(2);
                    long uid = cursor.longValue(3);

                    if (data == null) {
                        continue;
                    }

                    data.rewind();

                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();

                    if (message == null) {
                        continue;
                    }

                    message.dialog_id = uid;

                    MessageObject msgObj = new MessageObject(currentAccount, message, false, false);
                    result.add(msgObj);
                }

                cursor.dispose();

            } catch (Exception e) {
                FileLog.e(e);
            }

            AndroidUtilities.runOnUIThread(() -> callback.accept(result));
        });
    }
}
