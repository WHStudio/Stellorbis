package com.stellorbis.ringultra2026.device;

import android.content.Context;
import android.content.SharedPreferences;

import com.stellorbis.ringultra2026.protocol.Protocol;

/**
 * 设备运行状态（内存态 + 本地持久化）。
 * 耳机端为权威存储，App 本地仅缓存最近一次回读结果，
 * 每次连接成功后通过握手读序列重新同步。
 */
public class DeviceState {

    // 电量（0x0101 应答，索引 3/4/7）
    public int batteryLeft = -1;     // -1 = 未知
    public int batteryRight = -1;
    /** 充电仓电量（0x0101 应答索引 7）；-1 = 不在仓/未知 */
    public int batteryCase = -1;

    // 噪声模式（0x0602/0x0682）：Protocol.MODE_*（4 个互斥模式）
    public int noiseMode = Protocol.MODE_ANC;

    /**
     * 自适应降噪（0x0602/0x0682 的 m1 == 0x02）。
     *
     * <p>它不是独立模式，而是「降噪」下的子开关，且<b>该偏好会被耳机记忆</b>：
     * 非降噪模式下设备的 m1 恒为 0x00（不适用），并不代表用户关掉了自适应，
     * 因此回读只在降噪模式下校正本字段，其他模式下保留本地值。</p>
     */
    public boolean adaptiveAnc;

    // 梯度档位
    public int ancGrad = Protocol.ANC_GRAD_DEEP;    // 0x0607/0x0687
    public int transGrad = Protocol.TRANS_GRAD_DEEP; // 0x0604/0x0684

    // 音效 / 编码 / 提示音
    public int eq = Protocol.EQ_HIFI;                // 0x0201/0x0281
    public int codec = Protocol.CODEC_AAC_SBC;       // 0x0801/0x0881
    public int promptLevel = 0;                      // 0x0E03/0x0E83

    // 开关
    public boolean dualDevice;       // 0x0802/0x0882（on=1）
    public boolean inEarDetection;   // 0x0C01/0x0C81（on=0）
    public boolean gameMode;         // 0x0603/0x0683（on=0）
    public boolean touchSwitch = true; // 0x0A01/0x0A81（on=0）

    // 降噪触控清单（0x0605/0x0685）：{正常, 通透, 风噪, 降噪}
    public boolean[] ancTouch = {false, false, true, true};

    // 触控映射表（0x0401/0x0481）：[左/右][单击,双击,三击,长按] = 效果码；-1 = 未知
    public int[][] touchTable = new int[2][4];

    // 最近连接设备
    public String lastDeviceName;
    public String lastDeviceAddress;

    private final SharedPreferences prefs;

    public DeviceState(Context ctx) {
        prefs = ctx.getSharedPreferences("ringultra2026_state", Context.MODE_PRIVATE);
        for (int e = 0; e < 2; e++) {
            for (int g = 0; g < 4; g++) {
                touchTable[e][g] = -1;
            }
        }
        load();
    }

    public void load() {
        batteryLeft = prefs.getInt("batteryLeft", -1);
        batteryRight = prefs.getInt("batteryRight", -1);
        batteryCase = prefs.getInt("batteryCase", -1);
        noiseMode = prefs.getInt("noiseMode", Protocol.MODE_ANC);
        adaptiveAnc = prefs.getBoolean("adaptiveAnc", false);
        ancGrad = prefs.getInt("ancGrad", Protocol.ANC_GRAD_DEEP);
        transGrad = prefs.getInt("transGrad", Protocol.TRANS_GRAD_DEEP);
        eq = prefs.getInt("eq", Protocol.EQ_HIFI);
        codec = prefs.getInt("codec", Protocol.CODEC_AAC_SBC);
        promptLevel = prefs.getInt("prompt", 0);
        dualDevice = prefs.getBoolean("dual", false);
        inEarDetection = prefs.getBoolean("inEar", true);
        gameMode = prefs.getBoolean("game", false);
        touchSwitch = prefs.getBoolean("touchSw", true);
        for (int i = 0; i < 4; i++) {
            ancTouch[i] = prefs.getBoolean("ancTouch" + i, ancTouch[i]);
        }
        for (int e = 0; e < 2; e++) {
            for (int g = 0; g < 4; g++) {
                touchTable[e][g] = prefs.getInt("touch" + e + "_" + g, -1);
            }
        }
        lastDeviceName = prefs.getString("lastName", null);
        lastDeviceAddress = prefs.getString("lastAddr", null);
    }

    public void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putInt("batteryLeft", batteryLeft);
        e.putInt("batteryRight", batteryRight);
        e.putInt("batteryCase", batteryCase);
        e.putInt("noiseMode", noiseMode);
        e.putBoolean("adaptiveAnc", adaptiveAnc);
        e.putInt("ancGrad", ancGrad);
        e.putInt("transGrad", transGrad);
        e.putInt("eq", eq);
        e.putInt("codec", codec);
        e.putInt("prompt", promptLevel);
        e.putBoolean("dual", dualDevice);
        e.putBoolean("inEar", inEarDetection);
        e.putBoolean("game", gameMode);
        e.putBoolean("touchSw", touchSwitch);
        for (int i = 0; i < 4; i++) {
            e.putBoolean("ancTouch" + i, ancTouch[i]);
        }
        for (int le = 0; le < 2; le++) {
            for (int g = 0; g < 4; g++) {
                e.putInt("touch" + le + "_" + g, touchTable[le][g]);
            }
        }
        if (lastDeviceName != null) {
            e.putString("lastName", lastDeviceName);
            e.putString("lastAddr", lastDeviceAddress);
        }
        e.apply();
    }
}
