package com.jiuyue.lanmesh.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.AppCore;
import com.jiuyue.lanmesh.core.Prefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导航栏管理：拖拽排序 / 隐藏 / 设为主页。
 *
 * 拖拽逻辑参考 RecyclerView ItemTouchHelper：交换数据 + 其它行动画到新槽位。
 * 操作后只在同一个弹窗内清空重建（不 dismiss/show），避免闪烁。
 */
public final class NavManager {

    private NavManager() {}

    public static void show(final Activity act, final Runnable onChanged) {
        final Dialog d = new Dialog(act, R.style.DarkDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_panel);
        int pad = Ui.dp(act, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(act);
        title.setText("导航栏管理");
        title.setTextSize(17);
        title.setTextColor(act.getResources().getColor(R.color.text));
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView tip = new TextView(act);
        tip.setText("长按任意一行拖动排序（左侧 ≡ 为拖拽手柄）。\n设为主页 = 打开应用直接进这一页并排到最左；设置页不可隐藏。");
        tip.setTextSize(11);
        tip.setTextColor(act.getResources().getColor(R.color.text_faint));
        tip.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 10));
        root.addView(tip);

        ScrollView sc = new ScrollView(act);
        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        sc.addView(box);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sc);

        TextView close = new TextView(act);
        close.setText("完成");
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(null, Typeface.BOLD);
        close.setTextColor(act.getResources().getColor(R.color.on_accent));
        close.setBackgroundResource(R.drawable.bg_btn_primary);
        close.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = Ui.dp(act, 12);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        root.addView(close);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(width, Ui.dp(act, 520));
        }

        final Prefs prefs = AppCore.get(act).prefs;
        final int rowH = Ui.dp(act, 64);
        final List<Integer> order = new ArrayList<Integer>();
        final Map<Integer, View> rowByKey = new HashMap<Integer, View>();
        final Runnable[] rebuild = new Runnable[1];
        final View[] dragging = new View[1];
        final int[] dragKey = new int[1];
        final float[] startY = new float[1];
        final int[] startTop = new int[1];

        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                box.removeAllViews();
                rowByKey.clear();
                order.clear();
                order.addAll(prefs.pageOrder());
                for (int i = 0; i < order.size(); i++) {
                    final int key = order.get(i);
                    View row = buildRow(act, prefs, order, key, i, rowH, box, rowByKey,
                            dragging, dragKey, startY, startTop, rebuild, onChanged);
                    rowByKey.put(key, row);
                    box.addView(row);
                }
                box.setAlpha(0f);
                box.animate().alpha(1f).setDuration(160).start();
            }
        };
        rebuild[0].run();

        d.show();
        Ui.animateDialogIn(d, false);
    }

    private static View buildRow(final Activity act, final Prefs prefs,
                                 final List<Integer> order, final int key, final int index,
                                 final int rowH, final LinearLayout box,
                                 final Map<Integer, View> rowByKey,
                                 final View[] dragging, final int[] dragKey, final float[] startY,
                                 final int[] startTop,
                                 final Runnable[] rebuild, final Runnable onChanged) {
        final boolean hidden = prefs.pageHidden().contains(key);
        final boolean isHome = key == prefs.homePage();

        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowH));

        TextView handle = new TextView(act);
        handle.setText("≡");
        handle.setTextSize(20);
        handle.setGravity(Gravity.CENTER);
        handle.setTextColor(act.getResources().getColor(R.color.text_faint));
        handle.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 30),
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(handle);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nm = new TextView(act);
        nm.setText(nameOf(key) + (isHome ? "  ★主页" : "") + (hidden ? "  （已隐藏）" : ""));
        nm.setTextSize(14);
        nm.setTextColor(act.getResources().getColor(hidden ? R.color.text_faint : R.color.text));
        nm.setTypeface(null, isHome ? Typeface.BOLD : Typeface.NORMAL);
        col.addView(nm);
        TextView sub = new TextView(act);
        sub.setText(isHome ? "打开应用即进入此页"
                : (hidden ? "不在导航条显示" : "导航条第 " + (index + 1) + " 位"));
        sub.setTextSize(10);
        sub.setTextColor(act.getResources().getColor(R.color.text_faint));
        col.addView(sub);
        row.addView(col);

        if (!isHome) {
            row.addView(btn(act, "设为主页", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setHomePage(key);
                    order.remove(Integer.valueOf(key));
                    order.add(0, key);
                    prefs.setPageOrder(order);
                    if (onChanged != null) onChanged.run();
                    rebuild[0].run();
                }
            }));
        }
        if (key != 2) {
            row.addView(btn(act, hidden ? "显示" : "隐藏", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!hidden && visibleCount(prefs) <= 1) {
                        Ui.toast(act, "至少要保留一个页面");
                        return;
                    }
                    Set<Integer> h = prefs.pageHidden();
                    if (h.contains(key)) h.remove(key);
                    else h.add(key);
                    prefs.setPageHidden(h);
                    if (h.contains(prefs.homePage())) {
                        List<Integer> vis = visibleOrder(prefs);
                        if (!vis.isEmpty()) prefs.setHomePage(vis.get(0));
                    }
                    if (onChanged != null) onChanged.run();
                    rebuild[0].run();
                }
            }));
        } else {
            TextView lock = new TextView(act);
            lock.setText("固定");
            lock.setTextSize(10);
            lock.setTextColor(act.getResources().getColor(R.color.text_faint));
            lock.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
            row.addView(lock);
        }

        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // 布局未完成时高度为 0；第一项 top 本来就是 0，不能用 top 判断
                if (v.getHeight() <= 0) return false;
                dragging[0] = v;
                dragKey[0] = key;
                startY[0] = 0;
                startTop[0] = v.getTop();
                v.setAlpha(0.9f);
                // 不要 bringToFront：LinearLayout 会把该行移到 children 末尾，
                // 表现为"一按住就跳到最后一行"。用 elevation 让它在 Z 轴上浮起即可。
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    v.setElevation(Ui.dp(act, 8));
                    v.setTranslationZ(Ui.dp(act, 8));
                }
                return true;
            }
        });
        row.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (dragging[0] != v) return false;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_MOVE) {
                    if (startY[0] == 0) startY[0] = e.getRawY();
                    float dy = e.getRawY() - startY[0];
                    v.setTranslationY(dy);
                    int cur = order.indexOf(dragKey[0]);
                    // 滞回：超过半行才换位，避免手指微抖在边界来回抽搐
                    float pos = startTop[0] + dy;
                    float offset = pos - cur * (float) rowH;
                    int target = cur;
                    if (offset > rowH * 0.55f) target = cur + 1;
                    else if (offset < -rowH * 0.55f) target = cur - 1;
                    target = Math.max(0, Math.min(order.size() - 1, target));
                    if (target != cur) {
                        order.remove(Integer.valueOf(dragKey[0]));
                        order.add(target, dragKey[0]);
                        for (int i = 0; i < order.size(); i++) {
                            int k = order.get(i);
                            View r = rowByKey.get(k);
                            if (r == null || r == v) continue;
                            r.animate().translationY(i * (float) rowH - r.getTop())
                                    .setDuration(140).start();
                        }
                    }
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    v.setTranslationY(0);
                    v.setAlpha(1f);
                    if (android.os.Build.VERSION.SDK_INT >= 21) v.setElevation(0);
                    dragging[0] = null;
                    prefs.setPageOrder(order);
                    if (onChanged != null) onChanged.run();
                    rebuild[0].run();
                }
                return true;
            }
        });
        return row;
    }

    private static int visibleCount(Prefs prefs) {
        return visibleOrder(prefs).size();
    }

    private static List<Integer> visibleOrder(Prefs prefs) {
        List<Integer> out = new ArrayList<Integer>();
        Set<Integer> hidden = prefs.pageHidden();
        for (int k : prefs.pageOrder()) if (!hidden.contains(k)) out.add(k);
        return out;
    }

    private static String nameOf(int key) {
        if (key == 0) return "局域网";
        if (key == 1) return "P2P";
        return "设置";
    }

    private static TextView btn(Activity a, String text, View.OnClickListener l) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(a.getResources().getColor(R.color.text));
        t.setBackgroundResource(R.drawable.bg_btn_ghost);
        t.setPadding(Ui.dp(a, 9), Ui.dp(a, 6), Ui.dp(a, 9), Ui.dp(a, 6));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(a, 4);
        t.setLayoutParams(lp);
        return t;
    }
}
