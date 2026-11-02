@AGENTS.md

（上面一行把 AGENTS.md 里的协作红线引进来；红线只在 AGENTS.md 改，这样别的 AI 工具也读得到同一份。）

# 豆花儿：架构、规矩和文档索引

## 文档放哪（2026-11-02 瘦身）

这份 CLAUDE.md 每次对话都整份读进来（连子任务），所以只放每次干活都要知道的：架构图、文件索引、发布、多语言、不变量、测试设备的规矩、技术债。其余的挪到 docs/：

- `docs/files.md`：完整文件表（每个文件做什么、改过什么）。改文件职责时改那里，下面"文件索引"的那一行跟着改。
- `docs/decisions.md`：决策记录（为什么这么做、试过什么、和方案哪里不一样、审查后改了什么）。新的决策记录接着往后加。
- `docs/testing.md`：各组离线测试测了什么、真机怎么测。加了测试、改了测试组，在那里补。
- `docs/in-progress.md`：写完了、还没在真机上验证的批次，每批要看的点。验证过的删掉。
- `docs/wishlist.md`：用户提过、还没做完的功能。

规矩：
- 以后都要守的规则写进下面的"不变量"，不要只写在决策记录里。
- 正文里说"见决策记录 / 进行中 / 测试一节 / 想做的功能"的，指 docs/ 下对应的文件。
- 子任务、工作流的提示里只点名它要读的那几份 docs，不让它通读。

## 架构

两种连接方式（设置里选，Prefs.btMode）。桌面模式两个进程，代码在同一个 APK 里：

```
外屏触摸屏 /dev/input/eventN ──(服务独占)──┐
                                           ↓
       Server / Session（shell 身份，app_process 拉起；主线程只 accept，每条连接一个会话线程）
         ├ TouchReader + MtDecoder：读原始多点触控，用系统的换算矩阵转成屏幕坐标
         ├ 分屏 / 小窗时（App 报的窗口没占满）按手指按下的位置分（TouchSplit）：豆花窗口外、系统手势带里的 → Passthrough 注入回系统
         ├ 按 App 报来的区域分流：触控板 → GestureEngine；键盘键 → UhidKeyboard；按钮 → 报告给 App；
         │  "左键" → 直接按住鼠标左键；小红点 → TrackPoint（按住时 douhua-tick 线程按节拍推）；三个鼠标键 → 按住左 / 右键、中键滚动
         ├ UhidMouse / UhidKeyboard：/dev/uhid 造的 USB 鼠标和键盘 → 系统输入 → 外接屏 / 当前屏幕
         │  （双指开合 = UhidKeyboard 按住 Ctrl + UhidMouse 滚轮；鼠标带竖滚轮的分辨率倍率，读线程回应内核的 SET / GET_REPORT）
         │  UhidControls（协议 24，"Douhua Keyboard Controls"，和键盘同建同关）：实体键盘 F 行第二功能的多媒体键（报告 3）/ 系统键（报告 6）
         ├ 手柄页（REGION_GAMEPAD）→ GamepadTouch → UhidGamepad（第三个 uhid 设备，第一次用到才建、会话结束才关）；
         │  服务没接管时 App 本地分流出的经 CMD_GAMEPAD 发来，两路合并（GamepadMux）再写 → Moonlight → 电脑上的 Xbox 手柄
         ├ KeyReader + HardKeyGate：独占音量键设备（带电源键的只旁观），判断点一下 / 按住报给 App，没配的键注入补发
         └ Injector：带 displayId 往外接屏注入按键、往这块屏注入触摸（分屏透传）（隐藏 API，反射）
                ↑ 布局 / 参数 / 心跳 / 按键 / 实体键配置 / 手柄报文和参数 / 交焦点   ↓ 震动 / 三指四指手势 / 按钮事件 / 修饰键 / 接管状态 / 实体键 / 手柄报文 / 转发的多媒体·系统键
       MainActivity（App，普通权限）：画界面、震动、执行动作；127.0.0.1:37601
```

文件索引（一行一个；完整说明见 `docs/files.md`）：

- `src/dev/douhua/Proto.java`：协议常量，两边共用
- `src/dev/douhua/GestureEngine.java`：手势识别
- `src/dev/douhua/ScrollInertia.java`：惯性滚动的物理
- `src/dev/douhua/ConfigCodec.java`：CMD_CONFIG 的编解码
- `src/dev/douhua/TouchRouter.java`：触摸分流 + 键盘修饰键
- `src/dev/douhua/KeyHolds.java`：按着时要计时的键
- `src/dev/douhua/Hid.java`：鼠标 / 键盘 / 媒体键的 HID 描述符和报文、键盘按键状态
- `src/dev/douhua/BtHid.java`：蓝牙模式，注册成蓝牙键盘鼠标、连上次那台、发报文
- `src/dev/douhua/Haptics.java`：震动，点击、咔嗒、颗粒、拖动时的持续轻震、按键的"嗒"、惯性的"放开 / 刹住"
- `src/dev/douhua/Sounds.java`：声音，进程一份，主界面和设置页的试听台共用
- `src/dev/douhua/SoundSynth.java`：合成的声音
- `src/dev/douhua/SoundBank.java`：声音的清单
- `src/dev/douhua/SoundVary.java`：变化规则
- `src/dev/douhua/SoundQueue.java`：主线程 → 混音线程的请求队列
- `src/dev/douhua/SoundMixer.java`：混音：声部上限、同组淡出、软限幅、晚放的"哒"
- `src/dev/douhua/SoundEvents.java`：声音的事件层
- `src/dev/douhua/SoundBench.java`：设置 → 外观 → 声音那一节的纯 Java 部分（试听台、候选区）
- `src/dev/douhua/MainActivity.java`：界面、按钮事件、贴字面板、数字键盘、旋转、动作执行
- `src/dev/douhua/FixedSizing.java`：实体键盘逐级缩的状态
- `src/dev/douhua/KeyboardFace.java`：键盘页上两块面板对外的共同方法
- `src/dev/douhua/FixedLegends.java`：实体键盘的键帽字和布局清单的读法
- `src/dev/douhua/FixedLayout.java`：实体键盘的几何
- `src/dev/douhua/FixedPanel.java`：实体键盘摆到像素以后：键段、范围、区域、键帽上的字、小红点空位
- `src/dev/douhua/Boards.java`：键盘页用哪块和实体键盘自己的设置
- `src/dev/douhua/FKeyFunctions.java`：实体键盘 F1–F12 的第二功能
- `src/dev/douhua/FKeyFaces.java`：F1–F12 的第二功能在键帽上画什么
- `src/dev/douhua/FKeyChoices.java`：设置 → 键盘 → 实体键盘"F1–F12 的第二功能"那一节的规则
- `src/dev/douhua/FixedData.java`：实体键盘的数据从 res/raw 读进来
- `src/dev/douhua/FixedKeyboardView.java`：实体键盘的面板，逐级缩的几级、量尺寸时换到哪一级
- `src/dev/douhua/KeyboardPanel.java`：外接键盘的键帽、触摸、高亮
- `src/dev/douhua/KeyRows.java`：键位和排布
- `src/dev/douhua/KeyboardPlan.java`：键盘界面怎么摆
- `src/dev/douhua/KeyFingers.java`：键盘面板自己收触摸时每根手指按的是哪个键
- `src/dev/douhua/KeyboardScreen.java`：键盘界面，可调大小位置的键盘 + 小触控板占满整页
- `src/dev/douhua/MenuLayer.java`：⋯ 菜单，主界面和键盘屏共用一份
- `src/dev/douhua/Style.java`：共用样式
- `src/dev/douhua/Theme.java`：主界面的十八套配色，每套写几个底子、其余按规则推
- `src/dev/douhua/SettingsActivity.java`：设置页，顶上固定一排标签
- `src/dev/douhua/ThemeGrid.java`：设置 → 外观的主题色块网格
- `src/dev/douhua/ButtonStrip.java`：自定义按键，屏幕矮时自动分列
- `src/dev/douhua/PadArea.java`：触控板 + 数字键盘的排布
- `src/dev/douhua/PadStack.java`：主界面的触控板 + 顶上正中的 ⋯ + 可选的三键条
- `src/dev/douhua/TrackPoint.java`：小红点（模拟 ThinkPad TrackPoint）：推光标的曲线、节拍积分、中键滚动杆、左键杆
- `src/dev/douhua/TrackPointView.java`：小红点的样子，红帽 + 九个凹点
- `src/dev/douhua/GamepadSpec.java`：手柄页摆哪些控件：15 个预设和自定义
- `src/dev/douhua/GamepadPlan.java`：手柄页自动排
- `src/dev/douhua/GamepadTouch.java`：手柄页的触摸模型
- `src/dev/douhua/GamepadConfig.java`：手柄的手感参数和 CMD_GAMEPAD_CONFIG 的编解码
- `src/dev/douhua/GamepadScreen.java`：手柄页，按 GamepadPlan 摆、GamepadPainter 画
- `src/dev/douhua/GamepadPainter.java`：手柄控件的画法（手柄页和设置页的预览共用）
- `src/dev/douhua/GamepadPreview.java`：设置 → 手柄的预览
- `src/dev/douhua/GamepadGrid.java`：设置 → 手柄的预设网格
- `src/dev/douhua/MouseKeys.java`：一排左 / 中 / 右三个鼠标键
- `src/dev/douhua/TrackpadView.java`：触控板的画法：底色、背景图、边缘滚动带细线、状态字、边框
- `src/dev/douhua/PadFit.java`：触控板背景图怎么摆
- `src/dev/douhua/PadImage.java`：触控板背景图的 Android 部分
- `src/dev/douhua/PadBgSection.java`：设置 → 外观的"触控板背景图"一节
- `src/dev/douhua/Actions.java`：统一的动作清单
- `res/values*/strings.xml`：九种语言的界面文字
- `art/icon.webp` → `tools/make_icon.py`：App 图标原图
- 彩蛋：设置页"通用"页最下面的版本号快速连点 5 下
- `src/dev/douhua/Combos.java`：自定义组合键，可选的键、拼 id、起名字、迁移上一版键盘快捷行的存储格式
- `src/dev/douhua/HardKeys.java`：音量 + / − 的"点一下 / 按住"判断
- `src/dev/douhua/AirPointer.java`：陀螺仪 + 重力 → 光标位移
- `src/dev/douhua/CenterRunner.java`：主线程上按固定间隔一帧帧发位移
- `src/dev/douhua/AirMouse.java`：空中指针，按住移动 / 按住拖动 / 按住左键、陀螺仪按需开关、移动颗粒
- `src/dev/douhua/DebugMoves.java`：只在可调试包里：经 adb 广播发调试位移、开调试日志、主机实测扫描
- `src/dev/douhua/KeyScan.java`：主机实测扫描的纯逻辑
- `src/dev/douhua/RoundPad.java`：空中鼠标页的圆钮，圆 + 3×3 九个小点
- `tools/air-log.sh`：空中鼠标双日志：手机端调试日志 + 电脑端 KWin 记光标 + evtest
- `tools/type-log.sh`：打字测试记录：键盘触点日志 + logcat + 电脑浏览器打字页
- `tools/type-analyze.sh` + `tools/typelog/`：分析一次打字测试，analyze.py 对齐、误按率、每个键的落点偏移、电脑丢键核对
- `tools/air-analyze.sh` + `tools/airlog/`：分析一次空中鼠标双日志：送达节奏、重放 AirPointer
- `tools/kbd-scan.sh` + `tools/kbd/`：实体键盘布局：主机实测扫描 + 键帽数据生成（见 tools/kbd/README.md）
- `res/raw/kbd_*.txt`：生成的键帽数据，kbd_mac / win / linux / android 每家一份
- `res/raw/licenses.txt`：开源许可页的全文，Unicode CLDR、AOSP、xkeyboard-config、BoringSSL、Apache-2.0 全文
- `tools/adb-device.sh`：选哪台手机，start/stop-server、selftest、build --install 共用
- `src/dev/douhua/KeyboardSnap.java`：调整键盘时的轻微吸附
- `src/dev/douhua/EdgeMargin.java`：屏幕边缘留白
- `src/dev/douhua/Prefs.java`：设置键名和默认值
- `src/dev/douhua/MenuOrder.java`：⋯ 菜单有哪几项、什么顺序、哪一屏显示哪几项
- `src/dev/douhua/Link.java`：App 端连接，断线每秒重连
- `src/dev/douhua/FocusHandoff.java`：App 什么时候请服务交焦点
- `src/dev/douhua/DexDisplay.java`：找外接屏
- `src/dev/douhua/DisplayReceiver.java`：服务叫醒它，接上外接屏弹通知
- `src/dev/douhua/server/Server.java`：服务入口，主线程只 accept
- `src/dev/douhua/server/Session.java`：一次 App 连接，建虚拟设备、发 hello、独占时机、把触摸交给 TouchRouter、把结果写到 /dev/uhid 和发回 App
- `src/dev/douhua/server/OneShotTimer.java`：TouchRouter 的一次性定时器
- `src/dev/douhua/server/TaskFocus.java`：分屏里豆花拿着键盘焦点时把焦点交给另一半
- `src/dev/douhua/server/FocusPick.java`：挑谁接焦点
- `src/dev/douhua/server/SessionPolicy.java`：新连接来时旧会话怎么办
- `src/dev/douhua/server/GrabCondition.java`：独占条件里的心跳和释放原因码
- `src/dev/douhua/server/TouchGrabLock.java`：全服务一把锁，同一时刻只有一个会话能独占触摸屏
- `src/dev/douhua/server/RotationLock.java`：方向锁定全服务一份
- `src/dev/douhua/server/Shell.java`：带超时的子进程，只用 JDK
- `src/dev/douhua/server/SystemServices.java`：从 ServiceManager 现取系统服务代理
- `src/dev/douhua/server/LogThrottle.java`：同一条错误限频，展开异常原因链
- `src/dev/douhua/server/TouchReader.java`：独占并读触摸屏，poll 100ms 检查是否该释放
- `src/dev/douhua/server/MtDecoder.java`：多点触控协议 B 解码
- `src/dev/douhua/server/LiftWatch.java`：分屏透传接管前判断系统那边还有没有手指按着
- `src/dev/douhua/server/TouchSplit.java`：分屏透传：每根手指在按下时定归豆花还是归系统
- `src/dev/douhua/server/InjectStream.java`：分屏透传，归系统的手指拼成自洽的触摸流
- `src/dev/douhua/server/Passthrough.java`：分屏透传，读线程只改 InjectStream、排队
- `src/dev/douhua/server/Injector.java`：注入按键和触摸
- `src/dev/douhua/server/TouchDevice.java`：从 `dumpsys input` 找屏幕对应的触摸屏和 RawToDisplay 矩阵
- `src/dev/douhua/server/ScreenRotation.java`：物理屏坐标 → 当前方向坐标
- `src/dev/douhua/server/Evdev.java`：打开 / 独占输入设备
- `src/dev/douhua/server/Uhid.java`：/dev/uhid 造 USB HID 设备的公共部分
- `src/dev/douhua/server/UhidEvents.java`：uhid 的 GET / SET_REPORT 请求怎么解、回复怎么拼
- `src/dev/douhua/server/UhidGamepad.java`：虚拟 USB 手柄 "Douhua Gamepad"
- `src/dev/douhua/server/GamepadMux.java`：桌面模式 uhid 手柄的两路合并、接管时清 App 那路、没变不写
- `src/dev/douhua/server/UhidMouse.java` / `UhidKeyboard.java`：虚拟 USB 鼠标 / 键盘
- `src/dev/douhua/server/UhidControls.java`：虚拟键盘的第二个接口 "Douhua Keyboard Controls"
- `src/dev/douhua/server/PeerUid.java`：查 /proc/net/tcp 确认连进来的是谁
- `src/dev/douhua/server/TestClient.java`：服务自测，冒充 App 接管几秒后停发心跳
- `src/dev/douhua/server/KeyDevices.java`：跑 `getevent -i` 解析输入设备，挑能独占的音量键设备
- `src/dev/douhua/server/HardKeyGate.java`：音量键的判断，配了动作的交给 HardKeys、同设备上没配的透传注入
- `src/dev/douhua/server/KeyReader.java`：独占并读音量键设备、不独占地读旁观设备
- `src/dev/douhua/server/CallState.java`：问 Telecom 是不是在来电响铃 / 通话中
- `src/dev/douhua/server/DisplayWatcher.java`：每 1.5 秒查屏幕列表
- `src/dev/douhua/SelfStart.java`：手机自己拉起服务的编排
- `src/dev/douhua/SelfStartJob.java`：开机后等连上 Wi-Fi 调 SelfStart.autoStart
- `src/dev/douhua/AutoStartBackoff.java`：自动启动的退避规则
- `src/dev/douhua/BootReceiver.java`：开机广播，设置里"自动启动服务"开着就排 SelfStartJob
- `src/dev/douhua/PairingService.java`：配对用的前台服务，通知里 RemoteInput 输配对码
- `src/dev/douhua/ServiceSection.java`：设置页"通用"页最上面一节"豆花儿服务"
- `src/dev/douhua/AdbKeys.java`：豆花的 adb 密钥
- `src/dev/douhua/AdbMdns.java`：NsdManager 找 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`
- `src/dev/douhua/ServerCommands.java`：经 adb 执行的 shell 命令
- `src/dev/douhua/adb/`：adb 配对 / 连接协议（SPAKE2、TLS、消息帧），纯 JDK、不 import android.*

蓝牙模式：报文最后都经 BtHid（系统的 BluetoothHidDevice，键盘 + 鼠标 + 媒体键合成一个描述符）→ 配对的电脑 / 平板 / 电视。
触摸谁来收看服务在不在：
- 服务开着（布局带 relay=1）：和桌面模式一样由服务独占触摸屏、TouchRouter 分流，系统手势碰不到；鼠标键盘报文不写 uhid，
  发回 App（Proto.MSG_MOUSE / MSG_KEYBOARD），App 交给 BtHid。按钮、震动、手势照常走原来的消息。
- 服务没开 / 没接管：只有 App 一个进程，MainActivity 自己收触摸（dispatchTouchEvent）→ 同一个 TouchRouter → BtHid。
  这时系统的下拉通知栏、上滑回桌面挡不住，侧滑返回只能靠左右窄边的排除区和吞掉返回。

服务怎么拉起来（两条路，结果一样：shell 身份的 `app_process … dev.douhua.server.Server uid=<App uid>`）：
- 电脑：`tools/start-server.sh`（adb USB / 无线），照旧保留。
- 手机自己（SelfStart，不依赖 Shizuku）：豆花经"无线调试"连本机 adbd（127.0.0.1），用配对过的密钥以 shell 身份执行同样的命令。
  配对一次（设置页"豆花儿服务"→ 开始配对 → 系统里"使用配对码配对设备" → 在豆花的通知里输配对码），之后打开豆花时 / 开机连上 Wi-Fi 时自动启动。

构建不用 Gradle：`tools/build.sh` 直接 javac（Java 8 语法）→ d8 → aapt2 → zipalign → apksigner。
SDK 在 `.sdk/`（gitignored），调试签名在 `.private/debug.keystore`。

## 发布

- 版本号在 `VERSION`（x.y.z，versionCode = x*10000 + y*100 + z）。
- 正式包：`tools/package.sh`（要求工作区干净，从 HEAD 构建）；试打包：`tools/package.sh --snapshot`。
  源码只从 git 取（git archive 到临时目录），正式签名密钥库临时拷进去、用完删；成品拆开扫描，有私人信息或密钥就失败、不动 dist/。
- 本机专属、不进 git 的东西：
  - `.private/NOTES.md`（测试手机型号、地址等）、`.private/patterns`（额外拦截的字面串）
  - `.private/debug.keystore`（调试签名）、`.private/release.keystore`（正式签名，**丢了就没法给已安装的用户升级，要备份**）
  - `.env`：`DOUHUA_KEYSTORE_PASS`（正式签名口令），模板 `.env.example`
- 新克隆先启用提交钩子：`git config core.hooksPath .githooks`。
- 调试版和正式版签名不同，手机上从调试版换正式版要先卸载。

## 多语言

- 对外的名字：中文"豆花儿"（app_name 和中文界面、README），日文"豆花"，其它语言音译 Douhua / 더우화 / دوهوا。
  包名 dev.douhua、项目目录、代码和服务日志里仍叫"豆花"——包名一改就成了另一个 App，没法覆盖安装。
- 界面文字全在 `res/values*/strings.xml`：values（英文，默认）、values-zh、ja、ko、fr、de、it、es、ar，九个文件条目一一对应，
  **加一条文字要九个文件都加**，带 `%1$s` 这类占位符的各语言要一致。`res/xml/locales_config.xml` 声明支持的语言（系统里可单独给豆花选语言）。
- 服务读不到 App 的资源，所以服务给 App 的状态只发原因代码（Proto.GRAB_*）和细节，文字由 App 拼；服务日志写给开发者看，保留中文。
- GUI 键（Windows 上叫 Win）一律叫 Super，蓝牙连着苹果设备时显示 ⌘（用户 2026-10-15 定）。
- 键名（Esc、Tab、Home、PgUp、⌫、↵、方向键……）用键盘上通用的写法，不翻译；"符"键中文保留"符"，其它语言用 Gboard 的 `?123`。
- 阿拉伯文从右往左：清单里 supportsRtl，界面整体镜像；键盘和数字键盘强制从左往右（按实体键盘排）；触控板角上的按钮按物理左右放
  （设置里写的就是"左上 / 右上"）。
- 按钮文字太长时自动缩小（Style.button 里的 autoSize），德语、法语的短名普遍比中文长。
- 翻译是 AI 写的，没有母语者校对；正式面向这些语言的用户发布前最好找人看一遍。

## 不变量

- **独占触摸屏的条件**：App 报 active（在前台、打字面板没开；蓝牙模式下还要窗口占满整块屏——不在分屏 / 小窗、没接外接屏时还要是顶层 Activity；桌面模式分屏照样独占，MainActivity.serverActive / ownsScreen）、参数已到、心跳（每秒一次，主线程发）没超过 3 秒。
  任一条件不满足，100ms 内释放；连接断开立刻释放。这是"外屏不会被锁死"的保证，改动时必须保住。
  被新连接顶掉、10 秒没消息断开，都走"连接断开"同一条路。保活（CMD_PING）不是心跳，绝不能刷新心跳（规则在 GrabCondition.onMessage，ServerPartsTest 锁住）。
- **同一时刻只有一个会话能独占触摸屏**（TouchGrabLock，持有范围从打开触摸屏之前到关掉之后；内核 EVIOCGRAB 的 EBUSY 兜底）。
- **旧会话 2 秒收不完尾、还占着触摸屏 → 服务退出**（System.exit，内核收回独占和虚拟设备）；没占触摸屏就由新会话替它关掉虚拟设备。
- **键盘的缝分给最近的键只改"按在缝里"的触点**：hit() 先照旧按列表找第一个包含的区域，只有命中键盘范围（REGION_KEYBOARD，
  排在它的键后面）才找最近的键；按在键上的结果、排在前面的区域优先都不变（TouchRouterTest 逐点锁住）。规则只有 TouchRouter.nearestKey 一份，
  KeyboardPanel 自己收触摸、KeyReplay 重放都调它。
  **嵌在键里的小红点**（2026-11-01，g、h、v、b 被和红点同心的圆切掉一块）的区域排在键和键盘范围前面（MainActivity.collectRegions）：
  按得到的是报上来那个正方形的内切圆（TouchRouter.Region.contains；圆外、正方形的四个角归底下的键或照缝分），圆里的触点归红点；
  **小红点状态下三个鼠标键铺满底行、快捷行让出的空位**，同样排在键盘范围前面：空位里每一点归某个鼠标键。nearestKey 不选红点、鼠标键；
  红点圆、空位外的每一点和没有它们时一模一样（KeyboardPlanTest.stickRouting 三层逐点锁住；面板自己收触摸时 KeyFingers.pick 带洞——红点圆、空位——同样锁住；
  服务连着没接管时 TrackPointView 只吃圆里的按下）。按在红点、键盘里的鼠标键上的触点不进键盘触点日志（不是按键）。
- **服务日志不列这块屏上看不见的任务**（2026-11-01 晚）：/data/local/tmp/douhua-server.log 谁都能读（rw-rw-rw-），交焦点的诊断日志只列看得见的任务和挑中的那个，
  看不见的（最近用过的微信、WhatsApp……）只记个数（FocusPick.describe，FocusPickTest 锁住）；要全列只在人手动跑、输出到终端的 Server tasks 里。以后往服务日志里加任务 / 应用列表照这条。
- **键盘触点日志等于按键记录**：只在可调试包、只能由 adb 广播（DUMP 权限）打开、租期 10 秒不续自己关、只写 logcat 不落盘；
  服务端每条连接默认关，开关只能由通过 uid 校验的连接发（CMD_TRACE）。
- **主机实测扫描（DEBUG_KEYSCAN）只在可调试包、只由 adb 广播（DUMP 权限）触发**；一次广播最多 12 个键，只许出字的位置和 Shift / Alt，
  Ctrl / GUI / Enter / Esc / Backspace 绝不发（KeyScan.allowed，KeyScanTest 锁住）；停下（叫停、新的一段、界面销毁）时正按着的键补松开；
  电脑那边页面收完一段才发下一段、页面失焦就叫停——主机上的页面没拿到焦点时，最多多出一段字落进别的应用。
- **独占实体键的条件**：和触摸屏同一个 Session.wanted（active、参数到了、心跳 < 3 秒），再加 App 发过 CMD_HARD_KEYS 且至少截一个键、
  按键注入可用（不可用绝不独占，不然同设备上没配的音量键补发不出去；截着时补发失败一次也算不可用，等配置变了或 App 回前台再试）、
  没在来电响铃 / 通话中（服务问 Telecom，CallState；查不到不拦）；不依赖触摸屏接管成功。不满足 100ms 内放开，断开立刻放开
  （KeyReader 每轮 poll ≤ 100ms 问 keepGoing）。同一时刻只有一个会话打开实体键设备（Session.KEY_GRAB）；收尾 2 秒还独占着 → 服务退出。
- **注入过的每个"按下"在所有退出路径上都配一个"松开"**（HardKeyGate.releaseAll 在 KeyReader 的 finally 里，交还后由真松开配上），
  否则系统一直连发、音量推到头。F 行第二功能的多媒体 / 系统键同理（协议 24），见下面"多媒体 / 系统键……所有收尾路径归零"那条。
- **分屏透传注入出去的每个触摸 DOWN 在所有退出路径上都配 CANCEL（带 FLAG_CANCELED）**：释放独占 / 方向变了重新接管 / 读线程出错 → TouchReader 的 finally
  先 onStop（Passthrough.cancelAll）再 decoder.releaseAll（顺序不能反：反了系统收到的是 UP = 一次点击）；会话结束 → Passthrough.close；
  Session 里三处 System.exit(3) 之前 → Passthrough.cancelBeforeExit（最多等 300ms）；手掌、丢数据 → MtDecoder 报 cancel → 整条系统侧流 CANCEL、
  还按着的作废到抬起。Android 16 起系统里留着一根"按着"的注入手指 = 这块屏上所有注入的 DOWN 都被拒（连 adb shell input tap），直到重启；
  服务被直接杀掉（kill / stop-server.sh）时补不了：CANCEL 的手指集合要和系统记着的完全相等，所以系统那边按着的集合每变一次就落盘
  （/data/local/tmp/douhua-inject-down，只有屏幕编号和 slot 编号；系统收下 DOWN 时也写），下一个会话 DOWN 被拒时按记下的集合发一次 CANCEL；
  记录不在 / 对不上时先等 300ms 重发一次 DOWN（设备号 -1 的校验状态所有注入者共用，被拒可能只是 adb input / scrcpy 正按着），还被拒、又有残留的证据
  （服务启动时记录文件就在，或这个进程还没有 DOWN 被收下过）才猜单根和两根组合（每次最多 300ms，一个服务进程走一遍、猜中就不再猜，猜中后这次的 DOWN 不补发）；
  记录丢了又是三根以上的残留，只能重启手机（这块屏进程级记成不可用，之后的会话一开始就不独占）。
  会话收尾（Passthrough.close / cancelBeforeExit）后注入线程不再发流里的事件、不再猜，只按系统实际收下的集合补一个 CANCEL（不然和新会话的注入流交错）。
  注入流必须自洽（InjectStream，PassthroughTest 的校验器锁住）。
- **注入到豆花自己窗口里的手指必须带 NO_FOCUS_CHANGE**（InjectStream.FLAG_NO_FOCUS_CHANGE = 0x40，只加在那根手指的 DOWN / POINTER_DOWN 上；
  TouchSplit.SYSTEM_IN_WINDOW）：系统没认成手势、把它发回豆花窗口时，分发层会把键盘焦点挪给豆花（没接外接屏时豆花窗口能拿焦点），
  之后虚拟键盘打的字全被豆花吞掉、进不了另一边。按在豆花窗口外的手指不带（点 Moonlight 就该把焦点给它）。
- **分屏时带着"系统眼里按着的手指"不独占**：EVIOCGRAB 不给系统补抬起，独占开始时按在另一边的真手指，系统会一直当它按着（那个应用一直收着按住的触摸）。
  所以分屏时先不独占地开着触摸屏（Session 的 plain 描述符 + LiftWatch），再看 dumpsys 里这块触摸屏的 pressed：plain 上见过按下没抬起的、
  或 pressed > 0 就先不接管（GRAB_WAIT_LIFT，屏幕照常归系统；dumpsys 退避到每秒一次）；独占后先读空 plain（EVIOCGRAB 返回时独占前的投递都已完成），
  dumpsys 到独占之间按下没抬起的，一个独占事件都不读就放开（TouchReader.RACED，后面的抬起照常到系统）。plain 开着之前就按着、一直不动的手指仍然看不出来，
  独占后它一动由 MtDecoder.sawPreGrab 认出、放开（PRE_GRAB；放开后 150ms 再来，反复时不重复记日志、不报 App）；它独占后的第一个事件就是抬起时，抬起已被吞、补不回来。
  系统记着的手指可能永远不抬（独占那一刻冻住的旧状态），所以等了 1.5 秒、内核那边 1 秒没动静、数没变就当旧状态接管，不会永远不接管。全屏照旧不等。
- **透传不可用时分屏下不独占**：Passthrough.unavailable 不为 null（MotionEvent.setDisplayId 反射不到、注入抛异常 / 被拒 / 一次 500ms 没返回、清了残留还被拒）
  且 App 报的窗口没占满 → Session 不接管、接管着的 100ms 内释放（keepGrab），报 GRAB_NO_PASSTHROUGH，触摸整块交还系统、触控板暂停。
  注入在单独的线程里做，读线程绝不调系统服务（卡住就破坏"100ms 内释放"）。App 回前台、全屏 ↔ 分屏切换时再试（Passthrough.retry；清残留彻底失败的屏整个服务进程里不再试，重启手机才好）。
- **服务绝不独占带电源键（116）的设备**，任何配置下都不（同设备上的音量键因此截不了，报 HARD_SHARED）；带电源键的机身设备只旁观
  （不独占地读），只用来认"电源键 + 截住的音量键"（截屏）。设备白名单：事件类型只有 KEY（加 MSC；SYN 隐含；REP / PWR 用 getevent 看不见，连发事件被忽略）、键码只有 114 / 115、
  不是 USB / 蓝牙 / 虚拟总线、厂商号不是 0x1209、getevent 的输出认全了（KeyDevices.whyNotGrabbable）。
- **区域只报看得见的那一屏**：键盘（含空中鼠标页上调出来的）、空中鼠标页、主界面三组互斥（MainActivity.collectRegions，键盘最先判断）。
  只把键盘排前面不够：键盘空隙里看不见的圆钮、格子照样按得到。⋯ 菜单开着时（主界面、键盘界面一样，2026-11-01 起两屏共用一份 MenuLayer）
  只报这一屏的菜单项、菜单底板和一块盖住整页的"点外面"，底下的键、触控板、角上的按钮、自定义按键都不报（菜单在最先判断）；
  菜单只能从看得见的 ⋯ 打开，换屏（开关键盘屏、进空中鼠标页、开贴字面板）先关菜单。自定义按键整列藏着时一个都不报。
- **屏幕边缘留白那一条什么都按不到**（2026-11-01，MainActivity.updateEdgeMargins + EdgeMargin）：四页（主界面、键盘屏、空中鼠标页、⋯ 菜单那一层，连"点外面"）
  整体往里收，留白里没有任何 View、不报任何区域；只在窗口贴着屏幕边的那几条边留。按在那一条上的手指和按在空白处一样（找不到区域；转着的惯性照"碰哪里都停"停）；
  从触控板出发划进去照样是触控板（归属按下时定）。⋯、菜单、调整栏、三键条、键盘外面的红点的"离屏幕边 20dp"照旧按屏幕边算（EdgeMargin.edges：收进去多少就少留多少），
  加新的贴边控件时也照这条（页面坐标里离边用 edges，不用写死的 20dp）；键盘存的大小位置是键盘区（收进去以后的整页）的比例，不随留白改。
- 只接管 App 所在那块屏的触摸屏（按 displayId 在 dumpsys 里找）；找不到（比如虚拟屏）就不接管。
- 坐标换算：系统的 RawToDisplay 矩阵（原始 → 没转过的物理屏）+ ScreenRotation（物理屏 → 当前方向）。
  真机上这个矩阵转屏后仍不含旋转；万一某个系统版本的矩阵已经含旋转（unrotated() 为假），就不再自己转。
  方向 / 尺寸一变就重新接管、重新取矩阵。
- 鼠标报文里的 buttons 是当前状态不是增量；释放触摸屏或断开时引擎 cancel，按住的键自然松开。
- **惯性滚动转着时，任何收尾都让它停**：释放独占（TouchReader 的 finally → router.releaseAll）/ 断开 / 会话结束 / 退后台（onPause 的 local.releaseAll）/
  服务接管时本地分流收尾 / 蓝牙断开 / 换参数（setConfig 换引擎）都走 engine.cancel——TIMER_SPIN 用的是引擎唯一的定时器槽，cancel 一定撤掉、不震；
  看到任何一根手指按下也停（服务：TouchRouter.down 里不在触控板上的、Session.onDown 里分屏透传归系统的；App 本地分流：dispatchTouchEvent 里不归本地分流的按下；
  按在触控板上的由引擎自己"抓住"）。Ctrl 按着（左右都算：实体键盘上锁住的右 Ctrl 也是，Hid.ctrl）、手指被取消（手掌 / 丢数据；App 本地分流带 FLAG_CANCELED 的抬起）、TouchReader 收尾时补报的抬起（Session.stopping）都不起惯性。
  GestureEngineTest 的乱序序列锁住"cancel 之后定时器晚到也没有输出、转着时一直挂着定时器"。
  **撤掉 / 取代了的定时器绝不再到点**（引擎只有一个定时器槽、不认回调是哪一次排的，过期的惯性帧会被当成随后新排的长按）：服务的定时器线程到点后卡在 lock 上的那一次
  cancel(false) 撤不掉，所以 Session 的定时器走 OneShotTimer（每排一次一个令牌，拿到锁先比，ServerPartsTest 锁住，含真 TouchRouter 的"过期的惯性帧不被当成长按"）；
  App 用主线程 Handler.removeCallbacks，单线程，撤掉就不会再跑。按键专用的第二个定时器（2026-11-01，修饰键 / Caps / 合并键按住到点）同样：服务另一个 OneShotTimer（Session.keyTimer），
  App 另一个 Runnable（localKeyTimer）；而且 KeyHolds.onTimer 按截止时刻 + 当前状态处理，就算漏进来一次过期的回调也什么都不多做（ServerPartsTest 真起 OneShotTimer 锁住"松开之后才拿到锁的那次不震、不锁"）。
- **滚轮量在引擎 → TouchRouter → 服务 / App 这一路是 1/120 格（Hid.WHEEL_UNIT）**，只在最后一步按主机设的分辨率倍率换算成计数（Hid.WheelOut 一处，UhidMouse、BtHid 都用它）：
  主机设了倍率后缩放、"滚轮向上 / 向下"、按格滚也全按倍率换算（不然慢 8 倍）；按格滚 + 主机没设倍率时报文和以前逐字节一样（HidTest 锁住）。
  带倍率的描述符只多一个 Feature 报告，输入报文的字节排布一个都不动（已有报告的排布不能动，没重新配对的主机照旧）。
  uhid 那份（Hid.MOUSE_DESC_HIRES_V）的 Feature 报告里只能有一个字段（竖向倍率）：不止一个时内核先 GET，设备带 NO_INIT_REPORTS 就直接放弃、一个请求都不发（HidTest 锁住 maxfield = 1）。
- **描述符带 Feature 报告就必须有人回应内核的 SET / GET_REPORT**（server.Uhid 的读线程）：不回内核干等 5 秒、退回 ×1，虚拟鼠标晚 5 秒才出现。
  **uhid 读线程退出前不关 fd**（Uhid.close 先等它，300ms 没退出就留给它退出时关）：fd 号会被马上复用，晚一步 poll 的读线程会读到新会话鼠标的请求、把倍率记错。
- 一次手势里出现过多根手指，就按多指处理到全部抬起。
- **小红点的节拍在所有收尾路径上都停**（节拍 = 手指不动也一直推光标 / 一直滚，漏停 = 光标一直漂、页面一直滚、一直拖着）：节拍有三个来源——红点那根手指、
  中键滚动杆那根手指（按在三个鼠标键中键上的第一根，2026-11-01）、左键杆那根手指（按在三个鼠标键左键上的第一根，2026-11-01 晚），共用一套 startTicks / stopTicks：
  任一按着就开着，三个都松了才停（TouchRouter.ticksWanted / stopTicksIfIdle；杆让位了——leverYield / leftLeverYield——算松了，
  它那根手指还按着也不留节拍；按着的都 20 秒没事件暂停了，下一拍 onTick 停）。各自那根手指抬起 / 被取消、releaseAll（释放独占、断开、退后台、
  服务接管时本地分流收尾）、换参数（setConfig → clearSlots，三个都作废）都 stopTicks；服务收尾 ticker.shutdownNow 兜底；迟到的节拍 TouchRouter.onTick 看三个都没按着（或杆让位了）就什么都不出
  （TouchRouterTest / TrackPointTest 锁住，startTicks / stopTicks 配对；乱序交错开停 TouchRouterTest.leverTicksRandom、三个来源加小挪 / 大挪 / CMD_HOLD_LEFT 的 leftLeverTicksRandom 锁住）。按着却 20 秒没事件就暂停（TrackPoint.IDLE_PAUSE_MS），这根手指再动接着。
  左键杆推出来的每一帧都带着左键（它那根手指按着左键；抬起时先停杆再松左键，之后迟到的节拍不再推光标）；左键杆按着时左键照旧在所有收尾路径上松开。
  中键收尾（releaseAll）和被取消的抬起（TouchRouter.up 的 canceled：手掌 / 丢数据、TouchReader 收尾补报的、App 带 FLAG_CANCELED 的）都不补中键单击；
  三键条 / 键盘里（底行、快捷行空位）的左键和"左键"按钮、CMD_HOLD_LEFT 共用 TouchRouter.leftHolds 一个计数。**计数里每一份只由按下它的那个来源减**：
  三键条的左键、"左键"按钮按 slot 配对（收尾清了 slot，迟到的抬起什么都不做）；CMD_HOLD_LEFT 不按 slot，它的松开只减它自己那几份（TouchRouter.cmdLeftHolds，
  releaseAll / clearSlots 一起清零），收尾之前按下、收尾以后才松开的实体键，迟到的松开整个不理——不然会把收尾以后才按下的左键（左键杆正拖着）减掉，拖到一半左键松了、
  杆接着推出不带左键的位移（审查发现，TouchRouterTest.leftLeverLateHoldLeftRelease、holdLeftCommand、leftLeverTicksRandom 带迟到的松开锁住）。加新的按左键的来源照这条：不按 slot 配对的就单独记一份。
- **键盘上会显示的每个键都有键帽**（实体键盘同样：每个显示的键有外框、有字，出字的键在字表里有这一行，锁着哪种修饰键都有字——FixedLayoutTest.caps 按
  布局 × 形状 × 尺寸 × 模式 × ⋯ × 让位 × 窗口扫，笔记本另扫一遍；清单里每个布局（第 2 批起 34 个）、每种**有数据的**模式都扫，没数据的模式（拉美的 Mac，意大利 QZERTY、瑞典旧版的 Windows）拼不出键盘、退回豆花布局，
  FixedLegendsTest.missingModes 锁住"清单的 modes 和字表有没有那一段对得上"；巴西按 ABNT2 扫（多的 0x87 在字表里、小键盘的 0x85 写清单的 kpco），拼音的 Mac 模式 Caps 写 中/英
  （FixedLayout.forEntry，caps 那一扫就用它拼）；清单里每个能选的形状 FixedLayout 都认得（FixedLayoutTest.abnt2Geometry 锁住，不会被 validShape 悄悄换成 ANSI）；FixedPanel 的数组跟着键一起换、下标对得上，换键盘、逐级缩换级后还没重新量时也不越界）：KeyboardPanel 只按 KeyRows.capKeys 建键帽（showLayer 按 KeyRows.keys 取，取到没键帽的键就闪退），
  加新的行、新的布局变体（像小红点状态底行的 before / after）只改 KeyRows，两处规则在一起；KeyRowsTest.capKeysCover 锁住"keys() 给的每个键都在 capKeys 里"
  （2026-11-01 的合并键 COMMA_PERIOD、右空格 STICK_SPACE_R 在 before / after 里，同样锁住）。
  第 3 批的 75% / 65% / 苹果紧凑同样扫（FixedCompactTest 借 caps 再跑一遍：清单的每个布局 × 形状 × 三种新尺寸 × 有数据、拼得出的模式），
  **Fn 层也算**：有 Fn 层的键在 Fn 层也都有字（FixedPanel.fnGlyphs，写着名字的不是空的、出字的——65% 的 Esc → 1 左边那个键——字表里有那个码、锁着哪种修饰键都有字），
  Fn 按着 / 锁着时键帽整片换成它，不能换出空白的键帽；以后给别的键加 Fn 层，fnCaps 那一扫自动跟着。
  **F 行的第二功能也算**（协议 24）：每个能选的功能两种模式下都有图标或字（FKeyFaces.face，多媒体 / 系统键那一组都有图标，FixedFKeysTest.faces 锁住），
  F 键两面都印 / 只画会出的那一面、Esc 的 FnLock 在每种尺寸 × 形状 × 模式 × 配法 × ⋯ × 让位 × 窗口 × Fn 状态（按着 / 单次锁 / 永久锁 / 翻转）下都有字（FixedFKeysTest.glyphScan）；
  加新的功能时给它一个图标（FKeyFaces.ICON_OF）或者让它是键盘页的键（写键名 / 组合），不然这一扫报错。
- **修饰键只在"单独按住 0.4–1 秒"时锁**（2026-11-01，KeyHolds）：按下那一刻已有别的键的手指或鼠标键按着、按着期间按了别的键（含界面发来的 key()、Caps、合并键、别的修饰键）
  或动了鼠标键（按下 / 松开 / 带着按住的鼠标键移动）/ 滚轮 / 开合 / 三指四指手势，都绝不锁（例外见技术债：蓝牙模式下不经分流的几路鼠标键）；没按鼠标键的光标移动不算。
  按下马上发（HID 里按着），松手就松，只有锁住时松手 HID 照旧按着。规则只有 KeyHolds 一份，三条触摸路径（服务接管、蓝牙本地分流、面板自己收触摸经 CMD_HID_KEY / local.key）一样，
  App 只画（MSG_MODIFIERS 的锁住 / 按着 / 快锁了）。KeyHoldsTest 的随机序列锁住"armed ⊆ held、latched ∩ held = ∅、HID 修饰键字节 = latched | held | 自带修饰键 | 缩放 Ctrl、用过的永远不锁"。
- **修饰键在所有收尾路径上松开、锁定 / 按着 / 快锁了全部归零**：releaseAll（释放独占、断开、退后台、服务接管时本地分流收尾、蓝牙断开）清 KeyHolds、撤按键定时器、发一帧干净的报文、
  报 (0,0,0)（协议 21 起连 Fn 四样都是 0）；之后迟到的松开计数是 0、整个不理（不松、不解开新的锁定、不结算）；被取消的抬起（手掌、收尾补报的、FLAG_CANCELED、面板的 KEY_CANCEL）松开但不锁；被取消的修饰键松开也不结算待定的 Caps / 合并键
  （收尾补报按 slot / 手指编号的先后来，修饰键那根在前时以前会凭空发一次 Caps Lock、一个 "<"；KeyHoldsTest.canceledModifierNoSettle 锁住），待定的留给它们自己的抬起（被取消就什么都不出）或 releaseAll（丢掉）。
  普通键被取消照样松开（KeyHoldsTest 的随机序列在 releaseAll 之前查：全部抬起后主机上没有按着的键、修饰键字节 = 锁住的）。
  换参数（setConfig）不松、不丢按着的键（clearSlots(true) 留着键的 slot，抬起照常松开、照常判）。
- **Caps 只在点一下时发键码、按住只切显示；合并键只发一次点按、不连发**（按住 0.4 秒那一下发完，之后按多久都不再发、松手什么都不发）。
  **待定的键（Caps、合并键）在别的键按下、手指按着的修饰键（正常）松开、开合开始之前先当点一下结算**：出字的顺序和修饰键跟按下的先后一致（"，空格"不会变成"空格，"、
  按住 Shift 点合并键再先松 Shift 照样是 "<"）；锁定被解开（打完普通键、Caps 松开、CMD_UNLATCH）不结算（锁着 Shift 快打 "I." 出的是句号）。KeyHoldsTest 锁住。
- **Fn（实体键盘 75% / 65% / 苹果紧凑，协议 21）的状态只在 KeyHolds，带 Fn 层的键按下那一刻定码、松开松按下时的码**：Fn 键和带 Fn 层的键都是 REGION_KEY
  （Proto.FN_KEY / FN_GLOBE_KEY、Proto.fnLayerKey(底层, Fn 层)，FixedLayout.keyId），三条触摸路径（服务接管、蓝牙本地分流、面板自己收触摸经 CMD_HID_KEY / local.key）都进同一个 KeyHolds，
  App 不换层、不重报区域，只照 MSG_MODIFIERS 第四个字节画（Fn 键的高亮 / 实底、带 Fn 层的键帽整片换字）——所以没有"换层还没报到服务、另一根手指拿到底层的码"那个竞态（总方案 §9 说的）。
  按下那一刻 Fn 按着、单次锁或永久锁着就发 Fn 层的码、记下来；之后 Fn 松开 / 锁定解开都不改它，松开松的是记下的那个（先松 Fn 再松 ↑，松的照样是 PgUp）。
  同一个带 Fn 层的键已经有手指按着时，后来的手指跟着第一根的码（CMD_HID_KEY 只带 id、分不出哪根手指，三条路要一样就只能按 id 记）；最后一根松开才忘，releaseAll 连这份一起清
  （KeyHoldsTest：两根三根手指、先松 Fn、被取消、releaseAll 后先按同一个键；FixedCompactTest.fnPaths：真的 75% / 65% / 苹果紧凑上报的区域、面板自己收触摸、key() 三条路同一个 id、同样的报文）。
  **Fn 的规矩协议 22 起不同修饰键**（2026-11-01 晚，用户："fn 没必要跟别的那个功能键一样……点一下，就是单次锁，如果点住，就变成永久锁"）：
  Windows 模式单独点一下（没用过、没被取消、不到 0.4 秒）= 单次锁（下一个普通键出 Fn 层、它松开时解开；修饰键不消耗它），单独按住到 0.4 秒 = 永久锁（按多久都算，到点震一下），
  锁着（单次或永久）时单独再按一下 = 松手解开（按住也算、不再锁、不震），锁着时当组合用（用过、按下那一刻别的键 / 鼠标键按着）锁照旧（单次锁照样被那个键用掉）；
  用过 / 按下时别的键或鼠标键按着 / 被取消都不锁、不解开、不出 🌐；收尾（releaseAll、CMD_UNLATCH）两种锁都清，换参数不丢；打完普通键只清单次锁。Fn 不发 HID。
  Mac 模式 fn🌐 单独点一下照旧报 Proto.GLOBE_BUTTON_ID 的 BUTTON_TAP（和 🌐 键同一条路，App 的 onSpecialKey），没有单次锁；按住 0.4 秒永久锁、锁着时点一下解开，这两下、组合都不出 🌐。
  KeyHoldsTest 的 Fn 随机序列旁边跑一份照这套规矩写的模型，锁住"报的单次锁 / 永久锁和模型一样、两种锁不同时出现、快永久锁了 ⊆ 按着、按下出的码 = 那一刻定的、
  最后一根松开后报文里没有它、出不出 🌐 照规则、全部抬起后没有按着的键"（2026-11-01 晚把五条规则逐个改坏跑过，都报错）。
- **F 行键（实体键盘 F1–F12 的第二功能、Esc 的 F 行翻转开关，协议 24）按下那一刻定码、松开松记下的那个**（同带 Fn 层的键，规则只在 KeyHolds 一处，三条触摸路径一样）：
  F1–F12 发第二功能 = "Fn 起作用"（按着 \| 单次锁 \| 永久锁）和"F 行翻转"正好一个成立；Fn 层别的键只看 Fn 起没起作用（翻转只翻 F 行）。
  **翻转状态在 KeyHolds 里、绝不进区域 id**：面板自己收触摸那条路松开时会重新算 id，按着时翻了 id 对不上就卡键（FKeysTest.heldThroughToggle 三条路锁住"按着 F 键时 Fn+Esc 翻了，
  松开照样松掉按下时的码"）。Esc 在 Fn 起作用时只翻转（不发 HID、MSG_MODIFIERS 第四个字节报 Proto.FROW_FLIP、KeyHolds.FLIP_BUZZ_MS 后震一下——和 Esc 按下的"嗒"隔开，不然被它盖掉），松开用掉 Fn 单次锁、不解开锁住的修饰键。
  第二功能是一个普通键：锁住的修饰键带着它、它松开时解开，按着修饰键时按了它修饰键算用过，Fn 单次锁被它用掉——和别的键一个规矩，不另起一套。
  **F 行翻转是设置、不是锁**：存在 App 的设置里（Boards.frowFlip，kbx_frow_flip_<win\|mac>，设置页"平时按出"和键盘上 Fn+Esc 是同一份），经 CMD_CONFIG 的 frowFlip 给服务 / 本地分流；
  releaseAll、CMD_UNLATCH 都不清，setConfig / setFrowFlip 设它时不报界面（服务闲着时报的话会把 App 正显示的本地分流的修饰键状态盖成零）；
  App 收到的 FROW_FLIP 和设置不一样 = 那一边 Fn+Esc 翻了：存设置、同步给另一边（MainActivity.onFrowReported）。看 MSG_MODIFIERS 第四个字节的地方一律按位看，别拿 fn != 0 当"Fn 起作用"。
  实体键盘上（几何那一半）：配了第二功能的 F 键报 FKeyFunctions.regionId、没配的照旧报用法码，至少一个配了时 Esc 报 Proto.FN_LOCK_KEY（12 个都"无"时照旧是 Esc）；65% 不上（它的 F 键在 Fn+数字里）。
  键帽上亮哪一面用同一条规则（FixedPanel.secondShown = Fn 起作用 ^ FROW_FLIP），和按下去出的那一面对得上；一块面板里每个键的 id 各不相同，
  报的区域、面板自己收触摸、经 key() 的那一路逐点同一个 id、同样的报文（FixedFKeysTest.paths：每种尺寸 × 形状 × 模式 × 配法 × ⋯ × 窗口，每个 F 键和 Esc 两种起始翻转各跑一遍）。
  设置页（第三步）：选择列表只列真键盘能发的（FKeyChoices.items：FKeyFunctions 的功能 + 动作清单里发 HID 的快捷键 + 自定义组合键；豆花自己的动作、鼠标、注入安卓按键的不列），**行上写的就是按下去发的**——设置页选的每一项写进 Boards、照 FixedLayout 的同一条路拼 id、经真 TouchRouter / KeyHolds 按下去，发的正好是那个码（FKeyChoicesTest.endToEnd）；设置都存 kbx_ 开头的键（不碰豆花布局的 kb_，FKeyChoicesTest.randomSettings 锁住）；加有名字的功能要同时加 strings.xml 的 fkf_<id>（九个文件）和 SettingsActivity.fkeyName 的一行（FKeyChoicesTest.names 照源码核对）。
- **多媒体 / 系统键（协议 24）照真键盘一次报一个码，每个按下都配松开，所有收尾路径归零**：TouchRouter 两个槽（Hid.UsageSlot：报最后按下、还按着的那个，后按的松开报回先按的，
  全松开报 0，变了才发）；releaseAll（释放独占、断开、退后台、服务接管时本地分流收尾、蓝牙断开）不是 0 就各发一帧 0，之后迟到的松开不再多发；换参数不丢按着的；
  蓝牙那两处补发全零（onStop、服务断开）也补报告 3、6 的 0；桌面模式 UhidControls 关之前写两帧 0。漏一条 = 主机那头一直按着音量 / 一直播放快进。
  FKeysTest 的随机序列锁住"两个槽报的码 = 模型、全部抬起后是 0、releaseAll 后是 0"。
- **已有 HID 报告的字节排布不能动**（2026-11-02 这批再说一遍）：蓝牙描述符只在**末尾**加系统键（报告 6，Hid.combinedDescriptor(abs, hires, true)），前面报告 1–4 逐字节不变（HidTest.frozenDescriptors 把 f6b905d——加系统键之前——的四种蓝牙描述符、键盘 / 多媒体 / 三种鼠标 / 绝对坐标 / 手柄描述符的长度 + CRC32 冻结了，
  "三参数版本的前缀 = 两参数版本"有它垫底才算数：两参数版本现在就是调三参数版本，审查发现原来那条自己跟自己比；真要改这些字节连冻结的值一起改、写明要所有主机重新配对）；
  桌面模式的多媒体 / 系统键另造一个设备（UhidControls），现有的虚拟键盘（8 字节报文）一个字节都不动。描述符只用 1、2 字节数据的项（蓝牙栈认不了 4 字节项）。
- **Caps 的显示只有 App 一份**（MainActivity.capsShown，static）：服务（MSG_CAPS）和本地分流都只报"翻一下"，判点一下 / 按住只在 TouchRouter；不进 Prefs。
- **面板自己收触摸时换键（换层、分裂 / 合拢、⋯ 键、让位、重建底行）不松还显示着的键**（KeyboardPanel.changeKeys + KeyFingers.remap，按 Key 对象对上）：
  按住功能行的 Shift / Ctrl 点 ?123 不会把它们松掉、按住合并键时滚着点 ?123 逗号不丢；只有真消失的键当取消松开（KEY_CANCEL）。
- **切换键盘不写任何 kb_ 开头的键，豆花布局的面板对象不销毁**（第 1B 批，用户 2026-11-01："豆花布局整个保留"）：⋯ 菜单 / 设置页换键盘只写 kbd_board，
  实体键盘的设置全在 kbd_ / kbx_ 下（Boards，BoardsTest 用随机序列锁住"kb_ 开头的键集合和每个值都不变、只写了 kbd_ / kbx_"）；
  KeyboardPanel 一直在，层（字母 / ?123 / Fn）、键帽缓存、锁住的修饰键照旧（两块面板都收 setKeyState）。实体键盘自己记指点（kbx_pointer）、大小位置
  （尺寸 × 指点 × 横竖屏；逐级缩时取、存、恢复默认的都是**显示着的**那个尺寸那份，FixedSizing），显示实体键盘时 ⋯ 菜单"指点"、调整模式、恢复默认改的是它那份；
  连笔记本都放不下、暂时显示豆花布局时改的是豆花布局的（显示着的那块）。
- **实体键盘的小红点只给笔记本、75%、65% 的 Windows 模式**（2026-11-01 晚，用户："那就 TKL 和全尺寸都不给，和 Mac 键盘不给，其他都有"）：规则只有 FixedLayout.stickAllowed 一处，
  按**显示着的那一级**算（选 TKL、窗口窄到缩成 75% 时有小红点）。存着小红点、这一级不给时照触控板摆（KeyboardPlan.planFixed 一开头 fixedPointer 换掉）、大小位置存取触控板那份
  （Boards.pointer(尺寸)）、⋯ 菜单"指点"写触控板、轮换跳过小红点（触控板 ↔ 关），**存的值不动**（换回给小红点的那一级 / 模式时小红点回来）。豆花布局不受影响。
  FixedLayoutTest.stickRule 扫全部尺寸 × 形状 × 模式 × 窗口 × 默认 / 存过的位置：不给的那几级存着小红点时 planFixed 和触控板一模一样；BoardsTest.stickRule 锁住存取、轮换、存的值不动。
- **实体键盘的区域照豆花布局的规矩报**：只报显示着的那块（KeyboardScreen.face）；键段在前、键盘范围在后，范围每区块每行一块（FixedLayout.ranges，
  每段恰好落在一块同行同区块的范围里，范围不重叠，一行里没有键的格子——方向键上面那几格、↑ 两边、Mac 模式 Ins 那格——不在任何范围里）；
  分流和面板自己收触摸逐点一致（FixedLayoutTest.routing 扫两百多万个点，笔记本另扫九十多万个，ABNT2 另扫一百四十多万个）。跨两行的键按行切成同 id 的几段（ISO 回车、小键盘 + / Enter），画成一个形状
  （ABNT2 的小键盘 + 只有一行高，下面是 0x85，FixedLayoutTest.abnt2Pins 钉住两个键之间的缝各归一半）。
  笔记本（2026-11-01）：最下一排是一行，方向键在这一行里竖着分上下两层（Key.y0 / y1，不拆成两行）；**"每区块每行一块"的唯一例外**：这一行的范围是三块——
  方向键以外一块（0–12.1875u）、方向键上层一块、下层一块（12.1875u 到面板边，在 5.875u 一分为二；FixedLayout.ranges 把竖向子区间一样的键单独成组）。
  和整行高的键放在一块里的话，nearestKey 先比竖向，右 Ctrl 竖向距离是 0，两层之间那条缝全分给右 Ctrl（审查发现，按住还会把 Ctrl 锁住）；
  现在缝的上半给上层、下半给下层、右 Ctrl 旁的竖缝按中线分（FixedLayoutTest.laptopArrowSeam 用真 TouchRouter 和 keyAt 扫，一个点都不给右 Ctrl）。
  以后再有只占一截的键，照这条单独成组，别和整行高的键并进一块。Mac 模式笔记本 F 行的 Ins 那格只有 0.8125u，
  照"一段之内 ≤ 1.01u 的小缝并进范围"会被并进去、右半格分给 Del，所以声明成空格子（FixedLayout.blanks），不在任何范围里（FixedLayoutTest.laptopRanges / laptopPins 锁住）。
  逐级缩换级时只报换过去的那一块（KeyboardScreen 换级后 post onKeyboardFaceChanged → pushLayout）。
  第 3 批（2026-11-01）：75% / 65% / 苹果紧凑都只有一个区块、每行铺满到面板边，**一个空格子都没有**（每一点都恰好在一块范围里，FixedCompactTest.rangesU 和 routing 锁住）。
  苹果紧凑的 ↑↓ 是一格里上下两个半高的键，夹在整行高的 ← 和 → **中间**：照笔记本那条单独成组（↑ 一块、↓ 一块），而且整行高那一组**不跨过它们并成一段**
  （FixedLayout.ranges 的 occupied；并了的话 ← 那段会盖住 ↑↓、范围重叠，按在 ↑↓ 之间的缝里分给 ←），所以最下一排四块：← 以左、↑、↓、→（FixedCompactTest.appleArrowSeam 用真 TouchRouter 和 keyAt 扫）。
  ⋯ 槽一律算在它那一行的范围里，放没放 ⋯ 范围一样（苹果紧凑的槽在 F 行最右、触控 ID 那格，空着时分给 F12；1B / 笔记本本来就这样，FixedCompactTest 核对过结果没变）。
  **唯一的例外**（协议 24）：笔记本的 ⋯ 槽挪到了 F 行 Ins 那格，Mac 模式那格本来是声明的空格子——⋯ 不在槽里时照旧是空格子（不在任何范围里、按了什么都没有），
  ⋯ 在槽里时 ⋯ 占着它、F 行并成一块（FixedLayout.ranges 的 blankAt：槽正好是空格子、⋯ 又不在槽里时不把槽算进去；FixedLayoutTest.laptopRanges / laptopPins、FixedCompactTest.rangesU 锁住）。
  **配不配 F 行的第二功能几何一点不变**（协议 24）：第二功能只改 F 键 / Esc 报的 id 和键帽上的字，键的位置、范围、键数和"都没配"时一模一样（FixedFKeysTest.geometrySame 每种尺寸 × 形状 × 模式 × 七套配法 × ⋯ 比一遍）；
  TKL / 全尺寸 / 笔记本加 Fn 键是几何本身的改动（和配法无关、一直在），别处的键位置不动（FixedLayoutTest 原来那几组扫描照旧过，只改了写着右 Super / 🌐 / 笔记本 ⋯ 槽的那几条）。
- **不出没字的实体键盘**（第 2 批，2026-11-01）：某布局某模式没有键帽数据（清单的 modes 里没有，现在是拉美的 Mac，意大利 QZERTY、瑞典旧版的 Windows），设置页"语言"列表里这个布局在那种模式下灰掉、"模式"里那种模式在这个布局下灰掉
  （Boards.langChoices / modeAvailable，BoardsTest 锁住"灰不灰和清单的 modes 一致"）；存着的组合要是正好没数据，FixedData.ladder 是空的，键盘页照 1B 的规则显示豆花布局，
  ⋯ 菜单写 kbd_menu_no_mode、设置页标红一行，存的值不动（不悄悄换模式）。加没 Mac / Windows 数据的布局只改 catalog.json（mac / win 写 null，至少留一种），这几处自动跟着。
  **某家没有这一套就是没数据，不拿名字相近的布局顶上**（2026-11-01 审查后）：意大利 QZERTY、瑞典旧版只有 Mac 有，Windows 模式下原来照普通的意大利 / 瑞典画，
  列表、⋯ 菜单写的是"QZERTY"，画出来却是 QWERTY——现在 win 写 null、Windows 模式下灰掉（check_tables 的 MAC_ONLY、FixedLegendsTest、BoardsTest 锁住）。
  **苹果紧凑只有 Mac 模式、只有 ANSI / ISO**（第 3 批，FixedLayout.available）：同一条规矩，不可用的组合（× Windows 模式 / × ABNT2）forEntry 返回 null、FixedData.ladder 是空的，
  键盘页显示豆花布局，⋯ 菜单写 kbd_menu_no_size、设置页标红 kbd_no_size_data，存的值不动；设置页两边都灰（尺寸里的苹果紧凑、选着它时模式里的 Windows、形状里的 ABNT2、
  语言列表里只有 ABNT2 的布局），BoardsTest.batch3 锁住"配不上的一定是苹果紧凑 + Windows / ABNT2"、FixedCompactTest.availability 锁住 forEntry。
- **实体键盘的特殊键（⋯、🌐，REGION_BUTTON）只有一段**（2026-11-01）：TouchRouter.up 只在 `contains || Region.same(hit)` 时报 BUTTON_TAP，同一个键的几段坐标不同，
  跨段抬起就成了 BUTTON_UP（MainActivity.onButton 对 ⋯ / 🌐 只认 TAP），而面板自己收触摸那条路照样算点击，三条路不一样。以后的新尺寸、新排法不能把按钮切成几段
  （笔记本最下一排因此是一行；FixedLayoutTest.specialOneSegment 扫三种尺寸 × 形状 × 模式 × ⋯ × 让位 × 窗口、buttonTap 用真 TouchRouter 核对挪到段边抬起照样是点击）。
  第 3 批 65% 左下角的 ⋯、苹果紧凑右上角的 ⋯ 同样只有一段（FixedCompactTest 借 specialOneSegment / buttonTap 扫三种新尺寸）；Fn 键（Fn / fn🌐）协议 21 起是 REGION_KEY（几何里照旧是 special、照旧只有一段），
  fn🌐 单点出的 🌐 由 KeyHolds 在松开时报，不经"跨段抬起"这条（buttonTap 只扫报成按钮的 ⋯ / 🌐）。
  协议 24 起 TKL / 全尺寸 / 笔记本也有 Fn 键（Windows 模式 Fn、Mac 模式 fn🌐，整行高、一段），笔记本的 ⋯ 在 F 行 Ins 那格（一行、一段），Mac 模式笔记本 PrtSc 那格的 🌐 照旧；
  specialOneSegment 按"放没放 ⋯ + 有没有 Fn 键 + 有没有 🌐"数特殊键，每个都恰好一段。
- **修饰键掩码是 8 位，右边四个和左边四个一样能锁住**（实体键盘上右 Ctrl / 右 Shift / AltGr·右 Alt·右 ⌥ / 右 Super·右 ⌘ 都是单独的键，0xE4–0xE7，掩码 0x10–0x80；
  豆花布局只有左边四个）：只看低四位的地方就会漏一个一直按着的键。界面替服务解开锁住的修饰键一律发 CMD_UNLATCH（Link.unlatch → TouchRouter.unlatch → KeyHolds.unlatch，清全部 8 位锁定、按着的不动；协议 20 起，不再逐个点一下——新规则下点一下没锁着的修饰键就是给主机一次真单点，Windows 单点 Super 弹开始菜单）；"Ctrl 按着"一律走 Hid.ctrl（左右都算）；
  键帽的第三层只认 Mac 的任一 Option、Windows 有 AltGr 的布局的右 Alt（FixedPanel.layer）。加新的"看修饰键"的地方照这条（HidTest、TouchRouterTest、FixedLayoutTest 锁住）。
- **嵌在键里的小红点、键上切了角的键，三条触摸路径按同一个圆分**（实体键盘同样：红点 REGION_STICK、FixedPanel.holes 按同一个内切圆，FixedLayoutTest.stickRouting）：分流（服务 / App 本地分流）是 REGION_STICK 的内切圆（TouchRouter.Region.contains，红点区域排在键前面）；
  View 自己收触摸时红点 view 不收手指，面板的 KeyFingers 洞按同一个内切圆挖（不靠红点 view 的触摸目标：ViewGroup 会把落在它方块里的后来的手指直接交给它）。
- **每根手指归谁在按下那一刻定，抬起前不变**（TouchRouter 按按下点 hit；App 本地分流在按下时定整次手势；分屏透传 TouchSplit 同样按下时定）：
  从触控板出发的手指划出触控板（划到键盘上、屏幕边、别的区域）照样是触控板在动光标。用户 2026-10-31 点名要保留（"只要我是从触控板区域出发的，
  就可以连续的在超过的区域继续滑动，这个很好，不要改掉"）。加新区域、新手势时不能改成按当前位置重新判断。
  唯一的例外是手柄的面键簇内部（用户 2026-11-01 照推荐定的"搓键"）：归属照旧按下时定到"这一簇"（不会被改判给十字键、⋯ 或别的簇），
  只在簇自己的几颗键之间按当前位置挑键（GamepadTouch；N64 的 C 键也在簇里）；单键、十字键、摇杆滑出自己的范围照样有效。
  设置 → 手柄里关掉"面键之间滑动换键"（GamepadConfig.clusterSlide，默认开）就退回按下时挑一颗、滑动不换（两颗中间一起按也跟着关）。
- **手柄在所有收尾路径上回到中立**（发一帧全零：键全松、摇杆回中、方向键回中、扳机归零）：手指抬起 / 被取消、releaseAll（释放独占、断开、退后台、
  服务接管时本地分流收尾）、clearSlots（换引擎参数）、CMD_GAMEPAD_CONFIG（进出手柄页、换预设、连上时、设置 → 手柄改完回到主界面时 App 都发，setGamepadConfig）、关设备前（UhidGamepad.close 先写全零）。
  漏一条 = 电脑上一直按着 / 一直往一边走。每个按下（button DOWN）都配松开（GamepadTouchTest 的乱序随机序列、TouchRouterTest 锁住）。没用过手柄时收尾什么都不发（不会因此建手柄）。
- **手柄的全零报文 = 中立**：方向键逻辑 1..8、0 越界 = 回中（Linux hid-input 的 hat 换算），轴有符号、0 = 中（HidTest 锁住）。
- **只有手柄页报 REGION_GAMEPAD**（区域只报看得见的那一屏）；uhid 手柄第一次用到才建（布局里第一次出现手柄页的区域、或第一次收到不是中立的 CMD_GAMEPAD），
  会话结束才关（开始串流前没进过手柄页时电脑上不多一个手柄；游戏里手柄不会因为换页消失）。
- **豆花窗口不吞自己的多媒体 / 系统键接口**（协议 24，MainActivity.dispatchKeyEvent 按产品号 Proto.HID_CONTROLS_PRODUCT 排除）：厂商号和虚拟键盘一样，不排除的话会被"没有外接屏时吞掉豆花虚拟键盘的键"那条一起吞掉，
  豆花窗口拿着焦点时 F 行的音量就调不了这台手机的音量。这些键归主机（这台手机）处理；它发不出 Esc / 返回这类会关掉豆花的键。
- **豆花窗口吞掉自己手柄的键和摇杆事件**（MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent，按厂商号 + 产品号 Proto.HID_GAMEPAD_PRODUCT；贴字面板开着也吞）：
  不吞的话没人处理的手柄键安卓换成后备键，B = 返回把豆花关掉、Home 回桌面，摇杆还会被合成方向键。
- 桌面模式两路写同一个 uhid 手柄（服务分流、App 本地分流经 CMD_GAMEPAD）：写的是合并结果（GamepadMux：键按位或、方向键取不回中的那路、摇杆取远的、扳机取大的），
  服务开始接管时清掉 App 那路，谁先谁后都不会把对方刚按的松掉（ServerPartsTest 锁住）；设备晚建好时补写的那帧也记成"上次写的"
  （GamepadMux.deviceCreated，不然之后的松开被当成没变、电脑上一直按着）。CMD_GAMEPAD、CMD_GAMEPAD_CONFIG 不算心跳。
- **声音不碰主线程、不碰别的**（2026-11-02 第一批的引擎）：按键时主线程只往 SoundQueue 写一个数（暂停着时再 LockSupport.unpark），不等、不分配
  （一次点击的一对"咔哒"是两个数，"哒"由混音线程按帧数晚 45 ms 放，不靠主线程定时）；
  建管道、算声音（第一次几十到几百毫秒）、写、查路由、关全在 douhua-sound 线程（心跳在主线程，卡 3 秒服务释放触摸屏）。套装"关"时不建线程、不建管道（Sounds.configure → stopEngine，算好的那份也扔）。
  这个线程里出的任何错（含 Throwable）只让声音停掉，不让 App 闪退。只从手机喇叭出：实际路由不是手机喇叭就写静音。不请求音频焦点（不压低、不暂停 Moonlight）。
  声音和震动在同一处发起时声音先交出去、不等，震动不为声音往后拖；震动现有的行为一点不变（声音关着时整个 App 和以前一样）。
  按下一定出声（打得快只变轻、变短，SoundVary）；时间不随机（每块开头一律从第 0 帧放）。同时活着的最多 8 个声音（SoundMixer）。SoundTest 锁住。
  管道只在拿到低延迟时压缓冲（拿不到时压小了会一截一截地断）；换套重算前先停住管道；混音线程里走 binder 的查路由只在 play 之前、路由变了、没声音的空当里做（2026-11-02 审查后）。
  管道坏了不会一直哑到退后台：线程结束告诉主线程，还有页面开着就过 5 秒（连着坏翻倍、最多 60 秒）开新的。
- **电平表说的是响度**（2026-11-02 审查后）：SoundBank.levelDb 那张表（鼠标比普通键轻约 2 dB、触控板再轻约 4 dB、松开轻 6–12、滚轮轻 10–14）在手机喇叭上成立——
  Bank.gain 按每个声音算出来的响度（粗手机喇叭 800 Hz 高通、最响 50 ms 的能量）补齐，不按峰值。加新声音、新候选不用另调电平，但要过 SoundTest：
  每个声音 500 Hz 以下的能量 ≤ 40%（手机喇叭放不出，大半能量在那里的声音补成"一样响"只会白占峰值、推破喇叭）、单个声音的峰值在软限幅拐点以下、
  响度和电平表差 ≤ 0.6 dB（SoundTest.loudness 另写一份整段能量量）。
- **协议 25 的"被取消"只加信息**（2026-11-02，声音第一批第 ③ 步）：MSG_BUTTON / TouchRouter.Output.button 的松开带 Proto.BUTTON_CANCELED 的，只有被取消的手指
  （up 的 canceled：手掌、丢数据、TouchReader 收尾补报的；releaseAll；换参数 / setGamepadConfig 时手柄那一路）和 View 自己收的 ACTION_CANCEL（localTouch、mouseKeyTouch），按下从不带。
  App 收到先去掉（MainActivity.onButton 开头 Proto.buttonAction），之后的处理和以前逐字一样（还在按钮里被取消的照旧是 TAP、照旧执行动作）；只有声音看它（被取消的松开不出声）。
  "按住到点"（修饰键快锁了、Caps 只切显示、合并键出第二个字、Fn 快永久锁了、Fn+Esc 翻转 F 行）报 GestureEngine.HAPTIC_HOLD，Haptics 照点一下震；
  分流来的 HAPTIC_CLICK 因此只剩"点了一下"（触控板轻点、红点轻点）。加新的收尾路径时报的松开也要带标记（TouchRouterTest.protocol25Flags、SoundEventsTest 锁住）。
  同一个规矩的另外两处（审查后补的）：触控板上拖着的时候手指被取消、引擎 cancel 收尾（释放触摸屏、退后台、换参数）报 HAPTIC_DRAG_CANCEL（App 照拖动结束震、不出"哒"；
  GestureEngineTest.dragCanceledKind、SoundEventsTest.routerLongPressDrag 锁住），空中鼠标收尾放掉的拖动同样；实体键的按住被收尾结束（HardKeys.reset / cancel、
  服务的 releaseAll 和电源键组合）报 holdEnd(键, canceled = true) / Proto.HARD_HOLD_CANCEL，App 的收尾（onPause、断开、服务放开实体键）补的结束也当被取消（AirMouseTest、ServerPartsTest 锁住）。
- **声音关着时 App 和以前逐字一样**（默认关）：SoundEvents 关着时什么都不放、haptic 一律返回"照旧震"；发给引擎 / 服务的参数（MainActivity.engineConfig）和
  prefs.engineConfig() 一模一样；Sounds 不建线程、不建管道。声音开着也只多一件事：滚轮出声而"滚动咔嗒"震动关着时引擎照样发咔嗒（含惯性的放开 / 刹住），App 只出声不震
  （SoundEventsTest.offIsSilentAndUnchanged / ticksForSoundOnly 锁住）。挂新的声音照"声音和震动在同一处发起、声音先交出去"，规则只加在 SoundEvents，不另起一条路。
  调试版的"震动延后"（Haptics.setDelayMs）只在可调试包、套装不是"关"时才不是 0（MainActivity.applySound、SettingsActivity.syncBench 都这么配），正式包、声音关着时震动一点不变。
- **试听台就是实际用的**（2026-11-02 声音第一批第 ④ 步）：设置 → 外观 → 声音的试听台和候选区用进程里同一个 Sounds（试听的声音、音量、选的候选就是主界面以后用的），
  按键 / 触控板 / 三个鼠标键走自己的一份 SoundEvents（规则只有 SoundEvents 一处，不在设置页另写"什么时候出声"）和照 MainActivity 同样配的 Haptics（syncBench）；
  候选区每个槽点一下放的是落在这个槽的声音（SoundBench.hit，SoundBenchTest 经 SoundVary 核对、真混音器上"指定候选试听"和"选了以后正常放"逐个采样一样）。
  设置页开着时也 acquire 声音管道、onStop release（主界面 → 设置页 → 主界面管道不断）；onStop 撤掉没放完的那一小段、连打、诊断刷新，停掉试听台"左键"的持续轻震。
- **触控板背景图只是画面**：只在 TrackpadView 里画（PadImage），区域上报、TouchRouter、服务、协议都不知道有它（上面那条"从触控板出发划出去照样动光标"不受影响）。
  拷贝、解码（大图一次几百毫秒）一律在后台线程，主线程只比文件名和设置（心跳在主线程，卡 3 秒服务释放触摸屏）。
- **每套主题里（含纯黑）⋯ 都显眼、看得清**（2026-11-01，用户：⋯ 找不到）：键帽形状的 ⋯（menuKey）对页面、普通键帽、功能键都 ≥ 3:1、和功能键色差 ≥ 8，字对它 ≥ 4.5:1、对按下的底 ≥ 3:1；
  不带框的 ⋯（menuDots）对触控板底、页面 ≥ 4.5:1、对按着时垫的 pressed ≥ 3:1（ThemeTest.menu 十八套锁住）。加主题、加新的 ⋯ 时照这条：键帽形状的走 Style.menuKeyLook（或同样的颜色），
  不带框的走 Style.bare；哪个键是 ⋯ 只问 KeyRows.menuKey。⋯ 只换颜色，区域、按得到的范围、菜单的开法一样都不变。
- 按键一律带显式 displayId 注入到外接屏（没找到外接屏时是 INVALID_DISPLAY，由系统发给当前焦点）。
- 主窗口平时 `FLAG_NOT_FOCUSABLE`（不抢外接屏的焦点），打字面板打开时、没有外接屏时去掉（MainActivity.updateFocusable）。
  例外（2026-10-30，用户试：Moonlight 不抓豆花的鼠标、光标跑出它的边界，推测焦点被豆花抢走、捕获被系统收回）：桌面模式分屏 / 小窗时，
  焦点一旦被别的应用拿走（onWindowFocusChanged(false)），豆花窗口就不再拿焦点（yieldFocus），光标点到豆花这半边也抢不走；
  不是一进分屏就不拿，免得刚开进分屏、豆花还是当前应用时虚拟键盘的键没人接、5 秒后"没有响应"。回到全屏、重新 onResume 时清掉，贴字面板开着照样临时拿。
  这推翻了下面"分屏透传"里"没用分屏时 FLAG_NOT_FOCUSABLE"那句的顾虑：桌面模式的音量键由服务截、不靠焦点。
  主力机上验证过（2026-10-31，用户："捕获可以了"）：Moonlight 抓得住豆花的虚拟鼠标了，所以当时的推测（焦点被豆花抢走）大概是对的。
  没有外接屏时虚拟键盘的键只能发到手机屏，豆花是那块屏的当前应用、窗口却不收焦点，系统等 5 秒没人接就弹"豆花没有响应"
  （0.1.0 的 bug，日志里是 `Input dispatching timed out (Application does not have a focused window)`）；
  这时豆花收下自己虚拟键盘（厂商号 Proto.HID_VENDOR）的键并吞掉，免得 Esc 被系统当返回键。
  **分屏里豆花拿到焦点就交给另一半**（2026-11-01 晚，协议 22）：桌面模式、窗口没占满、没接外接屏、连着服务、贴字面板没开时，豆花窗口一拿到焦点（从设置页回来、
  拿着焦点进了分屏、贴字面板收起、连上服务时窗口正拿着）就发 CMD_FOCUS_OTHER，服务把焦点交给分屏另一半里最近用过的、看得见的、不是豆花 / 桌面 / SystemUI 的应用
  （setFocusedTask，等于用户点了它一下）；交出去以后豆花收到 onWindowFocusChanged(false)，照上面的规矩 yieldFocus。
  **只在分屏时交**（审查后）：豆花的任务窗口模式得是 MULTI_WINDOW（6）、接焦点的也得是 6（server.FocusPick）。豆花是小窗（5）时不交：setFocusedTask 会把目标挪到最上面，
  AOSP 上豆花的小窗浮在全屏应用上时等于把后面那个应用挪到小窗上面、盖住豆花（三星的弹出式视图是不是也这样没核）；分屏时只挑另一半，浮在分屏上的第三个应用的小窗不抢焦点。
  窗口模式读不出来也不交。以后要放开小窗，先在真机上确认弹出式视图不被盖住。
  **"没有焦点窗口、5 秒后没有响应"不能回来**：只交给服务列出来、看得见的另一个应用的任务（它有焦点窗口）；豆花的任务没拿着焦点（请求晚到、用户已经点了别处）、
  豆花不在分屏里（小窗）、没找到可交的、反射不支持，都什么都不做（等于以前的行为）；全屏、接着外接屏、蓝牙模式都不发。
  服务端只在单独的 douhua-focus 线程上调系统服务（getTasks、setFocusedTask），不在读触摸的线程、命令线程、定时器线程上调。
  App 端同一次拿到焦点（同一条连接上）只发一次，两次之间至少隔 1 秒（FocusHandoff，FocusHandoffTest 锁住）。
- 服务只接受豆花 App 的 uid、shell(2000)、root(0) 的连接；shell 本来就能注入输入，放行它不增加权限。

- **adb 私钥、配对码不落日志、不进界面和异常信息**：私钥只以 AndroidKeyStore 加密的形式存在 files/adb/key.enc（不进 "douhua" 那份
  SharedPreferences，"恢复默认"不清它）；配对码只经通知的 RemoteInput 进来、直接交给 SelfStart，不存。拿到这把私钥 = 能以 shell 身份连这台手机的 adbd。
- **豆花打开的无线调试用完就关**（SelfStart.closeWirelessIfOurs，成功失败都关）；用户自己开着的不动。"是豆花开的"在打开之前就 commit 进
  selfstart.xml（own_wireless_at，15 分钟内有效）：进程中途没了，下次进程起来（SelfStart 构造时）就关掉；开着又带着这个标记的，启动时照"豆花开的"用完关掉。
  打开后被系统关回去（网络没被信任，系统的确认弹窗还在）而放弃时，标记留着、盯 3 分钟：用户这时才点"允许"就接着启动、用完关；开始配对 / 断 Wi-Fi / 到点就不盯、清标记。
  前提是 USB 调试开着（不然关无线调试时 adbd 停掉、服务跟着死），所以 USB 调试没开就不启动（RESULT_ADB_OFF）。
- **用户自己开的无线调试豆花绝不替他关**（他的电脑可能正经无线调试连着这台手机，替他关了电脑就断），只提醒：配对成功的通知里、设置页"无线调试"一项
  （开着且不是"豆花开的"标记时）琥珀色提醒 + 跳到开发者选项的无线调试那一页。
- **自动的路径在退避中不自己打开无线调试**（AutoStartBackoff）：自动 = 打开豆花时的 autoStart、开机任务、App 更新后的重启（onServiceConnected），
  等"用户允许"后接着的那次启动受不受限沿用最初那次是自动还是手动。手动启动（设置页按钮、配对成功后那次）成功、或者盯守中用户在系统弹框里点了
  "允许"后接着的那次启动成功（最初是自动的也算，用户亲手确认了这个网络；AutoStartBackoff.after 的 allowedByUser）才解除；标记 commit 落盘。
- **不改 adb_allowed_connection_time**（不动全局安全设置）：密钥 7 天不用被系统作废，靠 keyRejected 提示重新配对。
  "密钥被拒"只认 TLS 层的报错（SSLException、不是包着 EOF / reset 的），无线调试还开着、重新找端口再连一次还被拒才算；被判被拒后设置页仍给"启动服务"，手动启动成功就解开。
- **"已配对"= 配对成功过**（selfstart.xml 的 paired，配对成功才写）且密钥文件还在；密钥文件在配对前就生成了，不能拿它在不在当已配对。
  loadOrCreate 新生成了密钥就清掉 paired。
- **清单必须申请 WRITE_SECURE_SETTINGS**：pm grant 只给清单里申请过的权限，不申请就永远开关不了无线调试（ServerCommandsTest 锁住）。
- **`dev.douhua.adb` 包只用 JDK**，不 import android.*；要 Android 的（Keystore、NsdManager、SSLSockets）放 dev.douhua。

## 测试

- **测试设备**：用 USB 连的测试机（序列号见 `.private/NOTES.md`），命令里显式 `adb -s`。用户的主力机禁止拿来测试。
  测试机不是三星、没有 DeX，服务在它上面找不到触摸屏（见"想做的功能"里"服务支持缩放面板的手机"），桌面模式只能交给用户在主力机上试；`tools/build.sh --install`、`start-server.sh` 等脚本连着多台时会列出来让人选（tools/adb-device.sh），我自己跑时一律加 `-s <测试机>`。
- 各组离线测试测了什么、真机怎么测：`docs/testing.md`。

## 已知技术债 / 没验证的

- F 行翻转的同步（协议 24）：App 照分流报的 FROW_FLIP 和设置不一样就当"那边 Fn+Esc 翻了"、存设置。设置页改的不受影响（去设置页时 onStop 断开连接，回来重连时先发 CMD_CONFIG）；
  只剩一个几毫秒的窗口：本地分流里刚 Fn+Esc 翻了、发给服务的 CMD_CONFIG 还在路上时，服务正好报了一次状态（比如刚放开触摸屏时 releaseAll 那一次）带着旧的翻转，
  App 会把设置改回旧值。要按准得给"翻转"单独一条消息或带个序号，没做。
- 蓝牙的多媒体报告 3 由 F 行的第二功能（按下发码、松开发 0）和按钮条 / 角上按钮的媒体动作（按一下 = 码 + 0）共用：按着 F 行的音量时点一下角上的"播放"，音量会被提前松开（不会卡键）。
  以后可以让按钮动作也走 TouchRouter 的槽。桌面模式下按钮的媒体动作照旧是注入安卓按键，F 行走 "Douhua Keyboard Controls"，两条路不相干。
- 修饰键"用过了"只认经分流（TouchRouter）的鼠标 / 键（2026-11-01）：蓝牙模式下有几路鼠标键由 App 直接经蓝牙发——音量键配的"按住左键"（MainActivity.holdLeft → air.holdLeft）、空中鼠标、
  快捷行 / 角上按钮里的"单击"类动作（btMode 下 click 直接 bt.sendMouse）——按着修饰键 0.4–1 秒时用这几路点了鼠标，松手照样会锁（0.4 秒有震动、键亮着，再点一下就解开）。
  要补得给"服务转发"那条路加一条命令（App 告诉服务"按着的修饰键用过了"）。桌面模式这几路都经服务的分流（CMD_HOLD_LEFT、CMD_CLICK），没有这个问题。
- 面板自己收触摸时按住字母层的 ⇧ 再点 ?123：⇧ 在符号层没有，换层时当取消松开 → Shift 松开（功能行的 Shift 各层都有，不受影响；符号层的键本来自带 Shift，少见）。
- Caps 的显示是豆花自己记的（主机的大写灯没读）：苹果设备开着"用大写锁定键切换输入法"时点 Caps 是切输入法、显示照样翻；主机那边被别的键盘改了大写锁定也不知道。长按 Caps 对回来。
- 打字测试工具（tools/typelog/analyze.py、KeyReplay）认不得合并键的 id（Proto.dualKey，> 0xFFFF），小红点状态的打字测试分析不了。
- 桌面模式服务连着没接管（面板自己收触摸）时，按住多久按服务收到 CMD_HID_KEY 的时刻算，消息排队晚到会让 0.4 秒 / 1 秒的边界差几十毫秒。

- 惯性转着时同一个触摸帧里一根按在键（或分屏时另一边）上、一根按在触控板上：按 slot 顺序先处理的是键那根时惯性已经停了，触控板那根就不算"抓住"，轻点照常点击
  （先处理触控板那根时不算点击）。罕见（要几毫秒内同时落下），没改：要改得让 TouchRouter 记住"这一帧停过惯性"再告诉引擎。
- 分屏 / 小窗：别的应用的小窗先开着、之后才把豆花切成全屏时，豆花一直是顶层，检测不到；服务独占后小窗点不到、也抢不到焦点，只能先按豆花的"退出"。
  桌面模式（和接着外接屏的蓝牙模式）不看顶层，别的应用的小窗浮在全屏豆花上面完全检测不到。先在全屏锁了方向再进分屏，锁定留到断开。

- 蓝牙发送全在主线程（本地分流、服务转发、空中鼠标都是）：sendReport 是同步 binder，主线程一卡报文跟着晚。要更稳可开专门的发送线程
  （BtHid 的 hid / connected 和鼠标状态要一起挪过去、保住键盘和鼠标报文的先后），等实测打字延迟再定。

- 手机自己拉起服务：Google 若让 adbd 只绑 wlan0（讨论中），连 127.0.0.1 这条路会整体失效；探测服务状态每次都让服务建 / 拆一次虚拟设备
  （要省得改服务：先发 hello，或者加只探测的命令）。

- 还没由真人试过：外接键盘（含桌面模式下手机输入法打中文）、双指缩放在哪些应用生效、双击、系统方向锁定版的旋转按钮、接上外接屏的通知。
- 服务被直接杀掉（tools/stop-server.sh）时不会恢复方向锁定；服务因兜底退出（旧会话 2 秒收不完尾还占着触摸屏）时也一样。
- start-server.sh 只能在"有连接在排队"时认出卡住；服务在 accept 之后、发 hello 之前卡住它看不出来（靠 App 那边 5 秒的提示）；
  会话中途卡住、App 照常写的情况 App 也察觉不到（服务不给 App 发保活）。
- 服务用 `--debug` 起时，Session.onDown 把每次按下落在哪个区域（键 id）写进手机上的 /data/local/tmp/douhua-server.log，等于把打的字落了盘（打字测试的触点日志只走 logcat，不受这条影响）。
- 实体键：音量上 + 音量下一起按（无障碍快捷方式）没当组合键交还，只要有一个音量键配了动作（被截住），豆花在前台时就用不了它（配了的那个被豆花吃掉，系统只看到另一个）；
  触摸屏释放时 router.releaseAll 会把实体键按着的"左键"（CMD_HOLD_LEFT）一起松掉（之后实体键迟到的松开不理，不减收尾以后才按下的左键 / 左键杆，见不变量）；服务被直接杀掉时，透传中按着的音量键
  系统会一直连发到下一次按键；交还系统时最后一次读 grabFd 和关掉它之间（微秒级）到的事件会丢；只配了"点一下"的音量键按下当场就发给 App，
  之后才认出是组合键的话动作已经做了。
- 键盘键位是初版，按实际用起来再调；竖屏一行 12 个键偏窄。
- MtDecoder 假设开始读时当前 slot 是 0（没法用 EVIOCGABS 查）；开始读之前就按着的手指一律忽略到抬起。
- TouchDevice 依赖 `dumpsys input` 的文字格式（三星 One UI 9 实测）；系统升级改格式会找不到触摸屏，状态栏会显示原因。
- 接管期间横幅通知、来电横幅点不了（全屏来电会让豆花退到后台，自动释放）。
- 接管期间长按电源键弹出的关机菜单点不了：它是系统对话框，豆花不 onPause、照报 active，触摸还归服务（还会分给菜单底下看不见的按钮）。以前就有。改法（失去窗口焦点也报 inactive）在接着外接屏时用不上（这时主窗口平时不收焦点，见不变量 MainActivity.updateFocusable），没做。
  同一类：分屏透传时下拉的通知栏 / 快捷设置、音量面板、权限弹窗、侧屏面板盖在豆花那半边的部分也摸不到（见决策记录"桌面模式分屏透传"的"留给用户定"③）。
- 全屏独占开始时按着的手指（MtDecoder 忽略到抬起），系统那边一直当它按着（按在豆花自己窗口上，影响小，没管）；只在分屏时等它抬起再接管。
  系统眼里卡着一根其实已经抬起的手指（独占时吞掉了它的抬起）时，分屏下先报 GRAB_WAIT_LIFT，约 1.5–2 秒内核那边没动静就当旧状态接管；
  代价：plain 开着之前就按在另一边、之后一动不动的真手指（长按）也会被当成旧状态，独占后它的抬起到不了系统（和没有这道检查时一样）。
  "独占前就按着"的手指第一个独占事件就是抬起时（plain 开着之前就按着、一直没动、轻轻抬起），抬起被吞，系统要等同一个 slot 下次按下才补上（日志"系统可能留着一根按着的手指"）。
- 滚动是整格滚轮。要更顺可以试 HID 高精度滚轮（Resolution Multiplier，需要服务回应 UHID_SET_REPORT）。
- 外屏一直亮着同样的边框，长期有烧屏风险；边框已经调得很暗。纯黑以外的主题（2026-10-30）整块键帽、触控板都亮着，更费电、烧屏风险更大，设置页那节有一句说明。
