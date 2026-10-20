package com.jiuyue.lanmesh.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.animation.OvershootInterpolator;
import android.widget.CompoundButton;

import com.jiuyue.lanmesh.R;

/**
 * 自绘开关：滑块会真正从左滑到右（或反向），带轻微过冲。
 *
 * 为什么不用 SwitchCompat：AppCompat 在 API 19 上没有 thumb 平移动画，
 * 状态切换是瞬间跳变。这里继承 CompoundButton（保留 checked 语义、
 * setOnCheckedChangeListener、无障碍），自己画 track + thumb，
 * 用 ValueAnimator 驱动 progress 0→1。
 */
public class AnimSwitch extends CompoundButton {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0f;
    private android.animation.ValueAnimator anim;
    private final int colorOn;
    private final int colorOff;
    private final int thumbColor;

    public AnimSwitch(Context c) {
        this(c, null);
    }

    public AnimSwitch(Context c, AttributeSet attrs) {
        super(c, attrs);
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        colorOn = getResources().getColor(R.color.accent);
        colorOff = getResources().getColor(R.color.panel_hi);
        thumbColor = getResources().getColor(R.color.on_accent);
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = isChecked() != checked;
        super.setChecked(checked);
        if (changed) animateTo(checked ? 1f : 0f);
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofFloat(progress, target);
        anim.setDuration(260);
        anim.setInterpolator(new OvershootInterpolator(0.35f));
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    public void toggle() {
        setChecked(!isChecked());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 首次挂载直接对齐状态，不播动画
        progress = isChecked() ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int defW = Ui.dp(getContext(), 52);
        int defH = Ui.dp(getContext(), 32);
        int w = resolveSize(defW, widthSpec);
        int h = resolveSize(defH, heightSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        float p = Math.max(0f, Math.min(1f, progress));

        trackPaint.setColor(blend(colorOff, colorOn, p));
        // API 19 没有 drawRoundRect(l,f,r,b,rx,ry,Paint)，用 Path 兜底
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            canvas.drawRoundRect(0, 0, w, h, r, r, trackPaint);
        } else {
            android.graphics.Path path = new android.graphics.Path();
            path.addCircle(r, r, r, android.graphics.Path.Direction.CW);
            path.addCircle(w - r, r, r, android.graphics.Path.Direction.CW);
            path.addRect(r, 0, w - r, h, android.graphics.Path.Direction.CW);
            canvas.drawPath(path, trackPaint);
        }

        float margin = Ui.dp(getContext(), 3);
        float thumbR = r - margin;
        float cx = r + (w - 2 * r) * p;
        thumbPaint.setColor(thumbColor);
        thumbPaint.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        canvas.drawCircle(cx, h / 2f, thumbR, thumbPaint);
    }

    private static int blend(int from, int to, float t) {
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}
