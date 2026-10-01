# Stellorbis

> **声明：** 本项目是独立的第三方蓝牙控制客户端，与设备厂商之间不存在任何形式的隶属、授权、赞助或背书关系。
> 项目中可能出现的设备名称与型号仅为说明互操作性所必需，相关商标归其各自权利人所有。
> 对于代码与文档包含的 AI 生成内容，请谨慎阅读与参考。
>
> **许可：** 本仓库公开源代码，仅供个人合法学习和研究用途，**未附任何开源许可证**，亦未授予任何使用许可。
> 在取得权利人明确书面许可前，请勿复制、修改、分发或用于商业用途。
>
> **兼容性：** 全部功能基于官方耳机固件 408 版本开发与验证，其他固件版本的行为可能存在差异。

面向 **StarRing Ultra 2026** 的第三方蓝牙控制应用。
通过经典蓝牙 SPP 通道与耳机通信，提供噪声控制、音效调节、触控映射与电量查询等基本能力。
注意：**不包含固件 OTA 功能**，应用未申请 `INTERNET` 权限，不会下载或上传任何数据。

| 项 | 值 |
| --- | --- |
| 应用名 | Stellorbis |
| 包名 | `com.stellorbis.ringultra2026` |
| 版本 | 26.10.01（versionCode 261001） |
| minSdk / targetSdk | 23 / 34 |
| 依赖 | 无第三方依赖（纯 `android.*` API，未使用 AndroidX） |
| 架构 | 单 Activity + 协议层 / 连接管理 / 状态缓存 |

协议的完整说明（帧结构、命令码、payload 语义、状态同步模型）见[`docs/协议规范.md`](docs/协议规范.md)。

---

## 1. 传输通道

控制通道是**经典蓝牙 SPP / RFCOMM**，不是 BLE GATT：

- SPP UUID：`00001101-0000-1000-8000-00805F9B34FB`
- 实际 RFCOMM 通道号：**28**。SDP 记录解析出的通道号可能并非实际控制通道
  （表现为命令能发出但收不到应答），因此连接时优先按通道号直连，失败再回退 SDP UUID。
- 设备经典侧名称形如 `StarRing Ultra 2026`，BLE 侧为 `StarRing Ultra_BLE`，
  取名称含 `starring` 且不含 `_ble` 的配对项。
- 已配对设备可直接建连，无需扫描；建连前会先取消正在进行的系统扫描，避免阻塞连接。

## 2. 帧格式

```text
 b0  b1  b2-b3  b4   b5-b6   b7    b8..      last
 08  EE  00 00  00   cmdHiLo  len   payload   checksum
 ↑请求           ↑请求        ↑全帧总长（含校验字节）
 09  FF  ...     01
 ↑应答
```

- 命令码 `b5-b6` 大端。**读命令 `0x0X0Y`，写命令 `0x0X8Y`**（写命令低字节置最高位）。
- `len` = 9 + payload 长度。
- **checksum = 帧内除末字节外的所有字节求和 mod 256**（是求和，不是 XOR / CRC）。
- **读请求必须带 1 字节 payload `0x00`**（帧长 `0x0A`）：空 payload 的读请求设备不回包。
- **写命令 payload 一律以 `[0x00, …]` 起头**；首字节非 0 时设备会静默丢弃整条命令。
- 设备对写操作的应答可能用读码也可能用写码，故解析时对同一功能的读写命令码同时匹配；
  写操作的最终结果以随后的一次回读为准。

## 3. 命令概览

### 读命令（payload 恒为 `[00]`）

| 功能 | 命令码 |
| --- | --- |
| 设备信息 / 电量 | `0x0101` |
| 音效 EQ | `0x0201` |
| 触控映射表 | `0x0401` |
| 噪声模式 | `0x0602` |
| 游戏模式 | `0x0603` |
| 通透梯度 | `0x0604` |
| 降噪触控清单 | `0x0605` |
| 降噪梯度 | `0x0607` |
| 编码协议 | `0x0801` |
| 双设备连接 | `0x0802` |
| 触控开关 | `0x0A01` |
| 入耳检测 | `0x0C01` |
| 提示音等级 | `0x0E03` |

### 写命令

| 功能 | 命令码 | payload 要点 |
| --- | --- | --- |
| 音效 EQ | `0x0281` | `[00, eq]`，HIFI 00 / POP 01 / ROCK 02 |
| 触控映射（单格） | `0x0481` | `[00, ear, gesture, effect]` |
| 噪声模式 | `0x0682` | `[00, m1, m2, m3, m4]` one-hot；`m1` = 降噪，其中 `01` 普通 / `02` 自适应 |
| 游戏模式 | `0x0683` | `[00, onOff]`，**开 00 / 关 01** |
| 通透梯度 | `0x0684` | `[00, level]`，0 深度 / 1 中度 / 2 轻度 |
| 降噪触控清单 | `0x0685` | `[00, 正常, 通透, 风噪, 降噪]`，至少开启 2 个 |
| 降噪梯度 | `0x0687` | `[00, level]`，0 轻度 / 1 中度 / 2 深度 |
| 编码协议 | `0x0881` | `[00, codec]`，AAC/SBC 00 · LDAC 01 · LHDC 02 · 两者 03 |
| 双设备连接 | `0x0882` | `[00, onOff]`，**开 01 / 关 00** |
| 触控开关 | `0x0A81` | `[00, onOff]`，**开 00 / 关 01** |
| 入耳检测 | `0x0C81` | `[00, onOff]`，**开 00 / 关 01** |
| 查找设备 | `0x0E82` | `[00, action, flag]`，左 `00 01` · 右 `01 01` · 停止 `03 00` |
| 提示音等级 | `0x0E83` | `[00, level]`，0..4 对应一至五档 |

> ⚠️ 各开关的**开 / 关极性并不统一**：游戏、入耳、触控为「开 00」，双设备为「开 01」。
> ⚠️ **切换编码协议会使耳机重启**，连接会短暂中断。
> ⚠️ **「自适应降噪」不是独立模式**，只是 `m1` 的一个取值：非降噪模式下 `m1` 恒为 `00`，
> 此时下发 `02` 不会生效。该偏好由耳机记忆，切回降噪时会恢复。
> ⚠️ **响铃指令独立可叠加**：先后下发左右两条即两侧同时响；而**停止是全局的**，
> 协议没有单耳停止动作，要只停一侧只能先全局停止再补发另一侧。

其余细节（触控效果码表、电量帧字段布局、梯度方向差异、尚未明确的字段等）见[`docs/协议规范.md`](docs/协议规范.md)。

## 4. 状态同步与防回环

设备主动上报很少（空闲期仅偶发一次电量帧），因此状态同步由 App 侧主动发起：

1. 连接成功后立即**全量回读** 13 条读命令，每条间隔 120 ms；
2. 回到前台（`onResume`）补一次全量回读；
3. 连接期间**每 4 秒轮询**关键状态（电量 / 噪声 / 游戏 / 双设备 / 入耳 / 触控开关）；
4. 右上角提供手动「重新加载」，图标旋转动画跟随回读进度。

**防回环**：耳机端始终是权威状态源，本地仅缓存最近一次回读结果。所有控件回调都做值比较，
只有用户操作造成的值变化才下发；程序化回填期间由统一抑制开关屏蔽回调，因此不会把本地缓存推回耳机。

自绘控件的契约与此一致，改控件类型时不得破坏：

- `SegmentedControl` 只有用户点击才回调，`setValue()` 一律静默，且一次点击只发一条命令。
- `SwitchView` 同理：只有点击才回调，`setChecked()` 静默。
- 噪声模式图标卡、降噪触控勾选卡的回填只做 `setSelected`，不派发任何回调，
  因此不需要额外的整体回填抑制开关。
- 触控映射以单选弹窗提交，时机为「用户点击列表项时」，时序可控。
- **自定义图标卡不像 `RadioGroup` 会自管选中态**：点击后必须自己调用对应的 `render*()`
  刷新，否则界面会停留在上一个选中项（仅靠设备回包校正）。

## 5. 项目结构

```tree
Stellorbis/
├── app/
│   ├── build.gradle                            # 模块构建脚本（AGP 7.4.2 / compileSdk 34）
│   └── src/main/
│       ├── AndroidManifest.xml                 # 蓝牙权限、入口 Activity、应用图标
│       ├── res/
│       │   ├── layout/activity_main.xml        # 主界面：自绘顶栏 + 4 页签 + 未连接横幅 + 4 个页面
│       │   ├── layout/dialog_choice.xml        # 单选弹窗
│       │   ├── layout/dialog_confirm.xml       # 危险操作二次确认弹窗
│       │   ├── layout/row_touch_gesture.xml    # 单条触控映射行（运行时按当前耳生成 4 条）
│       │   ├── layout/row_divider.xml          # 设置行之间的 1dp 分隔线
│       │   ├── drawable/                       # 自绘形状（卡片/按钮/分段/开关/弹窗）与 20 个矢量图标
│       │   ├── color/                          # 颜色状态表（分段文字、图标卡着色、开关着色等）
│       │   ├── mipmap-*/launcher_icon*.png    # 应用图标：legacy 图标 + adaptive 三层（background/foreground/monochrome）
│       │   ├── mipmap-anydpi-v26/launcher_icon.xml        # 自适应图标定义（API 26+）
│       │   ├── values/{colors,dimens,strings,styles}.xml   # 设计令牌：颜色 / 尺寸 / 文案 / 样式
│       │   └── values-night/{colors,styles}.xml            # 深色令牌（Android 10+ 随系统生效）
│       └── java/com/stellorbis/ringultra2026/
│           ├── MainActivity.java               # UI 编排 + 命令接线 + 防回环
│           ├── ui/SegmentedControl.java        # 自研分段选择控件（仅用户点击才回调）
│           ├── ui/SwitchView.java              # 自绘开关（胶囊轨道 + 内嵌圆点，用户点击才回调）
│           ├── ui/SwipePager.java              # 横向翻页容器（左右滑动切换 4 个页面）
│           ├── ui/BatteryView.java             # 自绘电量图形（外框 + 占比填充 + 分级配色）
│           ├── spp/SppManager.java             # 连接、全量回读、周期轮询、帧收发线程
│           ├── protocol/Protocol.java          # 协议层：帧编解码 + 命令码与 payload 语义
│           ├── device/DeviceState.java         # 状态缓存 + SharedPreferences 持久化
│           └── util/HexUtil.java
├── docs/
│   └── 协议规范.md                              # 协议规范（帧格式 / 命令表 / 状态同步）
├── build.ps1                                   # 无 Gradle 构建管线（支持 -Release）
├── build.gradle / settings.gradle / gradle.properties
├── .gitignore
└── README.md
```

## 6. 构建

### 方式 A：build.ps1 无 Gradle 管线

适用于未安装 Android Studio / Gradle 的环境，只依赖 SDK build-tools：

```powershell
# 普通构建（保留调试信息）→ dist/Stellorbis-v26.10.01.apk
powershell -ExecutionPolicy Bypass -File build.ps1

# 发布构建（d8 --release，剥离调试信息）→ dist/Stellorbis-v26.10.01-release.apk
powershell -ExecutionPolicy Bypass -File build.ps1 -Release
```

管线六步：`aapt2 compile → aapt2 link(+R.java) → javac(1.8) → d8 → zipalign → apksigner sign/verify`，
末步同时输出 `SHA-256` 校验文件，产物与校验文件统一落在 `dist/`。

- `-Release` 使用 `d8 --release` 剥离调试信息以减小 dex，**不做 shrink / 混淆**，
  因此 `SppManager` 中对 `BluetoothDevice.createRfcommSocket` 的反射调用不受影响。
- SDK / JDK 路径可用 `$env:ANDROID_SDK_HOME` / `$env:JAVA_HOME` 覆盖；
  默认取 `~/.apk-tools/` 下的工具链（`android-sdk` + `jdk-tmp/jdk-17.0.20.1+1`）。
- 若 `keystore/debug.keystore` 不存在，脚本会自动生成一个 RSA-2048 自签名密钥。
  **该密钥仅供联调**；对外发布请用正式密钥重签（apksigner 同参数替换 keystore 即可）。

> **`build.ps1` 必须保持纯 ASCII 内容。** Windows PowerShell 5.1 以系统 ANSI 代码页读取
> 无 BOM 的 UTF-8 文件，非 ASCII 注释会被解码为乱码，并可能吞掉紧随其后的代码行，
> 导致构建参数静默失效。英文注释可彻底规避。

### 方式 B：标准 Gradle

用 Android Studio 打开工程根目录（需 SDK Platform 34 + JDK 17）。本工程无任何仓库依赖。

### 安装

```bash
adb install -r dist/Stellorbis-v26.10.01-release.apk
```

`keystore/debug.keystore` 是不可再生的文件，丢失后新构建的包无法覆盖升级已安装版本，请单独备份。

## 7. 界面说明

### 顶栏与页签

| 位置 | 内容 |
| --- | --- |
| 顶栏左 | 连接状态圆点（灰=未连接 / 橙=连接中 / 已连接=成功色）+ 设备名 + 状态文本 |
| 顶栏右 | 重新加载图标（回读期间旋转，沿用 `ObjectAnimator`） |
| 页签 | 设备 / 噪声 / 音效 / 触控，文字垂直居中 + 底部居中的 3dp 短指示条 |

页面支持**左右滑动翻页**（`SwipePager`），也支持点击页签切换，两种方式双向同步。
滑动判定要求横向位移超过 touchSlop 且明显大于纵向分量，因此不会抢走页面内 `ScrollView` 的纵向滚动。

> **翻页动画的异步安全**：翻页动画（220 ms）是异步的，而连续快速滑动会让新手势打断旧动画。
> 因此目标页必须以 `final` 局部变量捕获进结束回调，并用自增的 `animGeneration` 让过期回调自我作废；
> 任何新手势开始前先把上一段动画落定。否则会出现「动画回调读取已被重置的字段 →
> `current` 变成非法值 → 所有页面被 GONE 掉 → `pages.get(-1)` 抛 `IndexOutOfBoundsException`」。
> 拖动期间页面会切到硬件层（`LAYER_TYPE_HARDWARE`），松手后还原，避免每帧重绘整个 `ScrollView`。

### 四个页面

| 页签 | 区块 |
| --- | --- |
| 设备 | 连接卡（状态 + 设备名 + 连接/断开按钮，二者按状态互斥显示）、电量三格（自绘电池图形 + 百分比）、查找设备（左/右响铃**可同时选中** + 停止响铃） |
| 噪声 | 4 个互斥噪声模式图标卡（2×2）、**自适应降噪子开关**（从属于「降噪」）、降噪强度 · 通透程度（自绘分段控件）、降噪触控轮换（2×2 勾选卡） |
| 音效 | 均衡器 EQ / 编码协议（「值 + 箭头」行 + 单选弹窗）、提示音等级（5 段分段控件）、其他设置（双设备连接 / 入耳检测 / 游戏模式开关） |
| 触控 | 触控功能开关、触控映射（左耳 · 右耳分段切换 + 4 条手势行） |

### 交互要点

- **未连接横幅**：未连接时顶部显示「设备未连接，当前显示的是上次缓存值」，
  所有设置项置灰（`alpha 0.5` + `setEnabled(false)`），避免控件点击无响应。
  电量卡保持原样，因为它显示的是合理的缓存值。
- **危险操作分级**：切换编码协议前弹二次确认，明确「耳机将重启、连接中断约 10 秒」；
  断开连接为次要按钮，降低误触概率。
- **单选弹窗**：EQ / 编码协议 / 触控映射三个功能共用 `dialog_choice.xml`，
  文字与背景全部取自设计令牌，不依赖平台 Dialog 主题，深色模式下同样正确。
- **降噪触控「至少 2 个」**：不满足时直接拒绝变更并显示行内提示（3 秒后淡出）。
- **自适应降噪是「降噪」的子开关**，不是第 5 个模式：仅降噪模式下可操作，
  其他模式下变灰并给出原因提示；该偏好由耳机记忆，切回降噪时仍按原值下发。
- **查找设备左右耳各自独立**：可同时选中、两侧一起响；「停止响铃」统一关闭两侧。
  协议没有单耳停止动作，因此取消单侧时会先全局停止、再对另一侧补发响铃。

> **两个梯度的硬件方向相反**（降噪 `0=轻度 2=深度`，通透 `0=深度 2=轻度`）。
> 界面统一为「左轻右重」，换算在 `MainActivity.ancGradPos` / `transGradPos` 中完成。
> 自绘分段控件把「轻度 / 中度 / 深度」直接写在段内，方向语义对用户可见。

### 深色模式

`values-night/` 提供全套颜色令牌。**Android 10（API 29）及以上随系统深色主题自动生效**；
本工程 minSdk 23 且不使用 AndroidX，API 23–28 无法在应用内切换深色，将始终使用浅色令牌。

### 设计令牌纪律

- 颜色只有 `values/colors.xml` 一个来源，**禁止硬编码**（含 Java 侧，一律 `getColor(R.color.*)`）。
- 间距取自 4dp 栅格，集中在 `values/dimens.xml`。
- 字阶全应用仅 7 级，禁止出现令牌之外的字号。
- 矢量图标的 `fillColor` 一律为 `#FF000000`，由 `android:tint` 着色，以便适配深色模式。

## 8. 调试

```bash
adb logcat -s RingUltraSPP     # 全部 TX/RX 帧（含 payload）
```
