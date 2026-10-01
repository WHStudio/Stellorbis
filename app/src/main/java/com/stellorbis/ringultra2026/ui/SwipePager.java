package com.stellorbis.ringultra2026.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 零依赖的横向翻页容器，用于让 4 个页面支持左右滑动切换。
 *
 * <p>页面即全部子 View（{@link #onFinishInflate()} 自动收集），每次只显示一页。
 * 手势判定：横向位移超过 touchSlop 且横向分量显著大于纵向分量时才接管，
 * 因此不会与页面内 ScrollView 的纵向滚动冲突。</p>
 *
 * <p>松手后按「位移超过 1/3 宽度」或「快速滑动」决定是翻页还是回弹。</p>
 *
 * <p><b>异步动画的安全约定：</b>翻页动画是异步的。若把「目标页下标」寄存在可变字段里、
 * 等待动画结束回调再去读取，新手势的 ACTION_DOWN 会把该字段重置，过期回调便可能把
 * {@code current} 写成非法值（页面被全部隐藏，随后 {@code pages.get(-1)} 抛
 * IndexOutOfBoundsException）。因此：目标页以 final 局部变量捕获进回调，用自增的
 * {@link #animGeneration} 让过期回调自我作废，且任何新手势开始前先把上一段动画落定。</p>
 */
public class SwipePager extends FrameLayout {

    public interface OnPageChangeListener {
        void onPageSelected(int index);
    }

    private static final int DURATION = 220;

    /** 横向位移需超过纵向的该倍数才接管手势，避免抢走 ScrollView 的纵向滚动 */
    private static final float HORIZONTAL_BIAS = 1.5f;
    /** 判定翻页所需的位移占页宽比例 */
    private static final float COMMIT_FRACTION = 1f / 3f;

    private final List<View> pages = new ArrayList<View>();
    private int current = 0;
    private OnPageChangeListener listener;

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;

    private float downX;
    private float downY;
    private boolean dragging;
    private int dragDir;      // 1 = 拖向下一页，-1 = 拖向上一页
    private int incoming = -1;
    private VelocityTracker tracker;

    /** 动画代次：每次作废或重建动画都自增，过期动画的结束回调据此自我作废 */
    private int animGeneration;
    /** 正在动画驶向的目标页；-1 表示当前没有进行中的翻页动画 */
    private int settleTarget = -1;

    public SwipePager(Context context) {
        this(context, null);
    }

    public SwipePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        pages.clear();
        for (int i = 0; i < getChildCount(); i++) {
            pages.add(getChildAt(i));
        }
        // 与布局里写死的初始可见性保持一致：只显示第 0 页
        current = 0;
        hardReset();
    }

    public int getCurrentItem() {
        return current;
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        listener = l;
    }

    // ==================== 程序化切换 ====================

    /** 程序化切换（页签点击）。slide=true 时带滑入动画。 */
    public void setCurrentItem(int index, boolean slide) {
        if (index < 0 || index >= pages.size()) {
            return;
        }
        // 若有未落定的翻页动画，先让它落定，避免两段动画互相踩踏
        settlePending(false);
        if (index == current) {
            return;
        }
        if (!slide || getWidth() == 0) {
            current = index;
            hardReset();
            return;
        }

        final int from = current;
        final int dir = index > current ? 1 : -1;
        final View cur = pages.get(from);
        final View inc = pages.get(index);

        inc.setVisibility(View.VISIBLE);
        inc.setTranslationX(dir * getWidth());
        inc.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        cur.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        settleTarget = index;
        final int gen = ++animGeneration;
        cur.animate().translationX(-dir * getWidth()).setDuration(DURATION).start();
        inc.animate().translationX(0f).setDuration(DURATION)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != animGeneration) {
                            return;   // 已被更新的手势或动画取代，丢弃本次结果
                        }
                        current = index;
                        settleTarget = -1;
                        hardReset();
                        if (listener != null) {
                            listener.onPageSelected(current);
                        }
                    }
                }).start();
    }

    // ==================== 手势 ====================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (pages.size() < 2 || getWidth() == 0) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                incoming = -1;
                // 上一段翻页动画若还没结束，先落定到目标页，避免状态交叠
                settlePending(true);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    return true;
                }
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * HORIZONTAL_BIAS) {
                    int dir = dx < 0 ? 1 : -1;
                    int target = current + dir;
                    if (target >= 0 && target < pages.size()) {
                        beginDrag(dir);
                        return true;
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseTracker();
                dragging = false;
                incoming = -1;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pages.size() < 2) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || incoming < 0) {
                    return false;
                }
                if (tracker != null) {
                    tracker.addMovement(ev);
                }
                applyDrag(ev.getX() - downX);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging || incoming < 0) {
                    dragging = false;
                    incoming = -1;
                    releaseTracker();
                    return false;
                }
                float dx = ev.getX() - downX;
                float vx = 0f;
                if (tracker != null) {
                    tracker.addMovement(ev);
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    vx = tracker.getXVelocity();
                }
                releaseTracker();
                finishDrag(dx, vx, ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void beginDrag(int dir) {
        // 先回到静止态：作废历史动画、隐藏其余页面并清掉硬件层
        hardReset();
        dragging = true;
        dragDir = dir;
        incoming = current + dir;

        View cur = pages.get(current);
        View inc = pages.get(incoming);
        // 拖动期间上硬件层：否则每帧都要重绘整个 ScrollView，明显掉帧
        cur.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        inc.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        inc.setTranslationX(dir * getWidth());
        inc.setVisibility(View.VISIBLE);

        if (tracker == null) {
            tracker = VelocityTracker.obtain();
        }
    }

    private void applyDrag(float dx) {
        // 只允许朝当前方向拖动：反向越界时钉在边界，避免拖出空白
        float limit = getWidth();
        if (dragDir > 0) {
            dx = Math.max(-limit, Math.min(0f, dx));
        } else {
            dx = Math.min(limit, Math.max(0f, dx));
        }
        pages.get(current).setTranslationX(dx);
        pages.get(incoming).setTranslationX(dx + dragDir * getWidth());
    }

    private void finishDrag(float dx, float vx, boolean cancelled) {
        dragging = false;
        final int width = getWidth();
        final int from = current;
        final int target = incoming;
        incoming = -1;

        // 位移方向必须与手势方向一致，否则视为用户中途反悔
        boolean aligned = (dragDir > 0 && dx < 0) || (dragDir < 0 && dx > 0);
        boolean farEnough = Math.abs(dx) > width * COMMIT_FRACTION;
        boolean fastFling = Math.abs(vx) > minFlingVelocity
                && Math.abs(dx) > touchSlop * 2
                && (vx < 0) == (dx < 0);
        final boolean commit = !cancelled && aligned
                && target >= 0 && target < pages.size()
                && (farEnough || fastFling);

        final View cur = pages.get(from);
        final View inc = pages.get(target);

        final int gen = ++animGeneration;
        settleTarget = commit ? target : -1;

        cur.animate().translationX(commit ? -dragDir * width : 0f)
                .setDuration(DURATION).start();
        inc.animate().translationX(commit ? 0f : dragDir * width)
                .setDuration(DURATION)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != animGeneration) {
                            return;   // 已被新的手势取代，禁止改写 current
                        }
                        if (commit) {
                            current = target;
                        }
                        settleTarget = -1;
                        hardReset();
                        if (commit && listener != null) {
                            listener.onPageSelected(current);
                        }
                    }
                }).start();
    }

    /**
     * 把未落定的翻页动画立即落到终点。
     *
     * <p>关键点：目标页下标不能寄存在可变字段里等待异步回调读取，
     * 否则新手势会使其失效，回调随即把 current 写成非法值。</p>
     */
    private void settlePending(boolean notify) {
        if (settleTarget >= 0) {
            int target = settleTarget;
            settleTarget = -1;
            current = target;
            hardReset();
            if (notify && listener != null) {
                listener.onPageSelected(current);
            }
        } else {
            hardReset();
        }
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    /** 作废全部进行中的动画（同时让它们的结束回调失效） */
    private void cancelAnimations() {
        animGeneration++;
        settleTarget = -1;
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).animate().cancel();
        }
    }

    /** 把全部页面恢复到「只显示 current、无位移、无硬件层」的静止态 */
    private void hardReset() {
        cancelAnimations();
        for (int i = 0; i < pages.size(); i++) {
            View p = pages.get(i);
            p.setTranslationX(0f);
            p.setAlpha(1f);
            p.setLayerType(View.LAYER_TYPE_NONE, null);
            p.setVisibility(i == current ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseTracker();
        cancelAnimations();
    }
}
