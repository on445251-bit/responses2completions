package io.r2c.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small always-on-top floating badge showing proxy status.
 *
 * <p>Two reasons it exists: the user can see at a glance whether the port is up
 * while another app is in the foreground, and the badge itself keeps a visible
 * surface tied to the process, which makes the OS less eager to reclaim it.
 *
 * <p>Draggable, and tapping it toggles the proxy.
 */
public final class FloatingWindow {

    /** Callbacks into the owner. */
    public interface Actions {
        void onToggle();

        void onOpen();

        void onClose();
    }

    private final Context context;
    private final Actions actions;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View root;
    private TextView dot;
    private TextView label;
    private WindowManager.LayoutParams params;
    private ProxyService.Snapshot last;

    public FloatingWindow(Context context, Actions actions) {
        this.context = context;
        this.actions = actions;
    }

    /** True when the app holds the overlay permission. */
    public static boolean canDraw(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(context);
    }

    public boolean isShowing() {
        return root != null;
    }

    public void show() {
        if (root != null) return;
        if (!canDraw(context)) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(10), dp(7), dp(12), dp(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6202124"));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.parseColor("#40FFFFFF"));
        container.setBackground(bg);
        container.setElevation(dp(6));

        dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(13f);
        dot.setTextColor(Color.parseColor("#8A8A8A"));
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(dp(16), LinearLayout.LayoutParams.WRAP_CONTENT);
        dotParams.rightMargin = dp(6);
        container.addView(dot, dotParams);

        label = new TextView(context);
        label.setText("未运行");
        label.setTextSize(12f);
        label.setTextColor(Color.parseColor("#EDEDED"));
        container.addView(label);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(120);

        attachTouchHandler(container);

        try {
            windowManager.addView(container, params);
            root = container;
            if (last != null) render(last);
        } catch (RuntimeException e) {
            // Permission was revoked between the check and the call.
            root = null;
        }
    }

    public void hide() {
        if (root == null || windowManager == null) return;
        try {
            windowManager.removeView(root);
        } catch (RuntimeException ignored) {
            // already detached
        }
        root = null;
        windowManager = null;
    }

    /** Updates the badge text; safe to call before show(). */
    public void render(ProxyService.Snapshot snapshot) {
        last = snapshot;
        main.post(() -> {
            if (root == null || dot == null || label == null) return;
            if (snapshot.running) {
                dot.setTextColor(Color.parseColor("#4ADE80"));
                label.setText(":" + snapshot.port + " · 运行中");
            } else {
                dot.setTextColor(Color.parseColor("#F87171"));
                label.setText("已停止");
            }
        });
    }

    private void attachTouchHandler(View view) {
        final int touchSlop = dp(6);
        final float[] down = new float[2];
        final int[] origin = new int[2];
        final boolean[] dragged = new boolean[1];

        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    origin[0] = params.x;
                    origin[1] = params.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) dragged[0] = true;
                    params.x = origin[0] + (int) dx;
                    params.y = origin[1] + (int) dy;
                    if (windowManager != null && root != null) {
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (RuntimeException ignored) {
                            // view detached mid-gesture
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragged[0]) actions.onToggle();
                    return true;
                default:
                    return false;
            }
        });

        // Long press opens the app; a short tap toggles the proxy.
        view.setOnLongClickListener(v -> {
            actions.onOpen();
            return true;
        });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}