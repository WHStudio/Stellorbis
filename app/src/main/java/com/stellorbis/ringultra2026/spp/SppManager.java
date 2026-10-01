package com.stellorbis.ringultra2026.spp;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.stellorbis.ringultra2026.protocol.Protocol;

/**
 * StarRing Ultra 2026 SPP(RFCOMM) 连接管理器。
 *
 * <p>该耳机的实时控制通道为经典蓝牙 SPP（UUID 00001101-…），而非 BLE GATT；
 * 已配对设备的经典侧地址可直接建立 RFCOMM socket。</p>
 *
 * <p>结构：连接线程 → 写线程（命令队列，串行发送）→ 读线程（流式帧解码）。
 * 连接成功后自动执行全量状态回读，并启动周期轮询。</p>
 */
public class SppManager {

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private static final String TAG = "RingUltraSPP";
    private static final int CONNECT_TIMEOUT_MS = 10000;

    public interface Listener {
        void onConnectionStateChange(int newState);
        void onFrameReceived(Protocol.Frame frame);
        void onCommandResult(int cmd, boolean success, String message);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile int state = STATE_DISCONNECTED;
    private BluetoothSocket socket;
    private OutputStream out;
    private Thread readThread;
    private Thread writeThread;
    private volatile boolean running = false;
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<byte[]>();

    public SppManager(Context ctx, Listener listener) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        this.listener = listener;
    }

    public int getState() {
        return state;
    }

    public boolean isBluetoothReady() {
        return adapter != null && adapter.isEnabled();
    }

    /**
     * 从系统配对列表中识别目标设备（经典侧优先）。
     * 经典侧名称如 "StarRing Ultra 2026"（SPP 在此侧），BLE 侧 "StarRing Ultra_BLE"。
     */
    @SuppressLint("MissingPermission")
    public List<BluetoothDevice> findBondedCandidates() {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        if (adapter == null) {
            return out;
        }
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String n = d.getName();
            if (n == null) {
                continue;
            }
            String ln = n.toLowerCase();
            if (ln.contains(Protocol.NAME_HINT) && !ln.contains("_ble")) {
                out.add(0, d); // 经典侧排最前
            } else if (ln.contains(Protocol.NAME_HINT)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 按地址连接（重连场景） */
    public void connectByAddress(String address) {
        if (adapter == null || address == null) {
            return;
        }
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            if (d != null) {
                connect(d);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "bad address " + address);
        }
    }

    /** 连接（异步）。会先取消系统扫描，避免 RFCOMM 建连被阻塞。 */
    public void connect(final BluetoothDevice device) {
        disconnect();
        setState(STATE_CONNECTING);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (adapter != null && adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }
                } catch (SecurityException se) {
                    // 无扫描权限时忽略
                }
                BluetoothSocket s = null;
                try {
                    // 实际控制通道为 RFCOMM channel 28。
                    // 标准 SPP UUID 走 SDP 解析可能拿到非控制通道（表现为只发不收），
                    // 因此优先反射 createRfcommSocket(28) 直连，失败再回退 SDP UUID。
                    try {
                        s = createRfcommSocket(device, Protocol.RFCOMM_CHANNEL, true);
                        s.connect();
                        Log.i(TAG, "connected via direct channel " + Protocol.RFCOMM_CHANNEL + " (insecure)");
                    } catch (Throwable e0) {
                        Log.w(TAG, "direct channel " + Protocol.RFCOMM_CHANNEL
                                + " failed: " + e0.getMessage() + ", retry secure");
                        if (s != null) {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                        try {
                            s = createRfcommSocket(device, Protocol.RFCOMM_CHANNEL, false);
                            s.connect();
                            Log.i(TAG, "connected via direct channel " + Protocol.RFCOMM_CHANNEL + " (secure)");
                        } catch (Throwable e1) {
                            Log.w(TAG, "secure direct channel failed: " + e1.getMessage()
                                    + ", fallback to SDP UUID");
                            if (s != null) {
                                try {
                                    s.close();
                                } catch (IOException ignored) {
                                }
                            }
                            // 回退：SDP UUID（secure → insecure）
                            try {
                                s = device.createRfcommSocketToServiceRecord(Protocol.SPP_UUID);
                                s.connect();
                            } catch (IOException e2) {
                                Log.w(TAG, "SDP secure failed, try insecure: " + e2.getMessage());
                                if (s != null) {
                                    try {
                                        s.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                                s = device.createInsecureRfcommSocketToServiceRecord(Protocol.SPP_UUID);
                                s.connect();
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "connect failed: " + e.getMessage());
                    if (s != null) {
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                    setState(STATE_DISCONNECTED);
                    return;
                } catch (SecurityException e) {
                    Log.e(TAG, "connect no permission: " + e.getMessage());
                    setState(STATE_DISCONNECTED);
                    return;
                }
                onSocketConnected(device, s);
            }
        }, "spp-connect");
        t.setDaemon(true);
        t.start();
    }

    private void onSocketConnected(BluetoothDevice device, BluetoothSocket s) {
        socket = s;
        try {
            out = s.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "getOutputStream failed");
            closeSocket();
            setState(STATE_DISCONNECTED);
            return;
        }
        running = true;
        writeQueue.clear();

        // 写线程：串行化所有命令发送
        writeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        byte[] frame = writeQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (frame == null) {
                            continue;
                        }
                        if (out != null) {
                            out.write(frame);
                            out.flush();
                            Log.d(TAG, "TX " + com.stellorbis.ringultra2026.util.HexUtil.toHex(frame));
                        }
                    } catch (IOException e) {
                        if (running) {
                            Log.e(TAG, "write failed: " + e.getMessage());
                            postCommandResult(-1, false, "命令发送失败: " + e.getMessage());
                            running = false;
                            closeSocket();
                            setState(STATE_DISCONNECTED);
                        }
                        return;
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "spp-write");
        writeThread.setDaemon(true);
        writeThread.start();

        // 读线程
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in;
                try {
                    in = s.getInputStream();
                } catch (IOException e) {
                    Log.e(TAG, "getInputStream failed");
                    running = false;
                    closeSocket();
                    setState(STATE_DISCONNECTED);
                    return;
                }
                Protocol.FrameDecoder decoder = new Protocol.FrameDecoder();
                byte[] chunk = new byte[1024];
                while (running) {
                    try {
                        int n = in.read(chunk);
                        if (n < 0) {
                            break;
                        }
                        if (n > 0) {
                            byte[] data = new byte[n];
                            System.arraycopy(chunk, 0, data, 0, n);
                            List<Protocol.Frame> frames = decoder.feed(data);
                            for (Protocol.Frame f : frames) {
                                Log.d(TAG, "RX " + f.toString());
                                postFrame(f);
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
                if (running) {
                    Log.w(TAG, "read loop exited (device closed?)");
                    running = false;
                    closeSocket();
                    setState(STATE_DISCONNECTED);
                }
            }
        }, "spp-read");
        readThread.setDaemon(true);
        readThread.start();

        setState(STATE_CONNECTED);

        // 握手：回读全部状态
        handshake();
        // 周期轮询：设备不主动推送，需 App 主动读才能跟随状态
        startPolling();
    }

    /**
     * 连接成功后的状态回读序列。
     * 设备主动上报很少，因此连接时需按固定顺序全量回读一遍：
     * 电量 → 噪声 → EQ → 游戏 → 双设备 → 入耳 → 编码 → 降噪梯度 → 通透梯度 →
     * 触控开关 → 提示音 → 触控映射 → 降噪触控清单。
     */
    private void handshake() {
        handshake(null);
    }

    /** 连接后全量回读；extra 为追加命令（可为 null） */
    public void handshake(final int[] extra) {
        handshake(extra, null);
    }

    /**
     * 连接后全量回读；extra 为追加命令（可为 null）。
     * onComplete 在整串读请求发送完毕（或连接中断）后回调于主线程，
     * 供界面停止「重新加载」动画。
     */
    public void handshake(final int[] extra, final Runnable onComplete) {
        final int[] base = {
                Protocol.CMD_INFO,
                Protocol.CMD_NOISE_GET,
                Protocol.CMD_EQ_GET,
                Protocol.CMD_GAME_GET,
                Protocol.CMD_DUAL_GET,
                Protocol.CMD_INEAR_GET,
                Protocol.CMD_CODEC_GET,
                Protocol.CMD_ANC_GRAD_GET,
                Protocol.CMD_TRANS_GRAD_GET,
                Protocol.CMD_TOUCH_SW_GET,
                Protocol.CMD_PROMPT_GET,
                Protocol.CMD_TOUCH_GET,
                Protocol.CMD_ANC_TOUCH_GET,
        };
        int n = base.length + (extra == null ? 0 : extra.length);
        final int[] cmds = new int[n];
        System.arraycopy(base, 0, cmds, 0, base.length);
        if (extra != null) {
            System.arraycopy(extra, 0, cmds, base.length, extra.length);
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int cmd : cmds) {
                        if (!running) {
                            return;
                        }
                        // 读请求必须带 1 字节 payload 0x00（帧长 10）；
                        // 空 payload 的读请求设备不回包。
                        sendCommand(cmd, READ_PAYLOAD);
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                } finally {
                    if (onComplete != null) {
                        main.post(onComplete);
                    }
                }
            }
        }, "spp-handshake");
        t.setDaemon(true);
        t.start();
    }

    /** 读请求统一 payload（单字节 0x00） */
    private static final byte[] READ_PAYLOAD = {0x00};

    /** 周期轮询的关键状态（设备主动上报很少，靠主动读才能跟随） */
    private static final int[] POLL_CMDS = {
            Protocol.CMD_INFO,
            Protocol.CMD_NOISE_GET,
            Protocol.CMD_GAME_GET,
            Protocol.CMD_DUAL_GET,
            Protocol.CMD_INEAR_GET,
            Protocol.CMD_TOUCH_SW_GET,
    };

    private static final long POLL_INTERVAL_MS = 4000;

    /** 周期轮询线程：连接期间持续回读关键状态，保证 UI 跟随耳机 */
    private void startPolling() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    for (int cmd : POLL_CMDS) {
                        if (!running) {
                            return;
                        }
                        sendCommand(cmd, READ_PAYLOAD);
                    }
                }
            }
        }, "spp-poll");
        t.setDaemon(true);
        t.start();
    }

    /** 发送命令（排队，由写线程串行发出） */
    public void sendCommand(int cmd, byte[] payload) {
        byte[] frame = Protocol.buildFrame(cmd, payload);
        writeQueue.offer(frame);
    }

    public void disconnect() {
        running = false;
        writeQueue.clear();
        closeSocket();
        if (state != STATE_DISCONNECTED) {
            setState(STATE_DISCONNECTED);
        }
    }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void setState(final int s) {
        state = s;
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnectionStateChange(s);
            }
        });
    }

    private void postFrame(final Protocol.Frame f) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onFrameReceived(f);
            }
        });
    }

    /**
     * 反射调用隐藏 API 直连指定 RFCOMM 通道（绕过 SDP）。
     * Android 源码中 BluetoothDevice.createRfcommSocket(int channel) 为 hide API。
     */
    private BluetoothSocket createRfcommSocket(BluetoothDevice d, int channel, boolean insecure) throws Exception {
        java.lang.reflect.Method m = BluetoothDevice.class.getDeclaredMethod(
                insecure ? "createInsecureRfcommSocket" : "createRfcommSocket", int.class);
        m.setAccessible(true);
        return (BluetoothSocket) m.invoke(d, channel);
    }

    private void postCommandResult(final int cmd, final boolean ok, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onCommandResult(cmd, ok, msg);
            }
        });
    }
}
