package com.stellorbis.ringultra2026;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.stellorbis.ringultra2026.device.DeviceState;
import com.stellorbis.ringultra2026.protocol.Protocol;
import com.stellorbis.ringultra2026.spp.SppManager;
import com.stellorbis.ringultra2026.ui.BatteryView;
import com.stellorbis.ringultra2026.ui.SegmentedControl;
import com.stellorbis.ringultra2026.ui.SwipePager;
import com.stellorbis.ringultra2026.ui.SwitchView;

/**
 * Stellorbis —— StarRing Ultra 2026（ringUltra_2026）蓝牙控制应用。
 * 控制通道：经典蓝牙 SPP（见 {@link Protocol}）。
 *
 * <p><b>防回环原则：</b>所有控件的回调都用「值比较」判定——
 * 只有用户操作导致的值变化才下发命令；程序化回填（初始化、状态回读）与当前状态相等，
 * 自然跳过下发，避免把本地缓存推回耳机。耳机端始终为权威状态源。</p>
 *
 * <p>界面结构为「顶栏 + 4 页签 + 未连接横幅 + 4 个独立页面」，
 * 选择类控件均为自绘实现；{@link #suppressCallbacks} 是程序化回填的统一抑制开关。</p>
 *
 * <p>本应用不包含固件 OTA 功能。</p>
 */
public class MainActivity extends Activity implements SppManager.Listener {

    private static final int REQ_PERMS = 100;

    private SppManager spp;
    private DeviceState state;

    // ---- 顶栏 ----
    private View vStatusDot;
    private TextView tvTopbarDevice, tvTopbarState;
    private ImageView ivReload;
    private ObjectAnimator reloadAnim, dotPulseAnim;

    // ---- 页签与翻页容器 ----
    private final FrameLayout[] tabs = new FrameLayout[4];
    private SwipePager pager;

    // ---- 未连接横幅 ----
    private LinearLayout llBanner;

    // ---- 设备页 ----
    private TextView tvState, tvDevice;
    private TextView tvBattL, tvBattR, tvBattC;
    private BatteryView bvLeft, bvRight, bvCase;
    private Button btnScan, btnDisconnect;
    private Button btnFindLeft, btnFindRight, btnFindStop;
    /**
     * 查找设备的响铃目标。左右耳各自独立：响铃指令可叠加，
     * 同时选中时两侧一起响；而「停止」指令是全局的。
     */
    private boolean findLeftOn, findRightOn;

    // ---- 噪声页 ----
    /** 下标即 {@link Protocol#MODE_ANC}..{@link Protocol#MODE_WIND}（4 个互斥模式） */
    private final LinearLayout[] noiseCards = new LinearLayout[Protocol.MODE_COUNT];
    private SegmentedControl scAncGrad, scTransGrad;
    /**
     * 自适应降噪不是独立模式，而是「降噪」下的子开关。
     * 仅当前模式为 {@link Protocol#MODE_ANC} 时才可操作、才允许下发。
     */
    private LinearLayout rowAdaptive;
    private SwitchView swAdaptive;
    private boolean adaptiveAvailable;
    /** 下标即 {@code ancTouch[0..3]} = {正常, 通透, 风噪, 降噪} */
    private final LinearLayout[] ancTouchCards = new LinearLayout[4];
    private TextView tvAncTouchHint;

    // ---- 音效页 ----
    private LinearLayout rowEq, rowCodec;
    private TextView tvEqValue, tvCodecValue;
    private SegmentedControl scPrompt;
    private SwitchView swDual, swInEar, swGame, swTouch;

    // ---- 触控页 ----
    private SegmentedControl scEar;
    private LinearLayout llTouchMatrix;
    private final LinearLayout[] touchRows = new LinearLayout[4];
    private final TextView[] touchValueViews = new TextView[4];

    /** 当前展示的触控映射耳别（{@link Protocol#EAR_LEFT} / {@link Protocol#EAR_RIGHT}） */
    private int touchEar = Protocol.EAR_LEFT;

    /**
     * 程序化更新控件（初始化 / 状态回读）期间抑制全部回调。
     *
     * <p>{@link SegmentedControl} 程序化赋值即静默，图标卡与勾选卡也只做
     * {@code setSelected}；本开关作为统一的二次保险，并覆盖 {@link SwitchView} 的回调。</p>
     */
    private boolean suppressCallbacks = false;

    /** 未连接时被置灰的全部设置项（纯视觉层，不下沉到协议层） */
    private final List<View> settingsViews = new ArrayList<View>();
    private boolean settingsEnabled = true;

    private final Runnable hideAncTouchHint = new Runnable() {
        @Override
        public void run() {
            tvAncTouchHint.setVisibility(View.GONE);
        }
    };

    /** 梯度档位：从左到右由轻到重 */
    private static final String[] GRAD_LABELS = {"轻度", "中度", "深度"};
    private static final String[] EQ_LABELS = {"HIFI", "POP", "ROCK"};
    private static final String[] CODEC_LABELS = {"AAC/SBC", "LDAC", "LHDC", "LDAC+LHDC"};
    private static final String[] PROMPT_LABELS = {"一档", "二档", "三档", "四档", "五档"};
    /** 下标 = 效果码（与 Protocol.EFFECT_* 一一对应） */
    private static final String[] EFFECT_LABELS = {
            "增大音量", "减小音量", "上一曲", "下一曲", "语音助手", "播放/暂停", "无作用", "降噪控制"
    };
    /** 手势名称（下标 = 手势序） */
    private static final String[] GESTURE_LABELS = {"单击", "双击", "三击", "长按"};
    /** 手势图标（下标 = 手势序） */
    private static final int[] GESTURE_ICONS = {
            R.drawable.ic_gesture_tap, R.drawable.ic_gesture_double,
            R.drawable.ic_gesture_triple, R.drawable.ic_gesture_long
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        state = new DeviceState(this);
        spp = new SppManager(this, this);

        bindViews();
        collectSettingsViews();
        initActions();
        initUiFromState();
        setTab(0);
        applyConnectionUi(spp.getState());
    }

    // ==================== 绑定 ====================

    private void bindViews() {
        vStatusDot = findViewById(R.id.v_status_dot);
        tvTopbarDevice = (TextView) findViewById(R.id.tv_topbar_device);
        tvTopbarState = (TextView) findViewById(R.id.tv_topbar_state);
        ivReload = (ImageView) findViewById(R.id.iv_reload);

        tabs[0] = (FrameLayout) findViewById(R.id.tab_device);
        tabs[1] = (FrameLayout) findViewById(R.id.tab_noise);
        tabs[2] = (FrameLayout) findViewById(R.id.tab_sound);
        tabs[3] = (FrameLayout) findViewById(R.id.tab_touch);

        pager = (SwipePager) findViewById(R.id.swipe_pager);
        pager.setOnPageChangeListener(new SwipePager.OnPageChangeListener() {
            @Override
            public void onPageSelected(int index) {
                updateTabVisuals(index);
            }
        });

        llBanner = (LinearLayout) findViewById(R.id.ll_banner);

        tvState = (TextView) findViewById(R.id.tv_state);
        tvDevice = (TextView) findViewById(R.id.tv_device);
        tvBattL = (TextView) findViewById(R.id.tv_battery_left);
        tvBattR = (TextView) findViewById(R.id.tv_battery_right);
        tvBattC = (TextView) findViewById(R.id.tv_battery_case);
        bvLeft = (BatteryView) findViewById(R.id.bv_left);
        bvRight = (BatteryView) findViewById(R.id.bv_right);
        bvCase = (BatteryView) findViewById(R.id.bv_case);
        btnScan = (Button) findViewById(R.id.btn_scan);
        btnDisconnect = (Button) findViewById(R.id.btn_disconnect);
        btnFindLeft = (Button) findViewById(R.id.btn_find_left);
        btnFindRight = (Button) findViewById(R.id.btn_find_right);
        btnFindStop = (Button) findViewById(R.id.btn_find_stop);

        noiseCards[0] = (LinearLayout) findViewById(R.id.nc_anc);
        noiseCards[1] = (LinearLayout) findViewById(R.id.nc_normal);
        noiseCards[2] = (LinearLayout) findViewById(R.id.nc_trans);
        noiseCards[3] = (LinearLayout) findViewById(R.id.nc_wind);
        rowAdaptive = (LinearLayout) findViewById(R.id.row_adaptive);
        swAdaptive = (SwitchView) findViewById(R.id.sw_adaptive);

        scAncGrad = (SegmentedControl) findViewById(R.id.sc_anc_grad);
        scAncGrad.setSegments(GRAD_LABELS);
        scTransGrad = (SegmentedControl) findViewById(R.id.sc_trans_grad);
        scTransGrad.setSegments(GRAD_LABELS);

        ancTouchCards[0] = (LinearLayout) findViewById(R.id.card_anc_normal);
        ancTouchCards[1] = (LinearLayout) findViewById(R.id.card_anc_trans);
        ancTouchCards[2] = (LinearLayout) findViewById(R.id.card_anc_wind);
        ancTouchCards[3] = (LinearLayout) findViewById(R.id.card_anc_anc);
        tvAncTouchHint = (TextView) findViewById(R.id.tv_anc_touch_hint);

        rowEq = (LinearLayout) findViewById(R.id.row_eq);
        tvEqValue = (TextView) findViewById(R.id.tv_eq_value);
        rowCodec = (LinearLayout) findViewById(R.id.row_codec);
        tvCodecValue = (TextView) findViewById(R.id.tv_codec_value);

        scPrompt = (SegmentedControl) findViewById(R.id.sc_prompt);
        scPrompt.setSegments(PROMPT_LABELS);
        scPrompt.setTextSizeSp(12f);

        swDual = (SwitchView) findViewById(R.id.sw_dual);
        swInEar = (SwitchView) findViewById(R.id.sw_in_ear);
        swGame = (SwitchView) findViewById(R.id.sw_game_mode);
        swTouch = (SwitchView) findViewById(R.id.sw_touch);

        scEar = (SegmentedControl) findViewById(R.id.sc_ear);
        scEar.setSegments(new String[]{
                getString(R.string.touch_ear_left), getString(R.string.touch_ear_right)});

        llTouchMatrix = (LinearLayout) findViewById(R.id.ll_touch_matrix);
        buildTouchRows();
    }

    /**
     * 未连接时需要置灰的控件集合。
     * 不含 btn_scan（未连接时它才是唯一可用的操作）与 iv_reload（点击会给出提示）。
     */
    private void collectSettingsViews() {
        settingsViews.clear();
        for (View v : noiseCards) {
            settingsViews.add(v);
        }
        for (View v : ancTouchCards) {
            settingsViews.add(v);
        }
        for (View v : touchRows) {
            settingsViews.add(v);
        }
        settingsViews.add(scAncGrad);
        settingsViews.add(scTransGrad);
        settingsViews.add(scPrompt);
        settingsViews.add(scEar);
        settingsViews.add(rowEq);
        settingsViews.add(rowCodec);
        settingsViews.add(swDual);
        settingsViews.add(swInEar);
        settingsViews.add(swGame);
        settingsViews.add(swTouch);
        settingsViews.add(btnFindLeft);
        settingsViews.add(btnFindRight);
        settingsViews.add(btnFindStop);
    }

    /** 生成 4 条手势行；一次只展示当前耳，避免 4x2 手势矩阵的宽度挤压 */
    private void buildTouchRows() {
        llTouchMatrix.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int g = 0; g < 4; g++) {
            final int gestureIndex = g;
            LinearLayout row = (LinearLayout) inflater.inflate(
                    R.layout.row_touch_gesture, llTouchMatrix, false);
            ((ImageView) row.findViewById(R.id.iv_gesture)).setImageResource(GESTURE_ICONS[g]);
            ((TextView) row.findViewById(R.id.tv_gesture)).setText(GESTURE_LABELS[g]);
            touchValueViews[g] = (TextView) row.findViewById(R.id.tv_gesture_value);
            touchRows[g] = row;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!settingsEnabled) {
                        return;
                    }
                    showGestureDialog(gestureIndex);
                }
            });
            llTouchMatrix.addView(row);
            if (g < 3) {
                llTouchMatrix.addView(inflater.inflate(R.layout.row_divider, llTouchMatrix, false));
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 页签 ====================

    /**
     * 点击页签：先同步指示条，再让翻页容器滑到目标页。
     * 每个页签容器固定为「[0] 居中标签 + [1] 贴底指示条」两个子 View。
     */
    private void setTab(int index) {
        updateTabVisuals(index);
        pager.setCurrentItem(index, true);
    }

    /** 只更新页签指示条。翻页容器回调与页签点击共用，避免互相触发造成递归。 */
    private void updateTabVisuals(int index) {
        for (int i = 0; i < tabs.length; i++) {
            FrameLayout tab = tabs[i];
            ((TextView) tab.getChildAt(0)).setTextColor(getColor(
                    i == index ? R.color.primary_text : R.color.text_secondary));
            // 用 INVISIBLE 而非 GONE，保证标签位置不因指示条显隐而跳动
            tab.getChildAt(1).setVisibility(i == index ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** 连接中时状态点做呼吸动画，其余状态静止 */
    private void setDotPulse(boolean pulse) {
        if (pulse) {
            if (dotPulseAnim == null) {
                dotPulseAnim = ObjectAnimator.ofFloat(vStatusDot, "alpha", 1f, 0.35f);
                dotPulseAnim.setDuration(1200);
                dotPulseAnim.setRepeatCount(ValueAnimator.INFINITE);
                dotPulseAnim.setRepeatMode(ValueAnimator.REVERSE);
                dotPulseAnim.start();
            }
        } else if (dotPulseAnim != null) {
            dotPulseAnim.cancel();
            dotPulseAnim = null;
            vStatusDot.setAlpha(1f);
        }
    }

    // ==================== 连接状态与禁用态 ====================

    /** 统一渲染连接状态：顶栏状态点、设备卡、按钮显隐、未连接横幅与全局禁用态 */
    private void applyConnectionUi(int connState) {
        boolean connected = connState == SppManager.STATE_CONNECTED;
        boolean connecting = connState == SppManager.STATE_CONNECTING;

        if (connected) {
            tvState.setText(R.string.state_connected);
            tvState.setTextColor(getColor(R.color.success));
            tvTopbarState.setText(R.string.state_connected);
            setDotColor(R.color.success);
        } else if (connecting) {
            tvState.setText(R.string.state_connecting);
            tvState.setTextColor(getColor(R.color.text_secondary));
            tvTopbarState.setText(R.string.state_connecting);
            setDotColor(R.color.warning);
        } else {
            tvState.setText(R.string.state_disconnected);
            tvState.setTextColor(getColor(R.color.text_secondary));
            tvTopbarState.setText(R.string.state_disconnected);
            setDotColor(R.color.text_tertiary);
            stopReloadSpin();
        }

        // 未连接时展示主按钮，已连接时降级为「断开连接」次要按钮，避免误触
        btnScan.setVisibility(connected ? View.GONE : View.VISIBLE);
        btnDisconnect.setVisibility(connected ? View.VISIBLE : View.GONE);
        llBanner.setVisibility(connected || connecting ? View.GONE : View.VISIBLE);
        setDotPulse(connecting);

        if (!connected) {
            // 断开后耳机侧的响铃状态无从得知，清掉选中态避免误示
            findLeftOn = false;
            findRightOn = false;
            renderFind();
        }

        setSettingsEnabled(connected);
    }

    /** 置灰 / 恢复全部设置项。仅改视觉与可点性，send() 的空连接静默丢弃逻辑保持不变。 */
    private void setSettingsEnabled(boolean enabled) {
        settingsEnabled = enabled;
        // 0.5 是「明显不可用但不至于看不清控件本身」的折中值
        float alpha = enabled ? 1f : 0.5f;
        for (int i = 0; i < settingsViews.size(); i++) {
            View v = settingsViews.get(i);
            v.setEnabled(enabled);
            v.setAlpha(alpha);
        }
        // 自适应行不随全局置灰一刀切，它还要叠加「是否处于降噪模式」这一条件
        updateAdaptiveAvailability();
    }

    private void setDotColor(int colorRes) {
        vStatusDot.setBackgroundTintList(ColorStateList.valueOf(getColor(colorRes)));
    }

    // ==================== 初始回填 ====================

    private void initUiFromState() {
        suppressCallbacks = true;
        try {
            renderNoise(sanitizeNoiseMode(state.noiseMode));
            scAncGrad.setValue(clamp(ancGradPos(state.ancGrad), 0, 2));
            scTransGrad.setValue(clamp(transGradPos(state.transGrad), 0, 2));
            tvEqValue.setText(EQ_LABELS[clamp(state.eq, 0, 2)]);
            tvCodecValue.setText(CODEC_LABELS[clamp(state.codec, 0, 3)]);
            scPrompt.setValue(clamp(state.promptLevel, 0, 4));
            swDual.setChecked(state.dualDevice);
            swInEar.setChecked(state.inEarDetection);
            swGame.setChecked(state.gameMode);
            swTouch.setChecked(state.touchSwitch);
            renderAncTouch(state.ancTouch);
            renderTouchRows();
            if (state.lastDeviceName != null) {
                tvDevice.setText(state.lastDeviceName);
                tvTopbarDevice.setText(state.lastDeviceName);
            }
            renderBattery();
        } finally {
            suppressCallbacks = false;
        }
    }

    // ==================== 渲染 ====================

    /**
     * 渲染噪声模式与自适应子开关。
     *
     * <p>噪声模式只有 4 个互斥取值；「自适应降噪」是「降噪」模式下的子状态，
     * 所以与模式一起渲染，并随模式决定是否可操作。</p>
     */
    private void renderNoise(int mode) {
        for (int i = 0; i < noiseCards.length; i++) {
            noiseCards[i].setSelected(i == mode);
        }
        swAdaptive.setChecked(state.adaptiveAnc);
        updateAdaptiveAvailability();
    }

    /**
     * 自适应开关的可用性 = 已连接 且 当前处于「降噪」模式。
     *
     * <p>不可用时整行变灰并拒绝点击——因为在其他模式下设备根本不会采用
     * m1 = 0x02，此时允许操作只会给用户「已经生效」的错觉。</p>
     */
    private void updateAdaptiveAvailability() {
        adaptiveAvailable = settingsEnabled && state.noiseMode == Protocol.MODE_ANC;
        swAdaptive.setEnabled(adaptiveAvailable);
        rowAdaptive.setAlpha(adaptiveAvailable ? 1f : 0.5f);
    }

    /** 持久化的模式值可能落在合法取值域之外，兜底到「降噪」，连接后由回读校正 */
    private static int sanitizeNoiseMode(int mode) {
        return (mode >= Protocol.MODE_ANC && mode <= Protocol.MODE_WIND)
                ? mode : Protocol.MODE_ANC;
    }

    /**
     * 整体回填 4 个降噪触控勾选卡。
     *
     * <p>本方法只做 {@code setSelected}，不会派发任何回调，因此无需额外的抑制开关——
     * 「至少 2 个」的约束只在用户点击路径上判定。</p>
     */
    private void renderAncTouch(boolean[] a) {
        for (int i = 0; i < ancTouchCards.length && i < a.length; i++) {
            ancTouchCards[i].setSelected(a[i]);
        }
    }

    private void renderTouchRows() {
        for (int g = 0; g < 4; g++) {
            int effect = state.touchTable[touchEar][g];
            touchValueViews[g].setText(effect >= 0 && effect < EFFECT_LABELS.length
                    ? EFFECT_LABELS[effect] : "--");
        }
    }

    private void renderBattery() {
        bvLeft.setPercent(state.batteryLeft);
        bvRight.setPercent(state.batteryRight);
        bvCase.setPercent(state.batteryCase);

        tvBattL.setText(state.batteryLeft < 0
                ? getString(R.string.battery_unknown) : state.batteryLeft + "%");
        tvBattR.setText(state.batteryRight < 0
                ? getString(R.string.battery_unknown) : state.batteryRight + "%");
        tvBattC.setText(state.batteryCase < 0
                ? getString(R.string.battery_case_absent) : state.batteryCase + "%");
    }

    private void showAncTouchHint() {
        tvAncTouchHint.setVisibility(View.VISIBLE);
        tvAncTouchHint.removeCallbacks(hideAncTouchHint);
        tvAncTouchHint.postDelayed(hideAncTouchHint, 3000);
    }

    /** 渲染查找设备的选中态。左右耳各自独立，因此可以两个都亮 */
    private void renderFind() {
        btnFindLeft.setSelected(findLeftOn);
        btnFindRight.setSelected(findRightOn);
    }

    /**
     * 设置查找设备的响铃目标。
     *
     * <p>响铃指令各自独立、可叠加（先后下发左、右两条，两侧会同时响），
     * 所以只是「新增一侧」时直接补发该侧指令即可。
     * 但停止指令是全局的——协议没有单耳停止动作，要「只停一侧」只能
     * 先全局停止，再对仍需响铃的一侧补发。</p>
     */
    private void setFind(boolean left, boolean right) {
        boolean wasLeft = findLeftOn;
        boolean wasRight = findRightOn;
        if (left == wasLeft && right == wasRight) {
            return;
        }
        findLeftOn = left;
        findRightOn = right;
        renderFind();

        if (!left && !right) {
            send(Protocol.CMD_FIND_SET, Protocol.FIND_STOP);
            return;
        }
        if ((wasLeft && !left) || (wasRight && !right)) {
            // 有一侧被取消：先统一关闭，再补发仍需响铃的一侧
            send(Protocol.CMD_FIND_SET, Protocol.FIND_STOP);
            if (left) {
                send(Protocol.CMD_FIND_SET, Protocol.FIND_LEFT_RING);
            }
            if (right) {
                send(Protocol.CMD_FIND_SET, Protocol.FIND_RIGHT_RING);
            }
            return;
        }
        // 只是新增响铃的一侧：指令可叠加，两侧会同时响
        if (left) {
            send(Protocol.CMD_FIND_SET, Protocol.FIND_LEFT_RING);
        }
        if (right) {
            send(Protocol.CMD_FIND_SET, Protocol.FIND_RIGHT_RING);
        }
    }

    // ==================== 弹窗 ====================

    private interface OnChoice {
        void onChoice(int index);
    }

    /**
     * 单选弹窗，替代平台 Spinner。
     * 用自绘 Dialog + 自定义选项行，文字与背景全部取自设计令牌，
     * 因此不依赖平台 Dialog 主题，深色模式下同样正确。
     */
    private void showChoiceDialog(String title, final String[] options,
                                  int checkedIndex, final OnChoice callback) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_choice);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.dialog_bg);
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) dialog.findViewById(R.id.tv_dialog_title)).setText(title);

        LinearLayout container = (LinearLayout) dialog.findViewById(R.id.ll_dialog_options);
        final TextView[] rows = new TextView[options.length];
        int rowHeight = dp(48);
        int rowGap = dp(6);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(options[i]);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            // 用 Context.getColorStateList（API 23+），而非已过时的 Resources.getColorStateList(int)
            item.setTextColor(getColorStateList(R.color.dialog_option_text));
            item.setBackgroundResource(R.drawable.dialog_option_bg);
            item.setSelected(i == checkedIndex);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int k = 0; k < rows.length; k++) {
                        rows[k].setSelected(k == index);
                    }
                    dialog.dismiss();
                    callback.onChoice(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            if (i > 0) {
                lp.topMargin = rowGap;   // 选项之间留缝，避免两块蓝色背景贴在一起
            }
            container.addView(item, lp);
            rows[i] = item;
        }

        // 选项多时限制高度，超出部分滚动。
        // 期望高度必须把「选项间距 + 容器底部内边距」算进去，
        // 否则最后一页的蓝色背景会被 ScrollView 裁掉一截。
        ScrollView scroll = (ScrollView) dialog.findViewById(R.id.sv_dialog);
        int contentHeight = options.length * rowHeight
                + Math.max(0, options.length - 1) * rowGap
                + container.getPaddingBottom();
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        scroll.getLayoutParams().height = Math.min(contentHeight, maxHeight);

        dialog.show();
    }

    /**
     * 危险操作二次确认：切换编码协议会令耳机重启，
     * 故在真正下发前要求用户显式确认。
     */
    private void confirmCodecChange(final int newCodec) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_confirm);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.dialog_bg);
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) dialog.findViewById(R.id.tv_confirm_title))
                .setText(R.string.codec_confirm_title);
        ((TextView) dialog.findViewById(R.id.tv_confirm_message))
                .setText(getString(R.string.codec_confirm_message, CODEC_LABELS[newCodec]));

        Button cancel = (Button) dialog.findViewById(R.id.btn_confirm_cancel);
        cancel.setText(R.string.confirm_cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        Button ok = (Button) dialog.findViewById(R.id.btn_confirm_ok);
        ok.setText(R.string.confirm_ok);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                applyCodecChange(newCodec);
            }
        });

        dialog.show();
    }

    /** 用户确认后真正下发编码协议切换 */
    private void applyCodecChange(int codec) {
        if (codec == state.codec) {
            return;
        }
        state.codec = codec;
        state.save();
        tvCodecValue.setText(CODEC_LABELS[codec]);
        send(Protocol.CMD_CODEC_SET, Protocol.valuePayload(codec));
        toast("已切换编码协议，耳机将重启，连接会短暂中断");
    }

    // ==================== 重新加载 ====================

    /** 重新加载：全量回读耳机状态，图标旋转至回读结束 */
    private void reloadAll() {
        if (spp.getState() != SppManager.STATE_CONNECTED) {
            toast(getString(R.string.not_connected));
            return;
        }
        startReloadSpin();
        spp.handshake(null, new Runnable() {
            @Override
            public void run() {
                stopReloadSpin();
            }
        });
    }

    private void startReloadSpin() {
        stopReloadSpin();
        reloadAnim = ObjectAnimator.ofFloat(ivReload, "rotation", 0f, 360f);
        reloadAnim.setDuration(650);
        reloadAnim.setInterpolator(new LinearInterpolator());
        reloadAnim.setRepeatCount(ValueAnimator.INFINITE);
        reloadAnim.start();
    }

    private void stopReloadSpin() {
        if (reloadAnim != null) {
            reloadAnim.cancel();
            reloadAnim = null;
        }
        if (ivReload != null) {
            ivReload.setRotation(0f);
        }
    }

    // ==================== 事件接线 ====================

    private void initActions() {
        ivReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadAll();
            }
        });

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setTab(index);
                }
            });
        }

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startConnectFlow();
            }
        });
        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFind(false, false);
                spp.disconnect();
            }
        });

        // ---- 噪声模式（0x0682）----
        // 卡片下标即模式码：0 降噪 / 1 正常 / 2 通透 / 3 抗风噪
        // 「自适应降噪」已不再是独立模式，而是降噪模式下的子开关（见下方）
        for (int i = 0; i < noiseCards.length; i++) {
            final int mode = i;
            noiseCards[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (suppressCallbacks || mode == state.noiseMode) {
                        return;
                    }
                    state.noiseMode = mode;
                    state.save();
                    // adaptive 只在降噪模式下参与编码，其他模式会被忽略
                    send(Protocol.CMD_NOISE_SET,
                            Protocol.modeToPayload(mode, state.adaptiveAnc));
                    // 必须自己刷新：自定义图标卡不像 RadioGroup 会自管选中态，
                    // 只依赖设备回包会让界面停留在上一个模式上。
                    renderNoise(mode);
                }
            });
        }

        // ---- 自适应降噪：降噪模式的子开关，不是独立模式 ----
        swAdaptive.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean on) {
                requestAdaptiveChange(on);
            }
        });

        // 整行可点，方便命中；不可用时给出原因而不是静默无响应
        rowAdaptive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!adaptiveAvailable) {
                    toast(getString(R.string.noise_adaptive_only_in_anc));
                    return;
                }
                requestAdaptiveChange(!state.adaptiveAnc);
            }
        });

        // ---- 降噪梯度（0x0687）：左→右 = 轻度/中度/深度 ----
        scAncGrad.setOnValueChangeListener(new SegmentedControl.OnValueChangeListener() {
            @Override
            public void onValueChanged(int index) {
                if (suppressCallbacks) {
                    return;
                }
                int code = ancGradCode(index);
                if (code == state.ancGrad) {
                    return;
                }
                state.ancGrad = code;
                state.save();
                send(Protocol.CMD_ANC_GRAD_SET, Protocol.valuePayload(code));
            }
        });

        // ---- 通透梯度（0x0684）：左→右 = 轻度/中度/深度 ----
        scTransGrad.setOnValueChangeListener(new SegmentedControl.OnValueChangeListener() {
            @Override
            public void onValueChanged(int index) {
                if (suppressCallbacks) {
                    return;
                }
                int code = transGradCode(index);
                if (code == state.transGrad) {
                    return;
                }
                state.transGrad = code;
                state.save();
                send(Protocol.CMD_TRANS_GRAD_SET, Protocol.valuePayload(code));
            }
        });

        // ---- 降噪触控清单（0x0685，至少 2 个）----
        for (int i = 0; i < ancTouchCards.length; i++) {
            final int index = i;
            ancTouchCards[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onAncTouchToggled(index);
                }
            });
        }

        // ---- EQ（0x0281）----
        rowEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settingsEnabled) {
                    return;
                }
                showChoiceDialog(getString(R.string.dialog_title_eq), EQ_LABELS, state.eq,
                        new OnChoice() {
                            @Override
                            public void onChoice(int index) {
                                if (index == state.eq) {
                                    return;
                                }
                                state.eq = index;
                                state.save();
                                tvEqValue.setText(EQ_LABELS[index]);
                                send(Protocol.CMD_EQ_SET, Protocol.valuePayload(index));
                            }
                        });
            }
        });

        // ---- 编码协议（0x0881，切换会令耳机重启）----
        rowCodec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settingsEnabled) {
                    return;
                }
                showChoiceDialog(getString(R.string.dialog_title_codec), CODEC_LABELS, state.codec,
                        new OnChoice() {
                            @Override
                            public void onChoice(int index) {
                                if (index == state.codec) {
                                    return;
                                }
                                confirmCodecChange(index);
                            }
                        });
            }
        });

        // ---- 提示音等级（0x0E83）：0…4 = 一…五档 ----
        scPrompt.setOnValueChangeListener(new SegmentedControl.OnValueChangeListener() {
            @Override
            public void onValueChanged(int index) {
                if (suppressCallbacks || index == state.promptLevel) {
                    return;
                }
                state.promptLevel = index;
                state.save();
                send(Protocol.CMD_PROMPT_SET, Protocol.valuePayload(index));
            }
        });

        // ---- 开关 ----
        swDual.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean on) {
                if (suppressCallbacks || on == state.dualDevice) {
                    return;
                }
                state.dualDevice = on;
                state.save();
                send(Protocol.CMD_DUAL_SET, Protocol.dualPayload(on));
            }
        });
        swInEar.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean on) {
                if (suppressCallbacks || on == state.inEarDetection) {
                    return;
                }
                state.inEarDetection = on;
                state.save();
                send(Protocol.CMD_INEAR_SET, Protocol.inEarPayload(on));
            }
        });
        swGame.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean on) {
                if (suppressCallbacks || on == state.gameMode) {
                    return;
                }
                state.gameMode = on;
                state.save();
                send(Protocol.CMD_GAME_SET, Protocol.gamePayload(on));
            }
        });
        swTouch.setOnCheckedChangeListener(new SwitchView.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean on) {
                if (suppressCallbacks || on == state.touchSwitch) {
                    return;
                }
                state.touchSwitch = on;
                state.save();
                send(Protocol.CMD_TOUCH_SW_SET, Protocol.touchSwitchPayload(on));
            }
        });

        // ---- 触控映射耳别切换：纯视图切换，不下发任何命令 ----
        scEar.setOnValueChangeListener(new SegmentedControl.OnValueChangeListener() {
            @Override
            public void onValueChanged(int index) {
                if (suppressCallbacks) {
                    return;
                }
                touchEar = (index == 1) ? Protocol.EAR_RIGHT : Protocol.EAR_LEFT;
                renderTouchRows();
            }
        });

        // ---- 查找设备（0x0E82）----
        // 左右耳是独立的响铃开关，可同时选中；停止按钮统一关闭两侧
        btnFindLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settingsEnabled) {
                    return;
                }
                setFind(!findLeftOn, findRightOn);
            }
        });
        btnFindRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settingsEnabled) {
                    return;
                }
                setFind(findLeftOn, !findRightOn);
            }
        });
        btnFindStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!settingsEnabled) {
                    return;
                }
                setFind(false, false);
            }
        });
    }

    /**
     * 处理自适应降噪的开关请求（来自开关本身或整行点击）。
     *
     * <p>非降噪模式下直接拒绝并回滚显示：设备侧的 m1 恒为 0x00，
     * 下发 m1 = 0x02 不会生效，界面也不该留下「已开启」的错觉。</p>
     */
    private void requestAdaptiveChange(boolean on) {
        if (suppressCallbacks || on == state.adaptiveAnc) {
            return;
        }
        if (state.noiseMode != Protocol.MODE_ANC) {
            swAdaptive.setChecked(state.adaptiveAnc);   // 静默回滚，绝不下发
            toast(getString(R.string.noise_adaptive_only_in_anc));
            return;
        }
        state.adaptiveAnc = on;
        state.save();
        swAdaptive.setChecked(on);
        send(Protocol.CMD_NOISE_SET, Protocol.modeToPayload(Protocol.MODE_ANC, on));
    }

    /**
     * 降噪触控勾选：先算出候选值再判定约束。
     *
     * <p>不满足「至少 2 个」时直接拒绝变更（不回滚控件），并给出行内提示，
     * 不产生多余的中间态，也不会派发冗余回调。</p>
     */
    private void onAncTouchToggled(int index) {
        if (suppressCallbacks) {
            return;
        }
        boolean[] next = state.ancTouch.clone();
        next[index] = !next[index];

        int count = 0;
        for (int i = 0; i < next.length; i++) {
            if (next[i]) {
                count++;
            }
        }
        if (count < Protocol.ANC_TOUCH_MIN_ON) {
            showAncTouchHint();
            return;
        }

        state.ancTouch = next;
        state.save();
        renderAncTouch(next);
        send(Protocol.CMD_ANC_TOUCH_SET,
                Protocol.ancTouchPayload(next[0], next[1], next[2], next[3]));
    }

    /** 触控映射：手势 index 对应当前耳的某一列 */
    private void showGestureDialog(final int gestureIndex) {
        String earLabel = getString(touchEar == Protocol.EAR_RIGHT
                ? R.string.touch_ear_right : R.string.touch_ear_left);
        String title = getString(R.string.dialog_title_gesture,
                GESTURE_LABELS[gestureIndex], earLabel);

        showChoiceDialog(title, EFFECT_LABELS, state.touchTable[touchEar][gestureIndex],
                new OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        if (state.touchTable[touchEar][gestureIndex] == index) {
                            return;
                        }
                        state.touchTable[touchEar][gestureIndex] = index;
                        state.save();
                        renderTouchRows();
                        send(Protocol.CMD_TOUCH_SET, Protocol.touchPayload(
                                touchEar, Protocol.GESTURE_ORDER[gestureIndex], index));
                    }
                });
    }

    // ==================== 档位换算（一律「左轻右重」） ====================

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 降噪梯度：硬件码 0=轻度 / 1=中度 / 2=深度降噪 → 与分段条同向直取 */
    private static int ancGradPos(int code) {
        return code;
    }

    private static int ancGradCode(int pos) {
        return pos;
    }

    /** 通透梯度：硬件码 0=深度通透 / 1=中度 / 2=轻度 → 与分段条反向 */
    private static int transGradPos(int code) {
        return 2 - code;
    }

    private static int transGradCode(int pos) {
        return 2 - pos;
    }

    // ==================== 通用下发封装 ====================

    /** 已连接才下发；否则忽略（UI 已本地更新，重连后回读校正） */
    private void send(int cmd, byte[] payload) {
        if (spp.getState() == SppManager.STATE_CONNECTED) {
            spp.sendCommand(cmd, payload);
        }
    }

    // ==================== 连接流程 ====================

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            List<String> need = new ArrayList<String>();
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS && hasPermissions()
                && state.lastDeviceAddress != null
                && spp.getState() == SppManager.STATE_DISCONNECTED) {
            spp.connectByAddress(state.lastDeviceAddress);
        }
    }

    private void startConnectFlow() {
        if (!hasPermissions()) {
            requestPerms();
            return;
        }
        if (!spp.isBluetoothReady()) {
            toast("请先开启系统蓝牙");
            return;
        }
        // SPP 走经典蓝牙：已配对设备的经典侧即可直连，无需扫描
        List<BluetoothDevice> bonded = spp.findBondedCandidates();
        if (!bonded.isEmpty()) {
            connectToDevice(bonded.get(0));
        } else {
            toast("配对列表中未找到目标耳机，请先在系统设置中配对耳机");
        }
    }

    private void connectToDevice(BluetoothDevice d) {
        state.lastDeviceName = d.getName();
        state.lastDeviceAddress = d.getAddress();
        state.save();
        String name = d.getName() != null ? d.getName() : d.getAddress();
        tvDevice.setText(name);
        tvTopbarDevice.setText(name);
        spp.connect(d);
    }

    // ==================== SppManager.Listener ====================

    @Override
    public void onConnectionStateChange(final int newState) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyConnectionUi(newState);
            }
        });
    }

    @Override
    public void onFrameReceived(final Protocol.Frame frame) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                handleFrame(frame);
            }
        });
    }

    /**
     * 处理设备回包。先写 state 再刷新界面：
     * 控件回调因值相等而自然跳过，不会回环下发。
     * 注意：设备对写命令的回包既可能用读码（0x0X0Y）也可能回写码（0x0X8Y），
     * 故每个功能同时匹配读/写两个命令码。
     */
    private void handleFrame(Protocol.Frame f) {
        suppressCallbacks = true;
        try {
            applyFrame(f);
        } finally {
            suppressCallbacks = false;
        }
    }

    private void applyFrame(Protocol.Frame f) {
        final byte[] p = f.payload == null ? new byte[0] : f.payload;
        switch (f.cmd) {
            case Protocol.CMD_INFO: {
                // [00, 左耳状态, 01, batL, batR, ?, 仓在位, 仓电量, 00]
                // 设备也会周期性主动推送
                if (p.length >= 5) {
                    state.batteryLeft = Protocol.parseBattery(p, Protocol.BAT_IDX_LEFT);
                    state.batteryRight = Protocol.parseBattery(p, Protocol.BAT_IDX_RIGHT);
                    state.batteryCase = Protocol.parseCaseBattery(p);
                    state.save();
                    renderBattery();
                }
                break;
            }
            case Protocol.CMD_NOISE_GET:
            case Protocol.CMD_NOISE_SET: {
                int mode = Protocol.payloadToMode(p);
                if (mode >= 0) {
                    state.noiseMode = mode;
                    // 自适应会被耳机记忆：非降噪模式下 m1 恒为 0x00，那是「不适用」
                    // 而不是「用户关掉了自适应」。只有在降噪模式下 m1 才携带该语义，
                    // 否则切回降噪时会因本地被清零而把自适应关掉、覆盖耳机的记忆。
                    if (mode == Protocol.MODE_ANC) {
                        state.adaptiveAnc = Protocol.payloadIsAdaptive(p);
                    }
                    state.save();
                    renderNoise(mode);
                }
                break;
            }
            case Protocol.CMD_EQ_GET:
            case Protocol.CMD_EQ_SET: {
                int v = Protocol.parseValue(p);
                if (v >= 0 && v <= 2) {
                    state.eq = v;
                    state.save();
                    tvEqValue.setText(EQ_LABELS[v]);
                }
                break;
            }
            case Protocol.CMD_GAME_GET:
            case Protocol.CMD_GAME_SET: {
                if (p.length >= 2) {
                    state.gameMode = Protocol.gameIsOn(p);
                    state.save();
                    swGame.setChecked(state.gameMode);
                }
                break;
            }
            case Protocol.CMD_DUAL_GET:
            case Protocol.CMD_DUAL_SET: {
                if (p.length >= 2) {
                    state.dualDevice = Protocol.dualIsOn(p);
                    state.save();
                    swDual.setChecked(state.dualDevice);
                }
                break;
            }
            case Protocol.CMD_INEAR_GET:
            case Protocol.CMD_INEAR_SET: {
                if (p.length >= 2) {
                    state.inEarDetection = Protocol.inEarIsOn(p);
                    state.save();
                    swInEar.setChecked(state.inEarDetection);
                }
                break;
            }
            case Protocol.CMD_TOUCH_SW_GET:
            case Protocol.CMD_TOUCH_SW_SET: {
                if (p.length >= 2) {
                    state.touchSwitch = Protocol.touchSwitchIsOn(p);
                    state.save();
                    swTouch.setChecked(state.touchSwitch);
                }
                break;
            }
            case Protocol.CMD_CODEC_GET:
            case Protocol.CMD_CODEC_SET: {
                int v = Protocol.parseValue(p);
                if (v >= 0 && v <= 3) {
                    state.codec = v;
                    state.save();
                    tvCodecValue.setText(CODEC_LABELS[v]);
                }
                break;
            }
            case Protocol.CMD_ANC_GRAD_GET:
            case Protocol.CMD_ANC_GRAD_SET: {
                int v = Protocol.parseValue(p);
                if (v >= 0 && v <= 2) {
                    state.ancGrad = v;
                    state.save();
                    scAncGrad.setValue(clamp(ancGradPos(v), 0, 2));
                }
                break;
            }
            case Protocol.CMD_TRANS_GRAD_GET:
            case Protocol.CMD_TRANS_GRAD_SET: {
                int v = Protocol.parseValue(p);
                if (v >= 0 && v <= 2) {
                    state.transGrad = v;
                    state.save();
                    scTransGrad.setValue(clamp(transGradPos(v), 0, 2));
                }
                break;
            }
            case Protocol.CMD_PROMPT_GET:
            case Protocol.CMD_PROMPT_SET: {
                int v = Protocol.parseValue(p);
                if (v >= 0 && v <= Protocol.PROMPT_LEVEL_MAX) {
                    state.promptLevel = v;
                    state.save();
                    scPrompt.setValue(v);
                }
                break;
            }
            case Protocol.CMD_TOUCH_GET:
            case Protocol.CMD_TOUCH_SET: {
                int[][] t = Protocol.parseTouchTable(p);
                if (t != null) {
                    state.touchTable = t;
                    state.save();
                    renderTouchRows();
                }
                break;
            }
            case Protocol.CMD_ANC_TOUCH_GET:
            case Protocol.CMD_ANC_TOUCH_SET: {
                boolean[] a = Protocol.parseAncTouch(p);
                if (a != null) {
                    state.ancTouch = a;
                    state.save();
                    renderAncTouch(a);
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onCommandResult(final int cmd, final boolean success, final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!success && message != null
                        && spp.getState() == SppManager.STATE_CONNECTED) {
                    toast(message);
                }
            }
        });
    }

    // ==================== 生命周期 ====================

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasPermissions()) {
            if (spp.getState() == SppManager.STATE_DISCONNECTED) {
                if (state.lastDeviceAddress != null) {
                    spp.connectByAddress(state.lastDeviceAddress);
                } else {
                    startConnectFlow();
                }
            } else if (spp.getState() == SppManager.STATE_CONNECTED) {
                // 回前台补一次全量回读（设备几乎不主动推送状态）
                spp.handshake(null);
            }
        } else {
            requestPerms();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReloadSpin();
        setDotPulse(false);
        spp.disconnect();
    }
}
