package org.telegram.ui.Adapters;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

public class NewsFeedAdapter extends RecyclerListView.SelectionAdapter {

    private final Context context;
    private final BaseFragment fragment;
    private final ArrayList<MessageObject> messages;
    private final int currentAccount;

    public NewsFeedAdapter(Context context, BaseFragment fragment, ArrayList<MessageObject> messages, int currentAccount) {
        this.context = context;
        this.fragment = fragment;
        this.messages = messages;
        this.currentAccount = currentAccount;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return true;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
        return new RecyclerListView.Holder(cell);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        ChatMessageCell cell = (ChatMessageCell) holder.itemView;
        MessageObject msg = messages.get(position);

        // setMessageObject(MessageObject, GroupedMessages, boolean, boolean, boolean)
        cell.setMessageObject(msg, null, false, false, false);

        cell.setOnClickListener(v -> {
            long dialogId = msg.getDialogId();

            Bundle args = new Bundle();
            args.putLong("dialog_id", dialogId);

            fragment.presentFragment(new ChatActivity(args));
        });
    }
}

