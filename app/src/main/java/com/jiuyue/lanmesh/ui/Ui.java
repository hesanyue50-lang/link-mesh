package com.jiuyue.lanmesh.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jiuyue.lanmesh.R;
import com.jiuyue.lanmesh.core.Util;

/**
 * UI 小工具：dp、暗色对话框、Toast。
 *
 * 为什么不用系统 AlertDialog：
 *  API 19 上 AppCompat 的 AlertDialog 在部分 ROM 会回落到亮色 Holo 主题，
 *  出现白底黑字，与主界面撕裂。这里用完全自绘的 Dialog + 自定义布局，
 *  字体、圆角、按钮都在我们控制内，且不依赖任何 API 21+ 的 Material 组件。
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 主题里给按钮的语义色：深色下 accent 亮、浅色下 accent 深，文字始终用 on_accent。 */
    public static int color(Context ctx, int resId) {
        return ctx.getResources().getColor(resId);
    }

    public static void toast(Context ctx, String msg) {
        if (ctx == null || msg == null) return;
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    public interface OnClick {
        void onClick();
    }

    public interface OnText {
        void onText(String text);
    }

    /** 单按钮提示。 */
    public static Dialog alert(Activity a, String title, String message, String button, final OnClick onClick) {
        return build(a, title, message, button, onClick, null, null, false, null);
    }

    /** 双按钮确认。secondary 为 null 时只有主按钮。 */
    public static Dialog confirm(Activity a, String title, String message,
                                 String primary, final OnClick primaryClick,
                                 String secondary, final OnClick secondaryClick) {
        return build(a, title, message, primary, primaryClick, secondary, secondaryClick, false, null);
    }

    /** 带输入框的对话框。 */
    public static Dialog input(Activity a, String title, String hint, String initial,
                               final OnText onOk) {
        return build(a, title, null, a.getString(R.string.save), null,
                a.getString(R.string.cancel), null, true, new Object[]{hint, initial, onOk});
    }

    private static Dialog build(final Activity a, String title, String message,
                                String primary, final OnClick primaryClick,
                                String secondary, final OnClick secondaryClick,
                                boolean withInput, Object[] inputParams) {
        final Dialog d = new Dialog(a, R.style.DarkDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_alert);
        d.setCanceledOnTouchOutside(true);

        TextView tvTitle = (TextView) d.findViewById(R.id.dlg_title);
        TextView tvMsg = (TextView) d.findViewById(R.id.dlg_message);
        final EditText et = (EditText) d.findViewById(R.id.dlg_input);
        TextView btnPrimary = (TextView) d.findViewById(R.id.dlg_primary);
        TextView btnSecondary = (TextView) d.findViewById(R.id.dlg_secondary);

        tvTitle.setText(Util.safe(title));
        if (message == null || message.length() == 0) {
            tvMsg.setVisibility(View.GONE);
        } else {
            tvMsg.setVisibility(View.VISIBLE);
            tvMsg.setText(message);
        }

        if (withInput && inputParams != null) {
            et.setVisibility(View.VISIBLE);
            et.setHint(String.valueOf(inputParams[0]));
            et.setText(String.valueOf(inputParams[1] == null ? "" : inputParams[1]));
            et.setSelection(et.getText().length());
            et.requestFocus();
            final OnText cb = (OnText) inputParams[2];
            btnPrimary.setText(primary);
            btnPrimary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String text = et.getText().toString();
                    hideKeyboard(a, et);
                    d.dismiss();
                    if (cb != null) cb.onText(text);
                }
            });
        } else {
            et.setVisibility(View.GONE);
            btnPrimary.setText(primary);
            btnPrimary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (primaryClick != null) primaryClick.onClick();
                }
            });
        }

        if (secondary == null) {
            btnSecondary.setVisibility(View.GONE);
        } else {
            btnSecondary.setVisibility(View.VISIBLE);
            btnSecondary.setText(secondary);
            btnSecondary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (secondaryClick != null) secondaryClick.onClick();
                }
            });
        }

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.6f;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
            int width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f);
            if (width > dp(a, 420)) width = dp(a, 420);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        animateDialogIn(d, false);
        if (withInput) {
            et.postDelayed(new Runnable() {
                @Override
                public void run() {
                    InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        }
        return d;
    }

    private static void hideKeyboard(Context ctx, View v) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 沉浸式 / 状态栏避让 ----------------

    public static boolean isNight(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 沉浸式 + 状态栏/导航栏避让。
     *
     * 分版本处理的原因：
     *  - API 21+ 才能设置 statusBarColor 与 LAYOUT_FULLSCREEN；
     *  - 状态栏图标反色（LIGHT_STATUS_BAR）要 API 23，导航栏反色要 API 26；
     *  - API 19/20 只有 FLAG_TRANSLUCENT_STATUS，状态栏是半透明黑底白字，
     *    因此浅色主题在 API 19-22 上仍保持深色状态栏，避免"白底白字看不见"。
     *
     * 避让用 WindowInsets 而不是固定 dp：刘海屏、手势导航、横屏切换都能自适应。
     */
    public static void applyEdgeToEdge(final Activity a, final View root) {
        final boolean night = isNight(a);
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            if (Build.VERSION.SDK_INT >= 23) {
                w.setStatusBarColor(0x00000000);
            } else {
                // API 21-22 无法把状态栏图标改成深色：浅色主题下用品牌色条兜底
                w.setStatusBarColor(night ? 0x00000000 : a.getResources().getColor(R.color.accent));
            }
            if (Build.VERSION.SDK_INT >= 26) {
                w.setNavigationBarColor(0x00000000);
            } else {
                w.setNavigationBarColor(night ? 0x00000000 : 0xFF0A0B0F);
            }
            int vis = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= 23 && !night) {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26 && !night) {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(vis);
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }

        final int pl = root.getPaddingLeft();
        final int pt = root.getPaddingTop();
        final int pr = root.getPaddingRight();
        final int pb = root.getPaddingBottom();
        final int statusFallback = statusBarHeight(a);

        if (Build.VERSION.SDK_INT >= 20) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,
                    new androidx.core.view.OnApplyWindowInsetsListener() {
                        @Override
                        public androidx.core.view.WindowInsetsCompat onApplyWindowInsets(
                                View v, androidx.core.view.WindowInsetsCompat insets) {
                            androidx.core.graphics.Insets bars =
                                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                            // 键盘也算一种 inset：不处理的话输入框会被键盘顶住不动
                            androidx.core.graphics.Insets ime =
                                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
                            int top = bars.top > 0 ? bars.top : statusFallback;
                            int bottom = Math.max(bars.bottom, ime.bottom);
                            v.setPadding(pl, pt + top, pr, pb + bottom);
                            return insets;
                        }
                    });
            androidx.core.view.ViewCompat.requestApplyInsets(root);
        } else {
            root.setPadding(pl, pt + statusFallback, pr, pb);
        }
    }

    public static int statusBarHeight(Context ctx) {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return ctx.getResources().getDimensionPixelSize(id);
        }
        return dp(ctx, 24);
    }

    /** 按压缩放反馈：所有可点元素统一 scale(.96)，松手弹回（Aurora Glass 铁律 5）。 */
    public static void pressable(final View v) {
        if (v == null) return;
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                        break;
                    default:
                        break;
                }
                return false; // 不拦截 click
            }
        });
    }

    /** API 21+ 才支持 letterSpacing；大字号收紧、小标签放宽。 */
    public static void letterSpacing(TextView tv, float em) {
        if (tv == null) return;
        if (Build.VERSION.SDK_INT >= 21) {
            tv.setLetterSpacing(em);
        }
    }


    /**
     * 所有自绘弹窗的统一入场动画：
     *  - 居中对话框：从 0.94 缩放 + 淡入（不会顶到屏幕边缘）
     *  - 底部操作表：从下方 60dp 滑入 + 淡入
     * 只做入场，关闭仍然即时——用户点"取消/知道了"时不需要等待。
     */
    public static void animateDialogIn(Dialog d, boolean fromBottom) {
        if (d == null) return;
        Window w = d.getWindow();
        if (w == null) return;
        final View decor = w.getDecorView();
        decor.setAlpha(0f);
        if (fromBottom) {
            decor.setTranslationY(dp(d.getContext(), 60));
        } else {
            decor.setScaleX(0.94f);
            decor.setScaleY(0.94f);
        }
        decor.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(fromBottom ? 280 : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    /** 供手动添加/昵称输入使用：数字与小键盘友好。 */
    public static void numberKeyboard(EditText et) {
        et.setInputType(InputType.TYPE_CLASS_TEXT);
    }

    /** 底部弹出的简单操作表（"+" 菜单），避免 PopupMenu 在 API 19 上的位置问题。 */
    public static Dialog sheet(Activity a, String[] items, final OnClick[] callbacks) {
        final Dialog d = new Dialog(a, R.style.DarkDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        int pad = dp(a, 12);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(a);
            tv.setText(items[i]);
            tv.setTextColor(a.getResources().getColor(R.color.text));
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
            tv.setBackgroundResource(R.drawable.bg_btn_ghost);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(a, 8);
            root.addView(tv, lp);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    if (callbacks != null && idx < callbacks.length && callbacks[idx] != null) {
                        callbacks[idx].onClick();
                    }
                }
            });
        }
        TextView cancel = new TextView(a);
        cancel.setText(a.getString(R.string.cancel));
        cancel.setTextColor(a.getResources().getColor(R.color.text_dim));
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(a, 12), 0, dp(a, 8));
        root.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            lp.gravity = Gravity.BOTTOM;
            w.setAttributes(lp);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        animateDialogIn(d, true);
        return d;
    }
}
