package com.stellorbis.ringultra2026.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.animation.ValueAnimator;

import com.stellorbis.ringultra2026.R;

/**
 * 自绘开关：胶囊轨道 + 内嵌圆形滑块，Material 3 形态。
 *
 * <p>不使用平台 {@code Switch}：该控件会把 track 拉伸到自身高度，且对
 * {@code android:thumb} 的固有尺寸处理不一致，滑块容易被拉高并溢出轨道。
 * 自绘后尺寸、圆角、配色全部可控且与设计令牌一致。</p>
 *
 * <p><b>防回环约定（与 {@link SegmentedControl} 一致）：</b>
 * 只有用户点击才会触发 {@link OnCheckedChangeListener}；
 * 程序化的 {@link #setChecked(boolean)} 一律静默。</p>
 */
public class SwitchView extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    private static final int DURATION = 180;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean checked;
    private float thumbPos;
    private boolean pressed;
    private OnCheckedChangeListener listener;
    private ValueAnimator anim;

    public SwitchView(Context context) {
        this(context, null);
    }

    public SwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
    }

    public boolean isChecked() {
        return checked;
    }

    /** 程序化赋值：静默，绝不触发监听器。 */
    public void setChecked(boolean value) {
        if (checked == value) {
            return;
        }
        checked = value;
        animateTo(value ? 1f : 0f, true);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) dp(46), widthMeasureSpec),
                resolveSize((int) dp(28), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float radius = h / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor());
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);

        float margin = dp(4);
        float diameter = (h - margin * 2f) * (pressed ? 1.12f : 1f);
        float travel = w - diameter - margin * 2f;
        float left = margin + travel * thumbPos;
        float top = (h - diameter) / 2f;
        paint.setColor(thumbColor());
        rect.set(left, top, left + diameter, top + diameter);
        canvas.drawRoundRect(rect, diameter / 2f, diameter / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                pressed = false;
                invalidate();
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                if (inside) {
                    checked = !checked;
                    animateTo(checked ? 1f : 0f, true);
                    if (listener != null) {
                        listener.onCheckedChanged(checked);
                    }
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    private void animateTo(float target, boolean animate) {
        cancelAnim();
        if (!animate || getWidth() == 0) {
            thumbPos = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(thumbPos, target);
        anim.setDuration(DURATION);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                thumbPos = (Float) a.getAnimatedValue();
                invalidate();
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

    private int trackColor() {
        if (!isEnabled()) {
            return getContext().getColor(R.color.btn_disabled_bg);
        }
        return getContext().getColor(checked ? R.color.accent_fill : R.color.switch_track_off);
    }

    private int thumbColor() {
        return getContext().getColor(
                isEnabled() ? R.color.on_accent_fill : R.color.text_disabled);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
