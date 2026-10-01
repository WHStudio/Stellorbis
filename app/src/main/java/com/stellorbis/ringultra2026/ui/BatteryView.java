package com.stellorbis.ringultra2026.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.stellorbis.ringultra2026.R;

/**
 * 电量图形：圆角电池外框 + 正极凸点 + 按占比填充的内芯。
 *
 * <p>填充色按电量分级：&lt;20% 危险色、20–59% 主色、&ge;60% 成功色。
 * {@code percent < 0} 表示未知（不在仓 / 未回读），只画灰色空壳而不填充。</p>
 */
public class BatteryView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bodyRect = new RectF();
    private final RectF tipRect = new RectF();
    private final RectF fillRect = new RectF();

    private int percent = -1;

    public BatteryView(Context context) {
        this(context, null);
    }

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPercent(int p) {
        int v = p < 0 ? -1 : (p > 100 ? 100 : p);
        if (v == percent) {
            return;
        }
        percent = v;
        invalidate();
    }

    public int getPercent() {
        return percent;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize((int) dp(36), widthMeasureSpec);
        int h = resolveSize((int) dp(17), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float stroke = Math.max(1f, h * 0.11f);
        float gap = stroke;
        float tipW = Math.max(1.5f, w * 0.06f);
        float tipH = h * 0.34f;
        float bodyW = w - tipW - gap;
        float radius = h * 0.26f;
        float half = stroke / 2f;

        bodyRect.set(half, half, bodyW - half, h - half);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(getContext().getColor(R.color.text_tertiary));
        canvas.drawRoundRect(bodyRect, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        tipRect.set(bodyW + gap, (h - tipH) / 2f, bodyW + gap + tipW, (h + tipH) / 2f);
        canvas.drawRoundRect(tipRect, tipW / 2f, tipW / 2f, paint);

        if (percent < 0) {
            return;
        }
        // 描边以路径为中心向两侧各扩 stroke/2，可见内沿在 bodyRect 内缩 stroke/2 处。
        // 再留 0.75*stroke 的呼吸间隙：左右与上下必须取同一个 pad，
        // 否则满电时填充会贴住右侧而左侧留缝，视觉上「绿色与灰色的缝隙左右不一致」。
        float pad = stroke;
        float innerWidth = bodyRect.width() - pad * 2f;
        float fillWidth = innerWidth * percent / 100f;
        if (fillWidth <= 0.5f) {
            return;
        }
        fillRect.set(bodyRect.left + pad, bodyRect.top + pad,
                bodyRect.left + pad + fillWidth, bodyRect.bottom - pad);
        float fillRadius = Math.min(radius * 0.7f, fillRect.height() / 2f);
        paint.setColor(fillColor());
        canvas.drawRoundRect(fillRect, fillRadius, fillRadius, paint);
    }

    private int fillColor() {
        if (percent < 20) {
            return getContext().getColor(R.color.danger);
        }
        if (percent < 60) {
            return getContext().getColor(R.color.primary);
        }
        return getContext().getColor(R.color.success);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
