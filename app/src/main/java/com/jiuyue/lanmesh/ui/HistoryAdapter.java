package com.jiuyue.lanmesh.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.History;
import com.jiuyue.lanmesh.core.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * 传输记录列表适配器。
 *
 * 一行只 inflate 一次，之后只改文字与图标，和 MessageAdapter 用同一套 ViewHolder 做法。
 * 图标是 24dp 的白色 vector，按方向/成败在代码里 tint（API 19 上没有 android:tint）。
 */
public class HistoryAdapter extends BaseAdapter {

    /** 点击与长按回调；两者都可以为 null。 */
    public interface OnHistoryAction {
        void onItemClick(History.Entry e);

        void onItemLongClick(History.Entry e);
    }

    private final Context ctx;
    private final List<History.Entry> data = new ArrayList<History.Entry>();
    private final OnHistoryAction listener;

    public HistoryAdapter(Context ctx, List<History.Entry> data) {
        this(ctx, data, null);
    }

    public HistoryAdapter(Context ctx, List<History.Entry> data, OnHistoryAction listener) {
        this.ctx = ctx;
        this.listener = listener;
        if (data != null) this.data.addAll(data);
    }

    public void setData(List<History.Entry> d) {
        data.clear();
        if (d != null) data.addAll(d);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public History.Entry getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        // ts 在同一条记录上稳定，删除时用它定位；不用 position，滚动后会变
        return position < data.size() ? data.get(position).ts : position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_history, parent, false);
            h = new Holder();
            h.icon = (ImageView) convertView.findViewById(R.id.iv_hist_icon);
            h.name = (TextView) convertView.findViewById(R.id.tv_hist_name);
            h.sub = (TextView) convertView.findViewById(R.id.tv_hist_sub);
            // 基础色只设一次；失败项的红色由 status 段 span 覆盖
            h.sub.setTextColor(ctx.getResources().getColor(R.color.text_faint));
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        final History.Entry e = data.get(position);
        boolean send = e.dir == History.DIR_SEND;

        String name = e.fileName;
        if (name == null || name.length() == 0) name = "未命名文件";
        h.name.setText(name);

        String peer = e.peerName;
        if (peer == null || peer.length() == 0) peer = "未知设备";

        String status;
        if (e.ok) {
            status = send ? "已发送" : "已接收";
        } else {
            status = "失败";
            if (e.error != null && e.error.length() > 0) status = status + "：" + e.error;
        }

        String head = peer + " · " + Util.humanSize(e.size) + " · " + Util.formatTime(e.ts) + " · ";
        int statusColor = ctx.getResources().getColor(
                e.ok ? R.color.text_faint : R.color.danger);
        // 只有状态段染色，对方/大小/时间保持次要色，红色不会糊满整行
        SpannableString sub = new SpannableString(head + status);
        sub.setSpan(new ForegroundColorSpan(statusColor), head.length(), sub.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        h.sub.setText(sub);

        h.icon.setImageResource(send ? R.drawable.ic_hist_send : R.drawable.ic_hist_recv);
        int iconColor = ctx.getResources().getColor(
                e.ok ? (send ? R.color.accent : R.color.text_dim) : R.color.danger);
        h.icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);

        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onItemClick(e);
            }
        });
        convertView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onItemLongClick(e);
                    return true;
                }
                return false;
            }
        });
        return convertView;
    }

    private static class Holder {
        ImageView icon;
        TextView name;
        TextView sub;
    }
}
