@AGENTS.md

（上面一行把 AGENTS.md 里的协作红线引进来；红线只在 AGENTS.md 改，这样别的 AI 工具也读得到同一份。）

# 架构与决策记录

## 架构

两种连接方式（设置里选，Prefs.btMode）。DeX 模式两个进程，代码在同一个 APK 里：

```
外屏触摸屏 /dev/input/eventN ──(服务独占)──┐
                                           ↓
       Server / Session（shell 身份，app_process 拉起；主线程只 accept，每条连接一个会话线程）
         ├ TouchReader + MtDecoder：读原始多点触控，用系统的换算矩阵转成屏幕坐标
         ├ 按 App 报来的区域分流：触控板 → GestureEngine；键盘键 → UhidKeyboard；按钮 → 报告给 App；
         │  "左键" → 直接按住鼠标左键
         ├ UhidMouse / UhidKeyboard：/dev/uhid 造的 USB 鼠标和键盘 → 系统输入 → DeX
         │  （双指开合 = UhidKeyboard 按住 Ctrl + UhidMouse 滚轮）
         ├ KeyReader + HardKeyGate：独占音量键设备（带电源键的只旁观），判断点一下 / 按住报给 App，没配的键注入补发
         └ Injector：带 displayId 往 DeX 屏注入按键（隐藏 API，反射）
                ↑ 布局 / 参数 / 心跳 / 按键 / 实体键配置   ↓ 震动 / 三指四指手势 / 按钮事件 / 修饰键 / 接管状态 / 实体键
       MainActivity（App，普通权限）：画界面、震动、执行动作；127.0.0.1:37601
```

| 文件 | 职责 |
|---|---|
| `src/dev/douhua/Proto.java` | 协议常量，两边共用（版本 16） |
| `src/dev/douhua/GestureEngine.java` | 手势识别，纯 Java（`tools/test.sh` 离线测） |
| `src/dev/douhua/TouchRouter.java` | 触摸分流 + 键盘修饰键锁定 + 开合的 Ctrl，纯 Java（离线测）；DeX 模式跑在服务的 Session 里，蓝牙模式跑在 App 里；键盘的缝分给最近的键（nearestKey）、键盘触点记录（Trace）也在这里 |
| `src/dev/douhua/Hid.java` | 鼠标 / 键盘 / 媒体键的 HID 描述符和报文、键盘按键状态，纯 Java；USB 和蓝牙发的字节一样，蓝牙多报文编号 |
| `src/dev/douhua/BtHid.java` | 蓝牙模式：注册成蓝牙键盘鼠标、连上次那台、发报文；进程里一份，最后一个用的界面走了 60 秒才停 |
| `src/dev/douhua/MainActivity.java` | 界面、按钮事件、贴字面板、数字键盘、旋转、动作执行；把区域位置报给服务 |
| `src/dev/douhua/KeyboardPanel.java` | 外接键盘键位（Gboard 式字母区 + 功能行 / 数字行 / 快捷行，符号层、Fn 层），键 id = 用法码 \| 自带修饰键 << 8；报区域时最后报面板整块（REGION_KEYBOARD）；服务没接管、没走本地分流时面板整块自己收触摸（onTouchEvent，按手指编号分给键）；三层的中间四行各建一次、换层只切显示；键帽文字 / 高亮没变的键不重设 |
| `src/dev/douhua/KeyFingers.java` | 键盘面板自己收触摸时每根手指按的是哪个键（按 pointer id 记，抬起只松自己那个）；按下定键 = 包含的键优先、缝里交给 TouchRouter.nearestKey，纯 Java（离线测） |
| `src/dev/douhua/KeyboardScreen.java` | 键盘界面：工具栏 + 可调大小位置的键盘 + 键盘上方的小触控板；调整模式由 App 自己收触摸 |
| `src/dev/douhua/Style.java` | 共用样式（边框、颜色、按下 / 高亮） |
| `src/dev/douhua/ButtonStrip.java` | 左侧按钮条，屏幕矮时自动分列 |
| `src/dev/douhua/PadArea.java` | 触控板 + 数字键盘的排布 |
| `src/dev/douhua/TrackpadView.java` | 只画边框和状态字 |
| `src/dev/douhua/Actions.java` | 统一的动作清单（按钮条 / 角上按钮 / 手势 / 键盘最下面一排共用；加动作只加一行；自定义组合键 id 形如 `combo:键id:标签`） |
| `res/values*/strings.xml` | 九种语言的界面文字 |
| `art/icon.webp` → `tools/make_icon.py` | App 图标原图（不带元数据）；脚本生成自适应图标的前景层 `ic_launcher_fg.webp` 和彩蛋大图 `egg.webp`（都在 `res/drawable-nodpi/`，换图后重跑，要 Pillow） |
| 彩蛋 | 设置页最下面的版本号快速连点 5 下（每下间隔 ≤ 0.6 秒），弹出 egg.webp；SettingsActivity.version / showEgg。README 里不写 |
| `src/dev/douhua/Combos.java` | 自定义组合键：可选的键、拼 id、起名字、迁移上一版键盘快捷行的存储格式 |
| `src/dev/douhua/HardKeys.java` | 音量 + / − 的"点一下 / 按住"判断（做什么由设置里选的动作定），纯 Java（离线测）；蓝牙模式 App 自己用，DeX 模式服务里的 HardKeyGate 用同一个；设了动作的键整个归豆花 |
| `src/dev/douhua/AirPointer.java` | 陀螺仪 + 重力 → 光标位移：斜基分解（左右 = 绕竖直轴、上下 = 绕手机自己的右轴、绕水平朝前拧不算）、固定灵敏度不加速、两级 1€ 滤波消抖 + 软死区（可选轨迹平滑、防抖半径），纯 Java（离线测） |
| `src/dev/douhua/CenterRunner.java` | 主线程上按固定间隔一帧帧发位移（只剩调试位移 DebugMoves 在用） |
| `src/dev/douhua/AirMouse.java` | 空中指针：按住移动 / 按住拖动 / 按住左键（按来源分开记）、陀螺仪按需开关、移动颗粒；回正中发一帧绝对坐标 |
| `src/dev/douhua/DebugMoves.java` | 只在可调试包里：`adb shell am broadcast -a dev.douhua.DEBUG_MOVES --es moves "dx,dy,ms;…"` 让手机经蓝牙发任意位移（要 DUMP 权限，adb shell 有），量回正中 / 加速用；另有 DEBUG_AIRLOG（空中鼠标日志）、DEBUG_KEYLOG（键盘触点日志，租期制） |
| `src/dev/douhua/RoundPad.java` | 空中鼠标页的圆钮：圆 + 3×3 九个小点（像 ThinkPad 小红点），按住移动、点一下锁定移动 / 再点解锁 |
| `tools/air-log.sh` | 空中鼠标双日志：手机端 AirMouse 调试日志（DebugMoves 的 DEBUG_AIRLOG 广播打开，可改激光笔式发送间隔、临时换消抖参数）+ 电脑端 KWin 记光标 + 能读就 evtest 记原始报文，结果在 `.private/airlog/`。默认按终端提示随手画；`--trace` 是描红：浏览器全屏页（tools/airlog/trace.html，本机小服务 trace_server.py 切参数、收结果），三轮盲测三组参数，最后选最顺手的 |
| `tools/type-log.sh` | 打字测试记录：DebugMoves 的 DEBUG_KEYLOG 广播开键盘触点日志（租期 10 秒，每 3 秒续）+ logcat 收 `douhua-keys` + 电脑浏览器打字页（tools/typelog/typing.html，本机小服务 server.py，句子 sentences.txt），结果在 `.private/typelog/`；`--dex` 用 adb reverse 把页面开在 DeX 里 |
| `tools/type-analyze.sh` + `tools/typelog/` | 分析一次打字测试：analyze.py 对齐、误按率（按空 / 按错没改 / 按错改过）、每个键的落点偏移、电脑丢键核对（`--selftest` 在 test.sh 里跑）；KeyReplay.java 用真正的 TouchRouter 重放触点，核对和手机一致、比较缝分给最近的键前后 |
| `tools/air-analyze.sh` + `tools/airlog/` | 分析一次双日志：送达节奏（卡不卡、排不排队）；用记下的原始转动按参数网格重放真正的 AirPointer（Replay.java），算静止抖动、2Hz / 5Hz 以上的晃、速度落后、距离保留 |
| `tools/adb-device.sh` | 选哪台手机（-s → ANDROID_SERIAL → 只连一台就用它 → 多台时列出来让人选），start/stop-server、selftest、build --install 共用 |
| `src/dev/douhua/KeyboardSnap.java` | 调整键盘时的轻微吸附，纯 Java（离线测） |
| `src/dev/douhua/Prefs.java` | 设置键名和默认值 |
| `src/dev/douhua/Link.java` | App 端连接，断线每秒重连，收发消息；空闲 2 秒发保活（CMD_PING），连接超时 / 5 秒等不到 hello 报"服务没响应"；记下 hello 里服务的 APK 路径（serverApk）；回调全部经一个异步 Handler 到主线程（不排在界面重画的同步屏障后面） |
| `src/dev/douhua/DexDisplay.java` | 找 DeX 所在的屏（按键注入目标） |
| `src/dev/douhua/DisplayReceiver.java` | 服务叫醒它：接上外接屏弹通知（点开在手机屏上打开豆花），拔掉收回 |
| `src/dev/douhua/server/Server.java` | 服务入口：主线程只 accept，按 SessionPolicy 接 / 顶掉旧会话 / 回"忙"，每条连接一个 douhua-session 线程；`probe` 只画方块不点击；`grabtest` 独占 0.5 秒；`keys` 只读：用 getevent -i 列出输入设备、音量键能不能截、会截哪块 / 旁观哪块 |
| `src/dev/douhua/server/Session.java` | 一次 App 连接：建虚拟设备、发 hello、独占时机、把触摸交给 TouchRouter、把结果写到 /dev/uhid 和发回 App；10 秒没消息自己断开，可被新连接顶掉（kick） |
| `src/dev/douhua/server/SessionPolicy.java` | 新连接来时旧会话怎么办：没有 / 已收尾 → 接，沉默 ≥ 5 秒 → 顶掉，否则回"忙"，纯 Java（离线测） |
| `src/dev/douhua/server/GrabCondition.java` | 独占条件里的心跳（只有心跳和布局算，保活不算）和释放原因码，Session 调它，纯 Java（离线测） |
| `src/dev/douhua/server/TouchGrabLock.java` | 全服务一把锁：同一时刻只有一个会话能独占触摸屏，纯 Java（离线测） |
| `src/dev/douhua/server/RotationLock.java` | 方向锁定全服务一份（`wm user-rotation`，记原值、正常断开时恢复），纯 Java（离线测） |
| `src/dev/douhua/server/Shell.java` | 带超时的子进程（wm 5 秒、dumpsys input / am 10 秒），只用 JDK（离线测） |
| `src/dev/douhua/server/SystemServices.java` | 从 ServiceManager 现取系统服务代理（框架重启后旧代理全死），DisplayWatcher / Injector / DexFocus 共用 |
| `src/dev/douhua/server/LogThrottle.java` | 同一条错误限频（第一次记、之后每 60 秒一行汇总、恢复时一行），展开异常原因链，纯 Java（离线测） |
| `src/dev/douhua/server/TouchReader.java` | 独占并读触摸屏，poll 100ms 检查是否该释放 |
| `src/dev/douhua/server/MtDecoder.java` | 多点触控协议 B 解码，纯 Java（离线测） |
| `src/dev/douhua/server/TouchDevice.java` | 从 `dumpsys input` 找屏幕对应的触摸屏和 RawToDisplay 矩阵，解析部分纯 Java（离线测） |
| `src/dev/douhua/server/ScreenRotation.java` | 物理屏坐标 → 当前方向坐标，纯 Java（离线测；方向 1 的公式取自真机） |
| `src/dev/douhua/server/Evdev.java` | 打开 / 独占输入设备（EVIOCGRAB，经 libcore 的 ioctlInt） |
| `src/dev/douhua/server/Uhid.java` | /dev/uhid 造 USB HID 设备的公共部分 |
| `src/dev/douhua/server/UhidMouse.java` / `UhidKeyboard.java` | 虚拟 USB 鼠标 / 键盘，只管发报文（描述符在 Hid，按键状态在 TouchRouter） |
| `src/dev/douhua/server/PeerUid.java` | 查 /proc/net/tcp 确认连进来的是谁 |
| `src/dev/douhua/server/TestClient.java` | 服务自测：冒充 App 接管几秒后停发心跳，看会不会自动释放；`keys=<4 位>` 一起截音量键，打印实体键事件 / 状态 |
| `src/dev/douhua/server/KeyDevices.java` | 跑 `getevent -i`（经 Shell，5 秒超时）解析输入设备，挑能单独独占的音量键设备（白名单，带电源键的绝不截）和只旁观的带电源键设备，解析部分纯 Java（离线测） |
| `src/dev/douhua/server/HardKeyGate.java` | 音量键的判断：配了动作的交给 HardKeys、同设备上没配的透传注入；旁观到电源键 + 截住的音量键一起按（截屏）就按原时间补发后交还系统，纯 Java（离线测） |
| `src/dev/douhua/server/KeyReader.java` | 独占并读音量键设备、不独占地读旁观设备（plain + grab 两个描述符，交还时读空 grabFd 再关），poll 100ms 检查是否该放开 |
| `src/dev/douhua/server/CallState.java` | 问 Telecom（ITelecomService 反射）是不是在来电响铃 / 通话中（这时音量键还给系统），缓存 400ms，结束后冷却 2 秒才算空闲；只在实体键线程用 |
| `src/dev/douhua/server/DisplayWatcher.java` | 每 1.5 秒查屏幕列表，外接屏接上 / 拔掉时 am broadcast 给 DisplayReceiver |
| `src/dev/douhua/SelfStart.java` | 手机自己拉起服务的编排（进程里一份，单线程 worker）：开 / 关无线调试、mDNS 找端口、连 adbd、pm grant、停旧服务、起服务、等 hello；配对（成功才记 paired）；自动启动、App 更新后重启（按 hello 里的 APK 路径）；"无线调试是豆花开的"落盘、放弃后盯 3 分钟"用户允许"；探测服务状态（连一次端口读 hello） |
| `src/dev/douhua/SelfStartJob.java` | 开机后等连上 Wi-Fi（截止 10 分钟）调 SelfStart.autoStart，失败只记日志 |
| `src/dev/douhua/AutoStartBackoff.java` | 自动启动的退避规则（被系统拒过就不再自动打开无线调试，手动启动成功一次才恢复），纯 Java（离线测）；标记在 selfstart.xml 的 auto_backoff |
| `src/dev/douhua/BootReceiver.java` | 开机广播：设置里"自动启动服务"开着就排 SelfStartJob |
| `src/dev/douhua/PairingService.java` | 配对用的前台服务（shortService），通知里 RemoteInput 输配对码；服务起来时无线调试还开着就在通知里提醒关掉（带跳转按钮） |
| `src/dev/douhua/ServiceSection.java` | 设置页最上面一节"豆花儿服务"：Wi-Fi / 开发者选项 / USB 调试 / 无线调试 / 配对 / 服务逐项实时显示、直达系统页面的按钮、配对分步引导（找端口时可"取消配对"；公共网络别选"始终允许"）、自动启动开关和退避说明、无线调试是用户自己开着时的琥珀色提醒 + 跳转 |
| `src/dev/douhua/AdbKeys.java` | 豆花的 adb 密钥：RSA 2048，AndroidKeyStore 的 AES-GCM 密钥加密后存 files/adb/key.enc（包装密钥不在 / 对不上 = 作废，删掉当没配对；换机迁移不带它，见 res/xml/data_extraction_rules.xml）；自签证书 + SSLContext；导出 TLS 密钥材料走公开的 SSLSockets（API 31+） |
| `src/dev/douhua/AdbMdns.java` | NsdManager 找 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`，只认本机地址、127.0.0.1 上真在监听的端口 |
| `src/dev/douhua/ServerCommands.java` | 经 adb 执行的 shell 命令（pm grant、停旧服务、起服务），和 tools/start-server.sh、stop-server.sh 同一套，纯 Java（离线测，sh -n 查语法） |
| `src/dev/douhua/adb/` | 纯 JDK、不 import android.*（tools/test.sh 整包离线测）：Ed25519Group + Spake2（BoringSSL spake25519.cc 的 Java 移植，保留其 Apache-2.0 声明）、Hkdf、PairingAuth（AES-128-GCM）、AdbMessage（消息帧）、AdbPublicKey（adb 公钥格式）、SelfSignedCert（手写 DER）、AdbTls（TLS 1.3、互不校验证书）、PairingClient、AdbConnection（CNXN → STLS → TLS → OPEN shell） |

蓝牙模式：报文最后都经 BtHid（系统的 BluetoothHidDevice，键盘 + 鼠标 + 媒体键合成一个描述符）→ 配对的电脑 / 平板 / 电视。
触摸谁来收看服务在不在：
- 服务开着（布局带 relay=1）：和 DeX 一样由服务独占触摸屏、TouchRouter 分流，系统手势碰不到；鼠标键盘报文不写 uhid，
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

- **独占触摸屏的条件**：App 报 active（在前台、打字面板没开）、参数已到、心跳（每秒一次，主线程发）没超过 3 秒。
  任一条件不满足，100ms 内释放；连接断开立刻释放。这是"外屏不会被锁死"的保证，改动时必须保住。
  被新连接顶掉、10 秒没消息断开，都走"连接断开"同一条路。保活（CMD_PING）不是心跳，绝不能刷新心跳（规则在 GrabCondition.onMessage，ServerPartsTest 锁住）。
- **同一时刻只有一个会话能独占触摸屏**（TouchGrabLock，持有范围从打开触摸屏之前到关掉之后；内核 EVIOCGRAB 的 EBUSY 兜底）。
- **旧会话 2 秒收不完尾、还占着触摸屏 → 服务退出**（System.exit，内核收回独占和虚拟设备）；没占触摸屏就由新会话替它关掉虚拟设备。
- **键盘的缝分给最近的键只改"按在缝里"的触点**：hit() 先照旧按列表找第一个包含的区域，只有命中键盘范围（REGION_KEYBOARD，
  排在它的键后面）才找最近的键；按在键上的结果、排在前面的区域优先都不变（TouchRouterTest 逐点锁住）。规则只有 TouchRouter.nearestKey 一份，
  KeyboardPanel 自己收触摸、KeyReplay 重放都调它。
- **键盘触点日志等于按键记录**：只在可调试包、只能由 adb 广播（DUMP 权限）打开、租期 10 秒不续自己关、只写 logcat 不落盘；
  服务端每条连接默认关，开关只能由通过 uid 校验的连接发（CMD_TRACE）。
- **独占实体键的条件**：和触摸屏同一个 Session.wanted（active、参数到了、心跳 < 3 秒），再加 App 发过 CMD_HARD_KEYS 且至少截一个键、
  按键注入可用（不可用绝不独占，不然同设备上没配的音量键补发不出去；截着时补发失败一次也算不可用，等配置变了或 App 回前台再试）、
  没在来电响铃 / 通话中（服务问 Telecom，CallState；查不到不拦）；不依赖触摸屏接管成功。不满足 100ms 内放开，断开立刻放开
  （KeyReader 每轮 poll ≤ 100ms 问 keepGoing）。同一时刻只有一个会话打开实体键设备（Session.KEY_GRAB）；收尾 2 秒还独占着 → 服务退出。
- **注入过的每个"按下"在所有退出路径上都配一个"松开"**（HardKeyGate.releaseAll 在 KeyReader 的 finally 里，交还后由真松开配上），
  否则系统一直连发、音量推到头。
- **服务绝不独占带电源键（116）的设备**，任何配置下都不（同设备上的音量键因此截不了，报 HARD_SHARED）；带电源键的机身设备只旁观
  （不独占地读），只用来认"电源键 + 截住的音量键"（截屏）。设备白名单：事件类型只有 KEY（加 MSC；SYN 隐含；REP / PWR 用 getevent 看不见，连发事件被忽略）、键码只有 114 / 115、
  不是 USB / 蓝牙 / 虚拟总线、厂商号不是 0x1209、getevent 的输出认全了（KeyDevices.whyNotGrabbable）。
- **区域只报看得见的那一屏**：键盘（含空中鼠标页上调出来的）、空中鼠标页、主界面三组互斥（MainActivity.collectRegions，键盘最先判断）。
  只把键盘排前面不够：键盘空隙里看不见的圆钮、格子照样按得到。
- 只接管 App 所在那块屏的触摸屏（按 displayId 在 dumpsys 里找）；找不到（比如虚拟屏）就不接管。
- 坐标换算：系统的 RawToDisplay 矩阵（原始 → 没转过的物理屏）+ ScreenRotation（物理屏 → 当前方向）。
  真机上这个矩阵转屏后仍不含旋转；万一某个系统版本的矩阵已经含旋转（unrotated() 为假），就不再自己转。
  方向 / 尺寸一变就重新接管、重新取矩阵。
- 鼠标报文里的 buttons 是当前状态不是增量；释放触摸屏或断开时引擎 cancel，按住的键自然松开。
- 一次手势里出现过多根手指，就按多指处理到全部抬起。
- 按键一律带显式 displayId 注入到 DeX 屏。
- 主窗口平时 `FLAG_NOT_FOCUSABLE`（不抢 DeX 的焦点），打字面板打开时、没有外接屏时去掉（MainActivity.updateFocusable）。
  没有外接屏时虚拟键盘的键只能发到手机屏，豆花是那块屏的当前应用、窗口却不收焦点，系统等 5 秒没人接就弹"豆花没有响应"
  （0.1.0 的 bug，日志里是 `Input dispatching timed out (Application does not have a focused window)`）；
  这时豆花收下自己虚拟键盘（厂商号 Proto.HID_VENDOR）的键并吞掉，免得 Esc 被系统当返回键。
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

## 决策记录

- **鼠标用 /dev/uhid 造真 HID 设备**，不用无障碍服务也不用 injectInputEvent 注入鼠标事件：
  无障碍只能模拟手指点屏，没有光标、悬停、右键；注入的鼠标事件不走 InputReader，光标图标不动。
- **服务独占触摸屏、自己读原始触摸**（第二版）。第一版是 App 收触摸再发给服务，真机上光标只动一下就停：
  用户开着 Tasker 的无障碍服务（带执行手势权限），系统因此装了 AccessibilityInputFilter（MotionEventInjector），
  它同一时刻只认一个输入设备——手指按下就把鼠标踢掉（HOVER_EXIT），鼠标一动就把手指取消（ACTION_CANCEL，
  flag 0x20），之后这次触摸的事件全丢。日志里每次手势都在第一个鼠标包后 7–80ms 被取消。
  虚拟屏 + `input swipe` 的注入测试测不出来（注入事件不受这条规则管）。
  独占后系统收不到触摸，只剩虚拟鼠标一个设备，不再冲突。代价：豆花在前台时系统手势用不了，所以有"退出"按钮。
- **三星自带触控板的做法**（反编译 SystemUI 的 dextouchpad 包查到）：界面只往 Settings.Global 写
  `dex_touchpad_enabled` / `dex_touchpad_desktop_display_id` / `dex_touchpad_rotation`，由系统输入层把触摸屏切成触控板。
  没用它：手势由系统识别，轻点拖动、震动、自定义三指四指都做不了。内屏触控芯片另有 `sec_touchpad` 设备（外屏没有）。
- **通信用 TCP 回环，不用 Unix socket**：实测普通 App 连 shell 的抽象 Unix socket 被 SELinux 拒绝。
  已知缺口：App 读不了 /proc/net，没法反过来校验服务身份；经过连接的只有布局、参数和按键码，打的字不经过它。
- **后台会被断网**：屏幕灭着时 Doze 会掐后台 App 的网络（包括回环）。前台使用没问题。
- **手势自己识别**，不交给系统触控板识别：否则豆花不知道何时点击（震动对不上），三指四指也定制不了。
  代价：滚动是一格一格的滚轮。
- **键盘做成虚拟 USB 键盘**（第三版）：第二版是"手机输入法打一句 → 剪贴板 → Ctrl+V"，用户嫌别扭。
  HID 键盘下 DeX 看到的是真键盘，打中文靠 DeX 侧输入法的外接键盘拼音。贴字面板保留成可选动作（语音输入有用）；
  用户开着"连着物理键盘也显示屏幕键盘"（show_ime_with_hard_keyboard=1），所以虚拟键盘在时手机输入法照样能弹。
- **键盘第二版（整屏键盘界面）**：第一版把 PC 键位塞在触控板下面，用户说难点。改成整屏键盘界面，字母区照 Gboard，
  修饰键 / 数字 / 快捷键各占一排；符号键和快捷键"自带修饰键"（id 高字节），一按就出，不用先锁 Shift / Ctrl。
  大小位置照 Gboard 的调整方式（拖四边、拖中间），横竖屏各存一份（Prefs.keyboardRect）。
- **修饰键点一下锁定**：触摸屏上按组合键靠"先点 Ctrl 再点 C"；锁定时修饰键真的按着（HID 报文里），所以 Ctrl + 点击也行。
  打完下一个普通键（松开时）自动解开。状态由服务发 MSG_MODIFIERS 给 App 画高亮，App 不自己记，避免两边不一致。
- **缩放 = Ctrl + 滚轮**：鼠标没法发捏合手势；InputReader 会把所有键盘的修饰键状态带进鼠标事件，所以虚拟键盘按住 Ctrl、
  虚拟鼠标滚滚轮，应用收到的就是 Ctrl + 滚轮。双指先判是滚动还是开合（间距变化超过 16dp 且比整体移动多 = 开合），判定后整次手势不变。
- **边缘单指滚动**：用户反馈有时不方便用两根手指。判断在服务里做（Session 知道触控板区域，按下点离左右边 < edgeWidthDp
  就把 edge 标记传给引擎）；引擎里边缘手势单指只滚竖向、不动光标，轻点照常单击；轻点后马上去边缘滑，先完成单击、不当拖动。
- **横向边缘滚动 + 咔嗒**：边缘标记是 EDGE_VERTICAL / EDGE_HORIZONTAL 的组合，角上两种都有，第一次滑过阈值时按主方向定。
  滚动每发一次滚轮报文就发一个 HAPTIC_TICK（PRIMITIVE_TICK），模拟滚轮的段落感。
  咔嗒强度单独可调（用户反馈太弱）：小 = TICK×1、中 = CLICK×0.8（默认）、大 = 系统的 HEAVY_CLICK；
  总强度为"关"时照样不响。键盘调整时的吸附提示也是 HAPTIC_TICK，跟着这个强度。
- **震动整体强一档**（0.1.0 之后，用户反馈太弱）：小 = CLICK×0.65、中 = CLICK×1、大 = HEAVY_CLICK。
  用调试版设置页的"试手感"区实测：拿在手里时 HEAVY_CLICK 最强也清脆；按"媒体"类发的 CLICK 比按"硬件反馈"类的稍强，
  说明系统"触摸交互"震动强度（用户设的 3/5）在打折，但没改用途：媒体类震动在省电模式下会被系统静音。
  外屏触摸屏没有压力轴（只有 ABS_MT_TOUCH_MAJOR/MINOR）；用户在试验板上实测，用力按和轻碰的接触面大小差不多，
  所以学不了苹果触控板的"按压点击"。"试手感"区用完已删。
- **精细移动 + 长按要手指基本不动**（用户反馈微调光标不好控制、微调时被当成长按变成拖动）：
  原来的加速只在快时放大、慢时不缩小，手指挪 1 毫米光标走十几个像素；现在慢速时再乘 slowFactor（默认 0.55），
  速度到 0.3 dp/ms 恢复，速度用 30ms 时间常数平滑（单帧速度抖，倍率跟着抖）。
  长按原来用轻点的容差（6dp，约 1 毫米），微调光标恰好在这范围内慢慢挪就被当成长按；现在长按单独用 2.5dp。
- **拖动时持续轻震**（用户要的）：DRAG_START 后、按着"左键"按钮时，低幅度 one-shot 一直嗡（幅度 18 / 40 / 80；最初 45 / 90 / 150，用户嫌"小"都有点强），
  DRAG_END 时停；别的震动打断它后 80ms 接着嗡。漏收 DRAG_END（连接断了）有兜底：60 秒自停，onPause / 断开 / 触摸被释放时停。
  马达不能调幅度时退成每 100ms 一下 12ms 的轻点。只改 App，服务端不变。
- **接管触摸屏时把键盘输入切到 DeX 屏**（DexFocus，2026-10-13）：在手机屏上打开豆花，系统就把键盘输入的目标切到手机屏；
  豆花窗口在接着 DeX 时故意不收按键，外接键盘（或豆花自己的虚拟键盘）打的字送到手机屏没人收，系统输入队列卡在这个键上
  干等十来秒（期间光标、点击全排队，看起来豆花的输入全没反应），然后 am_anr "Application does not have a focused window"。
  修法：Session 接管成功时对第一块外接屏调 IActivityTaskManager.focusTopTask（shell 有 MANAGE_ACTIVITY_TASKS），只切一次；
  不让豆花窗口收键——收了字就留在手机上，进不了 DeX。`Server focus <屏>` 可单独验证。
- **同一种操作，反馈一样**（用户 2026-10-15 定的原则："用空鼠移动光标是移动，触摸屏上移动也是移动，反馈就该一样"）：
  移动光标 = 颗粒（设置开关；触控板手指每划 10dp 一颗，空中指针手机每转 1.5° 一颗——慢速时光标走的距离两边差不多）；
  拖动 / 按着左键 = 持续轻震、不发颗粒（触控板拖动、"左键"按钮、空中按住拖动、音量键按住左键都一样）；
  滚动 = 咔嗒（双指和边缘一样，原来的"只在边缘"三选一改成开关，旧值不是"关"就当开）；点击 = 点一下。
  加新的输入方式时照这张表给反馈，别另起一套。
- **贴边**：触摸由服务独占后，系统的边缘手势碰不到，不再给系统栏 / 刘海留边（只给贴字面板的输入法让位），
  窗口 layoutInDisplayCutoutMode = ALWAYS。
- **动作清单统一**（用户要求"能选的类型都通用"）：键盘快捷键并入 Actions，新增 Kind.HID（虚拟键盘按组合键）；
  编辑 / 浏览器 / 方向键这类改走 HID（和真键盘一样），返回 / 主屏 / 最近任务 / 媒体键键盘上没有，仍用注入。
  键盘最下面一排里 HID 动作当真键（按住连发），其它动作当特殊键（编号 600+位置）由 App 执行。
- **长按拖动**：单指按住不动 longPressMs（默认 450）→ 按下左键 + 震一下，之后移动就是拖动；动了、或第二根手指来了就取消；
  和"轻点后按住拖动"并存（轻点后的第二下不再等长按）。
- **调整键盘的吸附**：只作用于显示位置（raw 照常累计，继续拖就能拖过去），居中优先于贴边；拖中间先判断方向再锁轴。
- **双击要隔开发**：第一版把"松开第一下、按下第二下、松开第二下"同一瞬间发出，用户反馈双击不灵——
  Android 判断双击要求两次点击之间至少 40ms（DOUBLE_TAP_MIN_TIME）。现在隔 60ms 按第二下、按住 40ms 再松开。
- **旋转按钮走系统方向锁定**（`wm user-rotation -d <屏> lock <方向>`，服务执行）：用户的自动旋转是关的，
  平时靠导航栏的旋转建议按钮；接管触摸后点不到它。第一版用 setRequestedOrientation，真机上失败——外屏设了
  `ignoreOrientationRequest=true`，系统只把 App 画面转过来塞进竖屏，App 报的区域坐标和真实触摸脱节，服务还每秒重新接管。
  现在整块屏真的转。服务记下转之前的锁定，连接断开时恢复；App 记住选的方向，连上就重新转。
  第二版又踩了一个坑：以为 RawToDisplay 矩阵含旋转（测试样本也是按想象编的），真机上转屏后触点全错位。
  实测这个矩阵只到没转过的物理屏，现在由 ScreenRotation 补上旋转，公式和测试答案都取自真机的屏幕变换记录。
- **接上外接屏弹通知**：学三星（它的 SystemUI 发 TOUCHPAD_AVAILABLE 通知）。App 进程平时不在，所以由常驻的服务轮询
  DisplayManagerGlobal（TYPE_EXTERNAL / TYPE_WIFI 才算，scrcpy 虚拟屏不算），用 `am broadcast -f 0x20` 叫醒 App 发通知。
  通知要 POST_NOTIFICATIONS 运行时权限，MainActivity 第一次打开时申请（申请对话框会让豆花 onPause，触摸自动释放）。
- **轻点拖动**：轻点后先按下左键，等 `dragWindowMs`；期间再按下并移动 = 拖动，再轻点 = 双击，超时 = 松开。
- **服务卡住却显示在运行**（2026-10-19，主力机：服务进程在、LISTEN 行 rx_queue=2，App 一直"正在连接"或"连接 1 秒超时"）。
  根因：accept 和会话在同一个线程里串行，一个不结束的会话把后来的连接全堵在 accept 队列里（backlog 1）。会话为什么不结束
  没取证（没拿到线程堆栈）：推测是 App 的断开包被 Doze 掐了、App 进程随后没了，服务这边一直停在已连接、卡在读；
  另一条没上限的等待是收尾时恢复方向的 wm 子进程。App 端 Link.stop() 叫不醒卡在 connect / 读 hello 上的线程，每次 onStop/onStart
  漏一个线程、占一个排队位置，多半也是把队列塞满的帮凶。
  修法不依赖到底是哪种：主线程只 accept；旧会话沉默 ≥ 5 秒就被新连接顶掉、还在说话就回"忙"（不无条件顶替：两个窗口 / 自测工具
  会来回互顶，每次都要重新接管触摸屏、重建虚拟设备）；正在收尾的旧会话不回"忙"，新会话等它收完（App 回前台总是断开旧的马上连新的）；
  App 空闲 2 秒发 CMD_PING，服务 10 秒收不到任何消息就断开（不复用心跳：心跳决定独占，App 暂停时不发）；子进程全有超时；
  方向锁定全服务一份，被顶掉的会话不恢复（免得和新会话的锁定交错）；切键盘焦点挪到定时器线程（不在已独占、没进读循环时等 binder）。
  App 连接超时（回环上只可能是 accept 队列满）或 5 秒等不到 hello 就提示"服务没响应"（蓝牙模式也提示，接在蓝牙状态后面）；
  start-server.sh 看 /proc/net/tcp(6) 的 accept 队列（连着 3 次有排队 / 没在监听 = 卡住）自动重启，不主动连服务探测（探测连接会被当成真会话）。
  顺带扛住系统框架重启：IDisplayManager / IInputManager / IActivityTaskManager 的代理调用失败就丢掉、从 ServiceManager 重取
  （app_process 里 ServiceManager 不缓存，servicemanager 不跟着框架重启；Android 17 上没验证），日志经 LogThrottle 限频并展开原因。
  框架重启会杀掉 App、清掉"打开豆花"通知，所以 DisplayWatcher 出过错后第一次查成功时，接着的外接屏都重发一次 ADDED。
- **键盘的缝和空位分给最近的键**（2026-10-19，盲打纠偏第一步）：原来按键就是"按下时落在哪个矩形里"，键间 4dp 的缝、
  第二行两头的半格空位、外圈 2dp 边距都是死区。现在 KeyboardPanel 在键后面多报一块面板整块的范围（Proto.REGION_KEYBOARD = 4），
  分流命中它时交给 TouchRouter.nearestKey：候选是整个落在范围里的键 / 按钮（?123、Fn、🌐、快捷行动作也算），距离按到键的矩形算，
  先比竖向再比横向（先定行、再在行里找），一样近取列表里靠前的。等于软键盘常见的判定框铺法（按记忆的 AOSP LatinIME，没联网核对）；
  第二行空位整块归 a / l（纯欧氏距离会把空位靠外的大半分给 q / p）。改在 TouchRouter 而不是让 App 把键的矩形扩大了再报：
  规则只有一处、能离线测，上报的还是真实矩形（触点日志才知道按在键外多远）。被分过来的特殊键手指停在缝里松开也算点击
  （up 里 contains || hit 还是它）；down 的 default 改成什么都不做（以前不认识的类型当按钮）。协议因此必须 14：旧服务会把类型 4
  当按钮 id 0，按钮条 id 0 是"退出"。数字键盘这次没加范围；不加开关（确定性、默认开，前后差别用记下的触点重放算）。
  服务没接管、各键自己收触摸时（DeX 下服务连着但没接管），缝里的触摸冒泡到 KeyboardPanel.onTouchEvent，用同一个 nearestKey 兜，只管第一根手指。
  （这样多指会丢字、晚松开，2026-10-22 改成面板整块收触摸、按手指编号分，见"打字延迟"一条。）
- **打字测试的触点日志**（同一天）：TouchRouter.Trace 把落在键盘范围里的按下 / 抬起写成 L/K/p/u 行，App 的本地分流和服务的分流
  格式一模一样，都写 logcat（标签 douhua-keys；服务进程 app_process 里调 android.util.Log 这个项目以前没用过，没在真机上验证）。
  开关是 DebugMoves 的 DEBUG_KEYLOG 租期（10 秒，type-log.sh 每 3 秒续），App 连着服务时转发 CMD_TRACE = 11。
  电脑这边网页记按键用 e.code（物理键位，和 HID 用法码一一对应），自己画"已打的字"（不用输入框，免得电脑输入法插手）。
  分析时按手机实际判定的键重放编辑，再和目标对齐；被删掉的字按"打它之前的字对应到目标哪里"推想打的字。
  重放用 Java（KeyReplay 和真正的 TouchRouter 一起编译），Python 只做对齐和统计，免得两份规则悄悄对不上。学落点偏移这次没做。
- **空中鼠标页能调出键盘**（2026-10-19）：原来"键盘"在空中鼠标页上打开了却压在黑色页面下面，区域也只报页上的按钮。
  现在键盘开着就盖在最上面：页面 GONE（不调层次：键盘屏没背景，页面会从空隙透出来；没接管、调整键盘时可点的页面会先吃掉触摸），
  只报键盘那组区域。键盘开着 + 空中鼠标模式 = 暂停（MainActivity.airPaused，只看键盘可不可见）：照 onPause 先 hardKeys.reset()
  再 releaseAir()，停跟随、圆钮解锁；收起后不自动恢复，回到页面时本地分流收尾、解开键盘上锁住的修饰键（同进页面时）。
  暂停期间：音量键全部交还系统调音量（暂停前按下、还没松开的那一下照旧吞掉、松开当取消，HardKeys.isPressed）；
  按住移动 / 拖动不生效、不提示，回正中照常，"空中鼠标"动作变成收起键盘回到页面；页上按钮在暂停中报来的轻点当松开
  （分流按按下时记的区域报，键盘盖上前就按着的手指抬起仍报给圆钮 / 格子）。从键盘屏进空中鼠标先收起键盘，退出回触控板屏。
  激光笔式原来的前提"页面上没有触控板"破了：触摸那一路真挪过光标（dx / dy 不为 0）就作废记的位置（AirMouse.forgetPosition），
  下次跟随先回正中；只打字没碰小触控板就接着原来的位置。数字键盘在空中鼠标页上的同类问题没做。
- **DeX 模式的音量键由服务独占实体键设备**（2026-10-19；当时还截电源键短按，2026-10-21 撤了，见下一条）：DeX 下豆花窗口不收焦点、收不到音量键。
  服务挑设备（现在用 getevent，见下一条），EVIOCGRAB 整个设备一起独占，所以白名单很严（见不变量），
  先严后松：拿到主力机 `Server keys` 的真实分组再看要不要放宽。
  "点一下 / 按住"在服务里判断（HardKeyGate 里跑同一个 HardKeys），App 只执行动作（MSG_HARD_KEY）：补发要在服务里做、时机准，App 卡一下也不拖慢透传。
  语义和蓝牙模式一样：设了动作的键整个归豆花，没设的那一半不调音量（考虑过"没设的一半照常调音量"，要把点一下挪到松手才算，
  用户没要求改蓝牙模式的手感，没做）；同一设备上没配动作的键原样注入补发（downTime 用原始的，系统自己连发）。
  电源键（旁观的设备上）和截住的音量键一起按（截屏）→ 按原始时间补发吞掉的音量键按下，把设备交还系统（关掉 grabFd，plain 接着读给补发的按下配松开），
  都空闲 2 秒再独占。补发注入不指定屏（INVALID_DISPLAY），和物理键一样由系统送到有焦点的屏。
  独占前的竞态：EVIOCGRAB 返回时独占前的投递都已完成（synchronize_rcu），读完 plain 上剩下的，有键动过就马上放开重来；
  打开设备前就按着的键，独占后收到"没见过按下的松开"，系统多半看到了按下：音量补一对按下 + 松开（注入的松开止不住物理设备那一路的连发，
  要先用注入的按下接过来；最坏多调一格）。通话 / 响铃时（AudioManager 模式不是 NORMAL）App 发全 0，服务全部放开；振动 / 静音来电时音频模式不变，
  所以服务自己也每 400ms 问一次 Telecom 的 isRinging / isInCall（CallState），响铃或通话中放开，结束 2 秒后再截。
  蓝牙模式下服务什么实体键都不截（App 发全 0；音量键由 App 自己收：通话中交还系统调音量，响铃时系统自己先截去静音）。
  DeX 下实体键按住配了"左键"：CMD_HOLD_LEFT 经服务的 TouchRouter 按住（和屏幕上的"左键"共用计数）。
- **只要音量键，电源键不截；找设备改用 getevent**（2026-10-21，用户定；协议 15）：电源键短按配动作是之前多做的，整块撤掉
  （设置槽、Prefs 的 hk_power_tap——旧值不迁移、不读，状态机、补发、协议里的那个字节）。带电源键的设备任何情况下都不独占，只旁观认电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）。
  三星 One UI 上 shell 读 /proc/bus/input/devices 被 SELinux 拒绝（EACCES，用户主力机上 `Server keys` 实测），改跑系统自带的 `getevent -i`
  （shell 能跑、直接开 /dev/input/eventN；-i 列完设备就退出），格式照 AOSP getevent.c 的 printf 写（没在真机上对过）：类型名补到三个字符，
  SW / FF 和括号之间是两个空格（"SW  (0005)"），类型以括号里的十六进制为准。getevent 按类型调 EVIOCGBIT，内核只认 KEY、REL、ABS、MSC、
  LED、SND、FF、SW，所以 REP / PWR / FF_STATUS 看不见、不参与判断：开了内核自动连发的 gpio-keys 也会判成能截（读 /proc 时会因 EV_REP 被拒），
  独占后多收的 value=2 被 HardKeyGate 忽略、透传的键由系统自己连发，无害；真要拒得另读 sysfs 的 capabilities/ev，先要确认 shell 读得了。
  标准输出、标准错误分开读（Shell.runSplit），"could not open …"只从标准错误里认（getevent 一开头就 setbuf(stdout, NULL)，合着读其实也不会把行打乱；
  分开读是为了不靠这个前提）。解析宽容，但 events 段有认不出的行、缺 bus / vendor / events 的设备一律不截。
  打不开的设备只知道路径、不知道带不带电源键，所以一块能旁观的电源键设备都没有（getevent 打不开、带触摸）就什么都不截、报 HARD_SHARED
  （截了电源键 + 音量键的系统组合键（截屏、电源 + 音量上的快捷方式等）会失灵、单按电源直接灭屏；App 提示只说"截不住、原因见服务日志 / Server keys"）；KeyReader 里旁观的设备一块都打不开也一样不截（HARD_FAILED）。
  getevent 不在、超时、一个能打开的设备都没有 → HARD_FAILED（不影响触摸屏接管）。
- **豆花自己连本机 adbd 拉起服务，不依赖 Shizuku**（2026-10-22，用户："wifi 和那个开发者选项 usb 调试都要有足够的指示，
  然后确实要把必要的组件塞进来，不能依赖 shizuku"）。以前服务只能靠电脑 `tools/start-server.sh`，手机重启后要重跑；电脑这条路保留不变。
  ① 配对协议自己实现：SPAKE2 从 BoringSSL 移植成 Java（保留 BoringSSL 的 Apache-2.0 声明；LGPL 的 MuntashirAkon/spake2-java、GPL 的
  Flyfish233/spake2-java 都没抄、没用）；TLS 用平台 SSLContext("TLSv1.3") + 自己的 X509ExtendedKeyManager；导出密钥材料用公开的
  android.net.ssl.SSLSockets.exportKeyingMaterial（API 31+，minSdk 33 正好）；HKDF 手写、AES-GCM 用平台 Cipher、自签证书手写 DER。
  不带 .so、BouncyCastle、hiddenapibypass、隐藏 API。Shizuku 的 manager/adb（Apache-2.0）只对照了结构，没照抄代码。
  ② 连本机：NsdManager 找端口，只认本机地址，连 127.0.0.1；协议照 AOSP packages/modules/adb（CNXN → STLS → TLS 1.3 → OPEN "shell:…"）。
  ③ 配对码在前台服务的通知里输（RemoteInput）：系统的配对弹窗一离开就关，不能切回豆花输码。前台服务类型 shortService（配对 + 启动几分钟内做完，
  不用额外权限、不用写 specialUse 的用途声明；代价：约 3 分钟到点收尾）。
  ④ 连上后：`pm grant dev.douhua android.permission.WRITE_SECURE_SETTINGS`（之后 App 自己能开关无线调试；清单里必须申请这个权限，
  第一版漏了，pm grant 必然失败）→ 停旧服务 → 和 start-server.sh 一样起服务 → 等 hello。
  ⑤ 自动启动：设置里"自动启动服务"（配对成功后默认开）；打开豆花时连不上服务（没在跑 / 卡住 / 协议版本不一致）就（重）启动；
  连上服务时 hello 里报的 APK 路径（协议 16，服务的 CLASSPATH）和 App 自己的 sourceDir 不一样（App 更新过、服务还跑着旧代码，协议版本没变也算；
  每次安装 APK 都换目录）就重启一次，不管服务是谁起的：装包后电脑再起的服务（含 --debug）路径一样、不会被动，装包前就在跑的会被重启成不带 debug 的；
  同一对（旧路径、新路径）只试一次。开机后等 Wi-Fi（SelfStartJob，截止 10 分钟），失败静默记日志。
  ⑥ 无线调试是豆花自己打开的，服务起来后马上关；用户自己开的不动；没有 WRITE_SECURE_SETTINGS 时引导用户手动打开。打开后被系统关回去
  （这个 Wi-Fi 没被信任，系统弹"是否允许"）再等 30 秒给用户点"允许"，没等到报 RESULT_WIFI_UNTRUSTED，界面提示去选"始终允许"；
  之后再盯 3 分钟，用户这时才点"允许"就接着启动、用完照样关（见不变量）。
  **公共网络、退避、提醒关掉**（2026-10-22 用户定，"还有就是得提醒关掉吧"）：
  - 公共网络：系统问"是否允许在此网络上使用无线调试"时，"始终允许"只在自己家里 / 信得过的 Wi-Fi 上选，咖啡馆、公司、酒店别选。
    说明写在配对引导（svc_guide_3 + 引导下面一段琥珀色 svc_public_wifi）和检测到系统弹框时（RESULT_WIFI_UNTRUSTED 的设置页提示后面接同一段、
    res_wifi_untrusted 里一句），"手动打开"的说明（svc_wireless_manual）也加了一句。说的是：选了的网络上以后豆花拉起服务会把无线调试打开几秒，
    同网络的设备能看到它开着；连进来要配好的密钥或配对码，2026-05 修过一个能绕过验证的漏洞（CVE-2026-0073，编号和时间是用户给的，没自己查证）。
  - 退避（AutoStartBackoff）：自动的路径被系统拒过（弹了框、一直没允许）就不再自己打开无线调试（返回 RESULT_PAUSED，不记成"上次的结果"），
    直到手动启动成功一次。30 秒宽限期到时（结果 Wi-Fi 未信任）先记上退避，之后盯的 3 分钟里用户点了"允许"、接着的启动成功就解除
    （按手动算；原来沿用自动身份、不解除，同一件事按点得快慢结果不同，设置页还一直说"没被允许"）；3 分钟里没允许才一直退避。手动启动被拒也进退避（下次自动启动会弹同一个框）。无线调试本来开着（用户自己开的，或豆花开的、用户随后点了允许）
    不受限，照常走，但自动的成功不解除退避。豆花读不到 Wi-Fi 名称（不要定位权限），分不清网络，只能整体暂停；设置页在退避中（自动启动开着时）
    显示一段琥珀色说明。App 更新后的重启在退避中、无线调试关着时不记"试过"，退避解除后下次连上再试。
  - 提醒关掉：配对成功、服务起来时无线调试还开着（用户为配对自己开的）就在同一条配对通知里提醒 + "去关掉无线调试"按钮；设置页"无线调试"
    开着、不是豆花开的、没在配对 / 启动时琥珀色提醒 + 同样的按钮。通知按钮经设置页（EXTRA_OPEN_WIRELESS）转一下，复用 ServiceSection.open 的退路
    （开发者选项打不开就退到"关于手机"）：PendingIntent 自己没法试第二个。
  ⑦ 不改 adb_allowed_connection_time；密钥被拒（TLS 层报错，连两次、无线调试还开着）→ keyRejected → 提示重新配对（同一把密钥再配一次就行）。
  只是被断开（无线调试正好被系统 / 用户关掉、Wi-Fi 漫游，adbd 踢掉所有 TLS 连接）不算。
  ⑧ adb 私钥 AndroidKeyStore 的 AES-GCM 加密后存私有目录。⑨ 电脑那条路不变。⑩ 指示：设置页最上面"豆花儿服务"一节（见文件表），主界面状态字在服务没开 / 没响应时可点、跳过去。
  服务状态靠连一次服务端口读 hello 探测：服务对每条连接先建虚拟设备再发 hello，所以只在设置页打开、调试开关变了、启动前后探测，不轮询。
  已知长期风险：Google 2026-07 起在讨论让 adbd 只绑 wlan0（issuetracker 526109803，转述），真改了"App 连 127.0.0.1 上的 adbd"整类做法都会失效。
  **主力机实测（同一天晚上）**：配对、连 adbd、pm grant 第一次就都通了；起服务失败了三轮，最后定位：
  ① 用老的 "shell:" 协议执行起服务命令，服务进程连同外面套的 sh 刚起来就被整个杀掉（服务日志只有启动命令那一行、退出码都没写出来），
  加了 setsid、连接一直开着也一样；电脑上 adb shell 走 shell 协议 v2（"shell,v2,raw:"）同一条命令却好好的。
  改成 AdbConnection 在 adbd 声明 shell_v2 时走 v2（CNXN 里也声明 features=shell_v2,cmd，分包见 AdbConnection.ShellV2）后，21:10、21:38 两次都拉起来了。
  为什么老协议会被杀没深究（推测和 adbd 对老协议子进程的收尾有关），**SelfStart 必须走 v2**。
  ② 起服务命令的 sh 一退出豆花就断开连接时（断开前 17ms），adbd 拆连接把刚起来的服务带走：现在连接开到服务应答才断，命令里也加了 setsid（start-server.sh 同样加了）。
  ③ 三星上 logcat 几秒就被别的程序刷满，事后抓不到现场：SelfStart 的每一步另记到私有目录 files/selfstart-log.txt（调试包 `adb shell run-as dev.douhua cat files/selfstart-log.txt`），
  起服务命令在服务日志里先写"启动命令执行了"、退出时写退出码，外加进程组 / 会话 / cgroup、1 秒和 4 秒后还在不在（诊断，稳定后可以删）。
  探测服务（probeNow）每次连上服务都会建 / 拆一次虚拟设备，服务日志里一串"连接出错：Connection reset"就是它，无害（见技术债）。

- **蓝牙 HID**（用户要"任何设备通用的外接 HID"、"装上就能用"）：系统的 BluetoothHidDevice（Android 9 起），不需要服务、
  不需要电脑。为了不维护两份分流逻辑，先把 Session 里的分流和修饰键搬进纯 Java 的 TouchRouter，两种模式共用。
  蓝牙下 App 自己收触摸：dispatchTouchEvent 在按下时决定整次手势归本地分流还是归普通按钮（贴字面板、键盘调整模式下归按钮）；
  历史点逐帧喂，引擎看到的和服务读触摸屏一样细。系统的边缘返回手势还在：左右边缘设排除区（导航栏粘性隐藏时没有 200dp 限制），
  再吞掉返回，免得横划移光标时豆花被关。KEY 类动作改发 Consumer 媒体键（返回 0x224、主屏 0x223、播放、音量），
  最近任务 / 所有应用 / 通知栏没有通用用法码，提示不可用。贴字靠手机剪贴板，蓝牙下不可用。
  配对：对方配对时要能读到 HID 的 SDP 记录，所以豆花要开着（注册着）；以前配对过的要删掉重配。
- **蓝牙卡顿的根因：对方顺带连了手机的音频**（2026-10-14 主力机 → 无头 Linux 实测）。电脑配对时把自己当耳机，
  连上了手机的通话（HFP）和媒体音频（A2DP），链路按音频的深度省电走：sniff 子速率最长 0.75 秒，电脑上鼠标报文每 734ms
  才到一阵、每阵约 8 个（一秒约 11 个），手机每秒 120 个触摸帧各发一个 → 排队，停手后光标还要走很久。
  手机蓝牙日志里每发一个报文都申请切 active，但一直挂起切不过去。在手机蓝牙设置里关掉该设备的"通话"和"音频"后
  立刻切成 active，用户反馈正常（三星那一页随后显示"已断开"，是因为它只看音频；豆花的键鼠连接还在）。
  测试机索尼没连音频，同一台电脑上报文每 11–22ms 到一组。多数电脑（带音频接收的）配对后都会这样。
  App 没法只声明自己是键鼠：手机的设备类型（"手机"）和通话、媒体音频这些服务记录归系统蓝牙管，改要系统权限；
  而且手机自己的策略也会主动连：电脑连上索尼的媒体音频时，索尼当场自己把通话音频也连上了。
  App 也没权限替用户断开这两项，所以设置页里提醒（SettingsActivity.refreshAudioWarning）：连着的那台（没连上就看上次那台）
  连着手机的通话或媒体音频时，在设备列表下面显示一段琥珀色提示，点一下打开系统蓝牙设置；配对说明末尾也加了一句。
  手机端按设备记住"不连音频"，关一次以后都不连。
  量法：电脑上 `evtest`（不独占）看鼠标节点的到达间隔，手机 `dumpsys bluetooth_manager` 看 Power mode change / Sniff subrating。
- **蓝牙模式也由服务独占触摸屏（服务开着时）**（2026-10-15，用户："为什么蓝牙模式要和 DeX 不一致"）。
  第一版蓝牙模式按"不要服务、装上就能用"设计，App 自己收触摸，系统的下拉 / 上滑 / 侧滑都挡不住；服务开着时也只借它转屏。
  现在服务开着就照 DeX 接管，只是报文发回 App 由蓝牙发（relay），独占条件、心跳、释放完全复用；模式一变（relay 变了）重新接管，
  按着的键在原来那一路上松开。转发模式下不做 DexFocus。服务没开时退回 App 自己收触摸。协议版本 11。
- **蓝牙模式下的方向：先等服务**（2026-10-14）。原来一回前台就 setRequestedOrientation，等连上服务再让服务锁定整块屏；
  三星外屏忽略 App 的方向请求，系统先把豆花塞进竖屏中间的横框（窗口 [0,591]–[1248,1381]），80ms 后服务转屏，
  窗口和触摸恢复了全屏，画面却留在横框的偏移上，要再转一次屏才好。现在蓝牙模式下：连上服务就交给服务（并清掉自己的请求），
  这次回前台后一次都没连上服务才自己请求（MainActivity.requestOrientationSelf / linkSeen）；连上过又断的不请求。
- **三星上关开蓝牙后注册卡死**（2026-10-15 主力机日志）：豆花注册着时关开一次蓝牙，系统蓝牙再起来后"手机连外接键鼠"（HID Host）
  那部分没启动；三星注册蓝牙键盘前要先停掉它、等它停好（BTA_HH_REG_HD_STATE NOT_READY 后等 BTA_HH_DISABLE_EVT），永远等不到，
  界面一直"正在准备蓝牙"，谁来注册都一样，重启手机才好（dumpsys 里 HidHostService 的设备列表是空的就是这个状态）。
  耳机走音频那块，不经过这段交接，所以没事。App 抢不到先注销（三星一开始关蓝牙就先停键鼠服务，比 App 收到广播还早），
  只能：注册 5 秒没回话显示"重启手机"（BtHid.STATE_STUCK）；连接 12 秒没结果先断开再连（BtHid.onConnectTimeout，
  用户当时关开蓝牙就是因为换到电脑时卡在"连接中"）；代理整个启动期间只要一个（以前关开蓝牙后两个代理各注册一次，第二次被拒成"注册失败"）。
  索尼测试机上同样关开蓝牙能自己恢复。三项都没在三星上验证过。
- **打字延迟：转发报文不等重画、键盘少做重活、多指不丢字**（2026-10-22，用户：蓝牙模式下整屏键盘打快了不跟手）。
  先只读代码查了一遍（蓝牙转发、DeX、App 界面三条路，没实测）。服务端、nearestKey、触点日志、实体键线程都是微秒级或打字时不跑。
  嫌疑按大小：① 蓝牙链路深度省电（重新配对后电脑多半又连上了手机的通话 / 媒体音频，见"蓝牙卡顿的根因"；关掉就好，不用改代码，设置页有琥珀色提醒）；
  ② 主线程的同步屏障：每个键都 invalidate，下一帧画完之前普通 post 的消息不执行，服务转发回来的报文（蓝牙模式下由 App 发）最多晚一帧；
  ③ 锁 Shift / 换层时主线程的重活：每次修饰键变了对全部约 63 个键 setText（开着自动缩字号，要重新排版）+ 新建背景，换层拆掉重建所有键 view；
  ④ 每个键主线程上两次同步 binder（sendReport、震动），平时不排队。
  改了：Link 的回调全部经一个异步 Handler（Handler.createAsync）到主线程，不受屏障挡。全部而不是只有 MSG_KEYBOARD / MSG_MOUSE：
  异步消息会越过排在它前面、被屏障挡住的同步消息，两种混用时 MSG_GRAB 的 onGrab（接管时本地分流 releaseAll，可能发全零报文）
  可能落到服务转来的第一个报文后面；按钮（快捷行的滚轮动作）、修饰键和报文之间也会乱。全走一个 Handler，先后就和服务发的一样（onLinkStatus 也在里面）。
  KeyboardPanel：文字没变的键不 setText、高亮 / 按下的样子没变的键不重画（Cap.look 记着上次画的）；三层的中间四行各建一次缓存着，
  换层只切显示（GONE 的层不在当前键列表里，报的区域、nearestKey 的候选还是只有当前这一层；切换照样触发重新布局、pushLayout）；
  换快捷行 / 换主机只重建底行和快捷行。面板自己收触摸时先发键、再震动、再画按下的样子（本地分流那条本来就是先发报文）。
  顺带修了 caeb7b8 带进来的多指丢字：以前各键自己收触摸，第一根手指按在缝里由面板兜底后，同一手势里后来的手指全被吞、缝里的键等全部抬起才松；
  第一根按在键 A、第二根按在缝里时 A 收到"多指中的一根抬起"被忽略、一直按着。现在面板整块收触摸（onInterceptTouchEvent），
  按手指编号分（KeyFingers）：按下时包含的键优先、缝里交给 TouchRouter.nearestKey（规则本身没动），抬起只松自己那个，取消全部松开；
  范围外的手指（多指时别处的也可能派到面板）不算。
  没做：专门的蓝牙发送线程（BtHid 的字段、鼠标 / 空中鼠标都在主线程，挪线程要全部一起挪，等实测再说）；震动挪后台线程。
  震动能打断（同一天，用户："不是轻重的问题，是不能打断"）：按键的"嗒"走 Haptics.playKey，先 vibrator.cancel() 停掉还在震的上一下再震
  （"大"档是系统的重击、余震长，打得快时不停就拖在手指后面；安卓原版本来会顶掉旧的，三星没核对，自己停）。强度不动。
  要是三星的固件波形停不下来，再给按键换成随时能切断的短震（createOneShot），要用户上手比过才定。数字键盘也改成先发键再震再画。
  键盘工具栏（同一天，用户：贴着屏幕边、拿手机时容易误触）：高 48 → 36dp，平时两个按钮定宽 104dp 挤在中间，
  和贴着的那条屏幕边之间空 20dp 不响应（KeyboardScreen.edgeInset）；调整模式四个按钮照旧铺满一行。
  只跑了离线测试（KeyFingersTest）和编译，没在真机上试。要看：打快了跟不跟手（先确认电脑没连手机的通话 / 媒体音频）；锁 Shift、换层后键帽和高亮对、
  换层后放大提示和按下的样子不残留；DeX 下服务连着没接管时（或贴字面板开着、调整键盘时）两三根手指交替打、按在缝里，字不丢、键不粘；
  Ctrl + 双指缩放照常。量法：tools/type-log.sh 录一轮，电脑按下时间 − 手机 p 行时间（analyze.py 还没算这个）。

## 测试

- **测试设备**：用 USB 连的测试机（序列号见 `.private/NOTES.md`），命令里显式 `adb -s`。用户的主力机禁止拿来测试。
  测试机不是三星、没有 DeX，DeX 模式只能交给用户在主力机上试；`tools/build.sh --install`、`start-server.sh` 等脚本连着多台时会列出来让人选（tools/adb-device.sh），我自己跑时一律加 `-s <测试机>`。

- `tools/test.sh`：GestureEngine、MtDecoder、TouchDevice 解析、KeyFingers（键盘面板自己收触摸时的多指）的离线测试；服务的会话交接部件（TouchGrabLock、SessionPolicy、
  RotationLock、GrabCondition）、LogThrottle、Shell（会真起 echo / sleep 子进程）、实体键（KeyDevices 解析 getevent 输出 / 挑设备、HardKeyGate 状态机）也在 ServerPartsTest 里。
- 服务安全链路（真机）：`adb shell CLASSPATH=<apk 路径> app_process / dev.douhua.server.TestClient 0 2`
  ——接管屏幕 0 两秒、停发心跳，服务应在 3 秒内报"App 没有响应，已把触摸屏还给系统"。**测试期间外屏摸不动约 5 秒。**
  第三个参数写 `relay` 按蓝牙模式接管，服务转发回来的鼠标 / 键盘报文会打印出来；不用真人摸，可以在接管后往触摸屏节点
  `sendevent`（slot / 跟踪号 / X / Y / SYN，原始坐标），独占期间写进去的事件只送给服务。
- 实体键（真机，测试机上做；服务找不到触摸屏时实体键照样截）：先 `adb shell CLASSPATH=<apk 路径> app_process / dev.douhua.server.Server keys`
  看设备分组（只读，用 getevent -i）；再 `TestClient 0 5 keys=1000`（只截音量+点一下）：日志"已独占实体键"，按音量+收到"实体键 0 事件 0"、音量不变，
  同设备上的音量−照常调音量；电源键 + 音量下截屏照常；停发心跳后服务日志"实体键已交还系统"、TestClient 收到"截住=0 原因=5"。
  紧急求救绝不实测；三星"侧键 + 音量下"强制重启也不能在主力机上试。
- 服务卡住 / 顶替（真机，**只在测试机上做**；主力机上禁止 kill -STOP 服务，那会让外屏触摸屏锁死）：
  ① 起服务后用 TestClient 连上再 `kill -STOP` 它（模拟沉默的死连接），然后开 App 或再起一个 TestClient：约 5 秒内日志"旧连接 N 秒没消息，
  被新连接顶掉"；不再连的话 10 秒后"App 10 秒没有任何消息……断开"。② `kill -STOP douhua_server` 后开 App：5 秒内状态栏"服务没响应"；
  这时跑 start-server.sh 应判定卡住并重启（之后 `kill -CONT` 收尾）。③ `adb shell am restart` 模拟框架重启（会把测试机界面整个重启，先问用户；
  shell 有没有这个权限没验证）：日志"查屏幕列表出错"一次，然后"恢复了"，注入按键、DexFocus 照常。测试机上服务找不到触摸屏、不会独占，所以安全。
- 界面（不碰真屏）：scrcpy 开私有虚拟屏，把豆花启动上去录屏。虚拟屏没有触摸屏，服务会拒绝接管，所以只能看布局：
  ```bash
  scrcpy --new-display=1248x1972/420 --no-window --no-audio --no-control --record=ui.mkv --time-limit=9 &
  adb shell am start --display <新虚拟屏 id> -n dev.douhua/.MainActivity
  ```
- 回正中 / 加速实测的工具：DebugMoves（见文件表）发位移；测试机触摸屏是 `/dev/input/event4`（原始坐标 = 屏幕 × 1.5）；
  `adb shell input tap` 每次约 1 秒才生效，量反应时间的场合不能用它模拟人点。
- 光标轨迹（量回正中、校准、加速）：主机是 KDE Wayland 时，可以临时加载一个 KWin 脚本，在 `workspace.cursorPosChanged` 里
  `console.info` 坐标（`qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.loadScript <文件> <名字>`，再对返回的
  `/Scripting/Script<N>` 调 `run`；`journalctl --user -f` 里看 `js:` 行；用完 `unloadScript <名字>`）。会动用户的光标，先问；
  用户自己在动鼠标时数据会乱（轨迹里出现斜着走的段就是）。
- 触摸手感、按钮、三指四指、震动只能真人在真机上试；服务用 `tools/start-server.sh --debug` 起，日志里有每秒的鼠标包统计。
- 打字测试（盲打纠偏的数据）：手机装调试包、服务重启成同一版，`tools/type-log.sh`（蓝牙连这台电脑；DeX 下加 `--dex`），
  在打开的网页里看着手机打 30 句（英文小写、拼音各半）；完了 `tools/type-analyze.sh` 出误按率、每个键的落点偏移、
  缝里的触点新规则分对 / 分错几个。`tools/test.sh` 里的 `analyze.py --selftest` + `KeyReplay check` 只验证分析脚本本身。

- 手机自己拉起服务（SelfStart）的离线部分在 `tools/test.sh`：AdbCryptoTest（SPAKE2 / HKDF / GCM，向量取自 BoringSSL）、
  AdbProtocolTest（消息帧逐字节、adb 公钥格式对照本机 `adb keygen` 生成的公钥（只放公钥）、自签证书用 JDK 解析验签、
  配对 / 连接客户端对本进程里的测试服务端，含配对码错、密钥被拒的报错分类）、ServerCommandsTest（命令和两个脚本对得上、sh -n 查语法、
  清单申请了 WRITE_SECURE_SETTINGS、迁移规则不带 adb 密钥）、AutoStartBackoffTest（退避的进入 / 解除，按经过串起来 + 全组合）。配对服务端的顺序照 AOSP DoExchangePeerInfo：先发自己的 PeerInfo 再读对方的，
  所以真 adbd 上配对码错时客户端是"解不开对方的 PeerInfo"（AEADBadTagException），读到 EOF 只是兜底；两条都测。
  假 adbd 拒绝密钥是在 TLS 层发致命警报（和 adbd 的校验回调一样），握手后直接断开（踢掉连接）的另测、不算被拒。
  端到端（不进仓库，scratchpad 的 selfstart/e2e/）：用 BoringSSL（libssl）照 AOSP adbd 写了配对服务端（PeerInfo 顺序同 AOSP）和连接端口，
  Java 客户端（JDK 25 的真导出密钥材料）去连：配对成功 / 配对码错（走解密失败那条）/ 连接 + shell / 没配对的密钥被拒（TLS 警报）都符合预期。没用真 adb 客户端测（`adb pair` 走本机正在用的 adb server、会写 ~/.android），
  也没在真手机上跑过。真机上要看的见"进行中"。

## 进行中（交接；做完删掉这一节）

- **豆花自己拉起服务（2026-10-22 写完，没提交，待真机验证；协议 16：hello 末尾带服务的 APK 路径，装完要重启服务）**：见决策记录。只跑了离线测试（密码学 187 项、协议 61 项、命令 14 项）、
  BoringSSL 端到端和编译。真机上要看：① 配对：设置页"开始配对"→ 系统里"使用配对码配对设备"→ 通知横幅 / 下拉里输码（三星横幅能不能直接回复、
  弹窗会不会因为下拉通知关掉）；配对码错能重输；② 启动：无线调试关着时豆花自己打开、起服务、马上关（日志标签 douhua 里"打开了无线调试""关掉了…"）、
  关掉后服务还在；第一次会 pm grant WRITE_SECURE_SETTINGS；没信任的 Wi-Fi 上系统弹"是否允许"→ 选"始终允许"后能接着起来；
  ③ 自动启动：打开豆花时服务没在跑自动起；装新包后打开豆花会重启一次装包前就在跑的服务（电脑装包后再起的不动）；开机连上 Wi-Fi 后自动起（三星开机广播、JobScheduler 的 Wi-Fi 约束）；
  ④ 各项指示：开 / 关 Wi-Fi、开发者选项、USB 调试、无线调试时设置页实时变，按钮能跳到对的系统页面（三星认不认 `:settings:fragment_args_key` 的高亮）；
  ⑤ 7 天不用后提示重新配对（Conscrypt 下 adbd 拒绝时客户端是不是报 SSLException，只用 JDK 客户端对 BoringSSL 核对过）；
  配对码输错后放弃、设置页不显示"已配对"；没信任的 Wi-Fi 上放弃后 3 分钟内点"允许"能接着起来、用完关；在最近任务里划掉豆花后无线调试被关回去（下次打开时）；关掉 USB 调试 / 开发者选项后服务确实停掉（文案是这么写的）；⑥ 探测服务时每次都建一次虚拟设备，DeX 上会不会闪一下光标。
  ⑦ 公共网络 / 退避 / 提醒（2026-10-22 补，只跑了离线测试（退避 26 项）和编译）：在没信任的 Wi-Fi 上自动启动弹框后点取消 → 设置页出现"自动启动暂停了"、
  之后打开豆花不再弹框（日志"自动启动服务暂停中"）、手动点"启动服务"成功后那段消失；退避中自己打开无线调试再打开豆花，自动启动照常起来；
  配对成功的通知里有"无线调试还开着"和"去关掉无线调试"按钮，点了能到无线调试那一页（经设置页转一下，看会不会闪一下设置页、三星认不认高亮）；
  无线调试已经关着时通知里不提；设置页无线调试是自己开的时显示琥珀色提醒 + 按钮，豆花临时开的那几秒不显示。

- **DeX 模式的音量键（2026-10-19 写完，2026-10-21 去掉电源键、改用 getevent；没提交，待真机验证；协议 15，装完要重启服务）**：见决策记录。
  只跑了离线测试（服务端部件 216 项：getevent 格式解析（样本照 getevent.c 的 printf 拼，不是真机输出）/ 白名单 / 旁观 / 看不到电源键不截 / 原因混合、HardKeyGate 透传 / 组合键（含补发排序）/ 冷却 / releaseAll / 竞态）和编译。
  待真机：**先跑只读的 `Server keys`**（getevent 在 One UI 上能不能打开 /dev/input/eventN、主力机的设备分组、音量下是不是和电源键在一起、
  Fold 合盖霍尔是不是和音量键分开）；再看 EVIOCGRAB 对音量键设备行不行、截屏组合键、通话时放开（含振动模式来电：服务查 Telecom 的反射在 One UI 上通不通，
  日志里有"来电响铃或通话中：实体键还给系统"）、DeX 下音量键按住"左键"拖动、App 端的提示文字。

- **键盘的缝分给最近的键 + 打字测试工具（2026-10-19 写完，没提交，待真机验证；协议 15（随 DeX 音量键一起升到 15），装完要重启服务）**：见决策记录。
  只跑了离线测试（分流 87 项、打字分析自测 30 项、重放核对）和编译。没验证：服务进程写 logcat、DeX 下各键自己收触摸那条路的兜底、
  缝里分到 ?123 / 快捷行动作时点击生效、tools/type-log.sh 和网页整套流程（一次都没跑过）。

- **空中鼠标页调出键盘（2026-10-19 写完，没提交，待主力机验证；协议不变，只改 App）**：见决策记录。
  只跑了离线测试（分流 95 项、空中鼠标 65 项）和编译。要在主力机上看：页上点"键盘"（格子 / 音量键）键盘盖上来、能打字、
  小触控板能挪光标；服务接管（relay）和没开服务两条路都试；键盘上锁住 Ctrl 后"收起键盘"，回到页面点击不带 Ctrl；
  暂停中音量键调音量、调出键盘的那一下松开时音量不变；调整键盘大小能拖；用小触控板挪过光标后再跟随先回正中，没挪过接着走。

- **服务卡住修复（2026-10-19 写完，没提交，待真机验证；协议 15（随 DeX 音量键一起升到 15），装完要重启服务）**：见决策记录"服务卡住却显示在运行"。
  只跑了离线测试和编译；测试机上的验证步骤见"测试"一节。主力机上那个卡住的旧服务（v13）被新 start-server.sh 认出来的前提是有连接在排队，
  部署时用 build.sh --install / deploy-phone.sh（先 stop 再 start）或 `start-server.sh --restart`。

- **版本号 0.2.0**（2026-10-15 用户定，单独一个提交）：包括 2026-10-14 蓝牙 HID + TouchRouter 重构 + DexFocus 等、
  2026-10-15 蓝牙模式由服务独占触摸屏两次提交。正式包（tools/package.sh）还没打，打包、打标签、推送都由人来。
- **主力机上装的就是最新提交的内容**（`.private/deploy-phone.sh` 装整个工作区，最近一次 2026-10-15 00:06）。装之前那个不带蓝牙的包
  备份在 `.private/main-phone-before-bt.apk`，`bash .private/deploy-phone.sh --rollback` 装回。旧的按段打补丁脚本
  `.private/deploy-phone-nobt.sh` 已经用不上，可以删。
  **DeX 模式在 TouchRouter 重构后还没人完整试过**（只看到服务能接管、点按钮能认），用户还没在 DeX 下用过这一版。
- **蓝牙已验证**：Linux 电脑上用户实测各种操作都对（关掉电脑连着的通话 / 媒体音频之后）；测试机上用 evtest 核对过报文
  （滑动、轻点、字母、Shift 锁定、自带 Ctrl 的"复制"、⌫ ↵、边缘滚轮、返回）。量法：电脑上 `sudo setfacl -m u:$USER:r /dev/input/eventN`
  后 `evtest`（不独占）；独占前按名字和 uniq（手机蓝牙地址）核对节点，编号会变，手机断开再连节点会重建、权限要重给。
- **iPad 正常了**（2026-10-15）：光标、点击、滚轮、键盘都对。之前"只认光标移动"是 iPadOS 自己的毛病（当时别的鼠标也点不了），
  不是豆花的问题；iPad 连上时只发过"切到 report 协议"，没切 boot。排查时把描述符改成鼠标排第一，保留（Hid.combinedDescriptor）。
- **GUI 键改名（主力机上验证过：连 Linux 显示 Super、连 iPad 显示 ⌘）**：功能行原来写 Win，改成 Super；蓝牙连着苹果设备时显示 ⌘（BtHid.isApple：
  主机的服务列表里有苹果专用标识 00000000-deca-fade-deca-deafdecacafe，iPad 实测有）。自定义组合键起名、"显示桌面（Super+D）"
  也跟着改；已经存下的组合键名字不变。只改显示，发出去的键不变。
- **地球仪键 🌐（iPad 上验证过能切换输入法）**：键盘最下面一排"符"后面加 🌐（空格从 5 份缩到 4 份），动作清单加
  lang_switch（按钮条 / 手势 / 快捷行都能选）。蓝牙发 Consumer 0x029D（AC Next Keyboard Layout Select），DeX 注入
  KEYCODE_LANGUAGE_SWITCH。苹果自家键盘的 🌐 是私有编码，做不了一样的；0x029D 在 iPadOS 上能切输入法（用户实测）。
  Linux 上用户实测不管用（桌面默认没绑这个键），所以键盘上的 🌐 只在连着苹果设备时出现（KeyboardPanel.BOTTOM_APPLE），
  键帽用 ColorMatrix 去色调暗（彩色 emoji 太扎眼）。动作清单里的"切换输入法"保留。
- **第九批（2026-10-15，没提交，待验证）**：用户说"还是抖、快了卡顿、不够走直线"。
  ① 抖：1€ 的速度原来先取大小再平均，手抖本身把截止频率抬到 5–8Hz，等于没滤；改成先把带方向的角速度低通再取大小
  （1€ 原文的做法），离线算持续手抖 10Hz 剩 32% → 1%、6Hz 45% → 4%，起步延迟不变。
  ② 走直线：玩家空间（双手手柄用的）换成侧倾补偿（单手空中鼠标通行做法，LG Magic Remote / 罗技 MX Air 用的 Hillcrest Freespace）：
  手机歪着拿水平扫也走水平线，抬着拿时拧手腕不再左右跑。代价：手机侧倾时绕手机自己的右轴点头会斜着走（按"手在空中画"设计）。
  ③ 快了卡顿：原因没定，没改。可能是重新配对后电脑又连上了手机的通话 / 媒体音频（链路深度省电，报文一阵一阵到），
  也可能是发送节奏（激光笔式约 15ms 一帧）和蓝牙 / 屏幕刷新对不齐，要看主力机日志里"报文没发出去"有没有刷屏再定。
  用户试了说轨迹更接近直觉、但还是很抖，要求把参数放进设置自己调：设置 → 空中鼠标里加了四个滑块（静止截止频率 0.1–5Hz、β 0–5、
  判断"在动"的截止频率 0.2–5Hz、软死区 0–15°/s，键名见 Prefs.AIR_*）和"恢复默认"，回到主界面生效（MainActivity.applyPrefs → AirMouse.loadFilter）。
  用户接着说：问题主要是跟手 + 轨迹，要在侧倾补偿基础上"再加入平滑，把轨迹平滑了"。加了轨迹平滑（AirPointer.pathSmoothMs，默认 150ms）：
  移动拆成沿走向 + 横向，沿走向照常跟手，横向单独低通（走向按 3 倍时间常数估），离线算横向晃剩 41%；原路掉头不拖、拐角变圆。
  先试过"拉绳"（画图软件防抖笔刷），只拉得平比绳长短的小弯，快动时没用（只少 1/16），留作"防抖半径"（默认 0 = 关）。两个都进了设置。
  用户自己调出"相对好用"的一组：截止 0.6Hz、β 1.0、判断在动 1.2Hz、死区 3°/s、轨迹平滑 180ms、防抖半径 0.65°，已设成默认值
  （我原来估的都偏弱，尤其以为拉绳没用）。用户说"凑合能用，不到真顺手"；光标"卡卡的"用户猜是绝对坐标太离散、要插值。
  空中鼠标页里按住左键（屏幕"左键"、音量键按住左键）0.2 秒后自动跟手 = 拖动，松开停（MainActivity.leftFollow）；快点一下只是单击。
  第一版把用户的"按住左键时自动解锁移动"理解反了，做成按左键就停掉锁定的移动，结果按着左键拖不动（用户 2026-10-16 指出）。
  下一步：用户用 tools/air-log.sh 画一轮横竖线 / 圆 / 方 / 三角 + 静止 5 秒，第二轮发送间隔改 0（每帧都发）对比卡顿，我再看日志定改法。
  **双日志结论（2026-10-15 20:50 那次，主力机 → KDE 电脑，灵敏度 6，1° ≈ 37.5 逻辑像素）**：
  ① "卡卡的"：电脑按 11.25ms 一格收报文，持续最多约 257 帧 / 秒；激光笔式原来约 13ms 发一帧，对不上格子（隔 11 / 22ms 交替到、7% 挤一起）；
  每帧都发（4.3ms）超运力，排队延迟到 260ms。激光笔式改成至少隔 8ms（AirMouse.ABS_SEND_GAP_MS），相对位移仍 12ms（挤一起会被对方当加速）。
  ② 轨迹平滑第一版有 bug：走向按带方向的速度估，来回画线掉头时估计穿过零点乱转，线上反而多出小折（重放：只开它时横线 5Hz 以上的晃 0.9 → 2.3px）；
  改成只认轴不认正反（倍角表示）后横线 2Hz 以上的晃 6.6 → 5.6px、沿线不多落后。
  ③ 运动中 5Hz 以上的手抖本来就不到 1px；看得见的"弯弯绕绕"是手自己 2–5Hz 的晃（约 6px），只能拿延迟换。用户手调那组重消抖主要在补①的卡，多落后几十毫秒。
  按 6480 组重放挑的默认值：截止 1.8Hz、β 2.5、判断在动 1.2Hz、死区 3°/s、轨迹平滑 250ms、防抖半径 0.25°——每种图形都比手调那组晃得少、落后少。
  这些都是代理指标（没有标准答案的路线）；用户提议的"对着屏幕上的固定图案描红"能给标准答案：已做成 `tools/air-log.sh --trace`
  （三轮盲测，每轮横线、竖线、圆、方、三角各描一次，结果 trace.json + plan.json；三组写在 trace_server.py 的 CONFIGS，可带发送间隔。
  第一次是新默认 / 手调 / 新默认去掉轨迹平滑；2026-10-16 换成"侧倾补偿那一版 12ms / 同参数 8ms / 上次最好的 8ms"）。
  交互照画图软件：按住左键开始一笔、松开结束，松开后显示这一笔平均 / 最远偏多少、3 秒后自动下一个（可"重画"），顶上一直有进度和状态
  （等你开始 / 记录中 / 已记下），每轮之间和结束都有提示页，终端里逐个打印描完的图形。第一版用"覆盖 85% 且停 1 秒"自动判完成、
  起点画成绿球，用户既看不出开始没开始、也不知道什么时候算完，还以为要拖那个球（2026-10-16）；
  **描红结果（2026-10-16 00:08，.private/airlog/20261016-000830，有 traces.png）**：用户"都不顺手"、没选出最好的一轮。
  客观上新默认最好：平均偏 17px（手调 24、去掉轨迹平滑 23），最远 27–52px（手调到 110）。三轮都有 1–2Hz、10–50px 的慢波浪
  （有延迟时人修正过头），竖线最差、三轮都往左鼓。确定的 bug：这台电脑屏幕 1126×776（≈3:2），按 16:9 猜，上下比左右灵敏 23%
  （圆描成宽高比 0.86–0.89）——设置里加了按主机记的"屏幕比例"（Prefs.hostAspect，MainActivity.updateAirAbs）。
  8ms 发送后电脑收到的间隔 p90 22 → 17ms。下一步候选：降灵敏度（还是激光笔）、慢速精细倍率（等于加速，用户以前说过不要奇怪的加速）。
  **用户：最得劲儿的是刚加侧倾补偿那一版，之后全是波浪、不跟手、如履薄冰（2026-10-16）**。默认值退回那一版：截止 1.0Hz、β 1.5、
  判断在动 2.0Hz、死区 3°/s、轨迹平滑 0、防抖半径 0（滑块都留着）。教训：开环重放的平滑 / 延迟指标看不出闭环修正的手感——
  轨迹平滑延后横向修正、防抖半径让反向修正有空程，人一修就修过头，描红里 1–2Hz 的波浪就是这个。手机上存过的滑块值要点"恢复默认"才换。
  屏幕分辨率（Sunshine 日志 + ~/.local/state/imeverywhere/imeverywhere.log）：显示器平时 3440×1440 缩放 2；用户用 Moonlight 串流时
  "桌面·自适应"按客户端切成 2816×1940 缩放 2.5（逻辑 1126×776），描红就是在这个模式下做的。比例跟着串流的设备变，按主机记的"屏幕比例"只能当手动备用。
  **竖线全鼓包的原因（用户："不只是参数，算法有问题"）**：描红时手机仰 40–60°、歪 6–17°。侧倾补偿按仰角在顶边 / 背面两种指向间切换（44° / 53°），
  往下描竖线正好穿过切换区；而且它把上下定义成绕水平轴转，手腕点头绕的却是手机自己的右轴，歪 8° 仰 50° 时每往下 1° 往旁边串 0.22°。
  改成斜基分解（AirPointer 类注释），连续、不切换；离线测试覆盖了歪 30°、仰 50° 歪 8° 下的水平扫 / 点头 / 转小臂。调试日志 g 行多记了手机坐标系的陀螺仪和重力，以后能离线重算方向。
  用户不认 Moonlight 延迟的说法（全本地），要我关注"怎么用手机初始状态修正轨迹、怎么让我想怎么操控就怎么操控"，觉得肯定有开源算法。
  查到的开源参考：JoyShockMapper / GamepadMotionHelpers（Jibb Smart）的 Local / Player / World 空间；Android 开源空中鼠标（SmartMouse、AirMouse3D、
  KDE Connect）只用本地轴 + 死区。AirPointer 现在三种换算可选（Config.space：0 斜基默认、1 玩家空间、2 世界空间），调试广播 --ei space 切；
  描红三轮改成同一组消抖参数、只换方向换算，手机日志 g 行带原始陀螺仪 + 重力。下一步：按描红的目标方向拟合用户横着 / 竖着画时手机实际绕哪根轴转，定一套个人换算。
  **换算对比结果（2026-10-16 00:38，.private/airlog/20261016-003841）**：描红平均偏 玩家空间 13.8px、世界 19.2、斜基 21.2（竖线 9 / 30 / 18.5），
  用户还是"说不上来"。但三次描红有两次第三轮最好（越描越熟），每种只 5 笔。按原始陀螺仪核对（tools/airlog/axes.py）：四种换算方向误差中位 11–14°、
  个人拟合 10.8°，斜基平均偏 0°、玩家 +5°、世界 −4.6°——换算不是瓶颈，主要误差是手本身方向在晃（RMS 约 25°）。
  另：跟随时 19% 的时间转速 < 3°/s，软死区在这里只给 60–70% 的反应，细调时"不听话"、一加力就冲过头，可能是"如履薄冰"的一部分（建议用户把死区调到 1 试）。
  默认换算改成玩家空间（描红最好、也是开源手柄陀螺仪的常用默认），设置里加了"方向换算"三选一（Prefs.AIR_SPACE），恢复默认会一起恢复。
  用户接着要"白板"数据集：不描红，用现在的设置随便画竖线 / 横线 / 斜线（会不松手来回折返）、描圆和三角，画到满意，再据此做轨迹修正。
  做成 `tools/air-log.sh --board`（tools/airlog/board.html，结果 board.json：每笔类别 + 页面坐标点；手机日志带原始陀螺仪 + 重力）。分析时线类在折返点切段、每段以起止点连线为本意。
  **白板结果（2026-10-16 00:57，.private/airlog/20261016-005715，89 笔 / 521 段）**：手机陀螺仪没记上——调试开关在豆花内存里，脚本发"开"之后约 14 秒豆花才（重新）启动，
  开关丢了。已改：air-log.sh 记录期间每 3 秒补发"开"，DebugMoves 只改带上的项（要重新装包，否则补发会把描红每轮的间隔 / 换算冲回默认）。
  页面数据的结论：用户画得又大又快（一段 800–1300px、约 2000–2800px/s）。斜线都往下塌 50–100px（"/" 往右下、"" 往左下），
  往上画时竖向比横向晚约 24ms 才动、往下画时早约 15ms，按这个速度正好塌这么多；竖线来回画成细长的圈（约 10px）、横线 S 形（约 10px）。
  算法对上下是对称的，所以更像手本身（抬手对抗重力、放手借重力）；要原始陀螺仪才能坐实。排除掉的：现在的换算会把墙上的直线算弯（模拟只弯 3px）；
  屏幕位置扭曲修正（验证集只直了 13%、角上要挪 300px，不用）。描红那次的原始陀螺仪看出：横画是绕竖直轴转，竖画是点头 + 约 35° 的转小臂，玩家空间正好抵消转小臂（竖线 9px vs 世界空间 30px）。
  **重录的白板（2026-10-16 01:22，.private/airlog/20261016-012214，陀螺仪 12 万帧 + 内核报文齐全）**：按发出 / 收到的坐标值配对对齐（毫秒级）后，
  页面光标 = 内核收到的坐标（差 0px）；手（消抖前）和算法输出（消抖后）形状几乎一样——消抖不弯线。用户提醒"排除边缘值、画到边上会沿着边跑偏"：
  去掉碰边的部分（tools/airlog/interior.py），竖 / 横 / 斜线偏离直线都只有约 3%（RMS 约 2%），几种换算差零点几个百分点。之前斜线 50–140px 的大拱
  全是撞边造成的：一段 1200–1300px，屏幕只有 776 高。原来激光笔式在边上截住位置，还会让手和光标越撞越错位。
  （试过让 AirMouse 另记手指向的位置、出界时光标停在出界点等手转回来，用户："贴边卡住了，像光标扎进去了，哪有鼠标是这样的"——
  他说"沿着边缘动一下"是提醒分析时剔除这段，不是要改 App。已撤回普通鼠标的行为：撞边截在边上、往回马上动、斜着撞边贴边滑。）
  **我又错了一次**：拿"每段偏离自己两端连线"当指标，把整条线歪了几度这种系统性偏差抹掉了，就说"不需要修正"，用户骂得对。按本意重算（tools/airlog/system.py、tilt.py）：
  竖线平均歪 +8.9°、横线 +6.7°（屏幕上），圆一圈一圈拟合是 1.4:1 的椭圆。同一批原始陀螺仪重算：世界空间下竖线每笔歪的角度散布 7°（玩家空间 14°），
  按手转的角度剩 −5.8° / −2.0° 的平均歪斜，用一半笔学的 2×2 校正在另一半笔上消到 0°（散布约 8°，是随机的）。
  圆：顺时针快圈（约 0.5s）椭圆朝右上 45°、逆时针慢圈（约 0.9s）竖着——一部分是快速转圈时上下比左右慢约 30ms（动态，补了会更拖，没补），一部分是静态竖向拉长。
  改了：默认世界空间；角度校正（AirPointer.fixVDeg / fixHDeg，按换算分开的默认 defaultFix，设置里两个滑块）；屏幕比例可按分辨率填（宽 × 高，Prefs.hostAspectWh）。
  **以后分析：先看相对本意（竖 = 90°、横 = 0°、圆 = 正圆）的系统性偏差，再看随机晃动；碰边的部分要先剔除。**
  描红结果的分析脚本还没写（离图案多远、描一个多久、和用户选的哪轮对照）。注意描红是闭环的（人看着光标修正），不能拿描红数据重放别的参数，只能 A/B 比。
  参数都按手转的角度算，和灵敏度无关；激光笔式下对方的鼠标速度 / 加速、屏幕分辨率都不起作用（一屏宽固定对应多少度）；页面外的相对位移会受对方鼠标设置影响，没测。
- **第八批（2026-10-15 写完，没提交，待验证；部署后 iPad 和电脑都要删掉手机重新配对一次）**：
  ① iPad 上回正中好了、点击又不灵：绝对坐标指针里带着按键，iPad 多半拿它当主指针看按键。改成只有 X / Y、不带按键
  （Hid.ABS_POINTER_DESC）；同样的坐标连发时差 1 再发（防内核吞掉重复值）。没在 iPad 上验证。
  **坑**：一开始连按键字节一起删了（报文 4 字节），没重新配对的 Linux 按旧描述符错一位读：回正中跑左上角、移动贴着顶边乱飞、
  低字节被当按键。现在第一个字节留作占位（报文 5 字节，排布和旧版一样），照发当前按键，新旧配对都读得对。
  **以后改描述符：已有报告的字节排布不能动**，只能在占位 / 新报告 ID 上做文章。
  ② 用户说走直线弯弯绕绕、Linux 上不跟手、加速怪：空中鼠标页里改成"激光笔式"——豆花自己记光标位置、发绝对坐标，
  对方的加速和蓝牙报文挤在一起都不起作用（进页面后第一次移动先跳到正中；屏幕宽高比先按苹果 4:3、别的 16:9 估）。
  页面外（按钮条 / 音量键的按住移动）仍发相对位移，但攒着、至少隔 12ms 才发（蓝牙约 11ms 送一次，发得更密就挤在一起到）。
  消抖 β 1 → 1.5（起步 60 → 50ms）。都没在真机上试手感。
- **第七批：回正中只用绝对坐标（2026-10-15 写完，没提交，待验证）**。用户："现在目标就是绝对座标，不要别的，别什么实验功能了"。
  校准整套删掉（CalibrateActivity、CenterPlan、每台主机的校准存储），两个开关也删掉：描述符里一直带绝对坐标指针，
  回正中 = 一帧绝对坐标到正中（交替 16384 / 16383，见 BtHid.sendAbsolute）。空中鼠标页的按钮就叫"回正中"，点一下回正中；
  设置里只剩一行说明"以前配对过的设备要删掉手机重新配对一次"。KDE 上实测能用；**iPad 认不认没验证**（之前用户在 iPad 上见过
  "飞来飞去"，不清楚是不是绝对坐标引起的），不认的主机回正中没反应。下面第三到第六批里的校准、跳当尺子、慢慢找边都已经撤了，只留作记录。
- **第六批（2026-10-15 写完，没提交，待主力机验证；用户在 iPad 上试的）**：
  ① 校准里豆花一下都不自己动（用户原话"你不要自动移动到边界……你移动的不准"）：上一版每按一下方向键都"从角上重新跳"，
  iPad 上光标飞来飞去、也不准。现在人用 ◀ ▶ 把光标挪到出发的边、点"从这里开始"，再挪到对边、点"到边了"，豆花只数中间的步子
  （16 计数 / 20ms，按住连续走、点一下一步）；回正中 = 推进角 + 同样的步子走一半（CenterPlan SCHEME 6，跳的那套撤了）。
  对边那头被挡住后多按的步子看不出来、会算进去，提示里写了"快到时一下一下点"。
  用户试了说方向键太粗（点一下就是整步 16 计数，iPad 上一下一大截，"一下一下点"没意义）：点一下改成 2 计数、按住照旧每 20ms 16 计数，
  按计数记（SCHEME 7，存横竖计数）；核对屏箭头一下 8 计数（原来 32）。
  用户又说按住一下就到头、靠边时看不见光标：按住改成慢速匀速（每 20ms 4 计数，SCHEME 8），回正中也只能按这个速度走（几秒）；
  出发那屏给 ◀ ▶ ▲ ▼ 十字键随便挪（不计数），往对边那屏只有一个往对边的键（计数）。
  用户问"测距离和回正中的速度为什么不能解耦"：对方有鼠标加速，同样的计数快慢不同走的像素不同（KDE 实测 16 计数：一下一下点 11.6、
  按住 21、一下大跳 32 像素），量出来的计数只在量的那个速度下等于那段距离。要解耦只有：绝对坐标（KDE 能用）、对方关掉加速
  （KDE 可选 flat、Windows 可关"提高指针精确度"，iPad 关不了）、或者再量一次快速度的倍率（要人多对一下）。
  ② 摇一摇撤掉（不好用）；空中鼠标页的按钮改成"回正中（长按校准）"：点一下回正中，按住 0.6 秒进校准。
  ③ 绝对坐标：描述符照样默认带，但每台主机的"回正中用绝对坐标"默认关（iPad 上不清楚是不是它导致飞来飞去，KDE 上确认能用，要手动打开）。
- **第五批（2026-10-15 写完，没提交，待主力机验证）**：用户骂"校准还是一坨屎、为什么加自动移动"、"绝对坐标为什么还是实验"、"加速不好控制、方向不直觉"：
  ① 校准去掉"自动退回慢慢找边"，全手动：挪光标给 ◀ ▶ 两个键（按住到边冲过头了自己按反方向退回来），到边点"到边了"就记。
  ② 绝对坐标默认开、不叫实验（Prefs.absPointer，键名仍是 abs_experiment；每台主机的开关默认开）。改了描述符，已配对的主机要删掉手机重配一次才生效。
  ③ 空中鼠标重写（照 GyroWiki / Steam Gyro To Mouse）：玩家空间（原来的世界空间手机一侧倾，点头就斜着走）、固定灵敏度不加速（原来 4 倍加速再叠电脑的加速）、
  两级 1€ 滤波消抖（离线模拟挑的参数：10Hz 手抖剩约 14%，起步约 60ms 跟上，停下多滑约 0.35°；GyroWiki 的"只平滑小输入"对手机的抖动几乎没用）+ 软死区 3°/s。
  ④ 圆钮：按住移动，点一下锁定移动、再点解锁；回正中改成摇一摇（空中鼠标页开着时；动作清单里的"回正中"照样能用）。
  只做了离线测试 + 测试机上打开不崩；**手感（消抖、灵敏度、方向）、摇一摇的力度阈值都要用户在主力机上试**。
- **回正中第四版：一下跳过去（2026-10-15 写完，没提交，待主力机验证）**。在 KDE 电脑上用测试机 + 光标记录器 + DebugMoves 实测定下来的：
  - 绝对坐标（实验开关 + 这台主机的开关 + 重新配对）在 KDE 上能用，瞬间准确到正中；Linux 内核会把相对、绝对并成一个"Mouse"设备，libinput 两种都认。
    坑：内核只转发变了的绝对坐标，同一个值第二次被吞，所以每次交替发 16384 / 16383（BtHid.sendAbsolute）。
  - 相对坐标的规律：归位后单个大报文（≥ 96 计数）每计数正好 2.000 像素、零散布、横竖一样；紧接着（10ms）跳约三成会丢（和最后一帧归位合在一起被边夹掉），
    停 30–100ms 并发不动的报文就一次不丢（JUMP_PAUSE_MS = 40）。按住一步步走（20ms 一步）每步随蓝牙时序飘 ±4%，一下一下点的步子只有按住时的一半远——
    所以"尺子"不能用步子。现在校准和回正中都用"从角上一下跳 t 计数"（CenterPlan.place / plan），回正中约 0.13 秒、电脑上一帧内到位。
  - 实测：点到边的那条路回正中落点离正中 2–3 像素；空中鼠标页圆钮从三个位置回正中都落在同一点。按住冲过头 → 自动退回慢慢找边（退回量不够就翻倍再来，最多 3 次）
    这条路逻辑跑过，但测试脚本模拟点屏幕延迟大（input tap 约 1 秒、sendevent 好像没被认出是点按），没有拿到可信的精度数字，**要用户在主力机上试**。
  - 做完一轮 5 个方向的审查（每条再由另一个助手核实），确认 18 个问题已修：换屏时停掉还在播的序列、退回量按松手速度、按住过就必走慢慢找边、
    服务断开时松开经服务按住的空中鼠标来源、没开服务时每秒的重连失败不再打断本地按住、蓝牙重连不打断按钮 / 宽限期回来接上自动重连、DeX 接管时停滚轮、
    退出时转发那一路的键松开、拖动中不回正中、转发模式下点鼠标键不盖掉服务那一路的左键、键盘行滚轮不多震、多处按左键时松一个不停轻震、
    恢复默认停蓝牙并提示重配、日文名字统一。
- **空中鼠标第三批（2026-10-15 写完，没提交，待主力机验证）**：
  ① 校准"看不到光标动"的根因（测试机连 KDE 电脑实测，KWin 脚本记光标坐标）：步子太快——每 10ms 12 计数，一秒约 1900 像素，
  从角上到正中不到 1 秒，人还没看过去就走到对边、贴在角上不动了（用户看到的"光标在左上角一动不动"）；而且归位后停的那 150ms
  蓝牙链路睡了，头几步攒成一团送到，对方当快速移动多走一倍多，每次攒多少不一样。改成每 20ms 8 计数（约 345 像素 / 秒，
  从角到 2560 宽的屏幕正中 3.7 秒），停顿时照节奏发不动的报文让链路醒着（CenterPlan.SCHEME 2，旧校准作废）。
  实测同样步数重放两次落点差 4 像素，多走 5 步的两次差 12 像素，离正中十几像素以内。箭头一下改成 5 步。
  用户接着指出"我怎么看出到正中了"——屏幕中间没有任何标记，估不准。改成量整条边：光标从一边走到对边，碰到边就点屏幕，
  两个方向各取一半步数（CenterPlan.edgePrefix / centerFromEdge，反应时间按 150ms 扣）；走之前先沿另一个轴离开角 15 步，
  免得贴着边走进对面的热角。代价：这块 2560 宽的屏横着要走约 7 秒；接多块显示器时量到的是整个桌面的边，不是某一块屏。
  "走到对边取一半"这个改法还没在电脑上实测（之前测的是速度和重放一致性）。
  用户再要求：校准不用光标自己走，引导人手动挪——先确认光标在这条边上（App 自动甩过去），只给一个方向键，按住挪到对边，
  到了点"到边了"。现在就是这样（按下先走一步，按住 0.25 秒后每 20ms 连续走；挪的时候没按也照节奏发不动的报文，链路不睡）。
  因为不再靠人反应，步子加快一倍到每 20ms 16 计数（回正中重放同一种步子，SCHEME 3，旧校准作废），这块屏上回正中约 2.8 秒。
  用户嫌回正中慢、"不需要对我可见"：对方有鼠标加速，同样的计数走得快慢不同距离就不同，所以重放必须和校准一样快；
  要一下到位只有两条路——绝对坐标（实验开关，要重新配对，KDE 上认不认没试）或校准时多量一种快速度（每个方向多挪一趟）。
  已向用户说明，等用户选。
  ③ 动作清单加"滚轮向上 / 向下"（Kind.WHEEL）：按钮、音量键按住时先滚一格、0.25 秒后每 80ms 匀速一格，每格照滚动咔嗒震；
  只能点一下的地方滚一格。DeX 模式经服务的虚拟鼠标（新命令 CMD_WHEEL，协议版本 13，部署后服务要重启），蓝牙模式直接发。
  ④ 圆钮改成画的：圆 + 九个小点（RoundPad），去掉中间的 ◎。
  ② 空中鼠标页改成"2n + 1"：每行两个大按钮，行数在设置里加减（2–6 行），"无"空着、位置留着；再加一个固定的圆钮（◎），
  点一下（不到 0.3 秒松开）回正中、按住移动，放在设置里选的两行之间的交叉点（"1、2、3、4 中间"）。回正中、按住移动归圆钮后，
  默认格子换成 左键 / 右键 / 按住拖动 / 返回 / 复制 / 粘贴（以前没改过的会跟着变）。
  测试机上看了页面布局和设置页、没崩溃；**校准新节奏只在 KDE 电脑上验证过，圆钮的手感、回正中要用户在主力机上试**。
- **空中鼠标第二批（2026-10-15 写完，没提交，待主力机验证）**：用户试了第一批说"很牛逼"，提了 5 条，照做：
  ① 手感：倍率改成 S 形曲线（smoothstep），慢速倍率 0.25（踩刹车的感觉），陀螺仪 200Hz。
  ② 按钮条可增减：只固定"退出""设置"和最下面的"收起"，其余（含"旋转"）最多 12 个，设置里 ✕ 删、"＋ 添加按钮"加；
  旧存储第一次打开时迁移（Prefs.migrateSlots：前面补"旋转"，去掉"无"）。
  ③ 音量键不再写死给空中鼠标：设置里"音量键"一节，音量 + / − 各有"点一下""按住"两个槽，从动作清单里选，默认"无" = 照常调音量
  （HardKeys；只设了点一下就按下当场算，设了按住就 0.3 秒为界）。只在蓝牙模式；DeX 模式要服务去读按键，以后做。
  新动作"按住移动 / 按住拖动 / 回正中"哪里都能放：按钮、音量键按住 = 按着才生效；只能点一下的地方（手势、音量键点一下）= 点一下开、再点一下关。
  陀螺仪不再跟着空中鼠标页开，开始跟随才注册、停了 3 秒注销。
  ④ 空中鼠标页：上面一行状态，中间 6 个可设定的大按钮（默认 按住移动 / 左键 / 右键 / 回正中 / 按住拖动 / 无；"无"不占位），底下校准、按住退出。
  页上"左键"和按钮条的一样走分流（REGION_HOLD_LEFT）。
  ⑤ 校准页每屏一句话（见文件表）；箭头每按一下多 / 少走 2 步并重走一遍。
  另外照用户的原则统一了反馈（见决策记录"同一种操作，反馈一样"）。离线测试全过（空中鼠标 50 项、手势 86 项）；
  测试机上只看了主界面、设置页、校准页能打开、按钮条迁移对，**手感、音量键、大按钮、校准流程都要用户在主力机 + 主机上试**。
- **空中鼠标第一批（2026-10-15 写完，没提交）**：动作清单 air_mouse 进出（只蓝牙模式）；覆盖层盖住界面，只报"校准回正中""按住退出"两个按钮；
  音量键按用户方案（AirKeys），陀螺仪换算（AirPointer），回正中 = 每台主机校准重放（CenterPlan / CalibrateActivity），或实验开关打开、
  对方重新配对过、这台主机打开"用绝对坐标"时一帧绝对报文（报告 ID 4，BtHid.sendAbsolute，开关一改 BtHid.reregister）。
  设计初稿由工作流写（评审那步撞了会话额度没跑成）；审查工作流确认 12 个问题并已修（服务断开时残留的左键、服务那边锁住的修饰键、音量键记录卡住、系统取消的按键、校准归位时误点、转 180° 方向不更新、断线后的旧按键状态、横屏时退出按钮被挤出屏幕、校准页不能滚动、校准时音量键松开漏给系统、设置页分设备开关不跟主机变、英文 / 韩文双引号没转义）。离线测试 51 项全过；测试机上只冒烟过界面路径
  （进空中鼠标、打开校准页、按住退出，没崩溃）。**音量键、手感、回正中、校准、绝对坐标都要用户在主力机 + 主机上试**；
  转屏方向 1/3 的轴向符号是照 AccelerometerPlay 写的、没在真机核对。
- **这一批写完、没提交、待主力机验证（2026-10-15）**：
  ① 苹果适配：连苹果设备时 Ctrl / Alt / Super 显示 ⌃ / ⌥ / ⌘；内置快捷键改按苹果习惯（带 Ctrl 的换 ⌘，切换窗口 ⌘Tab、
  关窗口 ⌘W、截屏 ⌘⇧3、浏览器后退前进 ⌘[ / ⌘]、刷新 ⌘R、显示桌面 ⌘H；Actions.Action.appleCode / hidCode）。用户自建的组合键不动。
  ② 蓝牙没连上时每 5 秒自动重连上次那台（BtHid.retryConnect），连接尝试和结果记日志（标签 douhua）。起因：用户 App 里第一次
  连不上 iPad、从 iPad 主动连又被当成音频设备；当时主力机 adb 断了没拿到日志，原因是推断的（只连一次、失败不重试）。
  ③ 移动光标时的颗粒震动（设置开关，默认关；GestureEngine.texture / HAPTIC_TEXTURE，协议版本 12）：手指每划 10dp 一颗、
  至少隔 25ms；拖动、按着左键、滚动时不发。震动按手机能力挑：LOW_TICK×0.3 → TICK×0.15 → 能调幅度就 5ms×30/255 → 不震。
  测试机上看到了请求（20 颗、间隔约 33ms），但它开着勿扰、震动被系统忽略，手感要用户在主力机上试。
- **蓝牙模式由服务独占触摸屏：主力机上验证过**（2026-10-15 00:06 部署）。服务日志"已接管触摸屏……（蓝牙模式，报文发回 App）"，
  从顶边 / 底边 / 右边往里划的触摸都进了服务的触控板区域；用户确认系统的下拉 / 上滑 / 侧滑都没反应，电脑上光标、点击正常。
  移动时服务每秒最多约 100 个鼠标报文（原始触摸），关掉电脑连着的音频后用户没觉得卡。测试机上跑不了（服务找不到它的触摸屏）。
- **测试现场（2026-10-15 回正中实测后）**：测试机蓝牙已关回去，KWin 光标记录脚本已卸载。测试机上豆花开着"绝对坐标"实验开关、这台电脑的"用绝对坐标"关着、
  存着一份校准；电脑上测试机的配对是带绝对坐标描述符重新配的（这次电脑还连上了测试机的媒体音频 AVRCP）。测试机系统设置里"取消保存"过电脑一次又重配了。
- **测试现场没收拾**：电脑蓝牙是测试时打开的（原来关着），测试机蓝牙 2026-10-14 打开的（原来关着），测试机和电脑的配对还在；
  测试机蓝牙 2026-10-15 测颗粒震动前又关掉了（恢复原状）。
  测试机"插电保持亮屏"改成了 true（=7，任何电源；原来 0。只设 usb 不够：电脑的口被它认成充电器），
  改回要 `adb -s <测试机> shell svc power stayon false`。

## 想做的功能（用户 2026-10-14 / 15 提的，先记下，一个个来）

**总方向（用户原话的意思）：做成"终极输入设备"**——任何设备（电脑、平板、电视、VR 头显……）都能用这台手机上的豆花来操控；
不看屏幕也能准确打字；手机本身的传感器、实体按键都用上；无线、有线都能连。下面每一条都是朝这个方向的一块，做的时候别丢了整体。

- **有线连接**（用户 2026-10-14 问过，当时漏记）：手机不 root 当不了"插上就认"的 USB 键鼠（要内核里的 USB 键鼠功能）。
  两条路：① USB 线 + 电脑上装个小接收程序（adb 或 USB 网络共享传过去，电脑上造虚拟键鼠；延迟最低，但每个系统都要装、要写一份）；
  ② 买 CH9329 这类"串口转 USB 键鼠"的转接线：一头插电脑（电脑看到的是真 USB 键鼠，任何系统都认、不用装软件），
  另一头 OTG 插手机，App 用安卓的 USB 主机接口驱动它，不用 root；代价是要买线、手机当 OTG 主机时一般不能同时充电。
- **VR 设备**：蓝牙键鼠是通用标准，VR 头显一般也认，但各家对鼠标的支持程度不一样，要拿真设备试。
- **苹果设备的进一步适配**（用户提过"苹果键位、鼠标设置和通用的不太一样，能自动识别更好"）：自动识别已经有了
  （BtHid.isApple），现在只用在 GUI 键显示 ⌘ 上。还可以：Alt 显示 ⌥ Option、Mac 习惯的键位顺序、滚动方向等，要用户说哪些别扭再做。

- **移动光标时极轻的颗粒震动**（已写完，见"进行中"）（模拟表面不光滑，设置里开关，默认关）。草案：引擎按手指划过的距离每隔一小段发一个新的震动种类，
  App 用最弱的轻触原语、幅度压很低；划得快颗粒密，不动不震；拖动时不加（会打断拖动持续轻震）、滚动时不加（有咔嗒）；两种模式都生效。
- **空中鼠标 / 激光笔 + 音量键**（已写完，见"进行中"；音量键写死的方案用户试了说不好用，第二批改成可设定；以下是当时定的方案和调研）（用户 2026-10-15 定的操作方案，只在"空中鼠标模式"下）：
  音量 − 点一下 = 光标回到屏幕正中；音量 − 按住 = 离合，按着时光标跟着手机转动走；
  音量 + 点一下 = 左键，点两下 = 右键，按住 = 拖动。手机转动用陀螺仪 → 相对位移（像电视的体感遥控器）；
  做不到"指哪儿红点就在哪儿"（手机不知道屏幕在哪），所以靠"回正中"+"按住才动"来对准。
  没定的：① "回正中"——相对鼠标不知道光标在哪、屏幕多大：要么描述符加一个绝对坐标指针（能精确跳到中间，但所有主机要重新
  配对一次，iPad 认不认绝对指针没验证），要么先甩到左上角再往回挪个估计值（对方系统的鼠标加速会让它不准）；
  ② 怎么进出空中鼠标模式（打算做成一个动作，按钮条 / 手势都能放）；③ DeX 模式下音量键到不了豆花（键盘焦点在 DeX 屏上），
  要服务去独占音量键设备，先只做蓝牙模式。点一下 / 点两下要等约 250ms 才能分辨，左键会晚这么一点。
  **"回正中"调研结论**（2026-10-15，网上资料 + 读源码，全部没在真机上验证）：
  - 甩左上再甩右下找不到中心：撞边后多余的位移被主机吃掉、手机收不到任何反馈，算不出屏宽的一半。
  - 甩角有副作用：GNOME 左上角热角（活动概览）、macOS 热角（右下默认快速备忘录）、iPad 推出底边调程序坞 / 右上角控制中心。
    归位角要每台主机单独选。
  - 绝对坐标指针（Generic Desktop / Usage Mouse 0x02，Pointer 物理集合，X/Y 0..32767 Absolute，新报告 ID 4，旧的 1/2/3 不动）：
    Windows 能（主显示器中心）；macOS 能（顶层必须是 Mouse，写 Pointer 会被当摇杆）；Linux Wayland 大概能、X11 会和相对鼠标
    合成一个节点后绝对事件被丢；Android / DeX / 电视作主机不起作用；ChromeOS 有害（被当触摸屏、带按键就是点击）；
    VR 头显没有固定"屏幕"；iPad 未知（Classic 有一份"能跳、松开会卡键"的报告，BLE 有 MouseLink 一份全正常的个例）。
    不要用 Digitizer 页。绝对报文的按键字节要照抄当前按键状态（合成节点时会共用左键），只用它移动不用它点击。
  - 每台主机校准一次 + 重放（按固定步长、固定节奏沿 X、Y 分别走，用户看到到中线时按一下，记步数；回放必须同样的步子、沿轴走）：
    Windows、macOS 大致可重复；Linux libinput 默认 adaptive 按收到事件的时间算速度，蓝牙抖动会偏，不稳；iPadOS 未知
    （强制加速、指针吸附按钮）。
  - 推荐：空中鼠标本身先做；回中心先用"校准 + 重放"；绝对坐标在测试机上做实验描述符，iPad / Linux 实测通过再做成按主机的开关。
    来源要点：developer.apple.com/forums/thread/770639、/652700，github.com/tukitohosi/mouselink，QEMU dev-hid.c，
    AOSP inputflinger CursorInputMapper / EventHub，Chromium event_device_info.cc，libinput pointer-acceleration 文档。
- **空中鼠标页放键盘**（用户 2026-10-15 顺口提的"甚至加个键盘都可以"）：长屏手机上格子下面还有地方，可以放一块小键盘。没做。
  （2026-10-19 做的"空中鼠标页能调出键盘"是盖上整屏键盘、空中鼠标暂停，不是这条；在格子下面嵌小键盘仍然没做。）
- **手机放桌上平移当真鼠标**：大概率做不好。加速度计二次积分漂移很快、陀螺仪测不到平移、后摄贴着桌面对不上焦。真想做先做个实验。
- **截获音量键 / 电源键，短按、长按各配动作**（蓝牙模式的音量键、DeX 模式的音量键已做，后者待真机验证，见"进行中"；电源键用户 2026-10-21 定了不要）（在触控板 / 键盘界面时）：音量键豆花在前台时能自己截（窗口要能收按键）；
  电源键普通 App 截不到，要服务独占按键设备，风险大（截住期间灭不了屏、紧急求救长按被吞），必须有和触摸屏一样的自动释放保证。
- **盲打纠偏（用户想不看键盘打字）**：现在按键就是"按下时落在哪个矩形里"，键与键之间有 4dp 的缝（各留 2dp 边距）、
  第二行左右半个键的空位（SPACER）都不算任何键，落进去就没反应——"感觉按到了实际没按到"多半是这个。
  ① 缝和空位分给最近的键（软键盘通行做法，确定性不变，建议默认开）；② 自学习每个键的实际落点、把判定区挪过去
  （按"打完马上退格改成相邻键"推断按错；只存每个键的位置统计，绝不存打过的字）；③ 交界处按前几个字母的概率挑键
  （像 iOS 的动态热区，英文 / 拼音各要一份字母统计）。②③ 做成开关、默认关。不做事后纠错（键已发出，自动退格会和对方输入法打架）。
  建议先只开统计看几天数据，偏移是系统性的话 ② 就够。
  **① 已做**（2026-10-19，见决策记录）；② 的数据工具备好了（tools/type-log.sh + tools/type-analyze.sh：每个键的落点偏移，
  学偏移以后加在 KeyReplay / TouchRouter 里），学习本身还没做；③ 没做。
- **蓝牙报文攒着发**：服务接管后原始触摸每秒约 100 个报文、App 自己收约 120 个；链路要是送不过来（比如对方连着手机音频时
  只有每秒约 11 个）就会排队。现在靠关掉音频解决；要防万一，可在 BtHid 里把移动量攒起来、按链路能送的节奏发（按键变化和
  键盘报文前先把攒着的发掉，保证 Ctrl+滚轮顺序）。
- **服务支持缩放面板的手机**：测试机这类 4K 面板缩放到 1096×2560 的，`dumpsys input` 里 RawToDisplay 是 IDENTITY、
  DisplayBounds 是原始尺寸（1645×3841），TouchDevice 只认三星那种带矩阵的格式，找不到触摸屏。要支持得按 Viewport / DisplayBounds
  推算缩放，只有一台样本，没做；做了才能在测试机上测服务那一半。

## 已知技术债 / 没验证的

- 蓝牙发送全在主线程（本地分流、服务转发、空中鼠标都是）：sendReport 是同步 binder，主线程一卡报文跟着晚。要更稳可开专门的发送线程
  （BtHid 的 hid / connected 和鼠标状态要一起挪过去、保住键盘和鼠标报文的先后），等实测打字延迟再定。

- 手机自己拉起服务：Google 若让 adbd 只绑 wlan0（讨论中），连 127.0.0.1 这条路会整体失效；探测服务状态每次都让服务建 / 拆一次虚拟设备
  （要省得改服务：先发 hello，或者加只探测的命令）。

- 还没由真人试过：外接键盘（含 DeX 侧输入法打中文）、双指缩放在哪些应用生效、双击、系统方向锁定版的旋转按钮、接上外接屏的通知。
- 服务被直接杀掉（tools/stop-server.sh）时不会恢复方向锁定；服务因兜底退出（旧会话 2 秒收不完尾还占着触摸屏）时也一样。
- start-server.sh 只能在"有连接在排队"时认出卡住；服务在 accept 之后、发 hello 之前卡住它看不出来（靠 App 那边 5 秒的提示）；
  会话中途卡住、App 照常写的情况 App 也察觉不到（服务不给 App 发保活）。
- 服务用 `--debug` 起时，Session.onDown 把每次按下落在哪个区域（键 id）写进手机上的 /data/local/tmp/douhua-server.log，等于把打的字落了盘（打字测试的触点日志只走 logcat，不受这条影响）。
- 实体键：音量上 + 音量下一起按（无障碍快捷方式）没当组合键交还，只要有一个音量键配了动作（被截住），豆花在前台时就用不了它（配了的那个被豆花吃掉，系统只看到另一个）；
  触摸屏释放时 router.releaseAll 会把实体键按着的"左键"（CMD_HOLD_LEFT）一起松掉；服务被直接杀掉时，透传中按着的音量键
  系统会一直连发到下一次按键；交还系统时最后一次读 grabFd 和关掉它之间（微秒级）到的事件会丢；只配了"点一下"的音量键按下当场就发给 App，
  之后才认出是组合键的话动作已经做了。
- 键盘键位是初版，按实际用起来再调；竖屏一行 12 个键偏窄。
- MtDecoder 假设开始读时当前 slot 是 0（没法用 EVIOCGABS 查）；开始读之前就按着的手指一律忽略到抬起。
- TouchDevice 依赖 `dumpsys input` 的文字格式（三星 One UI 9 实测）；系统升级改格式会找不到触摸屏，状态栏会显示原因。
- 接管期间横幅通知、来电横幅点不了（全屏来电会让豆花退到后台，自动释放）。
- 接管期间长按电源键弹出的关机菜单点不了：它是系统对话框，豆花不 onPause、照报 active，触摸还归服务（还会分给菜单底下看不见的按钮）。以前就有。改法（失去窗口焦点也报 inactive）在 DeX 下用不上（主窗口平时不收焦点），没做。
- 滚动是整格滚轮。要更顺可以试 HID 高精度滚轮（Resolution Multiplier，需要服务回应 UHID_SET_REPORT）。
- 外屏一直亮着同样的边框，长期有烧屏风险；边框已经调得很暗。
