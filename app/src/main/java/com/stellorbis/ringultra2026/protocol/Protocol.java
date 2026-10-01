package com.stellorbis.ringultra2026.protocol;

import java.util.ArrayList;
import java.util.List;

import com.stellorbis.ringultra2026.util.HexUtil;

/**
 * StarRing Ultra 2026（ringUltra_2026）蓝牙控制协议。
 *
 * <p><b>传输通道：</b>经典蓝牙 SPP / RFCOMM（标准 SPP UUID
 * 00001101-0000-1000-8000-00805F9B34FB），而非 BLE GATT。</p>
 *
 * <p><b>帧格式：</b>
 * <pre>
 *  ┌────┬────┬──────┬──────┬─────────┬──────────┬──────────┐
 *  │ b0 │ b1 │ b2-3 │ b4   │ b5-6    │ b7       │ b8..     │ last │
 *  └────┴────┴──────┴──────┴─────────┴──────────┴──────────┘
 *  b0    : 方向标识，请求 0x08 / 应答 0x09
 *  b1    : 同步字，请求 0xEE / 应答 0xFF
 *  b2-b3 : 保留 0x0000
 *  b4    : 请求 0x00 / 应答 0x01
 *  b5-b6 : 命令码（大端），如 0x0101 查电量 / 0x0682 设噪声模式
 *  b7    : 全帧总长度（含校验字节本身）
 *  b8..  : payload，长度 = b7 - 9
 *  last  : 校验 = b0..last-1 全部字节求和 mod 256（求和，非 XOR / CRC）
 * </pre></p>
 *
 * <p><b>命令码规律：</b>读命令 0x0X0Y，对应写命令 0x0X8Y（写命令低字节置最高位）。
 * 例：读 0x0602 ↔ 写 0x0682。</p>
 *
 * <p><b>payload 规则：</b>写命令 payload 一律以 [0x00, …] 起头；设备以首字节作为前导校验，
 * 首字节非 0 时整条写命令会被静默丢弃。帧末的校验和由 {@link #buildFrame} 计算，
 * 不属于 payload 字段。读请求则必须携带 1 字节 payload 0x00，否则设备不回包。</p>
 *
 * <p>本协议层集中在一个文件内，便于与固件协议规格对齐时统一调整。
 * 本应用不包含固件 OTA 相关命令。</p>
 */
public final class Protocol {

    // ==================== 设备识别 ====================
    /** 设备型号标识 */
    public static final String DEVICE_TYPE = "ringUltra_2026";
    /** 产品识别码（16 进制 12 位） */
    public static final String IDENT_CODE = "525400040001";
    /** 识别码字节序列，按广播数据（AD）中的字节序排列，用于扫描兜底识别 */
    public static final byte[] IDENT_BYTES = {0x52, 0x54, 0x00, 0x04, 0x00, 0x01};
    /**
     * 设备名称识别线索（小写比较）。
     * 经典蓝牙侧（SPP 控制通道所在侧）名称形如 "StarRing Ultra 2026"；
     * BLE 侧名称形如 "StarRing Ultra_BLE"。
     */
    public static final String NAME_HINT = "starring";

    // ==================== SPP ====================
    /** 标准 SPP UUID */
    public static final java.util.UUID SPP_UUID =
            java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /**
     * 实际 RFCOMM 通道号。
     * SDP 记录解析出的通道号可能并非实际控制通道（表现为命令能发出但收不到应答），
     * 因此建连时优先按该通道号直连，失败再回退 SDP UUID。
     */
    public static final int RFCOMM_CHANNEL = 28;

    // ==================== 帧常量 ====================
    public static final int FRAME_OVERHEAD = 9;      // b0..b7 + 校验 = 9
    public static final int MAX_PAYLOAD = 512 - FRAME_OVERHEAD;

    // ==================================================================
    //  命令码表
    //  读命令 0x0X0Y / 写命令 0x0X8Y（写命令低字节置最高位）。
    //  各命令的 payload 语义见 docs/协议规范.md。
    // ==================================================================

    // -------------------- 读命令（GET，payload 为单字节 0x00） --------------------
    /** 设备信息+电量: rsp [00, 左耳状态, 01, batL, batR, 充电标志, 仓在位, 仓电量, 00] */
    public static final int CMD_INFO = 0x0101;
    /** 音效调节 EQ: rsp [00, eq] */
    public static final int CMD_EQ_GET = 0x0201;
    /** 触控映射表: rsp [00, 左耳 4 手势, 右耳 4 手势]（各为效果码） */
    public static final int CMD_TOUCH_GET = 0x0401;
    /** 噪声模式: rsp [00, m1, m2, m3, m4]（one-hot，见 NOISE_*） */
    public static final int CMD_NOISE_GET = 0x0602;
    /** 游戏模式: rsp [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_GAME_GET = 0x0603;
    /** 通透梯度: rsp [00, level]（0=深度 / 1=中度 / 2=轻度） */
    public static final int CMD_TRANS_GRAD_GET = 0x0604;
    /** 降噪触控清单: rsp [00, 正常, 通透, 风噪, 降噪] */
    public static final int CMD_ANC_TOUCH_GET = 0x0605;
    /** 降噪梯度: rsp [00, level]（0=轻度 / 1=中度 / 2=深度） */
    public static final int CMD_ANC_GRAD_GET = 0x0607;
    /** 编码协议: rsp [00, codec] */
    public static final int CMD_CODEC_GET = 0x0801;
    /** 双设备连接: rsp [00, onOff]（on=0x01 / off=0x00） */
    public static final int CMD_DUAL_GET = 0x0802;
    /** 触控开关: rsp [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_TOUCH_SW_GET = 0x0A01;
    /** 入耳检测: rsp [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_INEAR_GET = 0x0C01;
    /** 提示音等级: rsp [00, level]（0..4 对应一档..五档） */
    public static final int CMD_PROMPT_GET = 0x0E03;

    // -------------------- 写命令（SET） --------------------
    /** 设 EQ: payload [00, eq] */
    public static final int CMD_EQ_SET = 0x0281;
    /** 设触控映射（单格）: payload [00, ear, gesture, effect] */
    public static final int CMD_TOUCH_SET = 0x0481;
    /** 设噪声模式: payload [00, m1, m2, m3, m4] */
    public static final int CMD_NOISE_SET = 0x0682;
    /** 设游戏模式: payload [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_GAME_SET = 0x0683;
    /** 设通透梯度: payload [00, level]（0=深度 / 1=中度 / 2=轻度） */
    public static final int CMD_TRANS_GRAD_SET = 0x0684;
    /** 设降噪触控清单: payload [00, 正常, 通透, 风噪, 降噪] */
    public static final int CMD_ANC_TOUCH_SET = 0x0685;
    /** 设降噪梯度: payload [00, level]（0=轻度 / 1=中度 / 2=深度） */
    public static final int CMD_ANC_GRAD_SET = 0x0687;
    /** 设编码协议: payload [00, codec]（切换会令耳机重启） */
    public static final int CMD_CODEC_SET = 0x0881;
    /** 设双设备连接: payload [00, onOff]（on=0x01 / off=0x00） */
    public static final int CMD_DUAL_SET = 0x0882;
    /** 设触控开关: payload [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_TOUCH_SW_SET = 0x0A81;
    /** 设入耳检测: payload [00, onOff]（on=0x00 / off=0x01） */
    public static final int CMD_INEAR_SET = 0x0C81;
    /** 查找设备: payload [00, action, flag]（见 FIND_*） */
    public static final int CMD_FIND_SET = 0x0E82;
    /** 设提示音等级: payload [00, level]（0..4） */
    public static final int CMD_PROMPT_SET = 0x0E83;

    // ==================================================================
    //  噪声模式（0x0602 / 0x0682）payload = [00, m1, m2, m3, m4]
    //  m1 = 降噪，m2 = 正常，m3 = 风噪，m4 = 通透（one-hot）。
    //
    //  「自适应降噪」不是独立模式，而是 m1 的两个取值之一：
    //    m1 = 0x01 → 普通降噪；m1 = 0x02 → 自适应降噪。
    //  因此自适应只能在「降噪」模式下生效：其他模式的 m1 恒为 0x00，
    //  此时下发 m1 = 0x02 设备不会切换（既无独立命令，也不该生效）。
    //  界面据此把它实现为「降噪」卡片下的子开关，而不是第 5 个模式。
    // ==================================================================
    public static final int MODE_ANC = 0;        // 降噪（含自适应子开关）
    public static final int MODE_NORMAL = 1;     // 正常
    public static final int MODE_TRANS = 2;      // 通透
    public static final int MODE_WIND = 3;       // 抗风噪

    /** 互斥模式个数（= 界面上的噪声模式卡片数） */
    public static final int MODE_COUNT = 4;

    public static final byte[] NOISE_ANC      = {0x00, 0x01, 0x00, 0x00, 0x00}; // 降噪
    public static final byte[] NOISE_ADAPTIVE = {0x00, 0x02, 0x00, 0x00, 0x00}; // 降噪（自适应）
    public static final byte[] NOISE_NORMAL   = {0x00, 0x00, 0x01, 0x00, 0x00}; // 正常
    public static final byte[] NOISE_WIND     = {0x00, 0x00, 0x00, 0x01, 0x00}; // 抗风噪
    public static final byte[] NOISE_TRANS    = {0x00, 0x00, 0x00, 0x00, 0x01}; // 通透

    /** 0x0602 应答 payload -> UI 模式值（{@code MODE_*}）；不匹配返回 -1 */
    public static int payloadToMode(byte[] p) {
        if (p == null || p.length < 5) {
            return -1;
        }
        // m1 的 0x01 / 0x02 都属于「降噪」这一模式，区别只在自适应子开关
        if (p[1] == 0x01 || p[1] == 0x02) return MODE_ANC;
        if (p[2] == 0x01) return MODE_NORMAL;
        if (p[3] == 0x01) return MODE_WIND;
        if (p[4] == 0x01) return MODE_TRANS;
        return -1;
    }

    /**
     * 0x0602 应答 payload 是否处于「自适应降噪」。
     *
     * <p>只有 m1 == 0x02 才算，且仅在 {@link #MODE_ANC} 下有意义；
     * 其他模式的 m1 恒为 0x00，不能据此判断用户的偏好。</p>
     */
    public static boolean payloadIsAdaptive(byte[] p) {
        return p != null && p.length >= 2 && p[1] == 0x02;
    }

    /**
     * UI 模式值 + 自适应子开关 -> 0x0682 payload。
     *
     * <p>{@code adaptive} 只在 {@link #MODE_ANC} 下参与编码；其他模式一律忽略，
     * 因为设备只在 m1 上区分普通降噪与自适应降噪，不存在「正常的自适应」这种状态。</p>
     */
    public static byte[] modeToPayload(int mode, boolean adaptive) {
        switch (mode) {
            case MODE_ANC: return (adaptive ? NOISE_ADAPTIVE : NOISE_ANC).clone();
            case MODE_NORMAL: return NOISE_NORMAL.clone();
            case MODE_WIND: return NOISE_WIND.clone();
            case MODE_TRANS: return NOISE_TRANS.clone();
            default: return NOISE_ANC.clone();
        }
    }

    // ==================================================================
    //  梯度档位（降噪 0x0687 / 通透 0x0684）payload = [00, level]
    //  两个梯度的档位方向相反：降噪 2=深度，通透 0=深度。
    // ==================================================================
    public static final int ANC_GRAD_LIGHT = 0x00;
    public static final int ANC_GRAD_MID   = 0x01;
    public static final int ANC_GRAD_DEEP  = 0x02;

    public static final int TRANS_GRAD_DEEP  = 0x00;
    public static final int TRANS_GRAD_MID   = 0x01;
    public static final int TRANS_GRAD_LIGHT = 0x02;

    // ==================================================================
    //  音效 EQ（0x0201 / 0x0281）payload = [00, eq]
    // ==================================================================
    public static final int EQ_HIFI = 0x00;
    public static final int EQ_POP  = 0x01;
    public static final int EQ_ROCK = 0x02;

    // ==================================================================
    //  编码协议（0x0801 / 0x0881）payload = [00, codec]
    //  切换编码协议会让耳机重启。
    // ==================================================================
    public static final int CODEC_AAC_SBC = 0x00;
    public static final int CODEC_LDAC    = 0x01;
    public static final int CODEC_LHDC    = 0x02;
    public static final int CODEC_BOTH    = 0x03; // LDAC + LHDC

    // ==================================================================
    //  提示音等级（0x0E03 / 0x0E83）payload = [00, level]（0..4 = 一档..五档）
    // ==================================================================
    public static final int PROMPT_LEVEL_MAX = 4;

    // ==================================================================
    //  查找设备（0x0E82）payload = [00, action, flag]
    //
    //  响铃指令是「各自独立、可叠加」的：先后下发左耳、右耳两条指令，
    //  两只耳机会同时响铃；只要不下发停止指令就会一直响。
    //  因此「左右耳同时响铃」不需要额外动作码，直接依次下发两条指令即可。
    //
    //  停止指令是全局的（统一关闭）——协议没有提供「只停单耳」的动作，
    //  要实现单耳停止只能先全局停止，再对仍需响铃的一侧补发响铃指令。
    // ==================================================================
    public static final byte[] FIND_LEFT_RING  = {0x00, 0x00, 0x01};
    public static final byte[] FIND_RIGHT_RING = {0x00, 0x01, 0x01};
    public static final byte[] FIND_STOP       = {0x00, 0x03, 0x00};

    // ==================================================================
    //  触控映射（0x0401 读 / 0x0481 写）
    //  写（单格）：payload = [00, ear, gesture, effect]
    //  读（整表）：payload = [00, 左耳 4 个手势效果, 右耳 4 个手势效果]
    //            手势顺序：单击, 双击, 三击, 长按
    // ==================================================================
    public static final int EAR_LEFT  = 0x00;
    public static final int EAR_RIGHT = 0x01;

    public static final int GESTURE_TAP    = 0x00; // 单击
    public static final int GESTURE_DOUBLE = 0x01; // 双击
    public static final int GESTURE_TRIPLE = 0x02; // 三击
    public static final int GESTURE_LONG   = 0x05; // 长按

    public static final int EFFECT_VOL_UP    = 0x00; // 增大音量
    public static final int EFFECT_VOL_DOWN  = 0x01; // 减小音量
    public static final int EFFECT_PREV      = 0x02; // 上一曲
    public static final int EFFECT_NEXT      = 0x03; // 下一曲
    public static final int EFFECT_ASSISTANT = 0x04; // 语音助手
    public static final int EFFECT_PLAY      = 0x05; // 播放/暂停
    public static final int EFFECT_NONE      = 0x06; // 无作用
    public static final int EFFECT_ANC       = 0x07; // 降噪控制

    /** 手势展示顺序（与 0x0401 应答中的顺序一致） */
    public static final int[] GESTURE_ORDER = {
            GESTURE_TAP, GESTURE_DOUBLE, GESTURE_TRIPLE, GESTURE_LONG
    };

    /**
     * 构造 0x0481（触控映射，单格）payload = {@code [00, ear, gesture, effect]}。
     *
     * <p>前导 0x00 不可省略：设备以 payload 首字节作为前导校验，非 0 时丢弃整条写命令。
     * 帧末校验和由 {@link #buildFrame} 计算，不是 payload 的第 4 个字节。</p>
     *
     * <p>参考帧（左耳机 + 双击 + 上一曲）：
     * {@code 08 EE 00 00 00 04 81 0D 00 00 01 02 8B}，
     * 末字节 {@code 0x8B} = {@code (0x88 + ear + gesture + effect) & 0xFF}。</p>
     */
    public static byte[] touchPayload(int ear, int gesture, int effect) {
        return new byte[]{0x00, (byte) ear, (byte) gesture, (byte) effect};
    }

    /** 解析 0x0401 应答 payload -> int[2][4]（[左/右][手势顺序] 的效果码）；无效返回 null */
    public static int[][] parseTouchTable(byte[] p) {
        if (p == null || p.length < 9) {
            return null;
        }
        int[][] t = new int[2][4];
        for (int e = 0; e < 2; e++) {
            for (int g = 0; g < 4; g++) {
                t[e][g] = p[1 + e * 4 + g] & 0xFF;
            }
        }
        return t;
    }

    // ==================================================================
    //  降噪触控清单（0x0605 读 / 0x0685 写）
    //  读、写 payload 格式完全对称： = [00, 正常, 通透, 风噪, 降噪]
    //  决定「降噪控制」触控可轮换的噪声模式列表，至少需开启 2 个。
    //  该帧的固定部分求和为 0x8F，校验和 = 0x8F + 已开启数（由 buildFrame 计算）。
    // ==================================================================
    /** 最少需开启 2 个模式供触控轮换 */
    public static final int ANC_TOUCH_MIN_ON = 2;

    /**
     * 构造 0x0685 payload = {@code [00, 正常, 通透, 风噪, 降噪]}（与 0x0605 读格式一致）。
     *
     * <p>前导 0x00 不可省略：设备以首字节为前导校验，非 0 则丢弃整条命令。</p>
     */
    public static byte[] ancTouchPayload(boolean normal, boolean trans, boolean wind, boolean anc) {
        return new byte[]{
                0x00,
                (byte) (normal ? 1 : 0),
                (byte) (trans ? 1 : 0),
                (byte) (wind ? 1 : 0),
                (byte) (anc ? 1 : 0)
        };
    }

    /** 解析 0x0605 应答 payload -> boolean[4] = {正常, 通透, 风噪, 降噪}；无效返回 null */
    public static boolean[] parseAncTouch(byte[] p) {
        if (p == null || p.length < 5) {
            return null;
        }
        return new boolean[]{p[1] != 0, p[2] != 0, p[3] != 0, p[4] != 0};
    }

    // ==================================================================
    //  开关类命令编解码（payload = [00, onOff]，各命令的 on 值不同）
    // ==================================================================

    /** 0x0683 游戏模式：on=0x00 / off=0x01 */
    public static byte[] gamePayload(boolean on) {
        return new byte[]{0x00, (byte) (on ? 0x00 : 0x01)};
    }
    public static boolean gameIsOn(byte[] p) {
        return p != null && p.length >= 2 && (p[1] & 0xFF) == 0x00;
    }

    /** 0x0882 双设备连接：on=0x01 / off=0x00 */
    public static byte[] dualPayload(boolean on) {
        return new byte[]{0x00, (byte) (on ? 0x01 : 0x00)};
    }
    public static boolean dualIsOn(byte[] p) {
        return p != null && p.length >= 2 && (p[1] & 0xFF) == 0x01;
    }

    /** 0x0C81 入耳检测：on=0x00 / off=0x01 */
    public static byte[] inEarPayload(boolean on) {
        return new byte[]{0x00, (byte) (on ? 0x00 : 0x01)};
    }
    public static boolean inEarIsOn(byte[] p) {
        return p != null && p.length >= 2 && (p[1] & 0xFF) == 0x00;
    }

    /** 0x0A81 触控开关：on=0x00 / off=0x01 */
    public static byte[] touchSwitchPayload(boolean on) {
        return new byte[]{0x00, (byte) (on ? 0x00 : 0x01)};
    }
    public static boolean touchSwitchIsOn(byte[] p) {
        return p != null && p.length >= 2 && (p[1] & 0xFF) == 0x00;
    }

    /** 通用单值 payload [00, v] */
    public static byte[] valuePayload(int value) {
        return new byte[]{0x00, (byte) value};
    }

    /** 从 [00, v] 应答里取值；无则 -1 */
    public static int parseValue(byte[] p) {
        if (p != null && p.length >= 2) {
            return p[1] & 0xFF;
        }
        return -1;
    }

    // ==================================================================
    //  电量帧（0x0101）payload 域
    //  [00, 左耳状态, 01, 左耳电量%, 右耳电量%, 充电标志, 仓在位, 仓电量%, 00]
    //  索引 3 / 4 / 7 分别对应左耳机、右耳机、充电仓三格电量。
    // ==================================================================
    public static final int BAT_IDX_LEFT = 3;
    public static final int BAT_IDX_RIGHT = 4;
    /** 充电仓电量：0xFF = 不在仓 / 未知 */
    public static final int BAT_IDX_CASE = 7;
    public static final int BAT_IDX_CASE_PRESENT = 6;
    public static final int BAT_UNKNOWN = 0xFF;

    /** 通用取值（0xFF 视为未知，返回 -1） */
    public static int parseBattery(byte[] p, int index) {
        if (p == null || p.length <= index) {
            return -1;
        }
        int v = p[index] & 0xFF;
        return v == BAT_UNKNOWN ? -1 : v;
    }

    /** 充电仓电量（<0 = 未知/不在仓） */
    public static int parseCaseBattery(byte[] p) {
        return parseBattery(p, BAT_IDX_CASE);
    }

    /** 充电仓是否在位 */
    public static boolean casePresent(byte[] p) {
        return p != null && p.length > BAT_IDX_CASE_PRESENT
                && (p[BAT_IDX_CASE_PRESENT] & 0xFF) != 0x00;
    }

    // ==================================================================
    //  帧编解码
    // ==================================================================

    private Protocol() {
    }

    /** 构造一帧完整请求：总长 = 9 + payload.length */
    public static byte[] buildFrame(int cmd, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] frame = new byte[FRAME_OVERHEAD + len];
        frame[0] = 0x08;
        frame[1] = (byte) 0xEE;
        frame[2] = 0x00;
        frame[3] = 0x00;
        frame[4] = 0x00;
        frame[5] = (byte) ((cmd >> 8) & 0xFF);
        frame[6] = (byte) (cmd & 0xFF);
        frame[7] = (byte) frame.length;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            frame[8 + i] = payload[i];
        }
        for (int i = 0; i < frame.length - 1; i++) {
            sum += frame[i] & 0xFF;
        }
        frame[frame.length - 1] = (byte) (sum & 0xFF);
        return frame;
    }

    /** 解析出的一帧 */
    public static final class Frame {
        public final int cmd;        // 命令码（应答帧与请求同码）
        public final boolean response; // b0==0x09
        public final byte[] payload;   // 长度 = 帧长 - 9

        Frame(int cmd, boolean response, byte[] payload) {
            this.cmd = cmd;
            this.response = response;
            this.payload = payload;
        }

        /** payload 视作 [00, value]，取 value（无则 -1） */
        public int valueAt(int index) {
            if (payload != null && payload.length > index) {
                return payload[index] & 0xFF;
            }
            return -1;
        }

        @Override
        public String toString() {
            return "Frame{cmd=0x" + Integer.toHexString(cmd) + (response ? ",RSP" : ",REQ")
                    + ", payload=" + HexUtil.toHex(payload) + "}";
        }
    }

    /**
     * 流式帧解码器：喂入 SPP 输入流读到的原始字节，累积拼包并输出完整帧。
     * 容忍粘包、半包与噪声字节。
     */
    public static final class FrameDecoder {

        private byte[] buf = new byte[0];

        /** 喂入一批数据，返回解出的帧（可能为空） */
        public List<Frame> feed(byte[] data) {
            byte[] merged = new byte[buf.length + data.length];
            System.arraycopy(buf, 0, merged, 0, buf.length);
            System.arraycopy(data, 0, merged, buf.length, data.length);

            List<Frame> frames = new ArrayList<Frame>();
            int pos = 0;
            int limit = merged.length;
            while (true) {
                // 1. 找帧头：0x08/0x09 + 0xEE/0xFF
                while (pos + 1 < limit) {
                    int b0 = merged[pos] & 0xFF;
                    int b1 = merged[pos + 1] & 0xFF;
                    if ((b0 == 0x08 && b1 == 0xEE) || (b0 == 0x09 && b1 == 0xFF)) {
                        break;
                    }
                    pos++;
                }
                if (pos + 1 >= limit) {
                    pos = limit; // 没有帧头，全部丢弃
                    break;
                }
                // 2. 需要至少完整帧头（8 字节）
                if (pos + 8 > limit) {
                    break; // 半包，等下一批
                }
                int len = merged[pos + 7] & 0xFF;
                if (len < FRAME_OVERHEAD || len > FRAME_OVERHEAD + MAX_PAYLOAD) {
                    pos += 2; // 非法长度，跳过伪帧头
                    continue;
                }
                if (pos + len > limit) {
                    break; // 半包
                }
                // 3. 校验：b0..len-2 求和 mod 256 == len-1
                int sum = 0;
                for (int i = pos; i < pos + len - 1; i++) {
                    sum += merged[i] & 0xFF;
                }
                if ((sum & 0xFF) != (merged[pos + len - 1] & 0xFF)) {
                    pos += 2; // 校验失败，跳过伪帧头
                    continue;
                }
                int cmd = ((merged[pos + 5] & 0xFF) << 8) | (merged[pos + 6] & 0xFF);
                boolean rsp = (merged[pos] & 0xFF) == 0x09;
                byte[] payload = new byte[len - FRAME_OVERHEAD];
                System.arraycopy(merged, pos + 8, payload, 0, payload.length);
                frames.add(new Frame(cmd, rsp, payload));
                pos += len;
            }
            // 保留未消费的半包
            byte[] rest = new byte[limit - pos];
            System.arraycopy(merged, pos, rest, 0, rest.length);
            buf = rest;
            return frames;
        }
    }
}
