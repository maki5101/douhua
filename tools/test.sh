#!/usr/bin/env bash
# 离线测试：手势识别、多点触控解码、触摸屏查找都不依赖 Android，直接用电脑上的 JDK 编译运行。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JDK="$JAVA_HOME"
else
    JDK="$(ls -d "$HOME"/.gradle/jdks/*17*/ 2>/dev/null | head -1)"
    JDK="${JDK%/}"
fi
if [ -z "$JDK" ] || [ ! -x "$JDK/bin/javac" ]; then
    echo "找不到 JDK 17（需要 javac）。装一个：sudo apt install openjdk-17-jdk-headless，或者用 JAVA_HOME 指过去。" >&2
    exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# 只编译不依赖 Android 的部分：手势识别、触摸分流、HID 报文、多点触控解码、触摸屏查找、空中鼠标、
# 键盘面板自己收触摸时的多指定键（KeyFingers）、键位和分裂键盘的分行 / 摆放（KeyRows）、键盘界面怎么摆（KeyboardPlan）、
# 屏幕边缘留白（EdgeMargin：哪几条边留、留多少，收进去的页面交给 KeyboardPlan 后离屏幕边还够不够远）、
# 分屏透传的分流和注入流拼装（TouchSplit、InjectStream）、
# 服务的会话交接部件（独占锁、顶替策略、方向锁定、心跳和释放原因）、日志限频、带超时的子进程、实体键（设备挑选、判断状态机）、
# TouchRouter 的一次性定时器（OneShotTimer：撤掉 / 取代后在等锁的那次不调）、
# 分屏里豆花拿着键盘焦点时交给另一半（协议 22：服务挑哪个任务 FocusPick、App 什么时候发 FocusHandoff；诊断日志只列看得见的任务 FocusPick.describe）、
# ⋯ 菜单按使用频率排（MenuOrder：每一屏显示哪几项、什么顺序）、
# 打字测试的重放（KeyReplay）、adb 无线配对的密码学（SPAKE2 / HKDF / AES-GCM，向量取自 BoringSSL）、
# 豆花自己连 adbd 的协议（消息帧、adb 公钥格式、自签证书、配对 / 连接客户端）和拉起服务的 shell 命令、自动启动的退避规则、
# 主界面的十八套配色（槽位齐全、对比度、小红点的红）、
# 惯性滚动的物理（ScrollInertia）、连续滚动的描述符 / 滚轮倍率换算、CMD_CONFIG 编解码（HidTest）、uhid 的 GET / SET_REPORT（UhidEvents）、
# 小红点（TrackPoint：速度曲线、负惯性、积分、轻点、颗粒、中键滚动；协议 23 的加速 / 最快速度——默认值时和改之前逐点一样、滑块每一格单调、设置页说明里现算的数）、
# 修饰键 / Caps / 合并键的点一下、按住、锁定，实体键盘的 Fn 层（KeyHolds：真 TouchRouter 跑报文序列，加随机序列查不变量；协议 21 的编码在 ServerPartsTest；
# 协议 22 起 Fn 点一下单次锁、按住永久锁，随机序列旁边跑一份照新规矩写的模型）、
# 实体键盘 F1–F12 的第二功能和 F 行翻转（协议 24，FKeysTest：真 TouchRouter / KeyHolds 跑定码、Fn+Esc 翻转、多媒体 / 系统键两个槽一次报一个码、
# 收尾归零、随机序列；FKeyFunctions 的功能表 / 预设 / 拼区域 id、Boards 里的存储；描述符、槽在 HidTest，id 和消息编号在 ServerPartsTest）、
# 触控板背景图怎么摆（PadFit：五种显示方式、焦点、吸附、平铺相位、按形状挑设置、解码多大）、
# 主机实测扫描许发哪些键、一段怎么拆成拍（KeyScan）、
# 实体键盘第 1B 批（FixedLegends：读 res/raw 的键帽数据、抽查值；FixedLayout / FixedPanel：几何、每区块每行的范围、分流、小红点在真 ThinkPad 的位置、
# 空格那一排的三个鼠标键、键帽齐全；KeyboardPlan.planFixed：默认位置、触控板、窄窗口退回豆花布局；Boards：切换键盘不动豆花布局的 kb_ 设置；
# 笔记本尺寸（照 ThinkPad，方向键两层）和逐级缩（KeyboardPlan.fixedLevel / fitFixedRect、FixedSizing：显示哪一级、用哪份位置、拖动怎么换回）；
# 第 2 批：巴西 ABNT2 的几何、拼音的 中/英、土耳其的 i / İ，设置页语言列表的名字 / 排序 / 没数据的模式灰掉（Boards）；
# 第 3 批（FixedCompactTest）：75% / 65% / 苹果紧凑的几何、哪些组合拼得出、逐级缩的梯子、Fn 键和 Fn 层的数据、Fn 层三条触摸路径同一个 id 同样的报文，借 FixedLayoutTest 的扫描再跑一遍；
# 协议 24 的几何和键帽那一半（FixedFKeysTest）：TKL / 全尺寸 / 笔记本加的 Fn、笔记本 ⋯ 槽挪到 F 行 Ins 那格，F 键报的 id 两面都取得回来、Esc 的翻转开关、
# 配不配第二功能几何不变，键帽两面都印 / 只画一面、哪面亮，FKeyFaces 的图标和组合键的写法，每行字放不放得进键帽（放不下末尾省略，FixedPanel.fitText），
# F 行三条触摸路径同一个 id 同样的报文，小红点照旧；
# 设置页那一节（FKeyChoicesTest）：选择列表的分组 / 去重 / Mac 模式不列的三个、存的 id 认回哪一项、自定义组合键存成什么、预设那一行、
# 设置页选的每一项写进 Boards 再经真 TouchRouter / KeyHolds 按下去发的就是列表上写的码，九个 strings.xml 的名字齐全）、
# 手柄页（GamepadTouch：单键 / 面键簇 / 十字键 / 摇杆 / N64 的 C 键的触摸模型、收尾回中立；GamepadPlan：15 个预设和自定义的自动排；
# GamepadSpec：字样、轮换、自定义存成一行再读回来；GamepadConfig 编解码、手柄描述符和两路合并（HidTest、ServerPartsTest 的 GamepadMux））、
# 声音第一批（SoundTest：合成的声音在 44.1 / 48 / 96 kHz 下的时长、峰值、开头没有静音、2.5–5.5 kHz 不出尖峰、300 Hz 以下切掉、可复现；
# 候选清单和电平；变化规则——类别、行、随机范围、变体不重复、打得快变轻变短但一定出声、滚轮合并；混音——下一块就出声、同键互斥、最多 8 个、
# 软限幅、确定性、音量、候选、晚放的那一个（一对"咔哒"的"哒"）；无锁队列几个线程同时写）、
# 声音的事件层（SoundEventsTest，协议 25：声音关着时什么都不放、震动和以前一样；方案 2.3 的表一条条、四个开关、"滚轮出声不震"；
# 真 TouchRouter 跑轻点 / 双指轻点 / 双击 / 长按拖动 / 滚动 / 惯性 / 键 / 按住到点 / 合并键 / 三个鼠标键 / 被取消 / releaseAll / 手柄页）、
# 设置 → 外观 → 声音的试听台和调试版候选区（SoundBenchTest：试听台的键、候选区点一下 / 连打放的落在哪个槽、真混音器上试听 = 选了以后放的、
# 不出声的候选、打乱顺序、点一项套装放的那一小段、试听台那一小块触控板认轻点 / 划）、
# 贴字面板的"打出来"（TypeOutTest：死键组合文件的读法；34 个布局 × 有数据的模式里每个能打的字反查出的键正着打一遍就是它、
# 先后（普通 > Shift > 第三层 > 第三层 + Shift、0x64 / 0x87 放后面）、死键照主机的组合表不照 Unicode 分解；一段字的换行 / Tab / \r、打不出的数法；
# 发送节拍（假时钟：速度、配对、随时停、等焦点、不续发、只卡一次不补发、给宿主的通知约定、随机序列）；接真 TouchRouter / KeyHolds 看主机收到的报文；
# 照 MainActivity.java 源码核对接线：蓝牙模式下贴字面板打得开、藏"发送"、换提示字、和空中鼠标页互斥）、
# 每个键 / 鼠标键至少按住 40 毫秒（MinHoldTest：按得短的松开晚到、按得长的照旧、留着的松开不和后面的输入叠在一起、真 GestureEngine 的双指轻点和点击动作、随机序列）
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    src/dev/douhua/Proto.java src/dev/douhua/GestureEngine.java \
    src/dev/douhua/ScrollInertia.java src/dev/douhua/ConfigCodec.java \
    src/dev/douhua/Hid.java src/dev/douhua/TouchRouter.java src/dev/douhua/KeyHolds.java test/dev/douhua/KeyHoldsTest.java \
    src/dev/douhua/TrackPoint.java test/dev/douhua/TrackPointTest.java \
    test/dev/douhua/HidTest.java src/dev/douhua/server/UhidEvents.java \
    src/dev/douhua/server/MtDecoder.java src/dev/douhua/server/TouchDevice.java \
    src/dev/douhua/server/ScreenRotation.java src/dev/douhua/KeyboardSnap.java \
    src/dev/douhua/server/TouchGrabLock.java src/dev/douhua/server/SessionPolicy.java \
    src/dev/douhua/server/RotationLock.java src/dev/douhua/server/LogThrottle.java \
    src/dev/douhua/server/Shell.java src/dev/douhua/server/GrabCondition.java \
    src/dev/douhua/server/KeyDevices.java src/dev/douhua/server/HardKeyGate.java \
    src/dev/douhua/server/TouchSplit.java src/dev/douhua/server/InjectStream.java \
    src/dev/douhua/server/LiftWatch.java src/dev/douhua/server/OneShotTimer.java \
    src/dev/douhua/server/FocusPick.java test/dev/douhua/server/FocusPickTest.java \
    src/dev/douhua/FocusHandoff.java test/dev/douhua/FocusHandoffTest.java \
    src/dev/douhua/MenuOrder.java test/dev/douhua/MenuOrderTest.java \
    test/dev/douhua/server/PassthroughTest.java \
    test/dev/douhua/GestureEngineTest.java test/dev/douhua/server/ServerPartsTest.java \
    test/dev/douhua/KeyboardSnapTest.java test/dev/douhua/TouchRouterTest.java \
    src/dev/douhua/KeyFingers.java test/dev/douhua/KeyFingersTest.java \
    src/dev/douhua/KeyRows.java test/dev/douhua/KeyRowsTest.java \
    src/dev/douhua/KeyboardPlan.java test/dev/douhua/KeyboardPlanTest.java \
    src/dev/douhua/FixedLegends.java src/dev/douhua/FixedLayout.java src/dev/douhua/FixedPanel.java src/dev/douhua/Boards.java \
    src/dev/douhua/FKeyFunctions.java src/dev/douhua/FKeyFaces.java test/dev/douhua/FKeysTest.java \
    src/dev/douhua/FKeyChoices.java test/dev/douhua/FKeyChoicesTest.java \
    src/dev/douhua/FixedSizing.java \
    test/dev/douhua/FixedLegendsTest.java test/dev/douhua/FixedLayoutTest.java test/dev/douhua/FixedCompactTest.java test/dev/douhua/BoardsTest.java \
    test/dev/douhua/FixedFKeysTest.java \
    src/dev/douhua/EdgeMargin.java test/dev/douhua/EdgeMarginTest.java \
    src/dev/douhua/HardKeys.java src/dev/douhua/AirPointer.java \
    test/dev/douhua/AirMouseTest.java \
    tools/typelog/KeyReplay.java \
    src/dev/douhua/adb/Ed25519Group.java src/dev/douhua/adb/Spake2.java \
    src/dev/douhua/adb/Hkdf.java src/dev/douhua/adb/PairingAuth.java \
    test/dev/douhua/adb/AdbCryptoTest.java \
    src/dev/douhua/adb/AdbMessage.java src/dev/douhua/adb/AdbPublicKey.java \
    src/dev/douhua/adb/SelfSignedCert.java src/dev/douhua/adb/AdbTls.java \
    src/dev/douhua/adb/PairingClient.java src/dev/douhua/adb/AdbConnection.java \
    test/dev/douhua/adb/AdbProtocolTest.java \
    src/dev/douhua/ServerCommands.java test/dev/douhua/ServerCommandsTest.java \
    src/dev/douhua/AutoStartBackoff.java test/dev/douhua/AutoStartBackoffTest.java \
    src/dev/douhua/Theme.java src/dev/douhua/PadTexture.java test/dev/douhua/ThemeTest.java \
    src/dev/douhua/PadFit.java test/dev/douhua/PadFitTest.java \
    src/dev/douhua/KeyScan.java test/dev/douhua/KeyScanTest.java \
    src/dev/douhua/GamepadTouch.java src/dev/douhua/GamepadConfig.java \
    src/dev/douhua/GamepadSpec.java src/dev/douhua/GamepadPlan.java \
    src/dev/douhua/server/GamepadMux.java \
    test/dev/douhua/GamepadTouchTest.java test/dev/douhua/GamepadPlanTest.java \
    test/dev/douhua/GamepadSpecTest.java \
    src/dev/douhua/SoundSynth.java src/dev/douhua/SoundBank.java src/dev/douhua/SoundVary.java \
    src/dev/douhua/SoundQueue.java src/dev/douhua/SoundMixer.java test/dev/douhua/SoundTest.java \
    src/dev/douhua/SoundEvents.java test/dev/douhua/SoundEventsTest.java \
    src/dev/douhua/SoundBench.java test/dev/douhua/SoundBenchTest.java \
    src/dev/douhua/TypeOut.java src/dev/douhua/TypeOutSender.java test/dev/douhua/TypeOutTest.java \
    src/dev/douhua/MinHold.java test/dev/douhua/MinHoldTest.java
"$JDK/bin/java" -cp "$OUT" dev.douhua.GestureEngineTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.HidTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.server.ServerPartsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.server.PassthroughTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.server.FocusPickTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FocusHandoffTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.MenuOrderTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyboardSnapTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.TouchRouterTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyHoldsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FKeysTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.TrackPointTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyFingersTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyRowsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyboardPlanTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FixedLegendsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FixedLayoutTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FixedCompactTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FixedFKeysTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.BoardsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.FKeyChoicesTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.EdgeMarginTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.AirMouseTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.adb.AdbCryptoTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.adb.AdbProtocolTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.ServerCommandsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.AutoStartBackoffTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.ThemeTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.PadFitTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.KeyScanTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.GamepadTouchTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.GamepadPlanTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.GamepadSpecTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.SoundTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.SoundEventsTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.SoundBenchTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.TypeOutTest
"$JDK/bin/java" -cp "$OUT" dev.douhua.MinHoldTest
# 打字测试的分析（tools/typelog/analyze.py）：造一份小日志测对齐和分类，再用真正的 TouchRouter 重放它导出的触点
python3 tools/typelog/analyze.py --selftest "$OUT/typelog"
"$JDK/bin/java" -cp "$OUT" KeyReplay "$OUT/typelog/replay.txt" check
# 实体键盘的键帽数据（tools/kbd/，不联网）：生成器的解析规则（小样本；本机有 libxkbcommon 和 xkb 数据时顺带测 xkb 取字，没有就跳过）、
# 生成好的 res/raw/kbd_*.txt（来源和 sources.json 一致、不变量、抽查值）、主机实测扫描的分析（拿生成的数据造假主机）
python3 tools/kbd/gen_layouts.py --selftest
python3 tools/kbd/check_tables.py
python3 tools/kbd/compare.py --selftest
