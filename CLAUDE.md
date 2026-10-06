@AGENTS.md

（上面一行把 AGENTS.md 里的协作红线引进来；红线只在 AGENTS.md 改，这样别的 AI 工具也读得到同一份。）

# 架构与决策记录

## 架构

两个进程，代码在同一个 APK 里：

```
外屏触摸屏 /dev/input/eventN ──(服务独占)──┐
                                           ↓
       Server / Session（shell 身份，app_process 拉起）
         ├ TouchReader + MtDecoder：读原始多点触控，用系统的换算矩阵转成屏幕坐标
         ├ 按 App 报来的区域分流：触控板 → GestureEngine；键盘键 → UhidKeyboard；按钮 → 报告给 App；
         │  "左键" → 直接按住鼠标左键
         ├ UhidMouse / UhidKeyboard：/dev/uhid 造的 USB 鼠标和键盘 → 系统输入 → DeX
         │  （双指开合 = UhidKeyboard 按住 Ctrl + UhidMouse 滚轮）
         └ Injector：带 displayId 往 DeX 屏注入按键（隐藏 API，反射）
                ↑ 布局 / 参数 / 心跳 / 按键      ↓ 震动 / 三指四指手势 / 按钮事件 / 修饰键 / 接管状态
       MainActivity（App，普通权限）：画界面、震动、执行动作；127.0.0.1:37601
```

| 文件 | 职责 |
|---|---|
| `src/dev/douhua/Proto.java` | 协议常量，两边共用 |
| `src/dev/douhua/GestureEngine.java` | 手势识别，纯 Java，跑在服务里（`tools/test.sh` 离线测） |
| `src/dev/douhua/MainActivity.java` | 界面、按钮事件、贴字面板、数字键盘、旋转、动作执行；把区域位置报给服务 |
| `src/dev/douhua/KeyboardPanel.java` | 外接键盘键位（Gboard 式字母区 + 功能行 / 数字行 / 快捷行，符号层、Fn 层），键 id = 用法码 \| 自带修饰键 << 8 |
| `src/dev/douhua/KeyboardScreen.java` | 键盘界面：工具栏 + 可调大小位置的键盘 + 键盘上方的小触控板；调整模式由 App 自己收触摸 |
| `src/dev/douhua/Style.java` | 共用样式（边框、颜色、按下 / 高亮） |
| `src/dev/douhua/ButtonStrip.java` | 左侧按钮条，屏幕矮时自动分列 |
| `src/dev/douhua/PadArea.java` | 触控板 + 数字键盘的排布 |
| `src/dev/douhua/TrackpadView.java` | 只画边框和状态字 |
| `src/dev/douhua/Actions.java` | 统一的动作清单（按钮条 / 角上按钮 / 手势 / 键盘最下面一排共用；加动作只加一行；自定义组合键 id 形如 `combo:键id:标签`） |
| `res/values*/strings.xml` | 九种语言的界面文字 |
| `art/icon.webp` → `tools/make_icon.py` | App 图标原图；脚本生成自适应图标的前景层 `res/drawable-nodpi/ic_launcher_fg.webp`（换图后重跑，要 Pillow） |
| `src/dev/douhua/Combos.java` | 自定义组合键：可选的键、拼 id、起名字、迁移上一版键盘快捷行的存储格式 |
| `src/dev/douhua/KeyboardSnap.java` | 调整键盘时的轻微吸附，纯 Java（离线测） |
| `src/dev/douhua/Prefs.java` | 设置键名和默认值 |
| `src/dev/douhua/Link.java` | App 端连接，断线每秒重连，收发消息 |
| `src/dev/douhua/DexDisplay.java` | 找 DeX 所在的屏（按键注入目标） |
| `src/dev/douhua/DisplayReceiver.java` | 服务叫醒它：接上外接屏弹通知（点开在手机屏上打开豆花），拔掉收回 |
| `src/dev/douhua/server/Server.java` | 服务入口；`probe` 只画方块不点击；`grabtest` 独占 0.5 秒 |
| `src/dev/douhua/server/Session.java` | 一次 App 连接：独占时机、触摸分流、引擎输出 |
| `src/dev/douhua/server/TouchReader.java` | 独占并读触摸屏，poll 100ms 检查是否该释放 |
| `src/dev/douhua/server/MtDecoder.java` | 多点触控协议 B 解码，纯 Java（离线测） |
| `src/dev/douhua/server/TouchDevice.java` | 从 `dumpsys input` 找屏幕对应的触摸屏和 RawToDisplay 矩阵，解析部分纯 Java（离线测） |
| `src/dev/douhua/server/ScreenRotation.java` | 物理屏坐标 → 当前方向坐标，纯 Java（离线测；方向 1 的公式取自真机） |
| `src/dev/douhua/server/Evdev.java` | 打开 / 独占输入设备（EVIOCGRAB，经 libcore 的 ioctlInt） |
| `src/dev/douhua/server/Uhid.java` | /dev/uhid 造 USB HID 设备的公共部分 |
| `src/dev/douhua/server/UhidMouse.java` / `UhidKeyboard.java` | 虚拟鼠标 / 虚拟键盘（boot 键盘描述符，6 键无冲） |
| `src/dev/douhua/server/PeerUid.java` | 查 /proc/net/tcp 确认连进来的是谁 |
| `src/dev/douhua/server/TestClient.java` | 服务自测：冒充 App 接管几秒后停发心跳，看会不会自动释放 |
| `src/dev/douhua/server/DisplayWatcher.java` | 每 1.5 秒查屏幕列表，外接屏接上 / 拔掉时 am broadcast 给 DisplayReceiver |

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
- 键名（Esc、Tab、Home、PgUp、⌫、↵、方向键……）用键盘上通用的写法，不翻译；"符"键中文保留"符"，其它语言用 Gboard 的 `?123`。
- 阿拉伯文从右往左：清单里 supportsRtl，界面整体镜像；键盘和数字键盘强制从左往右（按实体键盘排）；触控板角上的按钮按物理左右放
  （设置里写的就是"左上 / 右上"）。
- 按钮文字太长时自动缩小（Style.button 里的 autoSize），德语、法语的短名普遍比中文长。
- 翻译是 AI 写的，没有母语者校对；正式面向这些语言的用户发布前最好找人看一遍。

## 不变量

- **独占触摸屏的条件**：App 报 active（在前台、打字面板没开）、参数已到、心跳（每秒一次，主线程发）没超过 3 秒。
  任一条件不满足，100ms 内释放；连接断开立刻释放。这是"外屏不会被锁死"的保证，改动时必须保住。
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
- **服务目前要靠电脑 adb 拉起**（`tools/start-server.sh`），手机重启后要重跑。Shizuku 方案还没做。

## 测试

- `tools/test.sh`：GestureEngine、MtDecoder、TouchDevice 解析的离线测试。
- 服务安全链路（真机）：`adb shell CLASSPATH=<apk 路径> app_process / dev.douhua.server.TestClient 0 2`
  ——接管屏幕 0 两秒、停发心跳，服务应在 3 秒内报"App 没有响应，已把触摸屏还给系统"。**测试期间外屏摸不动约 5 秒。**
- 界面（不碰真屏）：scrcpy 开私有虚拟屏，把豆花启动上去录屏。虚拟屏没有触摸屏，服务会拒绝接管，所以只能看布局：
  ```bash
  scrcpy --new-display=1248x1972/420 --no-window --no-audio --no-control --record=ui.mkv --time-limit=9 &
  adb shell am start --display <新虚拟屏 id> -n dev.douhua/.MainActivity
  ```
- 触摸手感、按钮、三指四指、震动只能真人在真机上试；服务用 `tools/start-server.sh --debug` 起，日志里有每秒的鼠标包统计。

## 已知技术债 / 没验证的

- 还没由真人试过：外接键盘（含 DeX 侧输入法打中文）、双指缩放在哪些应用生效、双击、系统方向锁定版的旋转按钮、接上外接屏的通知。
- 服务被直接杀掉（tools/stop-server.sh）时不会恢复方向锁定。
- 键盘键位是初版，按实际用起来再调；竖屏一行 12 个键偏窄。
- MtDecoder 假设开始读时当前 slot 是 0（没法用 EVIOCGABS 查）；开始读之前就按着的手指一律忽略到抬起。
- TouchDevice 依赖 `dumpsys input` 的文字格式（三星 One UI 9 实测）；系统升级改格式会找不到触摸屏，状态栏会显示原因。
- 接管期间横幅通知、来电横幅点不了（全屏来电会让豆花退到后台，自动释放）。
- 滚动是整格滚轮。要更顺可以试 HID 高精度滚轮（Resolution Multiplier，需要服务回应 UHID_SET_REPORT）。
- 外屏一直亮着同样的边框，长期有烧屏风险；边框已经调得很暗。
