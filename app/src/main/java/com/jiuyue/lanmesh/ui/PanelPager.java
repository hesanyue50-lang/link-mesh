package com.jiuyue.lanmesh.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;

/**
 * 三页横向分页容器（跟手拖动 + 松手吸附）。
 *
 * 移植自 balance-widget 项目里验证过的 PanelPager，砍掉了与本项目无关的
 * 图表死区/键盘收起逻辑，保留四个关键设计：
 *
 * ① 用 HorizontalScrollView 打底 —— API 19 上没有 ViewPager2，而滚动、边缘钳制、
 *    跟手拖动这套机制它自带，真正要写的只有"松手吸附到整页"。
 * ② 每页宽度必须在 onMeasure 里显式改成视口宽。写 match_parent 时 LinearLayout
 *    会用 UNSPECIFIED 重测子项，每页会塌缩成内容宽度；行容器写 wrap_content 又会被
 *    压回屏宽，导致滚不到第二页。
 * ③ 禁用自带 fling。否则一次快速甩动会连翻两页，吸附时按位置取整直接飞到末页。
 * ④ 方向死区：垂直位移占优时把整段手势让给子页（ListView / ScrollView），
 *    否则列表几乎无法上下滚动 —— HorizontalScrollView 默认只看水平位移。
 */
public class PanelPager extends HorizontalScrollView {

    public interface Listener {
        /** 连续进度：0 = 第 1 页，1.5 = 正在第 2、3 页中间。 */
        void onPagerScroll(float progress);

        /** 停稳在某页（0 起）。 */
        void onPageSettled(int page);
    }

    private static final float FLING_VELOCITY = 500f;
    private static final long ANIM_MIN_MS = 180L;
    private static final long ANIM_MAX_MS = 360L;
    private static final float H_DEAD_ZONE = 2.0f;  // 水平要超过 touchSlop 的倍数
    private static final float V_RATIO = 1.2f;      // 垂直占优比例
    private static final float H_RATIO = 1.5f;      // 水平占优比例

    private Listener listener;
    private int pageWidth;
    private int currentPage = 0;
    private int pendingPage = -1;

    private VelocityTracker vt;
    private ValueAnimator anim;
    private int animFrom, animTo;

    private int touchSlop;
    private float downX, downY;
    /** 0=未判定 1=让给子页 2=归自己翻页 */
    private int gestureMode;

    public PanelPager(Context c) {
        super(c);
        init();
    }

    public PanelPager(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public PanelPager(Context c, AttributeSet a, int d) {
        super(c, a, d);
        init();
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public int getPage() {
        return currentPage;
    }

    public int pageCount() {
        if (getChildCount() == 0) return 0;
        ViewGroup row = (ViewGroup) getChildAt(0);
        int c = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getVisibility() != View.GONE) c++;
        }
        return c;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w > 0 && getChildCount() > 0 && getChildAt(0) instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) getChildAt(0);
            int shown = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View p = row.getChildAt(i);
                ViewGroup.LayoutParams lp = p.getLayoutParams();
                if (p.getVisibility() == View.GONE) {
                    if (lp != null && lp.width != 0) lp.width = 0;
                    continue;
                }
                shown++;
                if (lp != null && lp.width != w) lp.width = w;
            }
            ViewGroup.LayoutParams rlp = row.getLayoutParams();
            if (rlp != null && rlp.width != w * shown) rlp.width = w * shown;
            pageWidth = w;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0) pageWidth = w;
        if (pendingPage >= 0) {
            final int p = pendingPage;
            pendingPage = -1;
            currentPage = p;
            scrollTo(p * Math.max(pageWidth, 0), 0);
            post(new Runnable() {
                @Override
                public void run() {
                    scrollTo(p * Math.max(pageWidth, 0), 0);
                    if (listener != null) listener.onPageSettled(p);
                }
            });
            return;
        }
        final int p = currentPage;
        post(new Runnable() {
            @Override
            public void run() {
                scrollTo(p * Math.max(pageWidth, 0), 0);
            }
        });
    }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (listener != null) {
            int pw = pageWidth > 0 ? pageWidth : getWidth();
            if (pw > 0) listener.onPagerScroll(l / (float) pw);
        }
    }

    /** 禁止子视图获焦时把分页容器滚过去（设置页里的可聚焦控件会让页面莫名停在半路）。 */
    @Override
    public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rect, boolean immediate) {
        return false;
    }

    public void setPage(int idx, boolean smooth) {
        int n = pageCount();
        if (n == 0) return;
        final int target = Math.max(0, Math.min(n - 1, idx));
        if (pageWidth == 0) {
            pendingPage = target;
            currentPage = target;
            return;
        }
        final int to = target * pageWidth;
        cancelAnim();
        if (!smooth || Math.abs(getScrollX() - to) < 2) {
            scrollTo(to, 0);
            settle(target);
            return;
        }
        animateTo(to, target);
    }

    private void animateTo(int to, final int page) {
        animFrom = getScrollX();
        animTo = to;
        int dist = Math.abs(animTo - animFrom);
        long dur = Math.min(ANIM_MAX_MS, ANIM_MIN_MS + dist / 2L);
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(dur);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                scrollTo((int) (animFrom + (animTo - animFrom) * f), 0);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                scrollTo(animTo, 0);
                settle(page);
            }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private void settle(int page) {
        currentPage = page;
        if (listener != null) listener.onPageSettled(page);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                gestureMode = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (gestureMode == 1) return false;
                if (gestureMode == 0) {
                    float dx = Math.abs(e.getX() - downX);
                    float dy = Math.abs(e.getY() - downY);
                    if (dy > touchSlop && dy > dx * V_RATIO) {
                        gestureMode = 1; // 垂直占优 → 让给列表/滚动页
                        return false;
                    }
                    if (dx > touchSlop * H_DEAD_ZONE && dx > dy * H_RATIO) {
                        gestureMode = 2;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                gestureMode = 0;
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnim(); // 按下立即停掉吸附动画，改为跟手
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float vx = 0f;
                try {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                } catch (Throwable ignored) {
                }
                snap(vx);
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void fling(int velocityX) {
        // 故意不调 super：翻页交给 snap()，一次最多一页
    }

    private void snap(float vx) {
        if (pageWidth <= 0) return;
        int n = pageCount();
        if (n <= 0) return;
        int base = Math.round(getScrollX() / (float) pageWidth);
        int page = base;
        if (vx < -FLING_VELOCITY) page = base + 1;
        else if (vx > FLING_VELOCITY) page = base - 1;
        page = Math.max(0, Math.min(n - 1, page));
        page = Math.max(currentPage - 1, Math.min(currentPage + 1, page));
        setPage(page, true);
    }
}
