package org.telegram.ui;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.text.style.CharacterStyle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.NewsFeedAdapter;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NewsFeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private NewsFeedAdapter adapter;

    private final List<Long> channelIds = new ArrayList<>();
    private final ArrayList<MessageObject> feedMessages = new ArrayList<>();

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

        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        actionBar.setTitle("News Feed");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });


        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));

        adapter = new NewsFeedAdapter(context, this, feedMessages, currentAccount);
        listView.setAdapter(adapter);

        ((FrameLayout) fragmentView).addView(listView);

        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getDialogs(0);

        for (TLRPC.Dialog d : dialogs) {
            if (DialogObject.isChannel(d)) {
                long uid = d.id;
                if (!channelIds.contains(uid)) {
                    channelIds.add(uid);
                }
            }
        }

        reloadFeed();

        return fragmentView;
    }

    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

        Context context = parent.getContext();

        ChatMessageCell cell = new ChatMessageCell(context, currentAccount);

        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {

            @Override
            public void didPressUrl(ChatMessageCell cell, CharacterStyle url, boolean longPress) {
                Browser.openUrl(cell.getContext(), url.toString());
            }

            @Override
            public void didLongPress(ChatMessageCell cell, float x, float y) {
                MessageObject msg = cell.getMessageObject();
                if (msg == null) return;

                // Вызываем наше меню
                if (fragment instanceof NewsFeedActivity) {
                    ((NewsFeedActivity) activity).showMessageContextMenu(msg, cell);
                }
            }

            public void showMessageContextMenu(MessageObject msg, View anchor) {
                if (msg == null || getParentActivity() == null) return;

                ChatActivity.MessageMenu menu = new ChatActivity.MessageMenu(
                        getParentActivity(),
                        this,
                        msg,
                        false,
                        false
                );

                menu.show(anchor);
            }


            @Override
            public void didPressUserAvatar(ChatMessageCell cell, TLRPC.User user,float touchX, float touchY, boolean asForward) {
                if (user == null) return;

                BaseFragment fragment = parentFragment;
                if (fragment == null) return;

                fragment.presentFragment(new ProfileActivity(user.id));
            }

            @Override
            public void didPressReplyMessage(ChatMessageCell cell, int id, float x, float y, boolean longpress) {
            }

            @Override
            public boolean canPerformActions() {
                return true;
            }
        });

        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cell.setLayoutParams(params);

        return new RecyclerListView.Holder(cell);
    }


    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {

            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> newMessages = (ArrayList<MessageObject>) args[1];

            for (MessageObject msg : newMessages) {
                long uid = msg.getDialogId();

                TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).dialogs_dict.get(uid);

                if (dialog != null && DialogObject.isChannel(dialog)) {
                    if (!channelIds.contains(uid)) {
                        channelIds.add(uid);
                        reloadFeed();
                    }
                }
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
                                ") ORDER BY date ASC LIMIT " + limit
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

                    // -----------------------------
                    // ВАЖНО: получаем канал
                    // -----------------------------
                    long channelId = -uid; // uid = -channelId
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);

                    if (chat == null) {
                        // подгружаем, если нет
                        MessagesController.getInstance(currentAccount).loadFullChat(channelId, 0, true);
                        chat = MessagesController.getInstance(currentAccount).getChat(channelId);
                    }

                    // -----------------------------
                    // Создаём словарь чатов
                    // -----------------------------
                    HashMap<Long, TLRPC.Chat> chatsDict = new HashMap<>();
                    if (chat != null) {
                        chatsDict.put(chat.id, chat);
                    }

                    // -----------------------------
                    // Создаём MessageObject ПРАВИЛЬНО
                    // -----------------------------
                    MessageObject msgObj = new MessageObject(currentAccount, message, null, chatsDict, false, false
                    );

                    result.add(msgObj);
                }

                cursor.dispose();

            } catch (Exception e) {
                FileLog.e(e);
            }

            AndroidUtilities.runOnUIThread(() -> callback.accept(result));
        });
    }


    public void showMessageContextMenu(MessageObject msg, View anchor) {
        if (msg == null || getParentActivity() == null) return;

        ChatActivity.MessageMenu menu = new ChatActivity.MessageMenu(
                getParentActivity(),
                this,
                msg,
                false,
                false
        );

        menu.show(anchor);
    }
}
