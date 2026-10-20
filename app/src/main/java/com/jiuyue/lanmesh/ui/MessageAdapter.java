package com.jiuyue.lanmesh.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.Util;
import com.jiuyue.lanmesh.model.ChatMessage;
import com.jiuyue.lanmesh.model.Peer;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息列表适配器。
 *
 * 只用一个布局 + 运行时改 gravity/背景，避免两套 item 布局带来的
 * inflate 开销（API 19 低端机上每个额外布局都是一次 XML 解析）。
 */
public class MessageAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<ChatMessage> data = new ArrayList<ChatMessage>();
    private Peer peer;

    public MessageAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setPeer(Peer p) {
        this.peer = p;
        notifyDataSetChanged();
    }

    public void setData(List<ChatMessage> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void add(ChatMessage m) {
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).msgId.equals(m.msgId)) {
                data.set(i, m);
                notifyDataSetChanged();
                return;
            }
        }
        data.add(m);
        notifyDataSetChanged();
    }

    public void update(ChatMessage m) {
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).msgId.equals(m.msgId)) {
                // 保持引用一致：Bus 传来的是同一个对象时可直接替换
                data.set(i, m);
                notifyDataSetChanged();
                return;
            }
        }
        // 未在列表中（例如接收文件消息比 UI 先完成），补进来
        data.add(m);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_message, parent, false);
            h = new Holder();
            h.root = (LinearLayout) convertView.findViewById(R.id.msg_root);
            h.meta = (TextView) convertView.findViewById(R.id.msg_meta);
            h.bubble = convertView.findViewById(R.id.msg_bubble);
            h.body = (TextView) convertView.findViewById(R.id.msg_body);
            h.fileBox = convertView.findViewById(R.id.msg_file_box);
            h.fileName = (TextView) convertView.findViewById(R.id.msg_file_name);
            h.fileInfo = (TextView) convertView.findViewById(R.id.msg_file_info);
            h.progress = (ProgressBar) convertView.findViewById(R.id.msg_progress);
            h.fileStatus = (TextView) convertView.findViewById(R.id.msg_file_status);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        ChatMessage m = data.get(position);
        boolean out = m.isOutgoing();
        // 对齐：自己的消息靠右，对方靠左；同时用 padding 限制气泡最大宽度
        h.root.setGravity(out ? Gravity.END : Gravity.START);
        h.meta.setGravity(out ? Gravity.END : Gravity.START);
        h.bubble.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        int padSide = Ui.dp(ctx, 48);
        h.root.setPadding(out ? padSide : 0, 0, out ? 0 : padSide, Ui.dp(ctx, 12));

        String who;
        if (out) {
            who = "我";
        } else {
            who = peer != null ? peer.displayName() : "对方";
        }
        h.meta.setText(who + "  " + Util.formatTime(m.timestamp)
                + (ChatMessage.TYPE_SYSTEM.equals(m.type) ? "" : ""));

        if (ChatMessage.TYPE_SYSTEM.equals(m.type)) {
            h.body.setVisibility(View.VISIBLE);
            h.body.setTextColor(ctx.getResources().getColor(R.color.text_faint));
            h.body.setText(m.text);
            h.fileBox.setVisibility(View.GONE);
            return convertView;
        }

        if (m.isFile()) {
            h.body.setVisibility(View.GONE);
            h.fileBox.setVisibility(View.VISIBLE);
            h.fileName.setText(m.fileName == null ? "file" : m.fileName);
            String info = Util.humanSize(m.fileSize) + "  " + (out ? "发送" : "接收");
            h.fileInfo.setText(info);

            boolean running = ChatMessage.ST_SENDING.equals(m.status)
                    || ChatMessage.ST_RECEIVING.equals(m.status);
            h.progress.setVisibility(running || ChatMessage.ST_DONE.equals(m.status)
                    ? View.VISIBLE : View.GONE);
            h.progress.setProgress(m.progress);

            if (ChatMessage.ST_DONE.equals(m.status)) {
                h.fileStatus.setTextColor(ctx.getResources().getColor(R.color.accent));
                h.fileStatus.setText(ctx.getString(R.string.file_done)
                        + (m.sha256 != null && m.sha256.length() >= 12
                        ? "  " + m.sha256.substring(0, 12) : ""));
            } else if (ChatMessage.ST_FAILED.equals(m.status)) {
                h.fileStatus.setTextColor(ctx.getResources().getColor(R.color.danger));
                h.fileStatus.setText(ctx.getString(R.string.file_failed)
                        + (m.error == null ? "" : "：" + m.error));
            } else if (ChatMessage.ST_CANCELLED.equals(m.status)) {
                h.fileStatus.setTextColor(ctx.getResources().getColor(R.color.warn));
                h.fileStatus.setText(R.string.file_cancelled);
            } else {
                h.fileStatus.setTextColor(ctx.getResources().getColor(R.color.text_dim));
                String speed = m.speed > 0 ? Util.humanSpeed(m.speed) : "…";
                h.fileStatus.setText(m.progress + "%  " + speed);
            }
        } else {
            h.fileBox.setVisibility(View.GONE);
            h.body.setVisibility(View.VISIBLE);
            h.body.setTextColor(ctx.getResources().getColor(R.color.text));
            h.body.setText(Util.safe(m.text));
            if (ChatMessage.ST_SENDING.equals(m.status)) {
                h.body.append("\n…");
            } else if (ChatMessage.ST_FAILED.equals(m.status)) {
                h.body.setTextColor(ctx.getResources().getColor(R.color.danger));
            }
        }
        return convertView;
    }

    private static class Holder {
        LinearLayout root;
        TextView meta;
        View bubble;
        TextView body;
        View fileBox;
        TextView fileName;
        TextView fileInfo;
        ProgressBar progress;
        TextView fileStatus;
    }
}
