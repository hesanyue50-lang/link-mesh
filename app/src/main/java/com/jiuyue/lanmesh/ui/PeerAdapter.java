package com.jiuyue.lanmesh.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.model.Peer;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备列表适配器。
 *
 * 刻意使用 ListView + BaseAdapter 而不是 RecyclerView：
 *  - RecyclerView 会额外引入 recyclerview + collection 依赖，增大 API 19 冷启动开销；
 *  - 设备列表通常 < 20 项，ListView 的复用完全够用，且 API 19 上更稳定。
 */
public class PeerAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Peer> data = new ArrayList<Peer>();

    public PeerAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<Peer> peers) {
        data.clear();
        if (peers != null) data.addAll(peers);
        notifyDataSetChanged();
    }

    public Peer getItemAt(int position) {
        if (position < 0 || position >= data.size()) return null;
        return data.get(position);
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return getItemAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_peer, parent, false);
            h = new Holder();
            h.dot = (TextView) convertView.findViewById(R.id.tv_dot);
            h.name = (TextView) convertView.findViewById(R.id.tv_name);
            h.sub = (TextView) convertView.findViewById(R.id.tv_sub);
            h.badge = (TextView) convertView.findViewById(R.id.tv_badge);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        Peer p = data.get(position);
        long now = System.currentTimeMillis();
        boolean online = p.isOnline(now);
        h.name.setText(p.displayName());
        h.dot.setTextColor(ctx.getResources().getColor(online ? R.color.accent : R.color.text_faint));

        String src;
        if (Peer.SRC_NSD.equals(p.source)) src = "NSD";
        else if (Peer.SRC_MULTICAST.equals(p.source)) src = "组播";
        else src = "手动";
        h.sub.setText(p.shortId() + "  " + p.address() + "  " + src);

        if (!online) {
            h.badge.setText(R.string.conn_offline);
            h.badge.setTextColor(ctx.getResources().getColor(R.color.text_faint));
        } else if (!p.trusted) {
            h.badge.setText("待确认");
            h.badge.setTextColor(ctx.getResources().getColor(R.color.warn));
        } else {
            h.badge.setText(R.string.conn_direct);
            h.badge.setTextColor(ctx.getResources().getColor(R.color.accent));
        }
        return convertView;
    }

    private static class Holder {
        TextView dot;
        TextView name;
        TextView sub;
        TextView badge;
    }
}
