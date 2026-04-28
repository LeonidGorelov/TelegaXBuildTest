package org.telegram.ui;

import static org.telegram.messenger.utils.FrameTickScheduler.callback;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.NewsFeedAdapter;
import org.telegram.ui.Components.RecyclerListView;

import java.util.function.Consumer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class NewsFeedActivity extends BaseFragment {

    private List<Long> channelIds = new ArrayList<>();
    private RecyclerListView listView;



    @Override
    public View createView(Context context) {

        fragmentView = new FrameLayout(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));

        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getDialogs(0);

        for (TLRPC.Dialog d : dialogs) {
            if (DialogObject.isChannel(d)) {
                channelIds.add(d.id);
            }
        }

        final NotificationCenter.NotificationCenterDelegate delegate = (id, account, args) -> {
            if (id == NotificationCenter.didReceiveNewMessages) {

                ArrayList<MessageObject> newMessages = (ArrayList<MessageObject>) args[1];

                for (MessageObject msg : newMessages) {
                    long uid = msg.getDialogId();

                    TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(uid);

                    if (DialogObject.isChannel(dialog)) {

                        if (!channelIds.contains(uid)) {
                            channelIds.add(uid);
                        }
                    }
                }
            }
        };

        getNotificationCenter().addObserver(delegate, NotificationCenter.didReceiveNewMessages);

        loadNewsFeedMessages(currentAccount, channelIds, 500, (messages) ->{
            NewsFeedAdapter adapter = new NewsFeedAdapter(context, this, messages, currentAccount);
            listView.setAdapter(adapter);
            ((FrameLayout) fragmentView).addView(listView);
        });

        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {



        return super.onFragmentCreate();
    }


    public void loadNewsFeedMessages(int currentAccount, List<Long> channelIds, int limit, Consumer<ArrayList<MessageObject>> callback) {

        if (channelIds == null || channelIds.isEmpty()) {
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
