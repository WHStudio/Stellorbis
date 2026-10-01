package com.stellorbis.ringultra2026.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.stellorbis.ringultra2026.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖分段选择控件，段内直接标出档位文案，方向语义对用户可见。
 *
 * <p>段内直接写出「轻度 / 中度 / 深度」等档位文案，
 * 用户无需靠拖动后读右侧小字来判断方向。</p>
 *
 * <p><b>防回环关键约定：</b>只有用户点击才会回调 {@link OnValueChangeListener}；
 * 程序化 {@link #setValue(int)} 一律静默。因此状态回填（初始化、回读）天然不会
 * 把本地缓存推回耳机，且一次点击只下发一条命令。</p>
 */
public class SegmentedControl extends LinearLayout {

    public interface OnValueChangeListener {
        /** 仅由用户点击触发；index 从 0 起 */
        void onValueChanged(int index);
    }

    private final List<TextView> items = new ArrayList<TextView>();
    private int value = -1;
    private float textSizeSp = 13f;
    private OnValueChangeListener listener;

    public SegmentedControl(Context context) {
        this(context, null);
    }

    public SegmentedControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.segment_group_bg);
    }

    public void setTextSizeSp(float sp) {
        textSizeSp = sp;
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    /**
     * 重建分段。若此前已记录的值仍在合法范围则保留，否则归零。
     * 本方法不触发回调。
     */
    public void setSegments(String[] labels) {
        removeAllViews();
        items.clear();
        if (labels == null || labels.length == 0) {
            return;
        }
        int margin = dp(3);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView label = new TextView(getContext());
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            // 用 Context.getColorStateList（API 23+），而非已过时的 Resources.getColorStateList(int)
            label.setTextColor(getContext().getColorStateList(R.color.segment_text));
            label.setBackgroundResource(R.drawable.segment_item_bg);
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(margin, margin, margin, margin);
            addView(label, lp);
            label.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isEnabled() || index == value) {
                        return;
                    }
                    applyValue(index);
                    if (listener != null) {
                        listener.onValueChanged(index);
                    }
                }
            });
            items.add(label);
        }
        applyValue(value >= 0 && value < items.size() ? value : 0);
    }

    /** 程序化赋值：静默，绝不触发监听器。 */
    public void setValue(int index) {
        if (items.isEmpty()) {
            value = index;
            return;
        }
        applyValue(index < 0 || index >= items.size() ? 0 : index);
    }

    public int getValue() {
        return value;
    }

    public void setOnValueChangeListener(OnValueChangeListener l) {
        listener = l;
    }

    /** 禁用需下传到各段，否则子 View 仍可点击 */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setEnabled(enabled);
        }
    }

    private void applyValue(int index) {
        value = index;
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSelected(i == index);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
