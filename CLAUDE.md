@AGENTS.md

（上面一行把 AGENTS.md 里的协作红线引进来；红线只在 AGENTS.md 改，这样别的 AI 工具也读得到同一份。）

# 架构与决策记录

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
         ├ 手柄页（REGION_GAMEPAD）→ GamepadTouch → UhidGamepad（第三个 uhid 设备，第一次用到才建、会话结束才关）；
         │  服务没接管时 App 本地分流出的经 CMD_GAMEPAD 发来，两路合并（GamepadMux）再写 → Moonlight → 电脑上的 Xbox 手柄
         ├ KeyReader + HardKeyGate：独占音量键设备（带电源键的只旁观），判断点一下 / 按住报给 App，没配的键注入补发
         └ Injector：带 displayId 往外接屏注入按键、往这块屏注入触摸（分屏透传）（隐藏 API，反射）
                ↑ 布局 / 参数 / 心跳 / 按键 / 实体键配置 / 手柄报文和参数   ↓ 震动 / 三指四指手势 / 按钮事件 / 修饰键 / 接管状态 / 实体键 / 手柄报文
       MainActivity（App，普通权限）：画界面、震动、执行动作；127.0.0.1:37601
```

| 文件 | 职责 |
|---|---|
| `src/dev/douhua/Proto.java` | 协议常量，两边共用（版本 19）；手柄控件编号（gamepadId：0x4000 \| 种类 << 10 \| 副本 << 8 \| 参数）和输出码（GP_*，按位置；GP_C_* = N64 的 C 键，GamepadTouch 换成右摇杆推满） |
| `src/dev/douhua/GestureEngine.java` | 手势识别，纯 Java（`tools/test.sh` 离线测）；惯性滚动（边缘一拨接着转、碰触控板抓住，用唯一的定时器槽 TIMER_SPIN）、连续滚动（按 1/120 格出、每滚过一格咔嗒、双指锁主方向）也在这里；滚轮量的单位是 1/120 格；中键按着时单指划 = 滚动（setScrollHold，方向"推哪边视野往哪边走"、不起长按 / 惯性），小红点的位移、滚轮经 externalMove / externalScroll 出 |
| `src/dev/douhua/ScrollInertia.java` | 惯性滚动的物理（黏滞 + 恒定摩擦的闭式解、阻尼档位 → τ、停止时间 / 距离），纯 Java（离线测，HidTest），引擎和设置页共用 |
| `src/dev/douhua/ConfigCodec.java` | CMD_CONFIG 的编解码（App 的 Link 写、服务的 Session 读、TestClient 写，三处共用一份），纯 Java（离线测往返） |
| `src/dev/douhua/TouchRouter.java` | 触摸分流 + 键盘修饰键锁定 + 开合的 Ctrl，纯 Java（离线测）；桌面模式跑在服务的 Session 里，蓝牙模式跑在 App 里；键盘的缝分给最近的键（nearestKey）、键盘触点记录（Trace）也在这里；onTarget（只读：落在键 / 按钮 / 键盘范围上吗，分屏透传问手势带里的点用）；惯性转着时碰屏幕任何地方都停（down 里不在触控板上的按下、stopSpin 给服务的透传手指 / App 用），修饰键一变告诉引擎 Ctrl 按没按着；小红点（REGION_STICK，按得到的是报上来那个正方形的内切圆——Region.contains，服务、App 本地分流、KeyFingers 共用；第一根手指交给 TrackPoint，按着时请宿主开节拍 Output.startTicks / onTick）、三个鼠标键（REGION_MOUSE_BUTTON：左键和"左键"按钮 / holdLeft 共用计数，中键按着 = 滚动、没滚过松开补中键单击）；手柄页（REGION_GAMEPAD 交给 GamepadTouch，Output.gamepad 出报文，setGamepadConfig、releaseAll / 换参数时回中立） |
| `src/dev/douhua/Hid.java` | 鼠标 / 键盘 / 媒体键的 HID 描述符和报文、键盘按键状态，纯 Java；USB 和蓝牙发的字节一样，蓝牙多报文编号；带滚轮分辨率倍率的鼠标描述符（MOUSE_DESC_HIRES 蓝牙用，竖、横两个倍率；MOUSE_DESC_HIRES_V 桌面模式 uhid 用，只有竖向一个；输入报文都和旧的一字不差）、倍率字节编解码、WheelOut（1/120 格 → 计数、余数、拆帧；uhid 那份设 / 问倍率的 setUhidFeature / uhidFeatureReport，UhidMouse 只调它们；离线测 HidTest）；手柄描述符 GAMEPAD_DESC（Game Pad、15 键 + Hat + X/Y/Z/Rz + Brake/Accelerator，13 字节，全零 = 中立）和 Gamepad（状态、报文、两路合并 merge）；修饰键掩码的两个小工具（ctrl：左右 Ctrl 都算；modifierUsages：掩码里按着的左右八个修饰键的用法码，界面替服务解开锁住的修饰键时逐个点一下） |
| `src/dev/douhua/BtHid.java` | 蓝牙模式：注册成蓝牙键盘鼠标、连上次那台、发报文；进程里一份，最后一个用的界面走了 60 秒才停；描述符跟着"连续滚动"开关（变了就注销、重新注册，descriptorChanged），回应主机设 / 问滚轮倍率 |
| `src/dev/douhua/Haptics.java` | 震动：点击、咔嗒、颗粒、拖动时的持续轻震、按键的"嗒"、惯性的"放开 / 刹住"（调试版设置里能挑候选波形，正式包用默认） |
| `src/dev/douhua/MainActivity.java` | 界面、按钮事件、贴字面板、数字键盘、旋转、动作执行；把区域位置报给服务；几屏共用的 ⋯ 菜单的项（主界面 键盘 / 手柄 / 设置 / 旋转 / 退出，键盘屏 键盘（豆花 / 实体，MENU_BOARD_ID 418，switchBoard 只写 kbd_board）/ 收起键盘 / 调整大小 / 布局（只在显示豆花布局时）/ 指点（显示着的那块自己的）/ 手柄 / 设置 / 旋转 / 退出，手柄页 预设 / 返回触控板 / 设置 / 旋转 / 退出；"手柄"只在桌面模式）、开关（setMenu）、⋯ 在哪（menuAnchor），键盘屏调整栏的按钮；主界面四角的小按钮、自定义按键（applyCustomKeys）；屏幕边缘留白（updateEdgeMargins：五页往里收）；手柄页的进出（setGamepadMode：桌面模式下手柄页走本地分流，CMD_GAMEPAD 发给服务）、吞自己手柄的键和摇杆（dispatchKeyEvent / dispatchGenericMotionEvent） |
| `src/dev/douhua/KeyboardFace.java` | 键盘页上两块面板（豆花布局 KeyboardPanel、实体键盘 FixedKeyboardView）对外的共同方法（第 1B 批）：修饰键、按下的样子、松开、主题、报区域、⋯ 键、空位、红点；KeyboardScreen.face 给显示着的那块 |
| `src/dev/douhua/FixedLegends.java` | 实体键盘的键帽字（res/raw/kbd_win / kbd_mac）和布局清单（kbd_catalog）的读法，纯 Java（离线测，FixedLegendsTest）：格式照 tools/kbd/kbdtable.py（段、别名、死键 `\*`、转义），读不出就抛异常；四层（普通 / Shift / 第三层 / 第三层+Shift）；画不画得出来（printable：私用区的苹果标、组合附加符号不画）；布局 id → 语言标签（LOCALE_TAGS）。Mac 模式查不对调那段（`<id>`，不读 `<id>.iso`） |
| `src/dev/douhua/FixedLayout.java` | 实体键盘的几何（第 1B 批），纯 Java（离线测，FixedLayoutTest）：形状（ANSI / ISO）× 尺寸（TKL / 全尺寸）× 模式（Windows / Mac）拼一块，单位 u（F 行 + 隔 0.25u + 主键区五行，6.25u 高；主键区 15u、编辑区、小键盘）；每个键的用法码（Windows 照 PC 惯例；Mac 底行 ⌃ ⌥ ⌘ 空格 ⌘ ⌥ 🌐 ⌃、F13–F15、没有 Ins / Menu，ISO 的 1 左边发 0x64、Z 左边发 0x35 照真苹果 ISO 键盘）、L 形回车（x2 / w2）、两行高的小键盘 + / Enter；Esc 和 F1 之间的 ⋯ 槽（menu）；键盘范围每区块每行一块（ranges：伸到相邻行 / 区块空隙的中线、外圈到面板边，没有键的格子不在任何一块里）；小红点那条竖线（stickU：G 的右边 = B 的中线）；Windows 模式右 Alt 是不是 AltGr（altGr：字表有第三层才是，美式不是；Mac 模式一律假）；照字表拼（forLegends：字表是 null 就不拼、返回 null） |
| `src/dev/douhua/FixedPanel.java` | 实体键盘摆到像素以后，纯 Java（离线测，FixedLayoutTest）：每个键按行切成几段矩形（segments，相邻键共用边界、四边缩进 gap）、范围、报给服务的区域（regions：段在前、范围在后）、面板自己收触摸时哪根手指按哪个键（keyAt：KeyFingers + nearestKey，红点圆、空位是洞）；小红点在真 ThinkPad 的位置（stickPoint：G|H 的缝、G / H 键帽下沿）、空格那一排让给三个鼠标键的空位（stickHole：以红点为中线对称，规则同豆花布局的 stickHole，空格切成左右两段）、碰到切口的键（KeyboardPlan.cuts）和字挪多少（labelShift）、角上小字放哪个角；键帽上画哪几个字（glyphs：字母一个、数字标点上下两层、第三层角上小字，锁着 Shift / 第三层时不会出的压暗，死键；第三层 = Mac 的任一 Option、Windows 有 AltGr 的布局的右 Alt，layer）、放大提示（preview）、主次键帽（functionKey）、L 形回车的形状（shape）、几个键帽（capBoxes：空格被空位切开时两个） |
| `src/dev/douhua/Boards.java` | 键盘页用哪块（豆花布局 / 实体键盘，kbd_board）和实体键盘自己的设置（kbx_lang / shape / size / mode / pointer、大小位置 kbx_<尺寸>_<指点>_<land|port>_*），纯 Java（离线测，BoardsTest），跑在 Store 接口上（App 里是 Prefs.store）；一个 kb_ 开头的键都不读不写；形状按布局清单核（德语只有 ISO，存的值不动） |
| `src/dev/douhua/FixedData.java` | 实体键盘的数据从 res/raw 读进来（进程里读一次缓存），拼布局（右 Alt 写不写 AltGr 看字表有没有第三层、小键盘小数点看清单；字表读不出来 / 没有这一段时布局是 null，键盘页照样显示豆花布局）、布局在界面语言里叫什么、开源许可页的文本 |
| `src/dev/douhua/FixedKeyboardView.java` | 实体键盘的面板（第 1B 批）：一个 View 自己画全部键帽（L 形回车、两三层字、死键强调色、左右八个修饰键高亮、被小红点切到的键用 Style.CutCap 画），自己收触摸（服务没接管时，照 FixedPanel.keyAt）；规则全在 FixedPanel |
| `src/dev/douhua/KeyboardPanel.java` | 外接键盘的键帽、触摸、高亮（键位本身在 KeyRows；第 1B 批起叫"豆花布局"，implements KeyboardFace，切到实体键盘再切回来不销毁），键 id = 用法码 \| 自带修饰键 << 8；自己摆放键帽（ViewGroup，所有键帽是直接子 view，按 KeyRows.place 摆）；报区域时键后面报键盘范围（REGION_KEYBOARD：整块一块，分裂时左右两半各一块）；功能行带不带最右边的 ⋯ 键（setMenuKey，小触控板上没摆出 ⋯ 时带，menuKeyRect 给菜单定位）；服务没接管、没走本地分流时面板整块自己收触摸（onTouchEvent，按手指编号分给键；嵌在键里的小红点圆里、让给三个鼠标键的空位里不算任何键）；三层的全部行交给 KeyboardPlan 算红点多大、空位多宽（layers）；键帽照 KeyRows.capKeys 建（含小红点状态底行的 before / after）；小红点状态（2026-11-01）：底行在中间让出空位、快捷行分得开时也让（setHole(hole, spread)，KeyRows.place 的 hole / spread），红点嵌在键里时碰到切口的键帽沿圆弧画（setStick → Style.keyLook 的 cut）、字竖着挪开（上下内边距，KeyboardPlan.labelShift）；三层、两种布局的键帽各建一次，换层 / 分裂只切显示；键帽文字 / 高亮没变的键不重设 |
| `src/dev/douhua/KeyRows.java` | 键位和排布，纯 Java（离线测，KeyRowsTest）：键（Key）、每行整块 / 分裂时左右两半各是哪些键（Row）、键盘区宽 ≥ 2 × 高（shouldSplit）、每个键摆在哪（place）；分裂时两半中间放触控板（各三分之一宽）或只隔一条缝（leftEnd 的 middle），只隔一条缝时两块键盘范围伸到缝的中线（areaEnd / areaStart）；同一个键两种布局用同一个 Key 对象；功能行两份（topRows：不带 / 带最右边的 ⋯，⋯ 是特殊键 MENU_BUTTON_ID = 303）；哪些键算功能键（functionKey；数字键盘另有 numpadFunctionKey，"-" 在那里算运算符），主题按它分主次上色；一层的全部行（rows / layers，KeyboardPanel 和 KeyboardPlan 共用）；小红点嵌在哪（stickPoint：字母层 g|h、v|b 的缝和 a–l / z–m 两行交界那一点，只有整块）；小红点状态下底行、快捷行让出空位（Row 的 before / after：底行 ?123 , 空格（缩短，STICK_SPACE 1.5 份、最短 STICK_SPACE_MIN 1 份，只有它跟着空位伸缩）│ 空位 │ . ↵；spread：快捷行按个数分到两边 spreadLeft，place 的 spread 为假时照旧铺满；holeUnits / holeTop / holeBottom 给 KeyboardPlan 算空位）；所有布局下会显示的键（capKeys，KeyboardPanel 照它建键帽） |
| `src/dev/douhua/KeyboardPlan.java` | 键盘界面怎么摆，纯 Java（离线测，KeyboardPlanTest；实体键盘那部分 FixedLayoutTest）：实体键盘（第 1B 批：fixedFits 一格 ≥ 32dp 才显示、fixedDefaultRect 按"行高 ≈ 键宽 × 1.1"的自然比例（守调大小的约束：不比 fixedMinWidth 窄——一格 ≥ 32dp、不比 MIN_HEIGHT 矮）、planFixed：不分裂、触控板同合并、⋯ 在触控板顶上或 Esc–F1 的槽里、小红点在 G / H / B 中间 + 空格那一排的三个鼠标键；红点嵌在键里多大 / 嵌不下放哪和豆花布局共用 placeStickAt）；布局（自动 / 分裂 / 合并）轮换、分不分裂（键太窄不分）、分裂 / 合并时键盘占哪块、触控板放在空得最多的一侧（太小不放）、宽窗口没调过大小时的默认位置（触控板显示且放得下才三分之二宽）；⋯ 菜单的几何（menuTop：⋯ 放触控板顶上正中、padMenuTop：主界面的 ⋯ 放不下也照样放、menuPosition：菜单弹在哪不出屏、menuGrid：项多窗口矮时先压矮再分列、adjustBarTop：调整栏放上还是下、barButtonWidth：调整栏按钮宽度）；分裂时对称调大小的几何（左半"格子"、默认格子、fitSplit 按窗口守约束、dragSplit 拖法和约束）；指点三选一（POINTER_PAD / STICK / NONE，nextPointer）：小红点照真的 ThinkPad 键盘摆（2026-11-01）、固定合并（不管"布局"）——三个鼠标键铺满底行、快捷行在中间让出的空位（stickHole：以红点为中线对称、空格尽量长、三个键各 ≥ 44dp，放不下就空格最短、三个键最窄 32dp；holeKeys 铺法，Plan.hole / keys / middleKey；快捷行分到两边后每个键连缝 ≥ 32dp 才也让位、三个键跨两行，不然只占空格那一排，shortcutsSpread / Plan.spread），红点在 KeyRows.stickPoint（placeStick：嵌在四个键的交界、挨着的键被和红点同心的圆切掉一块，stickRadius 按每层每个键被切掉的面积 cutShare（精确算）、键上的字躲不躲得开 labelShift 定多大，Plan.cut 是切口半径；嵌不下放键盘外面上 / 下 / 旁边），中键窄、左右键宽、中键对着红点（middleKey，三键条也用）；触控板的三键条（cutBar，上沿 / 下沿，主界面也用）、⋯ 放哪（Plan.menuArea / menuMargin；没有触控板时回功能行）；离页面边的距离可以各边不一样（Sizes.edgeL / T / R / B、withEdges，menuTop / menuGrid / menuPosition / adjustBarTop 各有带 {左, 上, 右, 下} 的版本，屏幕边缘留白用） |
| `src/dev/douhua/KeyFingers.java` | 键盘面板自己收触摸时每根手指按的是哪个键（按 pointer id 记，抬起只松自己那个）；按下定键 = 包含的键优先、缝里交给 TouchRouter.nearestKey（几块范围时只在落进的那一块里分），落在洞（嵌在键里的小红点——REGION_STICK 按内切圆认；小红点状态下让给三个鼠标键的空位）里不算任何键，纯 Java（离线测） |
| `src/dev/douhua/KeyboardScreen.java` | 键盘界面：可调大小位置的键盘 + 小触控板占满整页（没有工具栏那一行）；两块键盘面板（豆花布局 / 实体键盘，第 1B 批）都在、同一时刻只显示一块（face：选了实体键盘、这个窗口放得下——KeyboardPlan.fixedFits——才显示它，不然暂时显示豆花布局，换了 post Listener.onKeyboardFaceChanged），实体键盘照 KeyboardPlan.planFixed 摆、自己记指点和大小位置（setFixedWanted / setFixedPointer / setFixedRect，调整模式拖的是它那份）；按键放大提示按 view 或按面板里的矩形定位（PreviewHost.showKeyPreviewAt）；⋯（触控板顶上正中、不带框）、调整模式的"完成 / 恢复默认"栏由它摆（视图由 MainActivity 建）；菜单本身在 MenuLayer，贴着这里的 ⋯ 弹出（menuAnchor）；每次量尺寸按 KeyboardPlan 摆；调整模式由 App 自己收触摸，分裂时两半对称着拖（pickSplitHandle，按右半时左右位移镜像）；指点选小红点时摆红点（TrackPointView，嵌在键里时只画红帽，键自己切口）和铺满底行、快捷行空位的三个鼠标键（MouseKeys 的铺满模式；快捷行太挤不让位时只铺底行那段），量键盘之前把空位和红点告诉面板（KeyboardPanel.setHole / setStick）；触控板开着三键条时从上沿 / 下沿切一条（同一组 MouseKeys）；屏幕边缘留白时整屏被往里收，离各边的 20dp 少留收进去的那么多（setEdgeInsets） |
| `src/dev/douhua/MenuLayer.java` | ⋯ 菜单，主界面和键盘屏共用一份（根视图最上面一层、铺满整页；屏幕边缘留白时和别的页一样往里收，离各边少留，setEdgeInsets）：盖住整页的"点外面" + 菜单底板（项是它的子 view，只排显示着的，KeyboardPlan.menuGrid 排、先排满第一列）；贴着当前那一屏的 ⋯ 弹出（Host.menuAnchor + KeyboardPlan.menuPosition）；⋯ 那一支单独重新布局时由 MainActivity 在根视图的布局监听里再 place 一次 |
| `src/dev/douhua/Style.java` | 共用样式（边框、按下 / 高亮、触控板角上的小按钮——small 的 onImage：触控板上有背景图时平时垫不透明的触控板底色）；颜色从当前主题取（setTheme，MainActivity 设），设置页预览用带 Theme 参数的那几个；键帽（整屏键盘、数字键盘）走 keyButton / keyLook（边框跟"按键边框"开关；缩到最小字号还放不下的键名末尾省略"…"），其它按钮走 button / look（一直有边框）；sp 换像素（Style.sp，跟着系统字体大小）；键帽分主次：keyLook / look 带 fn（功能键的颜色；高亮时照旧是普通键帽底色 + 强调色的边）；被小红点切掉一块的键帽（keyLook 的 cut → CutCap：圆角矩形减去圆，底色、边框沿着圆弧走，颜色同别的键帽） |
| `src/dev/douhua/Theme.java` | 主界面的十八套配色（设置 → 外观；ALL 先深色后浅色）：每套写几个底子、其余按规则推（灰字取"刚够 4.5:1"的、强调色当字用不够就推亮 / 推暗）；功能键的槽位 fnKey / fnText / fnPressed（底色掺强调色，到和键帽、页面色差 ≥ 8 为止，fnTint）；纯黑每个槽位照以前的常量写死（功能键和普通键一样）；对比度、CIE76 色差计算，纯 Java（离线测 ThemeTest）；小红点的红（stick：ThinkPad 红推到对页面、触控板底色都 ≥ 3:1，stickRed） |
| `src/dev/douhua/SettingsActivity.java` | 设置页：顶上固定一排标签（通用 / 触控板 / 键盘 / 按钮与按键 / 空中鼠标 / 手柄 / 外观，放不下横着滑），七页的内容每次都全建、只显示选中那一页（其它 GONE）；记住上次那一页（Prefs.settingsTab）；serviceIntent / tabIntent（带 EXTRA_TAB）打开时停在指定那一页（手柄页 ⋯ 菜单的"设置"停在"手柄"）；"手柄"页：预览 + 预设网格（最后一格自定义）、自定义那一节（选中自定义才显示：左手 / 面键排法 / 字样 / 右摇杆 / 肩键 / 扳机 / 中间三颗 / 摇杆按下、从某个预设重新开始）、摇杆 / 十字键 / 面键的手感（gp_*）、Moonlight 里建议怎么设；切页回顶时先停掉上一页的惯性滑动（selectTab）；"触控板"页的"角上的小按钮"四个（按 左上、右上、左下、右下 列），最后一节"小红点和左 / 中 / 右键"（灵敏度、轻点 = 左键、负惯性、三键条开关和上沿 / 下沿）；"键盘"页（第 1B 批，keyboardSection）：用哪块键盘（豆花布局 / 实体键盘）、实体键盘的语言 / 形状 / 尺寸（写键数）/ Windows · Mac 模式、主机那边要设成同一种布局的说明（桌面模式下一个按钮打开手机的实体键盘设置）、豆花布局的快捷行（从"按钮与按键"挪过来）；"通用"页最下面"开源许可"（licenses：res/raw/licenses.txt 的全文）；"按钮与按键"页第一节"自定义按键"（开关 + 开着时才列出的槽位，customKeys）；"外观"页主题之后是"屏幕边缘留白"（左右、上下两个滑块）、"触控板背景图"一节（PadBgSection，选图的 onActivityResult 转给它） |
| `src/dev/douhua/ThemeGrid.java` | 设置 → 外观的主题色块网格（一组深色或浅色）：每格一小块样子（普通键 A + 功能键 ⇧ + 一小块触控板）+ 名字，每行几格按宽度定（每格 ≥ 96dp），选中框用设置页自己的蓝 |
| `src/dev/douhua/ButtonStrip.java` | 自定义按键（主界面左边一列，原来的左侧按钮条；设置里打开，默认关），屏幕矮时自动分列 |
| `src/dev/douhua/PadArea.java` | 触控板 + 数字键盘的排布 |
| `src/dev/douhua/PadStack.java` | 主界面的触控板（连四角的小按钮）+ 顶上正中的 ⋯（KeyboardPlan.padMenuTop，三键条在上沿时在它正下方）+ 可选的三键条（KeyboardPlan.cutBar，onLayout 里按离窗口边多远切；48dp 高，中键窄、左右键宽（约 2.2 : 1 : 2.2、中键在正中），KeyboardPlan.middleKey）；离边 20dp 按窗口算，屏幕边缘留白不用另外管 |
| `src/dev/douhua/TrackPoint.java` | 小红点（模拟 ThinkPad TrackPoint），纯 Java（离线测，TrackPointTest）：手指离按下点的位移 → 速度（S 形曲线：死区、爬行、平台、陡升、封顶）× 灵敏度，负惯性，按节拍积分、余数留着；轻点 = 左键；中键按着时出滚轮（推哪边视野往哪边走、主方向）；颗粒 / 咔嗒；20 秒没事件暂停 |
| `src/dev/douhua/TrackPointView.java` | 小红点的样子：红帽 + 九个凹点（RoundPad.dots）；不压键时外面一圈淡圆（帽占 0.7），按着时圈换 focus 色；嵌在键里时（setEmbedded）只画红帽（半径 = 按得到的圆的半径 − 缝 / 2，键自己沿圆弧切口），按着时缝的里半圈描一圈 focus 色；isEmbedded（服务连着没接管时 MainActivity 让嵌在键里的红点一根手指都不收、全落到底下的面板上，面板按内切圆挖洞；放在键盘外面时整块吃掉） |
| `src/dev/douhua/GamepadSpec.java` | 手柄页摆哪些控件：一组参数（左手 / 面键排法 / 字样 / 右摇杆 / L1 R1 LT RT 各自有没有 / 中间 / 摇杆按下 / 偏好方向），15 个固定预设（PRESETS，⋯ 菜单里轮换、设置页网格同序，默认 Xbox）+ 自定义（Builder 从一个预设复制再改，encode / decode 存成一行，取值写名字、认不出的跳过、缺的照 base 那个预设）；每种排法 × 字样印什么（faceOutputs / faceLabels / outputLabel；N64、GameCube 的键帽固定），预设自己的肩键名（N64 的 Z；GameCube 左边本来没有的肩键也写了 ZL，不和左扳机 L 重名）只在字样没换时用，shoulderLabel 给设置页"肩键 / 扳机"开关写这一对在这一套里叫什么；按位置发（任天堂的 A 发东），纯 Java（离线测，GamepadSpecTest） |
| `src/dev/douhua/GamepadPlan.java` | 手柄页自动排（预设和自定义一样排），纯 Java（离线测，GamepadPlanTest）：宽 ≥ 1.3 × 高横排（两块对称的手区贴下沿、中间 Select / Home / Start + LS / RS、肩键贴上沿两角），不然竖排（手区在下半截）；一只手两个控件斜着错开（上靠外、下靠里）；放不下先缩缝、再按比例缩到最小值、再不放 LS / RS，还不行缩到最小值以下（fitted 假）；面键十种排法（faceCenters 给每颗圆心和直径，N64 的 C 键小一点、GameCube 的 A 大 B 小——按真机比例，不受 44dp 下限约束，各自的最小值 faceMin：C 键 / B 33dp、X Y 约 37dp）；一只手可以空着、两边肩键颗数可以不一样；输出控件的判定 / 画的范围（dp）、⋯、状态字 |
| `src/dev/douhua/GamepadTouch.java` | 手柄页的触摸模型，纯 Java（离线测，GamepadTouchTest）：单键（引用计数、滑出照样按着）、面键簇（最近的键、两颗中间一起按、回差、簇里滑动换键、滑出 24dp 全松）、十字键（8 方向斜向窄、5° 回差、只看角度）、摇杆（浮动中心、底座跟着走、死区 + 抗死区、推到边咔嗒）、N64 的 C 键（右摇杆推满，相邻两颗斜着、相反两颗回中，和右摇杆那根手指取远的）；键 / 十字键 / 扳机变了当场发、摇杆轴攒到一帧发；收尾一定全零 |
| `src/dev/douhua/GamepadConfig.java` | 手柄的手感参数（设置 → 手柄，Prefs.gamepadConfig 按 gp_* 读，存成整数：dp / 百分比 / 度）和 CMD_GAMEPAD_CONFIG 的编解码（length:u16 + 字段，多的跳过、少的用默认），纯 Java |
| `src/dev/douhua/GamepadScreen.java` | 手柄页（独立一页）：按 GamepadPlan 摆、GamepadPainter 画（看手柄报文画按下：本地分流的 + 服务 MSG_GAMEPAD 的合起来），顶上正中的 ⋯（MainActivity 建，按住 0.6 秒开菜单）、状态字；collectRegions 报 REGION_GAMEPAD（簇的范围在成员前面）；setSpec 按 key 比（内容一样的自定义不算换）；name：预设 / 自定义显示的名字（手柄页菜单、设置页共用）；强制从左往右 |
| `src/dev/douhua/GamepadPainter.java` | 手柄控件的画法（手柄页和设置页的预览共用）：面键圆、△○×□ 和 N64 的 C 键画形状、十字键、街机摇杆底座、摇杆、肩键 / 扳机 / 中间小长条；k = 一个 dp 画成几像素，minText = 0 时不写字（网格小图） |
| `src/dev/douhua/GamepadPreview.java` | 设置 → 手柄的预览：按 930 × 350dp（分屏下半边）摆、缩小了画（GamepadPainter，没按着的键），大预览写字、网格小图不写；颜色用设置里的主题 |
| `src/dev/douhua/GamepadGrid.java` | 设置 → 手柄的预设网格：每格一张 GamepadPreview 小图 + 名字，最后一格自定义；每行几格按宽度定、选中框用设置页的蓝（同 ThemeGrid） |
| `src/dev/douhua/MouseKeys.java` | 一排左 / 中 / 右三个鼠标键（选小红点时底行、快捷行让出的空位里，触控板的三键条）：中键窄、左右键宽（setMiddle，没设时平分）；铺满模式（setTiled，键盘里）三个键的 view 铺满空位、不留缝（报的区域也就铺满），键帽各缩进同键盘的键画；不写字，照真 ThinkPad：左右键下沿（靠用户那条长边）横着一道红线、几乎占满键宽，中键没有红线、上沿附近一排 7 个小点；按掩码画按下 |
| `src/dev/douhua/TrackpadView.java` | 只画底色、背景图（PadImage，可选）、边缘滚动带的细线、状态字和边框（颜色画的时候从当前主题现取）；状态字两边让出多宽（setStatusSide：主界面下面两个角有按钮时让过它们）；有图时状态字、⋯（setMenuChip，KeyboardScreen / PadStack 告诉它 ⋯ 在哪）下面垫不透明的触控板底色、细线下面垫 3dp 底条，边框最后画 |
| `src/dev/douhua/PadFit.java` | 触控板背景图怎么摆，纯 Java（离线测，PadFitTest）：五种显示方式（填满 / 完整显示 / 拉伸 / 平铺 / 原大小）、焦点规则（offset：要裁时焦点尽量在正中、有空余时照百分比）、吸附（snap）、平铺相位、按形状挑哪套（Settings.pick：分开设时高 > 宽用高的那套）、解码多大（decodeSize：短边到窗口最大长边、总像素 2²³、单边 4096）|
| `src/dev/douhua/PadImage.java` | 触控板背景图的 Android 部分：导入（后台拷原文件进 files/padbg/、限 50MB、ImageDecoder 解一张小图验证、换名、写 Prefs、删旧的；完了通知设置页——不在就留下结果码，下次 start 取走——和在前台的主界面）、显示用的后台解码（软件位图 + mipmap、代数号丢过时结果、文件不在就清 Prefs）、画（每块触控板一个 Brush，BitmapShader：平铺 REPEAT、其它 DECAL）；设置页预览的小图（loadPreview）、删文件（deleteAll）|
| `src/dev/douhua/PadBgSection.java` | 设置 → 外观的"触控板背景图"一节：三块预览（16:9 / 3:5 / 7:2，同一个画法）、从相册 / 从文件选（ACTION_PICK_IMAGES / ACTION_OPEN_DOCUMENT，不要权限）、移除、状态行、显示方式、焦点（缩略图上点 / 拖十字，吸附）、图片大小、不透明度、宽 / 高分开设 |
| `src/dev/douhua/Actions.java` | 统一的动作清单（自定义按键 / 角上按钮 / 手势 / 键盘最下面一排 / 音量键 / 空中鼠标页的格子共用；加动作只加一行；自定义组合键 id 形如 `combo:键id:标签`） |
| `res/values*/strings.xml` | 九种语言的界面文字 |
| `art/icon.webp` → `tools/make_icon.py` | App 图标原图（不带元数据）；脚本生成自适应图标的前景层 `ic_launcher_fg.webp` 和彩蛋大图 `egg.webp`（都在 `res/drawable-nodpi/`，换图后重跑，要 Pillow） |
| 彩蛋 | 设置页"通用"页最下面的版本号快速连点 5 下（每下间隔 ≤ 0.6 秒），弹出 egg.webp；SettingsActivity.version / showEgg。README 里不写 |
| `src/dev/douhua/Combos.java` | 自定义组合键：可选的键、拼 id、起名字、迁移上一版键盘快捷行的存储格式 |
| `src/dev/douhua/HardKeys.java` | 音量 + / − 的"点一下 / 按住"判断（做什么由设置里选的动作定），纯 Java（离线测）；蓝牙模式 App 自己用，桌面模式服务里的 HardKeyGate 用同一个；设了动作的键整个归豆花 |
| `src/dev/douhua/AirPointer.java` | 陀螺仪 + 重力 → 光标位移：斜基分解（左右 = 绕竖直轴、上下 = 绕手机自己的右轴、绕水平朝前拧不算）、固定灵敏度不加速、两级 1€ 滤波消抖 + 软死区（可选轨迹平滑、防抖半径），纯 Java（离线测） |
| `src/dev/douhua/CenterRunner.java` | 主线程上按固定间隔一帧帧发位移（只剩调试位移 DebugMoves 在用） |
| `src/dev/douhua/AirMouse.java` | 空中指针：按住移动 / 按住拖动 / 按住左键（按来源分开记）、陀螺仪按需开关、移动颗粒；回正中发一帧绝对坐标 |
| `src/dev/douhua/DebugMoves.java` | 只在可调试包里：`adb shell am broadcast -a dev.douhua.DEBUG_MOVES --es moves "dx,dy,ms;…"` 让手机经蓝牙发任意位移（要 DUMP 权限，adb shell 有），量回正中 / 加速用；另有 DEBUG_AIRLOG（空中鼠标日志）、DEBUG_KEYLOG（键盘触点日志，租期制）、DEBUG_KEYSCAN（主机实测扫描：一段键依次发给主机，每个后面跟空格和 Tab，经 MainActivity.onLocalKey；一次最多 12 个、只许出字的位置 + Shift / Alt，`--ez stop true` 停、按着的补松开） |
| `src/dev/douhua/KeyScan.java` | 主机实测扫描的纯逻辑：许发哪些键（allowed）、广播里的键怎么解析（parse，整段拒绝）、一段拆成哪几拍（键 / 空格 / Tab 各按下松开），纯 Java（离线测，KeyScanTest） |
| `src/dev/douhua/RoundPad.java` | 空中鼠标页的圆钮：圆 + 3×3 九个小点（像 ThinkPad 小红点，dots 键盘页的小红点也用），按住移动、点一下锁定移动 / 再点解锁 |
| `tools/air-log.sh` | 空中鼠标双日志：手机端 AirMouse 调试日志（DebugMoves 的 DEBUG_AIRLOG 广播打开，可改激光笔式发送间隔、临时换消抖参数）+ 电脑端 KWin 记光标 + 能读就 evtest 记原始报文，结果在 `.private/airlog/`。默认按终端提示随手画；`--trace` 是描红：浏览器全屏页（tools/airlog/trace.html，本机小服务 trace_server.py 切参数、收结果），三轮盲测三组参数，最后选最顺手的 |
| `tools/type-log.sh` | 打字测试记录：DebugMoves 的 DEBUG_KEYLOG 广播开键盘触点日志（租期 10 秒，每 3 秒续）+ logcat 收 `douhua-keys` + 电脑浏览器打字页（tools/typelog/typing.html，本机小服务 server.py，句子 sentences.txt），结果在 `.private/typelog/`；`--desktop`（旧名 `--dex`）用 adb reverse 把页面开在手机那边（外接屏 / 分屏） |
| `tools/type-analyze.sh` + `tools/typelog/` | 分析一次打字测试：analyze.py 对齐、误按率（按空 / 按错没改 / 按错改过）、每个键的落点偏移、电脑丢键核对（`--selftest` 在 test.sh 里跑）；KeyReplay.java 用真正的 TouchRouter 重放触点，核对和手机一致、比较缝分给最近的键前后 |
| `tools/air-analyze.sh` + `tools/airlog/` | 分析一次双日志：送达节奏（卡不卡、排不排队）；用记下的原始转动按参数网格重放真正的 AirPointer（Replay.java），算静止抖动、2Hz / 5Hz 以上的晃、速度落后、距离保留 |
| `tools/kbd-scan.sh` + `tools/kbd/` | 实体键盘布局第 1A 批（说明见 tools/kbd/README.md）。**主机实测扫描**：kbd-scan.sh（`--host` 手选主机系统、`--layout`、`--desktop` 用 adb reverse）起 scan_server.py（本机小服务，地址带随机口令，`--lan` 给 iPad 用），主机上开 scan.html 记每步的 e.key / e.code / 输入法组合，页面收完一段才让服务用 adb 发 DEBUG_KEYSCAN 下一段、失焦就叫停；compare.py 和生成的数据逐格比、逐条写前提成不成立（`--selftest` 拿生成的数据造假主机），结果在 `.private/kbdscan/`。**键帽数据生成**：gen_layouts.py（CLDR 43 osx / windows、本机 xkeyboard-config + libxkbcommon、AOSP KCM + Generic.kl → res/raw；原始文件缓存在 .cache/kbd/，按 sources.json 的 sha256 核对；`--selftest` 用 testdata/ 的小样本）、sources.json、catalog.json（手写：生成哪些布局）、check_tables.py（离线核对来源、不变量、抽查值）、kbdtable.py（共用：位置对照、文件读写、扫描计划） |
| `res/raw/kbd_*.txt` | 生成的键帽数据（勿手改，tools/kbd/gen_layouts.py）：kbd_mac / win / linux / android 每家一份（每个布局一段，每个键 普通 / Shift / 第三层 / 第三层+Shift 的字，死键 `\*`；Mac 每个布局分 ANSI 型 / ISO 型两段），kbd_catalog 是布局清单。只有美式、德语；第 1B 批的实体键盘读 kbd_win（Windows 模式）、kbd_mac 不对调那段（Mac 模式）和清单，linux / android 两份留着没人读 |
| `res/raw/licenses.txt` | 开源许可页的全文（设置 → 通用 → 开源许可）：Unicode CLDR（许可原文取自 ICU 73.1 的 LICENSE，和 CLDR 43 同一批发布，CLDR 43 自己那份没核）、AOSP、xkeyboard-config（版权行照 Debian xkb-data 的 copyright，去掉了邮箱）、BoringSSL、Apache-2.0 全文；scratchpad 里一次性脚本拼的，改了数据源要跟着改 |
| `tools/adb-device.sh` | 选哪台手机（-s → ANDROID_SERIAL → 只连一台就用它 → 多台时列出来让人选），start/stop-server、selftest、build --install 共用 |
| `src/dev/douhua/KeyboardSnap.java` | 调整键盘时的轻微吸附（分裂时 applySplit：贴边 / 两半合拢 / 上下边），纯 Java（离线测） |
| `src/dev/douhua/EdgeMargin.java` | 屏幕边缘留白，纯 Java（离线测，EdgeMarginTest）：哪几条边留、留多少（margins：窗口贴着屏幕边的才留，一个方向最多窗口的一半）、页面收进去以后"离屏幕边 20dp"在页面里还剩多少（edges）、设置的范围（0–48dp、步长 2、默认 8） |
| `src/dev/douhua/Prefs.java` | 设置键名和默认值 |
| `src/dev/douhua/Link.java` | App 端连接，断线每秒重连，收发消息；空闲 2 秒发保活（CMD_PING），连接超时 / 5 秒等不到 hello 报"服务没响应"；记下 hello 里服务的 APK 路径（serverApk）；回调全部经一个异步 Handler 到主线程（不排在界面重画的同步屏障后面） |
| `src/dev/douhua/DexDisplay.java` | 找外接屏（按键注入目标；类名是改叫"桌面模式"之前起的） |
| `src/dev/douhua/DisplayReceiver.java` | 服务叫醒它：接上外接屏弹通知（点开在手机屏上打开豆花），拔掉收回 |
| `src/dev/douhua/server/Server.java` | 服务入口：主线程只 accept，按 SessionPolicy 接 / 顶掉旧会话 / 回"忙"，每条连接一个 douhua-session 线程；`probe` 只画方块不点击；`grabtest` 独占 0.5 秒；`keys` 只读：用 getevent -i 列出输入设备、音量键能不能截、会截哪块 / 旁观哪块 |
| `src/dev/douhua/server/Session.java` | 一次 App 连接：建虚拟设备、发 hello、独占时机、把触摸交给 TouchRouter、把结果写到 /dev/uhid 和发回 App；10 秒没消息自己断开，可被新连接顶掉（kick）；小红点的节拍在单独的 douhua-tick 线程上（桌面模式 8ms、蓝牙转发 12ms，startTicks / stopTicks）；TouchRouter 的定时器交给 OneShotTimer；虚拟手柄（第一次用到才建 UhidGamepad、会话结束才关；CMD_GAMEPAD / CMD_GAMEPAD_CONFIG、MSG_GAMEPAD；两路合并 GamepadMux，接管时清 App 那路） |
| `src/dev/douhua/server/OneShotTimer.java` | TouchRouter 的一次性定时器（Output.schedule / cancelSchedule 的服务端实现）：再排取代上一次；撤掉 / 被取代之后，已经到点、卡在锁上的那次回调拿到锁先比令牌、过期了就不调（ScheduledFuture.cancel 撤不掉已经开始跑的任务），纯 JDK（离线测，ServerPartsTest 真起一个定时器线程） |
| `src/dev/douhua/server/SessionPolicy.java` | 新连接来时旧会话怎么办：没有 / 已收尾 → 接，沉默 ≥ 5 秒 → 顶掉，否则回"忙"，纯 Java（离线测） |
| `src/dev/douhua/server/GrabCondition.java` | 独占条件里的心跳（只有心跳和布局算，保活不算）和释放原因码，Session 调它，纯 Java（离线测） |
| `src/dev/douhua/server/TouchGrabLock.java` | 全服务一把锁：同一时刻只有一个会话能独占触摸屏，纯 Java（离线测） |
| `src/dev/douhua/server/RotationLock.java` | 方向锁定全服务一份（`wm user-rotation`，记原值、正常断开时恢复），纯 Java（离线测） |
| `src/dev/douhua/server/Shell.java` | 带超时的子进程（wm 5 秒、dumpsys input / am 10 秒），只用 JDK（离线测） |
| `src/dev/douhua/server/SystemServices.java` | 从 ServiceManager 现取系统服务代理（框架重启后旧代理全死），DisplayWatcher / Injector / DexFocus 共用 |
| `src/dev/douhua/server/LogThrottle.java` | 同一条错误限频（第一次记、之后每 60 秒一行汇总、恢复时一行），展开异常原因链，纯 Java（离线测） |
| `src/dev/douhua/server/TouchReader.java` | 独占并读触摸屏，poll 100ms 检查是否该释放；分屏时独占后先读空 Session 早开着的 plain 描述符（独占前按下没抬起的 → 一个独占事件都不读就放开，RACED；重复的按时间戳丢掉），读到独占前就按着的手指（MtDecoder.sawPreGrab）就放开（PRE_GRAB），全屏时认出过也清掉；drain / watchFor 不独占地读 plain 喂给 LiftWatch |
| `src/dev/douhua/server/MtDecoder.java` | 多点触控协议 B 解码，纯 Java（离线测）；手掌、丢数据报 cancel（默认当抬起），外部停止报抬起；sawPreGrab = 见过开始读之前就按着的手指（按帧判断，只能事后发现；sawPreGrabLift = 第一个事件就是抬起、已经被吞了），setSlot 接上独占前读到的当前 slot |
| `src/dev/douhua/server/LiftWatch.java` | 分屏透传接管前判断系统那边还有没有手指按着：plain 上见过按下没抬起 / 开头没看到但在动 = 按着；dumpsys 报按着时退避（150ms 起翻倍、封顶 1 秒），等够 1.5 秒、内核那边 1 秒没动静、数没变就当旧状态接管；内核那边见过按下 3 秒没动静就忘掉交给 dumpsys，纯 Java（离线测） |
| `src/dev/douhua/server/TouchSplit.java` | 分屏透传：每根手指归豆花 / 系统（窗口外）/ 系统（窗口里的手势带，注入不挪焦点）——按下时按 App 报的窗口 + 系统手势带 + 落没落在豆花的键 / 按钮上定，抬起才忘，纯 Java（离线测，PassthroughTest） |
| `src/dev/douhua/server/InjectStream.java` | 分屏透传：归系统的手指拼成自洽的触摸流（DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP，cancelAll = 带 FLAG_CANCELED 的 CANCEL、还按着的作废到抬起；手势带那根的 DOWN / POINTER_DOWN 带 NO_FOCUS_CHANGE 0x40），只吐纯数据；残留记录的格式（formatDown / parseDown）、清残留猜的集合也在这里，纯 Java（离线测，测试里照 input_verifier.rs 写了校验器） |
| `src/dev/douhua/server/Passthrough.java` | 分屏透传：读线程只改 InjectStream、排队，douhua-inject 线程调 Injector.motion；看返回值（被拒）、卡住 500ms、出错就判不可用；系统每收下改变按着集合的事件（含 DOWN）就把集合写进 /data/local/tmp/douhua-inject-down；DOWN 被拒先补救（按记录 CANCEL → 等 300ms 重发 → 有残留证据才猜单根、两根组合，每次最多 300ms、一个进程走一遍、猜中后这次的 DOWN 不补发）；收尾后注入线程只按系统实际收下的集合补 CANCEL；清不掉的屏进程级记下（DEAD_DISPLAYS） |
| `src/dev/douhua/server/Injector.java` | 注入按键（CMD_KEY、实体键补发）和触摸（motion，分屏透传；另一把锁、另一份代理）；代理失败就重取 |
| `src/dev/douhua/server/TouchDevice.java` | 从 `dumpsys input` 找屏幕对应的触摸屏和 RawToDisplay 矩阵，顺带读系统眼里按着几根（pressed，"Last Cooked Touch: pointerCount"，悬停不算，读不出 -1），解析部分纯 Java（离线测） |
| `src/dev/douhua/server/ScreenRotation.java` | 物理屏坐标 → 当前方向坐标，纯 Java（离线测；方向 1 的公式取自真机） |
| `src/dev/douhua/server/Evdev.java` | 打开 / 独占输入设备（EVIOCGRAB，经 libcore 的 ioctlInt） |
| `src/dev/douhua/server/Uhid.java` | /dev/uhid 造 USB HID 设备的公共部分；带 Requests 的（鼠标）另开读线程 douhua-uhid 回应内核的 GET / SET_REPORT，关闭时先等读线程退出再关 fd |
| `src/dev/douhua/server/UhidEvents.java` | uhid 的 GET / SET_REPORT 请求怎么解、回复怎么拼（照 uhid.h 的 packed 布局），纯 Java（离线测，ServerPartsTest） |
| `src/dev/douhua/server/UhidGamepad.java` | 虚拟 USB 手柄 "Douhua Gamepad"（0x1209 / 0xD0A2，描述符 Hid.GAMEPAD_DESC），单独一个设备（并进鼠标的话安卓把鼠标也当手柄），没有读线程；关之前先写一帧全零 |
| `src/dev/douhua/server/GamepadMux.java` | 桌面模式 uhid 手柄的两路（服务分流 + App 本地分流）合并、接管时清 App 那路、没变不写，纯 Java（离线测，ServerPartsTest） |
| `src/dev/douhua/server/UhidMouse.java` / `UhidKeyboard.java` | 虚拟 USB 鼠标 / 键盘，只管发报文（描述符在 Hid，按键状态在 TouchRouter）；鼠标记着内核设的竖滚轮倍率（WheelOut；描述符 MOUSE_DESC_HIRES_V，横向一直 ×1） |
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
| `src/dev/douhua/ServiceSection.java` | 设置页"通用"页最上面一节"豆花儿服务"：Wi-Fi / 开发者选项 / USB 调试 / 无线调试 / 配对 / 服务逐项实时显示、直达系统页面的按钮、配对分步引导（找端口时可"取消配对"；公共网络别选"始终允许"）、自动启动开关和退避说明、无线调试是用户自己开着时的琥珀色提醒 + 跳转 |
| `src/dev/douhua/AdbKeys.java` | 豆花的 adb 密钥：RSA 2048，AndroidKeyStore 的 AES-GCM 密钥加密后存 files/adb/key.enc（包装密钥不在 / 对不上 = 作废，删掉当没配对；换机迁移不带它，见 res/xml/data_extraction_rules.xml）；自签证书 + SSLContext；导出 TLS 密钥材料走公开的 SSLSockets（API 31+） |
| `src/dev/douhua/AdbMdns.java` | NsdManager 找 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`，只认本机地址、127.0.0.1 上真在监听的端口 |
| `src/dev/douhua/ServerCommands.java` | 经 adb 执行的 shell 命令（pm grant、停旧服务、起服务），和 tools/start-server.sh、stop-server.sh 同一套，纯 Java（离线测，sh -n 查语法） |
| `src/dev/douhua/adb/` | 纯 JDK、不 import android.*（tools/test.sh 整包离线测）：Ed25519Group + Spake2（BoringSSL spake25519.cc 的 Java 移植，保留其 Apache-2.0 声明）、Hkdf、PairingAuth（AES-128-GCM）、AdbMessage（消息帧）、AdbPublicKey（adb 公钥格式）、SelfSignedCert（手写 DER）、AdbTls（TLS 1.3、互不校验证书）、PairingClient、AdbConnection（CNXN → STLS → TLS → OPEN shell） |

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
  否则系统一直连发、音量推到头。
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
  App 用主线程 Handler.removeCallbacks，单线程，撤掉就不会再跑。
- **滚轮量在引擎 → TouchRouter → 服务 / App 这一路是 1/120 格（Hid.WHEEL_UNIT）**，只在最后一步按主机设的分辨率倍率换算成计数（Hid.WheelOut 一处，UhidMouse、BtHid 都用它）：
  主机设了倍率后缩放、"滚轮向上 / 向下"、按格滚也全按倍率换算（不然慢 8 倍）；按格滚 + 主机没设倍率时报文和以前逐字节一样（HidTest 锁住）。
  带倍率的描述符只多一个 Feature 报告，输入报文的字节排布一个都不动（已有报告的排布不能动，没重新配对的主机照旧）。
  uhid 那份（Hid.MOUSE_DESC_HIRES_V）的 Feature 报告里只能有一个字段（竖向倍率）：不止一个时内核先 GET，设备带 NO_INIT_REPORTS 就直接放弃、一个请求都不发（HidTest 锁住 maxfield = 1）。
- **描述符带 Feature 报告就必须有人回应内核的 SET / GET_REPORT**（server.Uhid 的读线程）：不回内核干等 5 秒、退回 ×1，虚拟鼠标晚 5 秒才出现。
  **uhid 读线程退出前不关 fd**（Uhid.close 先等它，300ms 没退出就留给它退出时关）：fd 号会被马上复用，晚一步 poll 的读线程会读到新会话鼠标的请求、把倍率记错。
- 一次手势里出现过多根手指，就按多指处理到全部抬起。
- **小红点的节拍在所有收尾路径上都停**（节拍 = 手指不动也一直推光标，漏停 = 光标一直漂）：红点那根手指抬起 / 被取消、releaseAll（释放独占、断开、退后台、
  服务接管时本地分流收尾）、换参数（setConfig → clearSlots）都 stopTicks；服务收尾 ticker.shutdownNow 兜底；迟到的节拍 TouchRouter.onTick 看红点没按着就什么都不出
  （TouchRouterTest / TrackPointTest 锁住，startTicks / stopTicks 配对）。按着却 20 秒没事件就暂停（TrackPoint.IDLE_PAUSE_MS），这根手指再动接着。
  中键收尾（releaseAll）和被取消的抬起（TouchRouter.up 的 canceled：手掌 / 丢数据、TouchReader 收尾补报的、App 带 FLAG_CANCELED 的）都不补中键单击；
  三键条 / 键盘里（底行、快捷行空位）的左键和"左键"按钮、CMD_HOLD_LEFT 共用 TouchRouter.holds 一个计数。
- **键盘上会显示的每个键都有键帽**（实体键盘同样：每个显示的键有外框、有字，出字的键在字表里有这一行，锁着哪种修饰键都有字——FixedLayoutTest.caps 按
  布局 × 形状 × 尺寸 × 模式 × ⋯ × 让位 × 窗口扫；FixedPanel 的数组跟着键一起换、下标对得上，换键盘后还没重新量时也不越界）：KeyboardPanel 只按 KeyRows.capKeys 建键帽（showLayer 按 KeyRows.keys 取，取到没键帽的键就闪退），
  加新的行、新的布局变体（像小红点状态底行的 before / after）只改 KeyRows，两处规则在一起；KeyRowsTest.capKeysCover 锁住"keys() 给的每个键都在 capKeys 里"。
- **切换键盘不写任何 kb_ 开头的键，豆花布局的面板对象不销毁**（第 1B 批，用户 2026-11-01："豆花布局整个保留"）：⋯ 菜单 / 设置页换键盘只写 kbd_board，
  实体键盘的设置全在 kbd_ / kbx_ 下（Boards，BoardsTest 用随机序列锁住"kb_ 开头的键集合和每个值都不变、只写了 kbd_ / kbx_"）；
  KeyboardPanel 一直在，层（字母 / ?123 / Fn）、键帽缓存、锁住的修饰键照旧（两块面板都收 setModifiers）。实体键盘自己记指点（kbx_pointer）、大小位置
  （尺寸 × 指点 × 横竖屏），显示实体键盘时 ⋯ 菜单"指点"、调整模式、恢复默认改的是它那份；窗口太窄暂时显示豆花布局时改的是豆花布局的（显示着的那块）。
- **实体键盘的区域照豆花布局的规矩报**：只报显示着的那块（KeyboardScreen.face）；键段在前、键盘范围在后，范围每区块每行一块（FixedLayout.ranges，
  每段恰好落在一块同行同区块的范围里，范围不重叠，一行里没有键的格子——方向键上面那几格、↑ 两边、Mac 模式 Ins 那格——不在任何范围里）；
  分流和面板自己收触摸逐点一致（FixedLayoutTest.routing 扫两百多万个点）。跨两行的键按行切成同 id 的几段（ISO 回车、小键盘 + / Enter），画成一个形状。
- **修饰键掩码是 8 位，右边四个和左边四个一样能锁住**（实体键盘上右 Ctrl / 右 Shift / AltGr·右 Alt·右 ⌥ / 右 Super·右 ⌘ 都是单独的键，0xE4–0xE7，掩码 0x10–0x80；
  豆花布局只有左边四个）：只看低四位的地方就会漏一个一直按着的键。界面替服务解开锁住的修饰键一律走 Hid.modifierUsages（左右八个都点一下）；"Ctrl 按着"一律走 Hid.ctrl（左右都算）；
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
- **豆花窗口吞掉自己手柄的键和摇杆事件**（MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent，按厂商号 + 产品号 Proto.HID_GAMEPAD_PRODUCT；贴字面板开着也吞）：
  不吞的话没人处理的手柄键安卓换成后备键，B = 返回把豆花关掉、Home 回桌面，摇杆还会被合成方向键。
- 桌面模式两路写同一个 uhid 手柄（服务分流、App 本地分流经 CMD_GAMEPAD）：写的是合并结果（GamepadMux：键按位或、方向键取不回中的那路、摇杆取远的、扳机取大的），
  服务开始接管时清掉 App 那路，谁先谁后都不会把对方刚按的松掉（ServerPartsTest 锁住）；设备晚建好时补写的那帧也记成"上次写的"
  （GamepadMux.deviceCreated，不然之后的松开被当成没变、电脑上一直按着）。CMD_GAMEPAD、CMD_GAMEPAD_CONFIG 不算心跳。
- **触控板背景图只是画面**：只在 TrackpadView 里画（PadImage），区域上报、TouchRouter、服务、协议都不知道有它（上面那条"从触控板出发划出去照样动光标"不受影响）。
  拷贝、解码（大图一次几百毫秒）一律在后台线程，主线程只比文件名和设置（心跳在主线程，卡 3 秒服务释放触摸屏）。
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
  HID 键盘下系统看到的是真键盘，打中文靠手机输入法的外接键盘拼音（当时在三星 DeX 上用）。贴字面板保留成可选动作（语音输入有用）；
  用户开着"连着物理键盘也显示屏幕键盘"（show_ime_with_hard_keyboard=1），所以虚拟键盘在时手机输入法照样能弹。
- **键盘第二版（整屏键盘界面）**：第一版把 PC 键位塞在触控板下面，用户说难点。改成整屏键盘界面，字母区照 Gboard，
  修饰键 / 数字 / 快捷键各占一排；符号键和快捷键"自带修饰键"（id 高字节），一按就出，不用先锁 Shift / Ctrl。
  大小位置照 Gboard 的调整方式（拖四边、拖中间），横竖屏各存一份（Prefs.savedKeyboardRect）。
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
- **接管触摸屏时把键盘输入切到外接屏**（DexFocus，2026-10-13）：在手机屏上打开豆花，系统就把键盘输入的目标切到手机屏；
  豆花窗口在接着外接屏时故意不收按键，外接键盘（或豆花自己的虚拟键盘）打的字送到手机屏没人收，系统输入队列卡在这个键上
  干等十来秒（期间光标、点击全排队，看起来豆花的输入全没反应），然后 am_anr "Application does not have a focused window"。
  修法：Session 接管成功时对第一块外接屏调 IActivityTaskManager.focusTopTask（shell 有 MANAGE_ACTIVITY_TASKS），只切一次；
  分屏 / 小窗时不切（2026-10-30）：分屏下重新接管很常见，切了会把焦点从用户刚点过的另一边应用抢走，焦点交给用户点哪边来定；
  不让豆花窗口收键——收了字就留在手机上，进不了外接屏。`Server focus <屏>` 可单独验证。
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
  服务没接管、各键自己收触摸时（桌面模式下服务连着但没接管），缝里的触摸冒泡到 KeyboardPanel.onTouchEvent，用同一个 nearestKey 兜，只管第一根手指。
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
- **桌面模式的音量键由服务独占实体键设备**（2026-10-19；当时还截电源键短按，2026-10-21 撤了，见下一条）：桌面模式下（接着外接屏时）豆花窗口不收焦点、收不到音量键。
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
  桌面模式下实体键按住配了"左键"：CMD_HOLD_LEFT 经服务的 TouchRouter 按住（和屏幕上的"左键"共用计数）。
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
  现在服务开着就照桌面模式接管，只是报文发回 App 由蓝牙发（relay），独占条件、心跳、释放完全复用；模式一变（relay 变了）重新接管，
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
  先只读代码查了一遍（蓝牙转发、桌面模式、App 界面三条路，没实测）。服务端、nearestKey、触点日志、实体键线程都是微秒级或打字时不跑。
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
  （2026-10-30 整条工具栏去掉了，换成 ⋯ 菜单，见"键盘工具栏换成 ⋯ 菜单"；离屏幕边 20dp 的规矩留给了触控板上的 ⋯、菜单和调整栏。）
  只跑了离线测试（KeyFingersTest）和编译，没在真机上试。要看：打快了跟不跟手（先确认电脑没连手机的通话 / 媒体音频）；锁 Shift、换层后键帽和高亮对、
  换层后放大提示和按下的样子不残留；桌面模式下服务连着没接管时（或贴字面板开着、调整键盘时）两三根手指交替打、按在缝里，字不丢、键不粘；
  Ctrl + 双指缩放照常。量法：tools/type-log.sh 录一轮，电脑按下时间 − 手机 p 行时间（analyze.py 还没算这个）。
- **蓝牙模式下分屏 / 小窗时不让服务独占触摸屏**（2026-10-29，用户："有时候会在大屏分屏用……能不能自动检测这种情况下就停止屏蔽那些操作"）。
  **桌面模式改回分屏照样独占**（同一天主力机实测，用户的用法是 桌面模式、Moonlight 在上豆花在下分屏，第一版两种模式都不独占：触控板动不了光标——桌面模式下没有本地鼠标那条路；
  手指直接点到豆花窗口上，系统把键盘焦点给了豆花，虚拟键盘的字被豆花自己吞掉，打一个字之后全进不了 Moonlight。协调者事先猜用户分屏时用蓝牙模式，猜错了）。
  桌面模式分屏下另一边的应用、分隔条、系统手势还是碰不到（独占是整块设备），要让它们能用得把豆花窗口外的触摸注入回系统（同一天接着做了，见下一条"分屏透传"）。
  多窗口下看得见的 Activity 都是 resumed，以前照报 active，服务独占整块触摸屏，另一边的应用、分隔条、系统手势全碰不到。
  现在 active 拆成两个：localActive（本地分流能不能用，分屏里不变）和 serverActive（报给服务的，= localActive 且窗口占满整块屏）。
  占满 = 不在多窗口模式（isInMultiWindowMode：豆花自己在分屏 / 三星弹出式视图里）且 getCurrentWindowMetrics 的范围等于 getMaximumWindowMetrics 的
  （兜住被系统塞进框里的情况，那时区域和真实触摸本来就对不上）。重新判断：每次 pushLayout（布局监听：拖分隔条、改小窗大小）+ onMultiWindowModeChanged。
  服务端、协议不改：active 为假走现有的释放路径，实体键跟着 active 一起放开。蓝牙模式退回本地分流（只收豆花自己窗口里的触摸，没开服务时和以前一样）；
  本地分流的触点改成"窗口坐标 + 报区域那一刻窗口的位置"（以前用 getRawX）：小窗只挪位置不改大小时不重新布局、不重报区域，用 getRawX 会和区域错位。
  蓝牙模式的左右边排除区、吞返回在分屏里照旧（系统返回只从整块屏的边划，豆花那边的返回被吞，免得误关豆花）。
  别的应用以弹出式小窗浮在全屏豆花上面：豆花照样 resumed、窗口照样占满，只有 onTopResumedActivityChanged(false)。蓝牙模式（没接外接屏时）把"是顶层"也算进条件
  （ownsScreen），小窗浮上来就释放；用户点回豆花那一下豆花重新成为顶层、服务重新接管，这一下的触摸会被本地分流收尾掉（可能不算）。
  接着外接屏的蓝牙模式不加这个条件：窗口不收焦点，豆花本来就不是顶层。
  接管那一刻按着的键要先松开（桌面模式没接管时键盘 / 数字键盘是 View 自己收触摸、经服务的虚拟键盘按下的，一接管系统收不到这根手指的抬起）：
  onGrab 里 KeyboardPanel.releaseTouches + releaseNumpad 替它们发松开（不然系统里一直连发）。
  方向锁定锁的是整块屏：分屏 / 小窗时连上服务、点旋转按钮都不锁（旋转按钮只记下方向、提示），回到全屏时 pushLayout 补上。
  已知局限：先在全屏锁了方向再进分屏，现有协议没有"恢复方向"的命令，锁定留到断开（要撤得加命令、升协议）。
  状态字放不下时 TrackpadView 在末尾加省略号（以前居中画、两头一起裁，开头的原因看不见），分屏的说明也改短了。
- **桌面模式分屏透传**（2026-10-29，用户："分屏时我想直接用手指点 Moonlight 那半边、拖分隔条、用系统手势，同时豆花这半边触控板照常"；协议 17）。
  服务照样 EVIOCGRAB 整块触摸屏（不独占的话用户开着的 Tasker 无障碍会把虚拟鼠标打断，见"服务独占触摸屏"那条），分屏 / 小窗时按每根手指按下的位置分（TouchSplit）：
  豆花窗口里 → TouchRouter；窗口外（另一边的应用、分隔条、状态栏）、或者豆花窗口里贴着屏幕边的系统手势带（返回：左右；回桌面：下；下拉：上）→ 注入回系统；
  归属按下时定、抬起才忘。全屏一律照旧（系统手势挡住）。App 在 CMD_LAYOUT 里多报：整块屏的尺寸（以前报窗口大小——分屏横过来时 ScreenRotation 会转错，
  顺手修了）、占没占满、窗口在屏上的范围（layoutOrigin + 根视图大小，和区域同一套坐标）、系统手势带宽度（窗口的 WindowInsets systemGestures；
  不用 mandatorySystemGestures：它不含左右返回区）。蓝牙模式分屏照旧不让服务独占。
  **研究结论**（读 AOSP 源码，android16-qpr2-release 为主，对照 main / 14 / 15 / 16；三星改过的部分看不到，都是 AOSP 的行为）：
  ① 注入的触摸不经过 AccessibilityInputFilter（过滤器只挂在 InputDispatcher::notifyMotion 硬件那条路上，injectInputEvent 不调 filterInputEvent），
  以前"同一时刻只认一个设备"的冲突不会回来；分发层另有"同一窗口同一时刻一个设备"（InputState::shouldCancelPreviousStream，flag enable_multi_device_same_window_stream
  关着时）：手指点 Moonlight 会取消它的鼠标悬停，鼠标在 Moonlight 里按键 / 移进来会取消正按着的注入手指——只在同时用手指和鼠标操作 Moonlight 时，鼠标捕获时不冲突；三星上这个 flag 的值不知道。
  ② Android 16 起注入的触摸流有一致性校验（shouldRejectInjectedMotionLocked + input_verifier.rs），不自洽直接拒（injectInputEvent 返回 false、不抛异常），
  CANCEL 必须带 FLAG_CANCELED，校验状态只在屏幕被移除时清——所以 InjectStream 严格按规则拼、Passthrough 看返回值、每条退出路径补 CANCEL、开局清残留。
  ③ 手势导航（SystemUI EdgeBackGestureHandler、Launcher3 TouchInteractionService 的 InputMonitor）、下拉、分隔条在 AOSP 里都不区分注入事件；
  注入的 DOWN 点到 Moonlight 会把键盘焦点给它（正好）；灭屏时注入的触摸被丢掉。**三星 One UI Home / SystemUI 会不会忽略注入事件不知道**：
  `adb shell input swipe` 走同一条路、同一个设备号 -1，可以零成本先试，它都触发不了手势的话透传也不行。
  ④ 参数：IInputManager.injectInputEvent 异步模式（等结果的模式会被卡住的目标应用拖住）；SOURCE_TOUCHSCREEN、每根 TOOL_TYPE_FINGER、手指编号 = slot、
  坐标 = 当前方向的屏幕坐标（和区域同一套）、displayId 反射 MotionEvent.setDisplayId、公开的 14 参数 MotionEvent.obtain、pressure / size 1。
  时间用读到那一帧时的 uptimeMillis（和 TouchReader 一样），**没照协调者说的用 evdev 时间戳**：我们自己打开的 fd 默认是墙钟（凭记忆，内核源码没取到），
  系统自己的 fd 才设成单调时钟（EventHub 的 EVIOCSCLOCKID）。
  ⑤ 复用 Injector（SystemServices 现取代理、失败重取），触摸注入另一把锁、另一份代理，卡住时不连带实体键补发。
  做法：读线程只改 InjectStream、排队；douhua-inject 线程注入，一次 500ms 没返回就判不可用（读线程每轮查）。不可用时分屏下不独占（GRAB_NO_PASSTHROUGH，
  状态字说明，触控板暂停）——不做"一半独占"：独占是整个设备，没有一半。
  App 那边：服务接管期间注入回系统的手指（在豆花窗口手势带里按下的）系统也会发给豆花窗口，设备号 -1（KeyCharacterMap.VIRTUAL_KEYBOARD）——
  桌面模式连着服务时整次手势吞掉（MainActivity.injectedGesture），不然边上轻点会点到按钮（副作用：桌面模式连着服务时 `adb shell input tap` 点不到豆花自己）。
  **审查后的修改（2026-10-30）**：
  - 焦点：按在豆花窗口里手势带的手指注入时带 NO_FOCUS_CHANGE（AOSP a16 InputDispatcher：DOWN 点到的窗口不是焦点窗口就请 WMS 挪焦点，
    不分是不是注入的；resolvedFlags 带 NO_FOCUS_CHANGE 且事件带 POLICY_FLAG_TRUSTED（注入的都带）就跳过；flag 值 0x40 取自 MotionEventFlag.aidl，
    input_verifier.rs 的 MotionFlags 认它、不会因此被拒）。没用"分屏透传时窗口 FLAG_NOT_FOCUSABLE"：影响截音量键、贴字面板，还会退回"没有焦点窗口、5 秒后豆花没有响应"。（2026-10-30 改成焦点被别的应用拿走后才不拿，见不变量里主窗口 FLAG_NOT_FOCUSABLE 那条。）
    **三星上认不认、Tasker 无障碍开着时事件被重新注入会不会丢掉这个标记，没核实**（真机看：边上轻点后打字还进不进 Moonlight）。
  - 手势带里的点落在豆花的键、按钮、"左键"、键盘范围上（TouchRouter.onTarget）就归豆花：以前一律归系统，轻点不是手势、被系统发回豆花窗口、又被 App 吞掉，
    键盘最左最右两列的外侧、最下一排的下沿、按钮条近一半、触控板四角按钮都会丢按键。只有落在触控板上、空白处的交给系统认手势。代价：从键、按钮上划不出返回 / 回桌面。
  - 独占开始时按在另一边的真手指（见不变量"分屏时带着系统眼里按着的手指不独占"）：dumpsys 的 "Last Cooked Touch: pointerCount"（格式照 AOSP TouchInputMapper::dump，
    没对过三星真机输出；读不出来不拦）+ MtDecoder.sawPreGrab。没用 EVIOCGMTSLOTS 查当时按着的：经 ioctlInt 发它，缓冲大于 4 字节会越界写 native 栈，等于 4 字节又读不到值。
  - 小窗被虚拟鼠标拖走（只挪位置、大小不变，根视图不重新布局）：decorView 的 OnGlobalLayoutListener 比位置、变了重报，心跳里也查一次兜底；
    注入的手指落在窗口中间（不在报过的手势带里）说明服务手里的范围过时了，App 马上重报（MainActivity.checkWindowMoved / insideUnbanded）。
  - 小窗的标题栏（拖动、最小化、关闭）按上边的手势带报、归系统：captionBar 插图（WM Shell 画的）和内容视图比 decor 低的那一截（decor 里自带的）取大；
    只在没占满时算。decor 里自带的那种是豆花窗口里的 View，注入回来的手指只会发到豆花窗口：落在那一截的整次手势交给 super.dispatchTouchEvent
    （MainActivity.injectedCaption），不吞，不然拖不动、关不掉。三星弹出式视图的标题栏到底是哪种没核对。
  - 清残留按落盘的集合（见不变量）：以前只发单根 CANCEL，系统里留着两根以上时永远清不掉，每个会话都判"重启才好"。
  **第二轮审查后的修改（2026-10-30，只跑了离线测试和编译）**：等抬起改成 plain 描述符 + LiftWatch（见不变量），dumpsys 退避、旧状态 1.5 秒后照常接管；
  "独占前就按着"反复接管 / 放开时限频、不重复记、不报 App；全屏时认出的独占前手指不留到切分屏（MtDecoder.clearSawPreGrab）；分屏时不切键盘焦点；
  decor 自带的标题栏不吞注入的手指；Passthrough 收尾后只补 CANCEL、清残留有时限、等 300ms 重发区分别的注入者、DOWN 也落盘、清不掉的屏进程级记下。
  **留给用户定、没做的**：① 从豆花那半边的边缘划返回：现在手指带 NO_FOCUS_CHANGE，焦点留在 Moonlight，返回发给 Moonlight（不再把豆花关掉）；
  要不要吞、或者豆花这半边干脆不透传左右返回带（方案 B：只留底边回桌面带）由用户定；
  ② 分屏时触控板左右边缘的单指滚动用不了（和返回手势带重叠，归系统）；
  ③ 下拉的通知栏 / 快捷设置，以及音量面板、权限弹窗、侧屏面板等系统浮层盖在豆花那半边时，那部分摸不到（触摸照样归豆花，轻点变成触控板点击发进 Moonlight）。
  要收起来，从 Moonlight 那半边往上划，或从边缘划返回。真要修：一根归系统的手指抬起后在定时器线程上问一次焦点窗口（IWindowManager 或带超时的 dumpsys window），
  系统浮层有焦点期间整块屏都算窗口外（或者干脆释放独占），查询绝不放在读线程里。
  服务被 SIGTERM / kill -9 时补不了 CANCEL（退出码 143 = 被信号直接杀，不跑 Java 的收尾），靠下一个会话清残留；清不掉只能重启手机。

- **分裂键盘**（2026-10-30，用户：豆花在大屏分屏下半边时整屏键盘那一页"所有键盘触控板都在下面，有点傻"，"其实键盘可以分裂，触控板放中间"；
  协调者给的方案用户说"没啥太大问题"，照做）。只改键盘界面，不改服务、不改协议、TouchRouter 没改（它本来就支持几块 REGION_KEYBOARD：
  hit 先按矩形找第一个包含的区域，命中哪块范围就在那块里找最近的键，候选只算整个落在那块范围里的键）。
  - 什么时候分：自动。KeyboardScreen 每次量尺寸时看键盘区（当时是工具栏、边上的空白以外；2026-10-30 起是整页）宽 ≥ 2 × 高（KeyRows.shouldSplit），拖分隔条、进出分屏、
    转屏、Fold 换屏都跟着变；没做回差（正好卡在门槛上来回拖会来回切）。**手机横屏全屏一般也够 2 倍**（20:9 的屏扣掉工具栏；2026-10-30 起没有工具栏，更够），所以横屏也分裂——
    以前横屏默认键盘占满、没有小触控板，现在中间有触控板；横屏存过的键盘大小位置分裂时不用（合拢后照旧）。用户确认：够宽就分裂，全屏横屏也一样（2026-10-30）。
  - 怎么分（照 Gboard 分裂键盘，KeyRows）：两半各占三分之一宽、中间三分之一给触控板，键盘占满整个键盘区的高（分裂时上方不再留触控板那块：
    窗口本来就矮，再切一块给触控板键就太扁，触控板挪到中间已经有一整块高的了）。
    字母层从中间切开，每半每行 5.5 份、键和整块时一样宽、错开的位置和整块时一样（肌肉记忆不变）：
    左 `1 2 3 4 5 _½` / `q w e r t _½` / `_½ a s d f g` / `⇧ z x c v`，右 `_½ 6 7 8 9 0` / `_½ y u i o p` / `_1 h j k l _½` / `_1 b n m ⌫`
    （第二行原来两头的半格空位留在外侧；切口那边的空位让 h、b 比 y 往右错半格，同整块）。
    功能行 `Esc Tab Shift Ctrl Alt | Super Caps Fn Del`；底行 `?123 , 空格 | 空格 . ↵`（连苹果设备时 🌐 在左半，左边的空格让出一份）；
    符号层、Fn 层、快捷行按个数对半分、奇数时多的放左边（符号层第四行 6 | 5，快捷行默认 9 个 5 | 4）。
  - 区域：键 + 两块 REGION_KEYBOARD（左半、右半各自的范围）+ 中间的触控板（REGION_PAD，左右各隔 8dp）。缝和空位只分给自己那一半里的键；
    键盘和触控板之间那 8dp 什么都不是（分屏透传时归系统）。面板自己收触摸时 KeyFingers.pick 的几块范围版本同一个规则，中间的手指不算任何键。
    每半的范围只到这一半最后一行有键的那一行下沿（KeyRows.halfBottom，分流和面板自己收触摸用同一份 KeyboardPanel.areas）：快捷行只有 1 个键时
    右半那一行是空的，范围盖到底的话按在那里会被 nearestKey 分给上一行的空格 / ↵（审查发现）；现在那块什么都不是。
  - 实现：KeyboardPanel 从 LinearLayout 改成自己摆放的 ViewGroup（所有键帽是直接子 view，KeyRows.place 算位置），分裂 / 合拢只改哪些键帽显示、
    重新摆，不重建 view；键位定义从 KeyboardPanel 挪到纯 Java 的 KeyRows（离线测）。同一个键两种布局用同一个 Key 对象 = 同一个键帽，
    高亮、按下、键帽缓存跟着走；只有分裂的两个空格是另外的键帽（份数不同）。分裂时两个空格 id 一样，服务转来的按下两个一起画按下（只是样子）。
    右半第二、三行最左边那一格（原来空着，让 h、b 对着整块时的位置）再放一个 g、一个 v（用户 2026-10-30："两边都放"，哪只手按都行），
    和两个空格一样是另外的键帽、发同一个键：按下时两个一起画按下；两根手指同时按两个副本时先抬起的那根就把键松开。
    KeyboardScreen.onMeasure 里判断、在量键盘之前调 KeyboardPanel.setSplit（这时改子 view 的显示只影响接着的这次测量）。（以前变了还 post 一个通知给 MainActivity 管"恢复默认"显示不显示，分裂能调大小后删了。）
  - 调整大小：分裂时位置大小固定，"调整大小"照样能进（为了换工具栏位置），键盘上盖一层说"分裂键盘不能调大小，工具栏位置照常能换"
    （kb_adjust_split_hint），不画拖把手、拖不动，"恢复默认"藏起来（恢复的是整块时的，分裂时看不出效果）；存的大小位置留给整块时用。
    没做分裂时的调整（两半宽度 / 中间多宽），要用户用过再说。（同一天用户要了，改成对称调大小，见"键盘布局按钮"一条最后。）
  - 打字测试工具（tools/typelog/analyze.py）每个进程只认最后一块布局（L 行），分裂时左半的触点找不到布局、退回按 hit / near 猜；要分析分裂键盘得先改它。

- **键盘布局按钮 + 触控板开关**（2026-10-30，用户："保留现在的自动，加个按钮，可以让它分裂，也可以让它合并；合并后把触控板放到一边；
  触控板是否显示也加个按钮，因为有时候那块充不满、就剩一条，根本摁不了，留着没用、看着还难受"）。只改键盘界面，不改服务、不改协议。
  摆法全在纯 Java 的 KeyboardPlan（离线测），KeyboardScreen 每次量尺寸照它摆。
  - （2026-10-30 这四个收进了 ⋯ 菜单，工具栏去掉，见"键盘工具栏换成 ⋯ 菜单"；下面这两条是当时的做法，"布局""触控板"的轮换和存法没变。）
    工具栏平时四个按钮：收起键盘 / 调整大小 / 布局 / 触控板。"布局"点一下 自动 → 分裂 → 合并 → 自动，按钮上写当前选的（"布局：自动"……）；
    "触控板"写"触控板：开 / 关"。两个都记在 Prefs（kb_layout、kb_pad），不分横竖屏；调整模式里这两个藏起来（一行照旧四个：收起、完成、恢复默认、工具栏位置），
    分裂时调整模式盖的那句提示改成"布局换成合并就能调"（kb_adjust_split_hint；后来分裂也能调了，改成调整提示）。
  - 布局：自动 = 键盘区宽 ≥ 2 × 高、而且分裂后一份（字母键连缝）≥ 32dp 才分裂（触控板开着按中间放触控板的三分之一布局算，关着按两半伸满算）；
    分裂 = 两半伸满时一份 ≥ 32dp 就分（手机竖屏约 36dp 够；再窄的小窗按合并摆，按钮照样写"分裂"）；合并 = 不分。
    32dp ≈ 5 毫米（手机竖屏整块键盘一份约 40dp），再窄拇指按不准。比原方案多的：**自动也看这个门槛**，宽而小的小窗（比如 500 × 240dp，一份约 30dp）
    以前会分裂，现在按合并摆。
  - 分裂时键盘占满键盘区的宽；高：宽窗口占满（同以前），窄窗口（只有强制分裂会遇到）照合并时键盘的上下沿（用户调过的高度，没调过竖屏是下面七成）——
    占满的话手机竖屏一行一百多 dp 高。触控板候选：两半中间（三分之一布局下一份 ≥ 32dp 才算）、键盘上方、下方，取面积最大的；不在中间时两半往中间伸、只隔 8dp
    （KeyRows.leftEnd 的 middle）。两块键盘范围伸到缝的中线（KeyRows.areaEnd / areaStart，中线那一像素归右半），缝分给各自那一半最近的键、同整块键盘的缝；
    缝只在各自那一半里分的规则不变，键的位置也不变。第一版这 8dp 什么都不是，加上两边键各 2dp 边距，t|y、g|h、v|b 之间约 12dp 按了没反应（审查发现，
    正好是双手交界最常按的那一列）。中间放触控板时范围照旧只到两半的边、中间那截什么都不是。触控板关着时两半同样伸满。
  - 合并：键盘照存下的大小位置；触控板放在键盘旁边空得最多的那块矩形——上方（整宽）、右边（整高）、左边（整高）、下方（整宽）四块里面积最大的，一样大按
    上 → 右 → 左 → 下（上方优先 = 竖屏和以前一样）。用户用"调整大小"把键盘拖到另一边，触控板跟着跑到空出来那一侧（拖的时候实时变）。触控板关着时空出来的地方空着、不响应。
  - 宽窗口合并的默认：没调过大小（Prefs 里这个方向没存）时，键盘区宽 ≥ 2 × 高、触控板设成显示、而且右边那三分之一放得下它（短边 ≥ 96dp）才靠左、占三分之二宽、满高，
    触控板落在右边；其它照旧（竖屏下面七成、横屏占满）。三分之二只是给触控板腾地方：第一版不管触控板开不开、放不放得下都是三分之二，右边那三分之一空着不响应
    （审查发现，正是用户嫌的"没用还难受"的那种空条）。所以没调过大小时开关触控板，键盘会在三分之二宽和满宽之间变（KeyboardScreen 没调过时每次量尺寸照 plan.rect 重设）。
    Prefs.keyboardRect 改成 savedKeyboardRect（没存过返回 null），默认位置由 KeyboardPlan.defaultRect 按当时的窗口给，不写进 Prefs——
    **已经调过的横屏位置照用，不动**；"恢复默认"清掉存的，回到按窗口给的默认。从默认位置开始拖，抬起时才存；抬起时位置和按下时一样（只点了一下、
    方向没判断出来、拖出去又吸回原位）就不存、也不算调过（KeyboardScreen 记按下时的 custom 和 rect）——不然在宽窗口里点一下，这个窗口的默认（三分之二宽）
    就存进横屏那一份，回到不宽的横屏全屏也只剩三分之二（审查发现）。
  - 触控板门槛：以前只要求宽、高都 ≥ 56dp（剩 60dp 高的一条也照放）；现在短边 ≥ 96dp（约 15 毫米：一根手指按上去还能来回划），不够就不放、不报区域。
    竖屏默认（上方约 240dp 高）、分屏下半边分裂（中间约 200dp 宽）都够。
  - 工具栏宽度（2026-10-30 工具栏去掉了；同一条规则留给调整栏的两个按钮，KeyboardPlan.barButtonWidth）：平时按钮一样宽、最宽 104dp、挤在中间；放不下时（四个按钮在手机竖屏要 480dp）一起缩，左右照样离屏幕边至少 20dp（edgeInset，和上下离边一样），
    字号本来就自动缩。KeyboardScreen.fitBar 在量调整栏前直接改按钮 LayoutParams 的字段（MainActivity.layoutToolbar 删了）。
    手机竖屏（360dp）一个按钮只有 64dp，法 / 德 / 意语的长名字和日文缩到最小 8sp 一行也放不下、只画出第一行（"Disposition :"，看不出选的是哪种）：
    调整栏按钮准许折两行（MainActivity.addBarButton 里 setMaxLines(2)，⋯ 菜单项也一样，自动缩字号会把行数算进去；Style.button 共用的一行不动）。按 Noto Sans 8sp 量过九种语言，
    折两行后每行最宽约 46dp（日文按字断、两行各约 36dp）；短名字照旧一行、字号不变。系统字体放大时没量。
  - 调整模式里键盘上的提示（kb_adjust_hint / kb_adjust_split_hint）居中折行（StaticLayout）：先按 15sp，超过三行再缩、最小 11sp，到了 11sp 还多就多折几行。
    以前一行到底按宽度等比缩、没有下限，手机竖屏强制分裂时德 / 法 / 西 / 意语的分裂提示缩到 5–6sp（审查发现）。
  - 切换时（按钮、窗口变了）：分裂 / 合拢、或者分裂时中间宽度变了（触控板开关），都走 KeyboardPanel.setSplit → dropTouches（面板自己按着的键松开、按下的样子 /
    放大提示收掉）+ 重新摆；区域随重新布局报上去（根视图的布局监听）。合并时开关触控板键盘不动、不收。
  - 触控板关着时状态字（"服务没开"之类、可点）也跟着没了；主界面的触控板还有。
  - ViewGroup 自己有 setLayoutMode，KeyboardScreen 的方法叫 setKeyboardLayout。
  - **分裂时对称调大小**（同一天，用户："分裂状态应该也可以调整大小啊，两边对称调啊"）。原来分裂时调整模式拖不动、盖一层"布局换成合并就能调"，现在能拖：
    - 存什么：左半的"格子" {左, 上, 右, 下}（键盘区的 0–1），右半是镜像；格子的右边量到两半中间那条线、连着半条缝（左半的键实际到 右 × 宽 − 缝 / 2），
      所以右 = 0.5 就是"两半只隔一条缝"，跟窗口多宽无关（分屏拖分隔条宽度一直变，存实际像素比例的话"合拢"会在别的宽度下变成差几像素的死缝）。
      Prefs.savedSplitRect（kb_split_land_* / kb_split_port_*），横竖屏各一份，和合并的 keyboardRect 互不影响。
    - 拖法（KeyboardPlan.dragSplit，按左半说；按在右半上左右位移先取反；按在哪一半看在两条内侧边中线的哪边（KeyboardPlan.onRightHalf），两半合拢时把手范围重叠，固定先判左半会把右半内侧边当成左半的、拖的方向反）：内侧边 = 两半一起变宽 / 变窄，中间（触控板）跟着变窄 / 变宽；外侧边 = 两半一起往里收、
      外面留空，或推回屏幕边；上 / 下边 = 一起变高变矮；拖任一半中间 = 一起上下挪，横向位移不要（两半对称，左右挪就不对称了；所以也不用拖中间时的方向锁定）。
      两半各画一个框、各一套拖把手，键盘区（连中间那截）压暗，提示字横跨两半写在中间（kb_adjust_split_hint 改成"拖任一半的边框两半一起改大小，拖一半的中间一起上下挪"）。
    - 约束：一半至少 5.5 份 × 32dp = 176dp（每个字母键连缝不窄于分裂的门槛，KeyboardPlan.minHalf）、两半不重叠（格子右边 ≤ 0.5）、不出界、
      高至少键盘区的 0.25（和合并一样，KeyboardPlan.MIN_HEIGHT）。窗口变窄、存的格子放不下时（KeyboardPlan.fitSplit，不改存的那份）先往中间伸（直到只隔一条缝）、
      再往外伸（直到贴边）；伸满都不够的窗口本来就不分裂（分不分裂的判断 shouldSplit 不看存的格子）。
    - 触控板：存过格子时两半照存的摆、不再"往中间伸"；中间那块（离两半各一条缝、和两半一样高）短边够 96dp 就放中间，不然照旧在上方 / 下方里挑大的、或者不放，
      中间留空。两块键盘范围：两半只隔一条缝时照旧伸到缝的中线（缝分给最近的键）；两半被拉开了（中间放着触控板或者空着）只到各自那一半的边，中间那截什么都不是
      ——不把几十 dp 外的触点分给 t / y（KeyboardPlan.Plan.seam → KeyboardPanel.setSplit 的 seam；规则 nearestKey 本身没动）。
    - 吸附（KeyboardSnap.applySplit，照合并的手感：只作用于显示位置、raw 照常累计、范围 10dp、吸上轻震）：外侧边吸到贴屏幕边、内侧边吸到两半合拢、
      上下边吸到键盘区上下边、拖中间吸上下边；两半永远居中，没有"居中"那一档。
    - 默认（没存过）：完全照原来的规则（各三分之一放触控板 / 往中间伸满、宽窗口满高、窄窗口照合并的上下沿），再折算成格子（Plan.splitCell），调整从它开始拖；
      没拖动（只点一下、拖出去又吸回原位）不存、不算调过。"恢复默认"调整模式里一直显示，只恢复眼下这种布局（分裂清 kb_split_*、合并清 kb_*），
      所以 MainActivity 不再需要"分裂变了"的通知（KeyboardScreen.Listener.onKeyboardSplitChanged 删了）。
    - 和方案不同 / 没做：存过格子后关掉触控板，两半不会自己往中间伸（中间空着，拖内侧边合拢即可；默认的才伸）；没加"刚好放得下触控板"的吸附点。

- **键盘工具栏换成 ⋯ 菜单**（2026-10-30，用户：键盘页工具栏四个按钮占的地方太大（一行 36dp + 离屏幕边 20dp）；协调者给了方案 A，用户选 A）。
  只改 App 的键盘界面，不改服务、不改协议（⋯ 键、菜单项、点外面都是普通的 REGION_BUTTON）。
  - 整条工具栏去掉，键盘区就是整页（KeyboardScreen 的 areaTop = 0、areaHeight = 页高）。"工具栏放上 / 放下"（Prefs.keyboardToolbarBottom、kb_toolbar_bottom、
    kb_toolbar_up / kb_toolbar_down 九种语言）删了，旧存储值不读。代价：键盘占满高（横屏、宽窗口分裂）时功能行贴着屏幕上边了，以前有工具栏那一截隔着。
  - ⋯ 在哪：小触控板摆出来时是触控板角上的小方块（48 × 40dp、暗字暗边框，同主界面触控板四角的小按钮），角由 KeyboardPlan.menuCorner 挑：每个角先离触控板的边 8dp，
    再往里推到离页面四条边都至少 20dp（拿手机的手指常压在边上，用户反馈过贴边的按钮误触），推完还要整个在触控板里；四个角里先比离最近那条边多远、再比第二近的，
    还一样按 右上、左上、右下、左下。结果：竖屏触控板在上（贴上边和两侧）→ 右下角（挨着键盘）；触控板在下 → 右上角；宽窗口触控板在右三分之一 → 左上角；
    分裂中间 → 右上角。放不下（页面太小）或者触控板关着 / 太小没摆 → ⋯ 是功能行最右边的特殊键（KeyRows.topRows(true)，十个键对半分，分裂时在右半最右，
    键宽、缝、nearestKey 照旧）；触控板角上有 ⋯ 时功能行不带它。KeyboardScreen.onMeasure 里判断、在量键盘之前调 KeyboardPanel.setMenuKey（同 setSplit）。
    调整模式里角上的 ⋯ 藏起来（功能行不跟着变，免得键挪位置）。
    **2026-10-31 改到触控板顶上正中、不带框**（用户："在右下角放着有点丑……放在触控板顶部居中不要带框，这个位置不咋按"）：KeyboardPlan.menuTop，
    按得到的范围 56 × 36dp、贴着触控板顶边、按触控板（不是页面）的中线居中，同样离页面四条边至少 20dp（竖屏触控板贴着屏幕上边时往下推 20dp）、推完要整个在触控板里，
    放不下照旧去功能行；只画 18sp 的暗字（Style.bare），按着时一块按下的底色；菜单和它居中对齐（menuPosition 的 center）。
    代价：横向边缘滚动开着时，顶边那条滚动带正中这一截归 ⋯（以前角上的小方块同样压着边缘带）。
  - 菜单豆花自己画（MainActivity.buildKbMenu：一列四项"收起键盘 / 调整大小 / 布局：… / 触控板：…"，最宽 220dp；2026-11-01 已改：两屏共用的 MenuLayer、键盘屏七项，见"主界面改版"；不能用 PopupMenu / Dialog：服务独占触摸屏时
    App 收不到手指）。位置 KeyboardPlan.menuPosition：⋯ 在页面右半就右对齐、左半就左对齐，先放 ⋯ 下面、放不下放上面、都放不下放空得多的那边再挪进屏，
    地方够时离边 20dp，不够少留，绝不出屏；挪进屏后还压着 ⋯（页面高约 400dp 以下、⋯ 在功能行上，审查发现）就横着放到 ⋯ 旁边（在右半放左边、在左半放右边，
    横着放得下才挪）；菜单跟着 ⋯ 走（换布局后 ⋯ 挪了，菜单跟着重摆）。
    开着时区域只报四项 + 菜单底板（KB_MENU_PANEL_ID，按了什么都不做）+ 一块盖住整页的"点外面"（KB_MENU_OUTSIDE_ID，排最后）：底下的键、触控板一个都按不到；
    分屏 / 小窗时"点外面"裁掉窗口四边的系统手势带（含标题栏，MainActivity.sendLayout 里裁，手势带要到那里才算出来）：带里本来是空白处，
    照透传规则交给系统认返回 / 回桌面 / 拖小窗，不然菜单一开整个窗口的手势带都归了豆花（审查发现）；系统没认成手势、注入回窗口的照常被吞，菜单留着。
    菜单项、底板不裁（真按钮，按在带里归豆花是既定代价）。
    "点外面"抬起时关菜单（按下就关的话，这次手势剩下的部分会按新报的区域走）。"布局"点一下轮换、菜单不关（好连点）；"触控板"开关后关菜单（⋯ 要换地方）；
    "调整大小""收起键盘"关菜单再做。返回（手势 / 导航键）先关菜单：开着时注册一个 PRIORITY_OVERLAY 的 OnBackInvokedCallback（target 36 默认走预测性返回，
    KEYCODE_BACK 不到 App），优先于蓝牙模式吞返回的那个；收起键盘、进调整模式也关菜单。状态字可点时，菜单开着 / 按在 ⋯ 上不算点状态字。
  - 三条触摸路径：服务接管（本机 / 蓝牙转发）和蓝牙本地分流都靠报的区域，按钮事件进 onButton（触控板上的 ⋯ KB_MENU_ID = 400、四项 401–404、点外面 405、
    完成 406、恢复默认 407、底板 408；功能行的 ⋯ 键 303 走 onSpecialKey）；服务连着没接管 / 没开服务时 View 自己收（localTouch，⋯ 键由 KeyboardPanel 自己收），
    "点外面"是 KeyboardScreen 里盖住整页的一个 View（叠在键盘、触控板上面；2026-11-01 起在 MenuLayer 里，编号改名 MENU_OUTSIDE_ID / MENU_PANEL_ID、数值不变），菜单底板 setClickable 挡住它。
  - 调整模式：临时出现一条"完成 / 恢复默认"（两个按钮挤在中间、规则同以前的工具栏，KeyboardPlan.barButtonWidth），离那条屏幕边 20dp，放在压着键盘少的那边
    （KeyboardPlan.adjustBarTop：竖屏默认在上，键盘拖到上面就跳到下面，键盘占满高时叠在上面）；叠在键盘上时压暗和框画完再画它一遍、盖在最上面。
    按下落在栏上的手势整个归栏（onInterceptTouchEvent 在按下时判断）；栏 setClickable，按在两个按钮之间的缝、两头的空处也归栏，不往下漏给叠在底下的键盘
    （不然调整模式里打出字，审查发现）；第一根手指在栏上时又落一根在栏外，截下来（栏上的按钮收到取消、不算点，这次手势什么都不拖）。
    栏也报区域（调整模式里服务不接管，报了无害）。
  - 和方案不同的地方："触控板"项点了关菜单（方案只说"布局"不关）；多了一块菜单底板区域（项与项之间的缝按了不当"点外面"）；
    菜单项按了不高亮当前状态（文字写着）；⋯ 字号 14sp（☰ 是 12sp，⋯ 太小看不清）。
- **"DeX 模式"改叫"桌面模式"**（2026-10-30，用户："不是专门给三星的"）：非蓝牙的那种连接方式（Prefs.btMode 为假：服务造虚拟鼠标键盘、注入按键）
  控制的是这台手机和它接的屏幕——外接屏（三星 DeX 只是其一）、或手机自己分屏（Moonlight 串流）。名字跟谷歌叫"桌面模式"（Desktop mode：安卓 16 起
  手机接外接屏的通用叫法，开发者选项里也是这个；三星的 DeX 是同一类东西）——第一版叫"本机模式"（和"蓝牙模式"对着叫），用户："按照谷歌的名字来"。
  手机自己分屏用 Moonlight 也走这个模式，名字不完全贴切，统一比贴切要紧。各语言：Desktop mode / デスクトップモード / 데스크톱 모드 / Mode bureau /
  Desktop-Modus / Modalità desktop / Modo escritorio / وضع سطح المكتب。泛指注入目标的"DeX 屏"改叫外接屏 / 正在输入的地方；
  专门说三星 DeX 的事实（三星自带触控板、DeX 设置里的"指针流向手机屏幕"、测试机没有 DeX 等）照旧写"三星 DeX"。
  只改文字：类名 DexFocus / DexDisplay、字符串键 conn_dex / status_no_dex、Prefs 键都不动；tools/type-log.sh 加了 `--desktop`，`--dex` 留作旧名。
  桌面模式不是三星专用，但只在三星上跑通过：TouchDevice 认触摸屏靠 dumpsys input 里的 RawToDisplay 矩阵（见"想做的功能"里"服务支持缩放面板的手机"）。
- **实体键盘布局第 1A 批：主机实测扫描 + 键帽数据生成**（2026-10-31；总方案在协调者的 scratchpad，design-keyboard-layouts.md）。
  先核主机那边最没把握的前提（iPad 把豆花当 ANSI 还是 ISO、iPad 是不是一律当美式、0x32、AltGr / Option 层、三星是不是 AOSP 的 KCM、Linux 是不是 xkb 默认变体），
  扫描结果定 1B 怎么做。不改服务、协议、描述符、键盘界面。
  - 主机系统**手选**（用户 2026-10-31："苹果非苹果设备不需要自动识别"），扫描 `--host` 自己填；1B 也照这条，不用 BtHid.isApple 去分主机系统。
  - 扫描：电脑上的小服务一段一段叫手机发（6 个键），页面按 Tab 分步、收完一段才要下一段、失焦 / 几秒没动静就叫停；每个键后面跟一个空格（死键看组合出什么）。
    一次只发一小段是为了页面没焦点时不把一长串字、Tab 打进别的应用（第一版想用 Enter 分步，改成 Tab：Enter 落进聊天窗口就是发消息）。
    键经 MainActivity.onLocalKey（同快捷行的组合键：蓝牙走本地分流、桌面模式走服务的虚拟键盘），锁着修饰键时不发（不然第一个键带着 Ctrl）。
    第三层：Mac / iPad 按左 Option（0x04），别的按右 Alt（0x40）；数据里这家这个布局没有第三层就不扫（美式在 Windows / Linux / 安卓上右 Alt 只是 Alt，
    Alt + 字母是菜单快捷键），扫第三层前页面先看普通层对得上八成以上才往下（主机布局没设对时停下问人）。
    地址带随机口令（`--lan` 时同网的人猜不到）；iPad 从局域网打开电脑上的页面，桌面模式照 type-log.sh 用 adb reverse。
  - 数据：Mac 用 CLDR 43 osx、Windows 用 CLDR 43 windows、Linux 用本机 xkeyboard-config 2.46（libxkbcommon 编译取字）、安卓用 AOSP KCM（GitHub 镜像，按 git blob 钉）。
    原始文件不进仓库，缓存在 .cache/kbd/（加进 .gitignore），按 sources.json 的 sha256 核对。这次的缓存是从调研时下载的那份拷进去的（AOSP 两个 KCM 的 blob id 和 GitHub 接口列出的对得上），
    **下载那条路没跑过**，新克隆第一次下载对不上会直接失败。
    Mac 的表每个布局两段：macOS 当 ANSI 键盘（不对调）/ 当 ISO 键盘（对调 0x35 / 0x64），扫描看哪段对得上就知道"互换"开关该默认开不开。
    xkb 的第三层按真按右 Alt 键算（不是直接设 Mod5：pc 里另有一个 LVL3 键，美式的 <> 键照样有第 3 级，会把美式算成有第三层）。
    KCM 照 AOSP 的匹配规则（后写的行为先比、多出来的 Alt 要被认领），所以安卓德语 AltGr+Shift+ß 是 ẞ、AltGr+Shift+Q 是 @（调研原型按字面找 'ralt+shift' 没找到、写成没字）。
  - 和方案不同：扫描页、服务都放 tools/kbd/（方案写的 tools/kbdscan/）；方案说的 Java 读法和 FixedLegendsTest 留给 1B（现在没人读数据），抽查值和不变量先用 Python 的 check_tables.py 锁住；
    "反查表"（字 → 位置，给组合键按主机布局发）没做（第 2 批之后才用）；数据格式的 `\uXXXX` 用代理对表示 BMP 以外的字（同 Java）。
  - 许可：数据文件头带来源、版本、版权行和许可名；三家许可全文还没进 APK，**发正式包前要补**（1B 的"开源许可"页）；CLDR 43 用的是哪一版 Unicode 许可凭印象，补全文时核对。
  - 审查后（同一天）：① 页面要段的竞态：sentTo 原来等 send 的回复回来才更新，adb 慢时这一段头一个 Tab 先到，页面又要一段（起点 cur + 1），手机顶掉旧的重发，
    后面整段错一位。现在发请求之前就按 scan_server.send_chunk 同一个规则自己算出这一段到哪（scan.html 的 chunkEnd），过时的回复（等回复期间暂停了、已经要了下一段）丢掉。
    只在 scratchpad 里用虚拟时钟 + 假手机（照 adb_lock 串行广播）跑过真正的页面脚本：adb 150ms 到手机、600ms 回复时以前 30 步错位 24 步，现在 0 步；没进 test.sh（要 node）。
    ② compare.py：死键只比死不死，"死键 + 空格"组合不出字（本机 Compose 里没有 dead_belowmacron + 空格这类）也算对，不再让第三层、"和同名数据一样吗"打 ✗；
    显示成"（死键，组合不出字）"。③ gen_layouts.py 自测的临时目录用完删（以前每跑一次 test.sh 在 /tmp 留一个 kbd-selftest-*）。
- **实体键盘布局第 1B 批：框架 + 美式 / 德语端到端**（2026-11-01；方案第 9 节"第 1B 批"，用户当天的决定和方案不同的以用户为准）。只改 App，不改服务、协议、
  描述符、TouchRouter、KeyFingers（键还是 REGION_KEY、id 就是用法码，用法码本来就 0–255 都发得出去），不用重新配对、不用重启服务。
  （审查后改了 TouchRouter 一处：Ctrl 左右都算，见本条最后；TouchRouter 两边共用，服务要换新代码才认右 Ctrl，装完重启服务。协议没变。）
  - **每块实体键盘自己有 Windows / Mac 两种模式，用户手选**（用户："第三方键盘都可以调一个 Windows 模式、一个 Mac 模式，Mac 模式跟 iPad 就是通用的"；
    "主机怎么读那是主机的事，不是键盘设计者应该考虑的问题……我已经给他提供 Mac 键盘的布局了，Mac 键盘也会发送 Mac 正常的那些东西"）。
    方案里的"按主机记住主机系统、第一次问、BtHid.isApple 自动认、苹果主机 1 左边和 Z 左边互换开关、按主机记住用哪块"都没做；实体键盘不看 appleHost（豆花布局照旧看）。
    Windows 模式 = PC 底行 Ctrl · Super · Alt · 空格 · AltGr（这个布局在 Windows 上有第三层才写 AltGr，美式写 Alt）· Super · ☰ · Ctrl，键帽字用 CLDR windows；
    连 Linux、安卓（桌面模式）也用它，kbd_linux / kbd_android 这批不读。Mac 模式 = ⌃ · ⌥ · ⌘ · 空格 · ⌘ · ⌥ · 🌐 · ⌃（Option 发 Alt 的用法码、Command 发 GUI，
    照真苹果键盘；🌐 同豆花布局的 🌐 切输入法；PC 尺寸的 Mac 版底行照 Keychron 这类，凭记忆），PrtSc ScrLk Pause 换 F13–F15、没有 Ins（那格空着）和 Menu、小键盘 Num 写 Clear，键帽字用 CLDR osx。
    **Mac 模式 ISO 的那两个键照真苹果 ISO 键盘发**：1 左边那个键（§ / ^）发 0x64、Z 左边那个键（` / <）发 0x35（PC 惯例正好相反）。依据：Linux hid-apple 驱动给苹果 ISO 键盘
    专门把 KEY_GRAVE 和 KEY_102ND 对调回来（apple_iso_keyboard[] 表、iso_layout 参数 "Swap the backtick/tilde and greater-than/less-than keys"，默认按国家码自动开；
    当天读了 torvalds/linux 的 hid-apple.c），说明苹果 ISO 键盘这两个位置报的码和 PC 惯例相反；macOS 不对调时 0x64 是 kVK_ISO_Section（§ 那个键，Chromium 的 dom_code_data.inc）。
    所以 Mac 模式的键帽字按发的码查 kbd_mac 不对调那段（`<id>`），`<id>.iso` 那段不用。macOS 把豆花当 ANSI 还是 ISO、iPad 怎么认，主机的事，没在真机上验证。
    回车左边那个键两种模式都发 0x31（不发 0x32，方案 §3 第 1 条）。
  - AltGr / Option 层：按数据先把字都配上、该发什么键发什么键（用户："以后测出不对再改"），没做扫描前提的开关。
  - 范围（方案第 1B 批原样）：美式（ANSI，和 ISO 版）、德语（ISO）× Windows / Mac × 全尺寸 / TKL。键数（测试按实际算出来锁住）：Windows TKL 87 / 88、全尺寸 104 / 105，
    Mac 各少一个（没有 Ins）。小尺寸、Fn 层、更多语言后面几批。
  - **只有一块实体键盘**（Boards，用户要的是"选哪块实体键盘：语言、形状、尺寸、Windows / Mac"和 ⋯ 菜单里"键盘：豆花 / 实体（…）"切换），方案推荐的"键盘列表"没做；
    存储键都不以 kb_ 开头（kbd_board、kbx_*），以后要做列表把 kbx_ 换成 kbx_<编号>_。实体键盘的大小位置按尺寸 × 指点 × 横竖屏各一份（换语言、形状、模式不挪），
    指点和豆花布局分开记。豆花布局的状态整个保留（不变量"切换键盘不写任何 kb_ 开头的键"）。
  - 几何（FixedLayout，u）：主键区宽度照主流机械键盘（底行 1.25 × 3 + 6.25 + 1.25 × 4，xkb pc105 是老比例，没用）；F 行和数字行之间 0.25u；Esc 和 F1 之间那 1u 是 ⋯ 槽。
    摆放（FixedPanel）：面板宽高各按 u 等分（不必等比），边界取整、相邻键共用边界、四边缩进 2dp（同 KeyRows.placeRow）。
    键盘范围每区块每行一块（方案 §5.3：整块一块的话 ↑ 正上方那格会分给同一行的回车 / '），伸到行 / 区块空隙的中线、外圈到面板边；一段之内的小缝（F4 | F5、空着的 ⋯ 槽）分给最近的键。
  - 放哪（KeyboardPlan.planFixed）：不分裂（用户："实体键盘不分裂"）；没调过大小时按自然比例（行高 ≈ 键宽 × 1.1）：窗口比它窄就满宽、贴底，上面给触控板；
    比它宽就满高、按自然宽度，触控板开着且右边放得下就靠左、右边给触控板，不然居中（**和方案不同**：方案只写了靠左；放不下触控板或指点不是触控板时居中，不留一条空着的边）。
    触控板同合并时的四边挑大的、可以切三键条；⋯ 在触控板顶上正中，放不下就是 ⋯ 槽里的键（同一个 MENU_BUTTON_ID）。调大小同合并的拖法，最窄到一格还有 32dp。
  - **窗口太窄（一格 < 32dp，所有手机竖屏、内屏左右分屏的一半）这个窗口里暂时显示豆花布局**（方案推荐、用户照推荐定）：KeyboardPlan.fixedFits 只看整页宽，
    选的还是实体键盘，⋯ 菜单写"键盘：实体（这里太窄，暂用豆花儿）"，菜单里的"布局""指点"是豆花布局的；横过来 / 拖宽了量尺寸时自动换回（KeyboardScreen.onMeasure 换面板、post onKeyboardFaceChanged）。
  - 键帽（FixedKeyboardView 整块自己画，FixedPanel.glyphs）：照实体键帽（用户照方案推荐定）——字母一个（跟锁住的 Shift 换大小写）、数字标点上 Shift 下普通、第三层右下角小字
    （被小红点切到的键挪到离切口最远的角）、死键用强调色（activeText）；锁着 Shift / 第三层时会出的那个字照常、别的压暗（四成）。修饰键左右八个都高亮；第三层在 Mac 模式是任一 Option、
    Windows 模式只认右 Alt。私用区的字（Mac 美式 Option+Shift+K、德语 Option+Shift++ 的苹果标）不画。放大提示照锁着的修饰键会出的字，功能键、修饰键、方向键、小键盘不放大。
    主次键帽照 KeyRows.functionKey（扩了：0x64 算标点、小键盘数字和小数点算普通键；豆花布局里没有这些用法码，结果不变）。
  - **小红点在实体键盘上**（用户："指点杆的位置是不一样的……要重绘的那个键的边缘，也不是那几个键了，因为电脑键盘不是对称的；在这种状态下，三个按键要占用的主要就是空格行了，因为没有自定义行"）：
    红点在真 ThinkPad 的位置——横着 G|H 那条缝（= B 的中线，最下一排右错半个键），竖着在 G、H 键帽的下沿（真机圆心在这条线上、比两排缝的中线略高，thinkpad-real 调研）；
    多大、嵌不下放哪和豆花布局共用 KeyboardPlan.placeStickAt / stickRadius（实体键盘只有一层，每个键按字母层的 16% 门槛，最小 32dp 时按 25%），切到哪些键是实际碰到切口圆的
    （KeyboardPlan.cuts；扫过的窗口都是 G 的右下、H 的左下、B 的上沿正中，V、N 不切，同真机），画法用同一个 Style.CutCap，面板自己收触摸时按同一个内切圆挖洞。
    B 的上沿正中被切成半圆，所以红点多半是最小的 32dp（Fold 外屏横屏 TKL、内屏横屏全尺寸），宽的窗口才大一点（内屏横屏 TKL 34、1800 宽 64），放外面的只有一格不到约 35dp 的时候。
    三个鼠标键占空格那一排的中间（FixedPanel.stickHole，规则照豆花布局：以红点为中线对称、空格两边各剩最长 1.5 份 / 最短 1 份、三个键各 ≥ 44dp，不够时最窄 32dp），
    空格切成左右两段（同一个键，两个键帽），中键正对红点（KeyboardPlan.holeKeys）；红点在空格里偏左（3.75–10u 里的 6.75u），右边那段多 0.25u。
  - 设置页新开"键盘"标签（触控板后面）：用哪块、实体键盘的语言 / 形状（德语只有 ISO）/ 尺寸（写键数）/ 模式，"主机那边要设成同一种布局"的说明（Mac / iPad、Windows 里叫什么，
    清单里的 CLDR 旧名），桌面模式下一个按钮打开手机的实体键盘设置（ACTION_HARD_KEYBOARD_SETTINGS，打不开退到输入法设置）；豆花布局的快捷行从"按钮与按键"挪过来。
    布局名字：美式、德语两个写进 strings（kbd_lang_*），以后的用 Locale 自己翻（FixedLegends.LOCALE_TAGS，第 2 批挪进 catalog.json）。
  - 开源许可：设置 → 通用 → 开源许可，res/raw/licenses.txt（CLDR 的 Unicode 许可、AOSP、xkeyboard-config、BoringSSL 的声明和 Apache-2.0 全文）。
    Unicode 许可原文取自本机 ICU 73.1 的 LICENSE（和 CLDR 43 同一批发布、版权年份 1991-2023 对得上），CLDR 43 仓库里那份没核；xkb 的版权行照 Debian xkb-data 的 copyright，
    去掉了邮箱（隐私扫描会拦，许可要的是版权声明）。法律上够不够我判断不了。
  - 没做（方案 1B 里有、用户的决定去掉的，或者推到后面）：主机系统 / 按主机记住 / 互换开关；键盘列表；"键帽：只画会出的字"那个选项；桌面模式检测键帽和手机系统的布局对不上（第 2 批）；
    组合键按主机布局反查（第 2 批之后）。打字测试工具（analyze.py、KeyReplay）不认几块范围、同 id 的几段，实体键盘分析不了。
  - **审查后的修改（同一天，只跑了离线测试和编译）**：
    ① 右边四个修饰键（右 Ctrl / 右 Shift / AltGr·右 Alt·右 ⌥ / 右 Super·右 ⌘，0xE4–0xE7，掩码 0x10–0x80）在实体键盘上也能锁住，界面替服务解开锁住的修饰键的两处
    （收起键盘回空中鼠标页 unlatchServiceModifiers、进手柄页 unlatchDesktopModifiers）原来只看左边四位：锁着 AltGr / 右 Ctrl 收起键盘，之后空中鼠标的每次点击都成了 AltGr+点击；
    锁着右 Ctrl 进手柄页，Moonlight 一直收到右 Ctrl 按着。改成 Hid.modifierUsages（左右八个都点一下，HidTest 扫 256 种掩码点完都切回 0）。
    ② "Ctrl 按着"（不起惯性、转着的停、连续模式照旧按格）原来只看左 Ctrl（Hid.MOD_CTRL）：锁住右 Ctrl 后边缘一拨照样起惯性 = 停不下来的缩放，连续滚动开着时一小段一小段地缩放。
    改成 Hid.ctrl（左右都算），TouchRouter.applyModifiers、setConfig 两处（服务端代码，要重启服务），TouchRouterTest.spinNotWithRightCtrlLatched 锁住。
    ③ 字表读不出来（照理不会；以后加布局时 kbd_mac 缺某一段也是这种）时 FixedData.layout 原来照样拼一块，键盘页显示一块出字的键全是空白的实体键盘，和类注释说的"照样显示豆花布局"相反。
    改成照注释做：FixedLayout.forLegends 字表是 null 就不拼、返回 null，KeyboardScreen 拿不到布局就显示豆花布局；⋯ 菜单写"键盘：实体（数据读不出来，暂用豆花儿）"（新字符串 kbd_menu_no_data，九种语言），
    设置 → 键盘那一节标红一行 kbd_no_data（以前只在清单读不出来时有）。
    ④ 又宽又矮的窗口（高约 220dp 以下，比如 780 × 170dp 的弹出式小窗）按自然比例摆，一格只有约 25dp（fixedFits 只看整页宽，照样显示实体键盘），调大小时第一下还会跳一截
    （拖边的最窄 = 一格 32dp，比默认还宽）。改成默认位置守调大小的约束（KeyboardPlan.fixedMinWidth，KeyboardScreen.minWidth 也调它，一份规则）：不比"一格 32dp、至少 0.4 宽"窄，
    这种窗口里行比键宽矮（窗口就这么高）；fixedFits 没加高度的门槛——退回豆花布局的话七行更矮。顺带同一类：又窄又高的窗口（平板竖屏 800 × 1280 全尺寸）按自然比例不到 MIN_HEIGHT（0.25）高，
    拖上边第一下也会跳，默认高度改成至少 MIN_HEIGHT。存过的大小位置不动（用户自己调的，窗口变了照旧可能不满足，同豆花布局）。FixedLayoutTest.defaultsKeepDragLimits 扫约 12 万种窗口锁住。
    ⑤ Windows 模式美式（没有第三层，右 Alt 写 Alt）锁住右 Alt 时，键帽照样当 AltGr 换到第三层，整块键盘的字全压暗（锁左 Alt 什么都不变）。改成第三层只认 Mac 的任一 Option、
    Windows 有 AltGr 的布局的右 Alt（FixedLayout.altGr，FixedPanel.layer）；美式锁右 Alt 和锁左 Alt 一样不换层、放大提示照普通层。FixedLayoutTest 原来把"锁右 Alt 字母压暗"写成了期望值，改了。
- **主题（外观）**（2026-10-30，用户：键盘和触控板都是纯黑底 + 暗灰边框，"难以分辨"，要像 Gboard / 三星键盘那样能上色；固定配色"参考再多一点，
  不只是其他键盘的调色，而是比较 match 比较好看的颜色搭配，量不要太大"）。设置页加一节"外观"：预览（一行键 + 一小块触控板，切了马上变）、
  主题九选一、"按键边框"开关（Gboard 同名；开 = 键帽画边框，关 = 只靠键帽底色区分，纯黑 / 高对比的键帽和页面同色，关了就只剩字；
  锁住的修饰键、换层键关着也照画强调色的边。只管键帽（含数字键盘）：按钮条、空中鼠标页的格子、⋯ 菜单项、调整栏、贴字面板的按钮一直有边框，
  第一版连它们一起关了，纯黑 / 高对比下空中鼠标页的大格子看不出范围）、一句费电 / 烧屏的说明。存 Prefs（theme / key_borders），回到主界面 applyPrefs → MainActivity.applyTheme
  逐个重新上色（不重建界面；按钮条、空中鼠标页的格子 applyPrefs 本来就整条重建；触控板、圆钮、键盘屏自己画的东西画的时候现取）。设置页自己的配色不跟主题变。
  九套：纯黑（默认，就是以前的样子，ThemeTest 逐个槽位锁住）、深色（Gboard 深色）、Nord、Dracula、Catppuccin Mocha、Tokyo Night、Gruvbox Dark、
  浅色（Solarized Light）、高对比（照三星高对比键盘：黑底黄字黄边、边框 2.5dp、触控板白边）。配色方案的英文名各语言都不翻译，"纯黑 / 深色 / 浅色 / 高对比"九种语言翻。
  底子是协调者挑的，每套只写页面 / 键帽 / 字 / 强调色 / 触控板底和边（有的写了键帽边框），其余按 Theme 里的规则推（Spec.build）。在协调者的值上改了三处：
  深色的触控板边框 #5F6368 → #669DF6（灰的对触控板底只有 2.3:1，和键盘分不开）；浅色的键帽文字 #586E75 → #4A6068（原值在键帽上 4.4:1，不到 4.5）；
  高对比按下的底色写死 #4D4000（推出来的 #262000 几乎看不出按下）。对比度（ThemeTest）：除纯黑外每套键帽字 / 页面字 / 高亮字 / 状态字 / 角上小字 / 提示字 / 放大提示 ≥ 4.5:1，
  边框类 ≥ 3:1，触控板边框对触控板底和页面都 ≥ 3:1、和键帽边框不同色。纯黑保持原样（状态字只有 2.4:1，是以前有意压暗的）。
  审查后又改的（同一天）：① 键帽边框改成推出来的"离键帽最近、对键帽和页面都 ≥ 3:1"（原来往字色混两成，浅色只有 1.3–1.5:1，和纯黑一样淡）；
  浅色写死 Solarized base00 #657B83，Nord 不再写死 nord3 #4C566A（对键帽 1.36:1）。深色几套的键帽边框因此是一圈中灰（约 3:1），嫌重可以关"按键边框"。
  ② 高对比的状态字、角上小字、提示字、圆钮的圈写死亮黄（推出来的是刚好 4.5:1 的暗土黄），出问题的状态字 #FF8A80，按 7:1 测。
  ③ 调整键盘的雾层不透明度从 0xB0 往上加到提示字对雾下透出的键帽字也 ≥ 4.5:1（Theme.shadeFor）；主题扩充后功能键的底和字也算
  （调整模式不切回字母层，Fn 层时提示字压着的全是功能键；2026-10-31 审查发现漏了，Everforest、Solarized Dark、Solarized Light 只有 4.41–4.44:1）。
  现在 Solarized Light 0xD0、Everforest 0xD0、Solarized Dark 0xE8、Catppuccin Latte 0xD0、Nord 0xB8，其余和纯黑仍是 0xB0。
  ④ 🌐 去色：有中灰键帽的深色几套改成黑 → 键帽底、白 → 键帽字线性映射（原来乘 0.75，地球和键帽一样亮）；高对比按黄字的色调、不压暗；纯黑、浅色不变（Theme.emojiMap）。
  ⑤ 贴字面板输入框的光标、选择把手、选中高亮：窗口是系统深色主题，原来是青绿色 #80CBC4，浅色底上 1.7:1 看不清；纯黑以外按主题着色（光标 strong、把手和高亮 focus），
  换回纯黑装回系统原来的（MainActivity.tintInput）。
  跟着变的：整屏键盘（键帽、高亮、按下、放大提示、🌐 去色按字的色调压暗、调整模式的框 / 把手 / 压暗层（浅色主题是一层发白的雾）/ 提示字、触控板上的 ⋯ 和菜单、调整栏）、
  主界面触控板和四角小按钮、左侧按钮条、数字键盘、空中鼠标页（底、格子、圆钮、状态字）、贴字面板（输入框、按钮）。没变的：设置页、通知、配对引导。
- **主题扩充 + 键帽分主次（2026-10-31，用户："键帽上一点点颜色或者键帽字符上色……选择还是少，而且现有的其实很相近"；协调者的方案用户批了）**。
  ① 主次键帽（照 Gboard / 三星键盘）：普通键 = 字母、数字、空格、标点（含符号层自带 Shift 的符号），其余是功能键（修饰键、Esc、Tab、Caps、⌫、↵、Del、F 键、
  Home / End / PgUp / PgDn / Ins / PrtSc、方向键、自带 Ctrl / Alt / GUI 的组合键、?123、Fn、🌐、⋯、快捷行里的每个键），规则在 KeyRows.functionKey（离线测）。
  数字键盘：数字、小数点普通，+ - ⌫ Tab ↵ 功能（numpadFunctionKey：同一个 "-" 在整屏键盘上是标点，所以分开）。按钮条、空中鼠标格子、⋯ 菜单项、调整栏不变。
  功能键的底色 = 键帽底色往强调色掺，从 15% 起加到和普通键帽、页面底色的 CIE76 色差都 ≥ 8（Theme.fnTint；樱花、薄荷的页面本身带着强调色色调，15% 时功能键和页面色差只有 3）；
  字 = 强调色、不够 4.5:1 推亮 / 推暗；按下另有 fnPressed（= 功能键底色往正文色混 15%：沿用普通键按下的底色的话，Catppuccin、Solarized Dark 这几套按下前后色差只有 3–4，看不出按了）。
  **和方案不同**：高亮（锁住的修饰键、换层键）照旧是普通键帽底色 + 强调色边框 + 高亮字，不画在功能键的底色上——功能键的字本来就是强调色，
  高亮的字也是强调色（推出来两者几乎同一个值），底色再照旧掺着的话锁住和没锁只差一圈边框；现在锁住时底色"退回"普通键帽色（色差 ≥ 8）+ 换边框。
  没另加开关。纯黑：功能键三个槽位写死成普通键的（透明 / #8A8A8A / #1C1C1C），和以前一模一样（ThemeTest 锁住）。高对比：功能键字白（强调色）、底 #262626，字母照旧黄。
  🌐 是功能键，去色映射改按功能键的底和字（Theme.emojiMap）；字是强调色不够亮，白那头往白推到中灰对键帽 3:1。
  ② 加 9 套，共 18 套，设置页分深色 13 套、浅色 5 套两组：Everforest、Solarized Dark、Rosé Pine、Monokai、海蓝；浅色（Gboard 浅色，id glight）、Catppuccin Latte、樱花、薄荷。
  原来 id light 的 Solarized Light id 不改（存的设置照用），名字固定成 "Solarized Light"，"浅色"这个名字给了 Gboard 浅色。海蓝、樱花、薄荷和两个组标题九种语言翻。
  在协调者给的值上改了一处：浅色（Gboard）的触控板边框 #4285F4 → #4182EF（往黑推 2%；原值对页面 #E8EAED 只有 2.96:1，不到 3）。
  规则改了一处：放大提示的字（previewText）不够 4.5:1 就往亮 / 暗推（Everforest 4.38、Solarized Dark 4.03：正文本来不亮，盖在掺了字色的提示底上不够）；原有九套本来都够，值不变。
  ③ 设置 → 外观的主题从一列单选改成色块网格（ThemeGrid）：每格页面底色上一个普通键 A + 一个功能键 ⇧ + 一小块触控板，名字在下面（放不下先缩到 9sp 再省略）；
  选中的格子一圈设置页自己的蓝 #8AB4F8 + 淡蓝底；手机竖屏一行 3 格。上面的大预览保留，⌫ 画成功能键、⇧ 是锁住的样子。
  已知：键帽边框对功能键底色只有 2.1–2.5:1（深色几套），没要求（边框对页面仍 ≥ 3:1，键的轮廓靠它对页面）。
- **设置页分标签页**（2026-10-31，用户："设置页现在有点太混乱了，考虑分出来不同的标签页"；协调者的方案用户批了）。只改 App 的设置页，不改协议、不改服务。
  - 五页：通用 = 豆花儿服务、连接方式（含蓝牙设备）、恢复默认、版本号（彩蛋照旧）；触控板 = 光标、点按、滚动（含咔嗒强度）、三指 / 四指手势、角上的小按钮；
    按钮与按键 = 左侧按钮条（2026-11-01 起叫自定义按键）、键盘最下面一排、音量键；空中鼠标 = 原来那一节（桌面模式下只有一句 air_bt_only，五个标签不随模式增减）；外观 = 外观、震动。
    标签名九种语言（tab_*）。标题和标签在滚动的那块外面（不随页面滚）；选中的标签亮一档的蓝字 #AECBFA + 加粗 + 淡蓝圆角底，没选中的灰字；选中的标签自动挪进看得见的地方。
    （字原来是设置页的链接色 #8AB4F8：叠在窗口底色 #303030 上的淡蓝底是 #465060，字只有 3.9:1、比没选中的灰字 5.0:1 还淡，审查发现；换成 Google 蓝 200 是 4.9:1。）
  - 五页每次 build 都全建、只显示选中那一页：ServiceSection（观察者、实时刷新）、蓝牙设备列表、音频提醒都引用建好的 view。切页只改显示、不重建，
    配对引导（状态在 SelfStart 里）、填了一半的分辨率都不丢；切页时输入框跟着藏起来，先收起输入法。外壳（标题、标签、ScrollView）只建一次，
    build 只换里面的五页，所以加删按钮条 / 换连接方式 / 恢复默认重建后停在原来那一页、滚动位置不动（ScrollView 重新布局时按新内容截一下）；切页滚到顶。
    切页时上一页可能还在惯性滑动：光 scrollTo(0, 0) 的话 ScrollView 下一帧 computeScroll 按滑动的绝对位置把新页拽回去（审查发现，对照 SDK 源码）；
    现在 scrollTo(0, 0) 后再从顶上 fling(0)，零速的那次（时长 0、终点 = 起点）顶掉还在走的、下一帧就结束（OverScroller 源码看的，没在真机上试）。
  - 记住上次那一页：Prefs.settingsTab（存 id 不存下标），每次切页都存；转屏、从最近任务回来照它。"恢复默认"跟着清掉，不特意留：那个按钮就在"通用"页，清掉后默认也是"通用"。
  - 入口：以前"豆花儿服务"在整页最上面，打开就看得到，没有专门定位的代码。现在主界面点状态字（服务没开 / 没响应）、配对通知、通知里"去关掉无线调试"
    都经 SettingsActivity.serviceIntent（EXTRA_TAB = general）打开，停在"通用"页最上面；设置页开着时从通知点进来（onNewIntent）也换过去、滚到顶。按钮条的"设置"照旧停在上次那一页（2026-11-01 起主要从 ⋯ 菜单的"设置"进，同样停在上次那一页）。

- **惯性滚动 + 连续滚动**（2026-10-31，用户："罗技鼠标有一个无限滚动……在触控板那个边缘模拟……阻尼可调……用震动区分手感……加个开关能连续滚动"；
  协调者的方案用户逐条定了，和方案不同的以用户为准；协议 18）。
  - 惯性（模拟罗技 free-spin）：只从**边缘滚动**起，**双指滚动不带**（用户定；方案里"双指惯性"开关和字段都没做）。松手前 60ms 里首尾两帧算速度，最后一帧离松手超过 40ms 当停住了；
    ≥ "多快算拨"才起（设置里滑块，0.2–2.0 dp/ms、步长 0.1、默认 0.6 ≈ 9.5 厘米/秒，用户要可调；0.2 约 3 厘米/秒，慢慢滚一松手也会溜，2.0 约 32 厘米/秒要使劲甩）。
    起转速度 = 手指的滚动速度换算成格/秒，不放大，封顶 150 格/秒。减速 dv/dt = −(v + 3) / τ（闭式解推进，定时器晚到也不丢距离），阻尼 10 档 τ = 3 秒 → 0.25 秒等比，默认 4 档（中拨约 3.8 秒 / 54 格），
    15 秒兜底。设置页阻尼下面一行"中等的一拨大约转 N 秒"按 50 格/秒现算。默认开。
  - **碰屏幕任何地方马上停**（用户定，方案推荐的是"碰触控板停 + 按键静默停、按钮条和左键按钮不停"）：服务看到的任何一根手指按下（触控板、键、按钮条、"左键"按钮、
    空中鼠标页的格子、空白处、分屏透传归系统的手指）、App 本地分流时 App 收到的任何按下都算。碰的那一下照常生效；只有碰在触控板上的那一下不算点击、不起长按拖动（动了照常移动 / 滚动，
    在边缘带动就是新的一拨）。停的反馈：触控板、按钮、空白处、透传的手指震一下"刹住"；按在键上的不震（键自己会"嗒"，方案里的"静默停"）。代价：方案里"按着左键时惯性照转 = 拖着东西自动滚"
    只剩"先按住左键再去边缘拨"这一种（拨之后再碰左键按钮就停了）。"滚轮向上 / 向下"动作、界面发来的键（CMD_HID_KEY、组合键动作）、Ctrl 按下也静默停。
  - 反馈：拨出去一下"放开"、转的时候不震（像 free-spin 没有段落）、刹住一下；都跟"滚动咔嗒"开关和"咔嗒强度"走。波形要手摸：调试版（可调试包，Haptics.tuning）设置 → 触控板 → 滚动
    最下面列"放开"（旋转原语 / 三下渐弱的轻嗒 / 快速下落原语）、"刹住"（闷响原语 / 轻嗒 + 实的一下 / 系统重击）各三种，点一下震一下、选中的存 Prefs.spinStyle，
    正式包一律用默认（按马达支持的挑：SPIN → 三下渐弱 → 系统 TICK；THUD → 轻嗒 + 实的一下 → 重击）。马达不支持的候选标"不支持"、放的是默认那种。
    方案里"Haptics 在调试版日志里记一次支持哪些原语"没做，设置里的"不支持"标记顶替。挑定以后把默认改过去、删掉这一节。
  - 连续滚动（开关，默认关）：HID 分辨率倍率 ×8（MOUSE_DESC_HIRES）；引擎按 1/120 格出，每滚过一格的距离咔嗒一下（带方向攒，来回抖不会一直响）；锁着 Ctrl（缩放）时、"滚轮向上 / 向下"照旧按格；
    双指连续滚动走过 30dp 时一个方向占 2 倍以上就锁住它（斜着的不锁，没定之前先只出主方向、另一轴攒着）。主机没设倍率时 WheelOut 攒满 120 才出一格（只攒余数的帧不发），余数换向 / 停 300ms 清掉。
    桌面模式 uhid 的描述符一直带倍率、服务读 /dev/uhid 回应内核（Android 认不认高精度滚轮看系统开关 high_resolution_scroll，不认就照旧一格一格；2026-11-01 改成只带竖向倍率，见"桌面模式的虚拟鼠标只留竖向滚轮倍率"）；
    蓝牙描述符跟着开关走，开 / 关都要在对方设备上删掉手机重新配对（设置里写了）；BtHid 切开关时注销、用新描述符重新注册（unregisterApp 的回话 5 秒没来也照样重新注册）。
    主机来设倍率就照办（不管注册的是哪份）、每次连上先当 ×1；问 Feature 报告回当前倍率，问别的 Feature 回 INVALID_RPT_ID（以前不分类型回全零输入报文）。
    设置页在蓝牙连着、开关开着时显示这台主机开没开平滑滚动（倍率变了 BtHid 再报一次同样的状态，设置页借 onBtState 刷新）。
  - 协议 18：CMD_CONFIG 末尾加 inertia、inertiaTauMs、inertiaMinSpeed、smoothScroll（编解码收进 ConfigCodec，三处共用）；MSG_MOUSE 的 wheel / pan 改 i16、单位 1/120 格；
    MSG_HAPTIC 多 6（放开）、7（刹住）。CMD_WHEEL 不变（格数，服务里乘 120）。
  - 方案里没有、做的时候加的：蓝牙模式下服务接管着（转发）时"滚轮向上 / 向下"改走服务（CMD_WHEEL → 服务的分流 → 转发回来由蓝牙发），服务那边转着的惯性才停得住；
    服务没接管时照旧直接发、先停本地分流的惯性。TouchReader 收尾（释放独占、PRE_GRAB、RACED）时补报的抬起当取消式抬起（Session.stopping），不然放开触摸屏那一刻手指正在边缘带上划
    会白白"放开"震一下。松手速度记最近 16 帧（方案写 8：240Hz 的触摸屏 60ms 有 15 帧）。服务 --debug 时日志记每次边缘松手的速度和起没起惯性（"边缘松手 0.83dp/ms → 惯性 41 格/秒"），校准门槛用。
  - 用户同一条回复里的"上沿下沿可调吧""苹果非苹果设备不需要自动识别"不是这一项的（这一项没有上下沿、不按苹果分），没动。
  - 审查后的修改（同一天）：服务的定时器加令牌（OneShotTimer，见不变量）。以前 Session.schedule 直接 timer.schedule、cancelSchedule 只 cancel(false)：
    惯性的一帧到点、卡在锁上时，同一帧里先按键（停了惯性）再按触控板中间（排上长按），那一帧拿到锁就把长按提前触发、左键按下进了拖动；
    原来的轻点拖动 / 双击那几个一次性定时器有同类竞态，一起修了。小红点的节拍（douhua-tick）有同样的"撤不掉在等锁的那一拍"，没加：迟到的一拍 onTick 看红点没按着就什么都不出，
    按着新的一次时只是多积分一拍（按当时的时间算），无害。
- **小红点 + 左 / 中 / 右三键**（2026-10-31，用户："你知道 thinkpad 吧，我想来一个模拟小红点，因为这个占地儿小，然后红点可以配上左键右键中间键，
  这个三个虚拟按键其实也可以选择在触控板上面显示"；协调者的方案用户逐条定了，"上沿下沿可调吧"是三键条的位置）。协议还是 18（和惯性滚动一起，没发布过，不再升）。
  - 放哪：只在键盘页，⋯ 菜单里"触控板：开 / 关"换成"指点：触控板 → 小红点 → 关"轮换（换了关菜单；Prefs.keyboardPointer，没存过按旧的 kb_pad 迁移：关 → 关）。
    主界面不放红点。**（下面红点组的摆法 2026-11-01 已改成照真的 ThinkPad 键盘摆，见"小红点放进键盘里"一条；三键条从 36dp 加高到 48dp）**
    红点组 = 红点（按得到 72dp）+ 下面一排左 / 中 / 右（各 ≥ 44 × 36dp、隔 4dp），竖排 140 × 112dp，地方矮时横排（三个键在左、红点在右，猜的右撇子）。
    分裂、没调过大小：中间只留一列 156dp 放红点组、两半各自变宽（大屏分屏下半边约 300 → 372dp），红点组贴着两半下沿（和空格那排齐，下沿不另外离屏幕边——方案原话），
    ⋯ 放这一列红点组上面；自动分不分裂按这一列算。调过大小：中间够宽放中间，不然上方 / 下方或者不放。合并：同触控板挑空得最多的一侧，贴着键盘那条边居中、离页面边 20dp；
    宽窗口合并没调过大小时键盘只让出右边一列（**和方案不同**：方案写 156dp，那样红点组贴着屏幕右边；改成缝 + 组宽 + 离边 20dp ≈ 170dp）。放不下就不放（⋯ 回功能行）。
  - 三键条：设置里开关 + 上沿 / 下沿（默认下沿 = 竖屏键盘页贴着键盘那一边），主界面和键盘页的触控板都管（主界面 PadStack）。从触控板切出来，左右、上下离窗口边 20dp，
    切完触控板短边不到 96dp 就不切；放上沿时键盘页的 ⋯ 在三键条正下方隔 8dp（Plan.menuArea / menuMargin）。键盘页的三键条和红点组共用同一组 MouseKeys。
  - 手感（TrackPoint，数字是方案的初值、按资料和小模型定的，没在真机上试）：原点 = 按下点（浮动摇杆），位移当"力"；死区 2.5dp、爬行 40、平台 450、推到底（32dp）3000 计数 / 秒（2026-11-01 降到 1200，见"小红点：用户第一次上手后的修改"），
    smoothstep 过渡；× 灵敏度（设置 0.3–3.0）；负惯性 I = 3、τ = 10ms，夹在不反向；不加平滑；松手立刻停。**和方案不同**：死区按原始位移判断（在死区里负惯性也不出东西），
    方案的模型是先加负惯性再过死区——那样按住不动时 ±0.3dp 的抖被放大 4 倍、会越过死区爬。
  - 中键（ThinkPad 式）：按下先不发；按着时推红点 / 单指在触控板上划 = 滚动（红点：2 / 10 / 40 格每秒同一条曲线，2026-11-01 翻倍成 4 / 20 / 80；触控板：按滚动速度设置），方向"推哪边视野往哪边走"、
    不跟自然滚动开关，主方向占 2 倍以上只滚它；一格都没滚过就松开 = 中键单击（收尾、被取消的都不补）。代价：按住中键拖不了。触控板上的中键滚动挪出轻点容差（6dp）才开始，
    不起长按、不起惯性（边缘带里按下的也按中键滚动走）；单指轻点照旧左键单击。
  - 节拍：TouchRouter.Output 加 startTicks / stopTicks（不做成 default，忘了实现就编译不过）。服务在单独的 douhua-tick 线程（**方案说可选**：定时器线程要等切焦点的 binder），
    桌面模式 8ms、蓝牙转发 12ms；App 本地分流用异步 Handler 12ms。每拍按实际时间积分、一拍最多算 50ms；兜底 20 秒没事件暂停（方案说"停"，改成暂停、再动就接着，免得误停后红点失灵）。
  - 反馈不新增种类：推着走 = 颗粒（每 30 计数一颗、跟颗粒开关，按着鼠标键时不发）、轻点红点 = 点一下、左键 = "左键"按钮那一套（嗒 + 持续轻震，共用计数）、
    右键 / 中键按下嗒一下、中键滚动 = 咔嗒。碰红点 / 三键条也停惯性（TouchRouter.down 本来就是"不在触控板上的都停"），震"刹住"。
  - 样子：红点一直是红的（Theme.stick：ThinkPad 红 #E2231A，凭记忆的近似值；Nord、Everforest、深色这三套推亮了一点，其余原样），帽 32dp（2026-11-01 已改：按范围的比例画）、九个凹点、淡圈；
    三个键不写字（不用翻译）：左 / 右键靠中键那一侧一道细红线（方案写"朝红点那一侧"，横排 / 三键条时说不清，改成都朝中键）、中键三个凹点。
    （2026-11-01 已改照真 ThinkPad：红线在左右键下沿、中键上沿 7 个点，见"小红点放进键盘里"一条的"和真 ThinkPad 对照"。）
  - 服务连着没接管时（View 自己收触摸）：红点不动（同触控板）；三个键轻点 = 点一下那个键（桌面模式 CMD_CLICK，蓝牙模式经本地分流），按住拖不了（同"左键"按钮）。
  - 没做（方案写"以后"的）：触控板放不下时自动换红点、横排左右可换（左撇子）、画手指偏了多少。
  - 审查后的修改（同一天）：中键被取消的抬起不补中键单击（TouchRouter.mouseButton 带上 canceled）。以前只有 releaseAll 不补，可释放触摸屏时 TouchReader 的 finally
    先把还按着的手指按抬起补报（Session.onUp → router.up(…, stopping)）、之后才 releaseAll，手掌 / 丢数据（Session.onCancel）也走 up(…, true)——
    中键按着时豆花退后台、心跳超时、转屏重新接管，主机都会收到一次中键单击（Linux 上 = 粘贴选区）。左 / 右键被取消照常松开。
    App 本地分流顺带：带 FLAG_CANCELED 的 POINTER_UP / UP 也当被取消（不起惯性、中键不补单击），以前一律当正常抬起。
- **触控板背景图**（2026-10-31，用户："触控板区域可以允许添加各种格式图片背景，像 png 这种保留图片原本的透明区域啥的，然后设置带透明度调节的，
  还有因为触控板会一直变化，还得有不同适配的设置"；协调者的方案里要拍板的几条用户说"其他按你推荐"，全照推荐做）。只改 App，不改服务、不改协议。
  - 范围：主界面的触控板、键盘页的小触控板（含空中鼠标页调出的键盘）用同一张图、同一套设置（两块共用一张位图）。三键条、小红点、按钮条不画图。
  - 适配：形状一直变，所以按"显示方式 + 焦点"给，不按布局存：填满（默认）/ 完整显示 / 拉伸 / 平铺 / 原大小；焦点 = 图上一点，要裁时尽量留在正中、
    有空余时照百分比（离 0 / 0.5 / 1 在 6% 以内吸过去，等于 3×3 对齐）；"图片大小"25–400%（只管平铺 / 原大小，100% = 一个图片像素一个屏幕像素）；
    开关"宽的和高的触控板分开设"（默认关；按宽 ≥ 高 / 高 > 宽分，不按横竖屏：横屏分裂时中间那块是高的；刚打开时把现在那套复制给高的；拖分隔条正好过 1:1 时会跳，不做回差）。
  - 透明：透明的地方露出触控板底色（不是透到页面）。不透明度 10–100%，默认 100%（看不看得清靠下一条保证，不靠压暗）。动图只显示第一帧（设置页提示一句）。
  - 图上的字：状态字、角上小按钮、键盘页的 ⋯ 下面垫一块不透明的触控板底色，字色不变（对比度和没图时一模一样，用不着证明；每套主题的 padFill 不透明由 PadFitTest 锁住）；
    边缘滚动带的细线下面垫一条 3dp 底色；边框挪到最后画，盖住图的边（没图时和以前一样：几样东西互不重叠）。⋯ 的底由触控板自己画（KeyboardScreen 告诉它 ⋯ 在哪，
    ⋯ 不在触控板上就不垫；2026-11-01 起红点组没了，⋯ 只在触控板上或功能行上），角上小按钮的底是 Style.small 的 onImage。
  - 选图：照片选择器 + 文件选择器两个入口（照片选择器只列媒体库，文件里的 PNG 贴纸要走文件选择器），都不用申请权限。拷原文件进 files/padbg/（不记 URI：读权限是临时的），
    上限 50MB；ImageDecoder 能解的都收（PNG / JPEG / WebP / GIF / BMP / ICO / 相机 RAW，HEIF / AVIF 看机器），解不了提示、SVG 点名让先转 PNG。
  - 解码：后台单线程，短边解到窗口最大可能的长边（画在任何形状上都不用再放大），总像素 ≤ 2²³、单边 ≤ 4096（撞上限时极端形状会放大、略糊：长宽比 ≤ 3 的图最多 2.9 倍，
    在 4000 长边的窗口上）；软件位图 + mipmap（硬件位图配 BitmapShader 没查到出处）；Fold 展开 / 换屏要的大 5% 以上才重新解；进程退到后台（TRIM_MEMORY_BACKGROUND）放掉，回来再解。
  - "恢复默认设置"删图片；换机迁移带图片（data_extraction_rules 不排除；新手机上文件不在就当没设、清掉 Prefs）。
  - 和方案不同的地方：英文的"显示方式"叫 Scaling（方案写 Fit，和选项"Fit (show the whole image)"撞词）；⋯ 的底由触控板画、不改 Style.bare
    （⋯ 换位置——红点组上面、调整模式藏起来——时不用另外换样子）；导入成功后写 Prefs 也在后台线程（设置页中途转屏、关掉，结果照样存下）。
    没做（方案写可选 / 以后的）：外观页最上面那块主题预览的触控板换成同一个画法、"播放动图"、"键盘页也显示"开关、半透明底块 + 亮字。
  - 审查后（同一天）：导入完的结果原来只交给当时挂着的设置页，"正在读取"时按返回，主界面要等下一次 onStart 才换图，失败（太大 / 读不了）的提示也丢了。
    现在成功时通知在前台的主界面照新 Prefs 重新 apply（PadImage.setImportedHook，MainActivity onStart 到 onStop 挂着）；设置页没挂着就留下结果码，
    下次 start 取走显示（takeMissedResult）。摘监听只摘自己的（转屏重建时新的可能先挂上）。
- **主界面改版 + ⋯ 菜单固定在每块触控板上**（2026-11-01，用户：从键盘页回设置要先收起键盘再开侧边栏点设置，太麻烦——三个点里直接给设置；
  默认界面只剩触控板和它四个角上的键；侧边栏那几个键基本没用上，改叫"自定义按键"、设置里开关、作为单独的功能，里面不要退出 / 收起；
  退出、旋转这些最重要的放进三个点；不管什么界面，触控板上的三个点是固定的）。只改 App，不改服务、不改协议。
  - 主界面触控板顶上正中也有 ⋯（PadStack 摆，和键盘页同一套：KeyboardPlan.menuTop、Style.bare 不带框、有图时 TrackpadView 垫底；三键条在上沿时在三键条正下方隔一条缝）。
    主界面没有功能行可退，放不下也照样放（KeyboardPlan.padMenuTop：不出触控板，离屏幕边少一点）。叠在角上的按钮上面、区域排在它们前面：
    触控板窄于约 168dp（横屏开数字键盘的小窗）时会挨着左上 / 右上角的按钮。代价同键盘页：横向边缘滚动带顶边正中那一截归 ⋯。
  - 菜单两屏共用一份（MenuLayer，根视图最上面一层；原来菜单和"点外面"在 KeyboardScreen 里）：主界面 = 键盘、设置、旋转、退出；
    键盘屏 = 收起键盘、调整大小、布局、指点、设置、旋转、退出（原来四项顺序不变，后面加三项，退出最后）。设置 / 旋转 / 退出照 Actions 的
    settings / rotate / exit 做（设置页照按钮条上"设置"的做法打开），都先关菜单；"键盘"打开键盘屏。项的字用动作清单的全名（act_keyboard / act_settings /
    act_rotate / act_exit），没加新字符串。编号：主界面的 ⋯ 409、键盘 410、设置 411、旋转 412、退出 413（点外面 405、底板 408 照旧，改名 MENU_*）。
    开着时区域只报菜单那组（不变量扩到主界面）；点外面抬起时关、返回先关（PRIORITY_OVERLAY）、换屏先关；三条触摸路径同键盘页原来的
    （服务接管 / 蓝牙本地分流靠区域，服务没接管时 View 自己收，localTouch）。菜单跟着 ⋯ 走：根视图的布局监听里先 MenuLayer.place 再报区域
    （只有 ⋯ 那一支重新布局时，菜单那一层大小没变、自己的 onLayout 不跑）。
  - 项多了（键盘屏七项一列约 356dp 高，手机横屏全屏只有三百多 dp）：KeyboardPlan.menuGrid——离页面边 20dp 放得下的话先一列、每项从 44 压到最矮 36dp；
    还放不下就分列（先排满第一列，同按钮条；列宽至少 120dp）；再不行贴着页面边再试一遍；最矮的窗口列宽放宽到 60dp、挑每项最高的。
    按 1dp = 1px 算的结果：手机竖屏一列每项 44；手机横屏全屏（约 360dp 高）七项还是一列、每项 38；300dp 高分两列（4 + 3）、每项 44；
    260 × 250dp 的小窗贴边两列；200 × 160dp 两列、每项 32。从右往左的语言第一列在右边。
  - 四个角都能设：原来左下是固定的 ☰（toggle_strip，按钮条收起后回到按钮条的入口），而且角上的按钮只在按钮条收起时显示。
    现在四个角一直显示，左下（corner3）默认数字键盘（以前没存过这个键，升级上来的也是数字键盘），另外三个默认值不变（左上退出、右上旋转、右下键盘），改过的照旧。
    设成"无"的角不放按钮、不报区域（以前是一个空的框）。设置页按 左上、右上、左下、右下 列（Prefs.CORNER_ORDER，存的下标还是 左上、右上、右下、左下）。
  - 左侧按钮条改叫"自定义按键"：设置 → 按钮与按键第一节，最上面一个开关（Prefs.customKeys，键名 custom_keys，默认关；不读以前的 strip_hidden——
    以前默认显示按钮条，升级上来的也照新默认关；槽位照旧，打开就是原来那些），开着时下面列出槽位（加删、上限 12 照旧），关着时列表藏起来。
    条上写死的"退出 / 设置 / 收起"去掉了，按钮编号 = 槽位下标（0–11）；开着但一个槽位都没有时整列不占地方；整列藏着时一个都不报。
    动作清单的"收起 / 显示左侧按钮条"（toggle_strip、Kind.TOGGLE_STRIP、字符串 act_toggle_strip / act_toggle_strip_s / strip_hidden 九种语言）删了，存过它的位置按"无"。
    自定义按键和触控板之间的缝改用 marginStart：从右往左的语言下这一列在右边，以前 8dp 的缝跑到了窗口最左边。
  - 核对过、没动的：空中鼠标页（有自己的"按住退出"，没有 ⋯）；数字键盘、贴字面板照旧从角上 / 动作打开；PadArea、ButtonStrip 分列、蓝牙模式左右边的排除区
    （按窗口算，和按钮条无关）；状态字可点时按在 ⋯、角上按钮上的不算点状态字（以前只排除角上的按钮）。
    改了的：底部状态字以前默认没有角上的按钮挡着（按钮条显示时角上没按钮），现在左下、右下默认都有，状态字两边各让出 62dp
    （TrackpadView.setStatusSide，下面两个角都设成"无"时照旧 18dp），长的截在两个按钮中间、不压在按钮上。

- **小红点放进键盘里、三个鼠标键放大**（2026-11-01，用户："红点可以稍微大一点，现在太小了；三个按键做得太小了，基本上没法点……甚至可以像真的 ThinkPad
  键盘一样，指点杆直接放键盘中间，然后在键盘下边有三个按键"；协调者给了两种做法，用户选"ThinkPad 那样"）。只改 App，不改服务、不改协议；
  手感（TrackPoint 曲线、负惯性、轻点 = 左键）、节拍、中键滚动、反馈、"碰哪里都停"、红点颜色都没动。几何全在纯 Java 的 KeyboardPlan（离线测）。
  - （**2026-11-01 已改**：三个鼠标键改成占底行和快捷行中间让出的空位、红点按圆切键、小红点固定合并，见"小红点状态再改一轮"一条；下面这段是当时的做法。）
    三个鼠标键是键盘最下面一排，横跨整块键盘（分裂时左半 + 中间 + 右半），48dp 高，四边和键帽一样缩进 2dp（和上面的键隔 4dp，同键与键）。
    **算在键盘的框里**（Plan.block）：从框的下沿切走 52dp，键盘面板（键）变矮这么多；调大小拖的、存的、默认位置都是连这一排的整块。没另加在键盘下面：
    默认位置（竖屏占下面七成、横屏占满）下面本来就没地方，加在下面就得另定一套默认位置、调大小时拖两个框。代价：选小红点时键比关着时矮约 7dp 一行
    （手机竖屏 7 行，从约 91 到 84dp），上面那三成照旧空着（同指点关着）。框太矮、切完面板不到 3 × 48dp（键会挤成一条条）就不切这一排。
  - 中键窄、左右键宽：中键 = 三个键总宽的 1 / 5.4（左 : 中 : 右约 2.2 : 1 : 2.2，照真 ThinkPad 量的，见下面"和真 ThinkPad 对照"；第一版凭印象按 3 : 2 : 3、四分之一），
    至少 56dp，左右键填满剩下的、不窄于中键（整排窄得三个一样宽都不到 56 时三个一样宽）；中键对着红点（ThinkPad 的中键在 TrackPoint 正下方），挪不过去就挪到最近的地方
    （KeyboardPlan.middleKey）。合并时红点在 0.55 宽处，所以左键比右键宽一点（411dp 宽的手机竖屏约 183 : 74 : 142dp）。触控板的三键条（主界面、键盘页）也加高到 48dp、
    同样的比例、中键在正中（411dp 宽的主界面约 148 : 67 : 148dp）；上沿 / 下沿、⋯ 的位置照旧。56dp 的下限只在整排不到约 310dp 时起作用。
  - 红点的位置：字母层第二行 g|h 的缝、第三行 v|b 的缝和两行之间的缝交在一起那一点（KeyRows.stickPoint；整块时 g|h、v|b 都在一行的 5.5 / 10 份处，正好四个键交在一点）。
    - 分裂、两半中间留得出一列（红点 64 + 两边各 8 = 80dp）：在这一列正中、竖着和那两行的交界齐平，不压键（STICK_COLUMN）。没调过大小时自动留这一列
      （以前红点组留 156dp），两半相应变宽（900dp 宽时每半 410dp，触控板时 300、以前红点组时 372）；自动分不分裂也按这一列算（440 × 220 起就能分）。
    - 合并、分裂两半挨得近（强制分裂的窄窗口、存过的格子）：嵌在四个键交界上（分裂只隔一条缝时 = 左半的 g、v 和右半的 g、v 交界），挨着的键各让出一个角（STICK_KEYS）。
      红点的区域排在键和键盘范围前面（不变量），面板自己收触摸时 KeyFingers 带洞；画成键上挖出的一个方块洞（页面底色）+ 帽占洞宽的 0.8。
      洞的角只圆 2dp（TrackPointView.HOLE_CORNER_DP，审查后改的）：分流的区域、KeyFingers 的洞、这个 view 自己收触摸都是整个正方形，第一版圆角是半边长的 0.6，
      四个角各露出 g / h / v / b 一小块（手机竖屏约 36dp²、对角线方向深约 5dp），看着是键、按上去却归红点（分流时轻点 = 左键）。没改成按圆角判断：区域是矩形、服务那边也按矩形分。
    - 嵌在键里多大（KeyboardPlan.stickSize，正方形）：越大越好按、键让出的角也越大。取最大的：≤ 64dp、不出面板、字母层每个键让出 ≤ 16% 面积、
      每一层每个键 ≤ 25%（硬上限）；到不了 32dp 时只要 32dp 每层每个键都 ≤ 25% 就取 32，还不行就不嵌。**符号层、Fn 层那一点上是别的键**
      （符号层第三行的 ] 正好居中、第四行 < 在旁边，是 T 字交界，不是四个键的角），红点不跟着换层挪（"指点杆"是固定的），所以每一层的键都要守 25%：
      手机竖屏（约 411 × 915dp，键 37 × 80dp）卡在符号层的 ] 上，红点 44dp（] 让出 25%，g、h、v、b 各约 14%），画出来的帽约 35dp（以前 32）；
      分裂中间那一列 64dp、帽约 45dp。
    - 嵌不下（键小：比如 360 × 600dp 的窗口、键 32 × 48dp，符号层的 ] 只够 28dp）：放到键盘的框上面、隔 8dp、对着那一点，离页面边 20dp、32–64dp（STICK_OUTSIDE，
      画成不压键的样子）；上面没地方放下面；都没有就不放（轻点红点 = 左键也就没了，三个鼠标键照样有）。和协调者说的"键盘上沿正中"不同：对着红点那一点，中键也对着它。
  - 红点范围：分裂中间那一列 64dp（以前 72dp）、帽占范围的 0.7（约 45dp，以前 32dp）；外面一圈淡圆标出范围，按着时换 focus 色（TrackPointView）。
  - 选小红点时键盘页没有触控板，⋯ 一定在功能行最右边（原来分裂中间红点组上面那块 menuArea 删了）。宽窗口合并没调过大小时键盘占满（原来给红点组让出右边一列）。
  - 删掉的：红点组（竖排 / 横排、placeGroup / placeMiddle、Sizes 的 groupW / groupH / rowW / rowH / stickMiddle、Plan.stickSide），对应的测试改写。
  - 打字测试的触点日志不记按在红点上的触点（TouchRouter.down：红点范围在键盘范围里面，以前会记成 hit = 810 的一行 p）。
  - **和真 ThinkPad 对照**（2026-11-01，对着联想 X1 Carbon Gen 13 / TrackPoint Keyboard II / 2016 年那一代的用户指南插图和维基共享资源的实拍量的，调研笔记在协调者的 scratchpad，thinkpad-real.md）：
    - 照了的：左 / 右键的红线在靠用户那条长边（下沿）、横着、几乎占满键宽、约 2dp 粗（真机约 1mm，按键距折算），三键条在触控板上沿时也画在下沿；
      中键没有红线，上沿附近横着一排 7 个小点（以前正中 3 个"凹点"，凭记忆；真机看着像凸点，没有文字说明）；宽度约 2.2 : 1 : 2.2（各代 2.1–2.4 : 1）；中键对着红点；
      按住中键推红点 = 滚动（以前就有）。都在 MouseKeys（画法，键盘最下面那排和三键条共用）和 KeyboardPlan.middleKey。
    - 故意不照的：① 红点帽真机约 0.43 个键距（约 7.6mm），我们画得大得多（手机竖屏合并时帽约 35dp ≈ 0.85 个键距，分裂中间那一列约 45dp）：用户 2026-11-01 嫌红点太小。
      ② 真机"按红点 = 点击"（Press-to-Select，往下按）默认关，我们"轻点红点 = 左键"原来默认开（2026-11-01 用户上手后要关，现在也默认关，见下面"用户第一次上手后"）。
      ③ 真机红点在 G、H、B 之间（最下一排比中间一排往右错半个键，B 骑在 G|H 那条缝下面，V 不挨着），我们照 Gboard 排法（v 在 g 下、b 在 h 下），所以是 G、H、V、B 四个键的交界。
      ④ 真机三个键整排只有约 5–6 个键距宽、居中对着红点，我们横跨整块键盘：用户嫌键太小。
      ⑤ 真机中键是上宽下窄的梯形（左右键靠中键那条边跟着斜），我们画圆角矩形：报上去的区域、服务的分流都是矩形，画成梯形就和按得到的范围对不上。
      ⑥ IBM 时代（R51）中键的蓝线没照（现在的联想机没有）；中键下限 56dp（约 9mm）比真机（约 1 个键距、19mm 左右）窄，我们的键距本来就小。
- **屏幕边缘留白**（2026-11-01，用户："很多安卓手机在边缘上都有额外操作……不管是键盘还是触控板，都留一个安全距离，靠近屏幕边缘的部分都留一小条"；
  协调者的方案用户没反对，照做）。只改 App，不改服务、不改协议。
  - 设置 → 外观，主题之后一节"屏幕边缘留白"：两个滑块"左右""上下"，0–48dp、步长 2、默认各 8（Prefs.EDGE_MARGIN_LR / TB，EdgeMargin 的常量），回到主界面生效（applyPrefs）。
  - 做法：MainActivity 给根视图下的四页（mainRow、keyboardScreen、airOverlay、menuLayer）设一样的 margins（按物理左右，从右往左的语言下也一样），整页往里收，
    留白那一条露出根视图的页面底色。不是各控件各自缩：四个角的按钮、自定义按键、数字键盘、键盘、小触控板、小红点、最下面那排鼠标键、⋯、空中鼠标页的格子和圆钮都跟着页面进去。
  - 哪几条边留（EdgeMargin.margins）：窗口在屏上的范围（decor 的 getLocationOnScreen + 大小，同报给服务的）和整块屏（getMaximumWindowMetrics）比，那条边贴着（差 ≤ 2 像素，或超出屏幕）才留；
    分屏下半边的上沿、浮在中间的小窗四条边都不留；当前方向的坐标，转屏后"左右"还是当前方向的左右两条边。一个方向两边加起来超过窗口这个方向的一半就按比例缩（窄小窗贴着屏幕边时内容不被挤没）。
    什么时候重算：每次布局完（decor 的 OnGlobalLayoutListener 进 checkWindowMoved：窗口大小变了、小窗挪了都会走一次）、心跳兜底、applyPrefs；变了才重新设 margins
    （再布局一次，根视图的布局监听照常重报区域，报的区域一直和 View 的实际位置一致）；buildUi 时 decor 还没量好，先按 getCurrentWindowMetrics 的窗口范围算，第一次布局就是对的。
  - "离屏幕边至少 20dp"照旧按屏幕边算：页面收进去 m，这条边在页面里还要离 max(0, 20 − m)（EdgeMargin.edges）。KeyboardScreen、MenuLayer 因此要各边不一样的距离（setEdgeInsets）：
    KeyboardPlan.Sizes 的 edge 改成四边各一个（edgeL / T / R / B，withEdges；三键条 cutBar、键盘外面的红点 placeStick 用），menuTop / menuGrid / menuPosition / adjustBarTop
    加了带 {左, 上, 右, 下} 的版本（一个数的旧版本转过去、结果和以前一模一样）；菜单地方不够时两边按各自要的比例少留（四边一样时就是以前的"各留一半"）。
    调整栏按两边要得多的那边算按钮宽度、照旧在正中。PadStack（主界面的三键条、⋯）本来就按窗口坐标算离边多远、东西都在触控板里，不用改。
  - 键盘区 = 收进去以后的整页：存的大小位置（键盘区的 0–1）不动，留白一变键盘按比例跟着变。蓝牙模式左右边的系统手势排除区（32dp）照旧按窗口边。
  - 和要求不一样 / 没做：① 留白那一条和别处的空白一样，转着的惯性滚动碰到它照样停、震"刹住"（"碰哪里都停"那条：服务的 TouchRouter.down 没命中区域也停）——要连这个也不响应，
    得给服务加一种"死区"区域、改协议，没做，等用户定；② 分屏透传时留白落在系统手势带里，照现有规则交给系统（返回 / 回桌面），本来就是这个规则；
    ③ 贴字面板开着、输入法弹出时下边照样留一条（挨着的是输入法、不是屏幕边，没特判）。
- **小红点：用户第一次上手后的修改**（2026-11-01，用户："合并键盘看不到小红点，完全找不见""加速有点过快，移动远一点非常不好控制，要降低一些""点击小红点的单击给它关掉"
  "按住中键上下滚动这速度也不快"；"三个键、小红点还原得非常好"）：
  ① 合并时红点不见了：分屏下半边（约 641 × 288dp）换成合并，用的是以前存的横屏键盘位置（左边六成、上下 11%–89%，有触控板时调的），扣掉鼠标键那排后键只剩约 29dp 高，
  嵌不下；退路"放到键盘外面"只找上面和下面，右边空着的四成没去找，红点就没了（拿主力机实际的窗口尺寸和存的位置喂给 KeyboardPlan 复现：stickAt = NONE）。
  现在上下都没地方时放到框旁边空得多的那一边（挨着框、隔一条缝、竖着对齐 g|h 那一点），中键照样按 g|h 那一点算（KeyboardPlan.placeStick 的第 6 个返回值）；四边都没地方才不放。
  ② 键盘位置按指点状态分开记（用户："有触控板的时候我要避让触控板，没有触控板的情况下我就不要避让了，但是总不能调来调去吧，写死居中也有问题，比如我要单手，可能就是要侧放"）：
  合并、分裂的位置都是 横竖屏 × 指点（触控板 / 小红点 / 关）各一份（Prefs.rectKey：触控板沿用以前的 kb_land_* / kb_split_*，小红点 kb_stick_*、关 kb_none_*），
  换指点时照新状态的那份重摆，"恢复默认"只恢复眼下这种状态的。以前存的都算触控板那份，小红点、关从默认位置开始（没触控板时默认不给它腾地方）。
  ③ 推远了太快：最快 3000 → 1200 计数 / 秒（平台到最快只翻 2.7 倍，原来 6.7 倍），平台、爬行不变。④ 中键滚动 2 / 10 / 40 → 4 / 20 / 80 格 / 秒。
  ⑤ "轻点红点 = 左键"默认关（GestureEngine.Config.stickTap；设置里照样能开）。
  ⑥ 中键点一下触发了安卓自己的动作：豆花这边没问题——中键单击照常发给系统（桌面模式下豆花的虚拟鼠标就是这台手机的一个鼠标），是用户手机上
  三星"鼠标和触控板 → 自定义鼠标按键"把中键设成了一个动作（`settings get system mouse_middle_button_option` = 6，默认应是中键单击），所有鼠标（含豆花的）中键都被系统截走；
  另外用户开着的无障碍服务 Solaar Android（MouseActionService）也可能截鼠标按键。用户自己的设置，豆花不动（按住中键滚动不受影响：按着时不发中键）。
  ⑦ 用户提的"三个键占空格的位置、别让它们占一整行把别的键压矮"：已做，见下一条"小红点状态再改一轮"。
- **小红点状态再改一轮：固定合并、三个键占空格行 + 快捷行、G / H / V / B 圆弧切口**（2026-11-01，用户上手后："小红点状态把分裂布局取消掉，默认就是合并的布局"；
  "现在 UI 是直接叠加的吧，G、H、V、B 这四个键得做一个避让，它那个边缘现在看起来有点丑"；"三个键……想用它占用空格行，还有包括空格下边那一行（那不是有一个自定义的按键吗），
  高度跟这两个高度等高"；协调者之前给的摆法 ?123 │ , │ 空格（缩短）│ 左 │ 中 │ 右 │ . │ ↵、中键正对红点下方，用户说"大致没错"）。只改 App，不改协议
  （TouchRouter 是服务和 App 共用的，红点按圆认要服务也换新代码：装完照样重启服务，不重启的话服务那边按正方形认，四个角会归红点）。
  - **固定合并**：指点 = 小红点时 KeyboardPlan.plan 一律按合并摆（不管"布局"选的自动 / 分裂 / 合并），shouldSplit 对小红点永远假；⋯ 菜单的"布局"那一项小红点状态下不显示
    （MainActivity.onMenuScreen），选过的布局值不动，换回触控板 / 关时照它。删掉的：分裂中间留一列（STICK_COLUMN、Sizes.stickColumn、defaultSplit 的小红点分支、
    自动分裂按这一列算）、红点嵌在两半之间的缝上、KeyRows.stickPoint 的分裂参数、对应的测试（stickSplitDefault / stickAutoThreshold / stickSeam / stickSavedSplit）。
    Prefs 里小红点那份分裂格子（kb_split_stick_*）不再读，留着无害。
  - **三个鼠标键占底行 + 快捷行**：不再从键盘下面切一排（Plan.block、Sizes.mouseRow、MOUSE_ROW_MIN_KEYS 删了），别的行恢复原来的高度（7 行时同指点关着）。
    底行（空格那一排）和快捷行在中间让出同一段空位（Plan.hole，面板 x；KeyRows.place 的 hole），三个键竖着跨这两行（连两行之间的缝；快捷行没有键时只占底行；
    快捷行分到两边太挤时也只占底行——审查后加的，见本条最后），
    view 铺满空位（MouseKeys 铺满模式，键帽各缩进 2dp，同键盘的键），报的区域也铺满——空位里每一点都归某个鼠标键，不会被"缝分给最近的键"分给旁边的键。
    底行：?123 , 空格 │ 空位 │ . ↵（连苹果设备时 🌐 照旧跟在 ?123 后面）；?123 , 照整行时的宽度不动，只有空格伸缩（又一个键帽，STICK_SPACE_KEY）；
    快捷行的键按个数分到空位两边（KeyRows.spreadLeft：按两边宽度的比例、四舍五入，两边都有地方又不止一个键时每边至少一个），顺序不变、各自变窄。
    一个快捷键时它放在宽的那边，另一边那截空着，按上去照"先定行"分给同一行的它（以前它铺满整行，结果一样）。
    空位多宽（KeyboardPlan.stickHole），先后照顾：① 中键正对红点、左右键一样宽（空位以红点那一点为中线对称，照真 ThinkPad）；② 空格尽量长；③ 三个键各 ≥ 44dp（Sizes.keyW）：
    1. 对称：空格能多长就多长、最长 1.5 份（同 ?123、↵，协调者建议的"不少于 1.5 份"），空位至少 144dp（三个 44 + 缩进）；对称时空位右边比 . 原来的左边靠左，
       多出来的那截给 . ↵（一起变宽）。宽的窗口（手机横屏、大屏分屏）空格 1.5 份、空位 3 份宽（884dp 宽时三个键帽约 98 : 56 : 98dp）；
       手机竖屏空格 1–1.5 份、三个键各 44dp（411dp 宽：空格 1.25 份；360dp：1 份）；连苹果设备的宽窗口（≥ 约 720dp）空格约 1–1.35 份、三个键各 44dp。
    2. 对称放不下（连苹果设备 + 窗口不到约 720dp 宽：🌐 把空位左边挤到红点附近；或者很窄的窗口）：空格最短 1 份（STICK_SPACE_MIN）、右边到 . 原来的左边，中键离红点最近
       （苹果设备 + 411dp 宽的手机竖屏：三个键各约 37dp、中键往右偏约 20dp），三个键各最窄 32dp（Sizes.minKey）。
    3. 再窄（不到约 270dp；苹果设备不到约 360dp）：不让位、没有三个鼠标键（红点照样有）。
    **和要求不一样**：① 空格不是一直 ≥ 1.5 份：手机竖屏（360–480dp 宽）三个 44dp 的键对称摆、空格就只剩 1–1.5 份，要 1.5 份就只能让三个键比 44 窄、或者中键偏离红点；
    按"中键正对红点 > 空格长 > 鼠标键宽"的顺序取舍（空格最短 1 份 = 一个字母键宽）。② 苹果设备 + 手机竖屏时三个键比 44dp 窄（最窄 32）：空格 1 份、. ↵ 不缩也排不下三个 44，
    要么不显示三个键、要么变窄，取了变窄（三个键有两行高，按得到）。③ 中键在宽窗口上下限 56dp 比 1 / 5.4 宽（照旧的 middleKey 规则），所以 2.2 : 1 : 2.2 只在空位很宽时成立。
  - **G / H / V / B 圆弧切口**（照真 ThinkPad：键帽被和红点同心的圆弧切掉，切口和红点之间约 0.5 毫米的缝）：红点按得到的范围改成圆（报的还是正方形，
    TouchRouter.Region.contains 对 REGION_STICK 按内切圆算，服务、App 本地分流、KeyFingers 同一份），半径 R；红帽半径 R − 1.5dp，
    键上切口半径 R + 1.5dp（Sizes.stickGap = 3dp，范围的边在缝的中线：缝的一半归红点、一半归键，同"缝分给最近的键"的意思）。凡是键帽矩形碰到切口圆的键
    （字母层是 g、h、v、b；符号层是 ] < ~ 这些，Fn 层是 PgUp / PrtSc……）都按"圆角矩形减去圆"画（Style.CutCap，Path 的 DIFFERENCE；底色、按下、高亮、边框都沿圆弧走，
    按键边框开关、主题色照旧）；TrackPointView 嵌在键里时不再画方块洞（页面底色），只画红帽和按着时缝里的一圈。看着是键的地方按下去就是键（以前方块的四个角看着是键、按上去归红点）。
    服务连着没接管（View 自己收触摸）时：（已改，见本条最后"审查后"④）第一版只让红点 view 吃圆里的按下，圆外落到底下的面板上（KeyFingers 的洞也按圆）。
  - **红点多大**（KeyboardPlan.stickRadius）：每个键被切掉的面积按切口圆和键帽矩形相交的面积精确算（cutShare：按 x 积分竖条，圆和上下边交点处分段，弧那段用原函数），
    门槛照旧——字母层每个键 ≤ 16%、每一层每个键 ≤ 25%；外加**字不被切口吃掉**：把键上的字看成以键帽中心为圆心、半径 10sp（Sizes.labelRoom，跟着系统字体大小；第一版是 10dp，审查后改的，见本条最后；18sp 的单个字连上下出头约 ±9sp）的一块，
    切口碰到它时字竖着往远离切口的方向挪（labelShift，KeyboardPanel 加上下内边距：往上挪 d 就在下面加 2d），挪完那块字还要在键帽里，挪不开就算嵌不下。
    切口也不过底行的上沿（三个鼠标键在那儿）。同样的面积，圆比方块能大约 13%：手机竖屏 360 / 411dp 宽时直径 40 / 46dp（以前方块边长 36 / 42），大屏分屏下半边（884 × 360）46dp（以前 40），
    画出来的红帽 = 直径 − 3dp（以前 0.8 × 边长）。卡住大小的是符号层的 ]、<（25%，红点正对着 ] 的下沿正中），字母层只切掉约 10–12%；横屏 / 分屏这种行矮的键盘上 ] 的字往上挪约 8dp、< 往下挪约 6dp。
    嵌不下的退路照旧（放键盘上面 / 下面 / 旁边，STICK_OUTSIDE 从 3 改成 2）。
  - 打字测试的触点日志不记按在键盘里那三个鼠标键上的触点（同红点：它们在键盘范围里面、不是按键；TouchRouter.down）。
  - 没加：三个鼠标键的最小高度（它们和底行、快捷行一样高，键盘矮到这两行加起来不到 48dp 时照样摆——那时字母键本来也没法按）。
  - 测试：KeyboardPlanTest 的小红点部分改写（固定合并扫一遍、空位规则、切口几何、红点 + 鼠标键 + 键的逐点分流、全尺寸扫描），KeyRowsTest（让位的行、摆法），
    KeyFingersTest（圆形的洞、空位的洞），TouchRouterTest（红点按内切圆认、角上按的是键）。
  - **审查后的修改（同一天，只跑了离线测试和编译）**：
    ① **一进小红点状态就闪退**：缩短的空格（STICK_SPACE_KEY）只在底行的 before 里，KeyboardPanel.addCaps 只给 whole / left / right 建键帽，
    showLayer 拿到 null、refreshLabels 抛 NullPointerException；指点存在 Prefs 里，之后每次打开键盘页都闪退、菜单也改不回去。离线测试不编译 KeyboardPanel（Android 类），测不出来。
    改法：哪些键要建键帽收进纯 Java 的 KeyRows.capKeys（whole / left / right / before / after，去重、不含空位），addCaps 只调它；
    KeyRowsTest.capKeysCover 照 KeyboardPanel 建键帽的范围（功能行带 ⋯ 那份 + 三层中间四行 + 底行和快捷行）收一遍，拿 keys() 在功能行带不带 ⋯ × 三层 × 整块 / 分裂 / 让位
    × 苹果与否 × 快捷键 9 / 3 / 1 / 0 个时给的每个键去比（把 capKeys 改回漏 before / after 的样子，这组检查报缺缩短的空格）。
    ② **快捷行分到两边太挤就不让位**（**和用户的要求不一样**：用户要三个键"高度跟这两个高度等高"）：手机竖屏 9 个默认快捷键分到空位两边，键帽只剩 21–27dp 宽、
    空格 32–47dp，德 / 法 / 西 / 意 / 日 / 韩语的短名缩到 8sp 也放不下、从词中间截成半截（审查者用本机 Noto Sans 量的，没在真机上看）。现在 KeyboardPlan.shortcutsSpread：按 spreadLeft 分开后每边每个键连缝 ≥ 32dp
    （Sizes.minKey，同分裂时字母键"再窄拇指按不准"的门槛）才让，Plan.spread；不让时快捷行照旧铺满整行（KeyRows.place 的 spread 为假），三个键只占空格那一排
    （Plan.keys 的下沿 = 底行下沿，KeyRows.holeBottom；面板自己收触摸时的空位洞同样只到底行下沿）。结果：手机竖屏（360–411dp）9 个快捷键时三个键只有空格那排高，
    411dp 宽 7 个以下、手机横屏、大屏分屏下半边（884dp）照旧跨两行；用户主力机分屏下半边存的那份合并键盘（约 390dp 宽）9 个快捷键时也只占空格那排。
    没取的另一条路：把三个鼠标键缩到 32dp 宽（411dp 宽时快捷键就有约 34dp），但 360dp 宽照样分不开，中键对着红点、左右一样宽也保不住。
    ③ 键帽（Style.keyButton，整屏键盘和数字键盘）放不下时末尾省略（ellipsize END），不再在词中间断开只露半截；按钮条、菜单项这些（Style.button）不动。
    缩短的空格在 360dp 宽时约 32dp，德语"Leertaste"在 8sp 下约 35dp（审查者量的），会显示成"Leerta…"这类。
    ④ 服务连着没接管（View 自己收触摸）时，嵌在键里的红点 view 一根手指都不收（MainActivity，TrackPointView.isEmbedded），全落到底下的面板上，
    面板的 KeyFingers 洞按内切圆把红点挖掉——和分流同一条规则。第一版只在 ACTION_DOWN 时看圆：红点收下第一根手指后，ViewGroup 把落在它方块里的后来的手指
    直接交给它、不再问（AOSP ViewGroup.dispatchTouchEvent 的"Child is already receiving touch within its bounds"），第二根手指按在 g 被切掉的那个角上就被吞了。
    红点放在键盘外面时照旧整块吃掉（不然第一根手指落空，这次手势后来按在键上的手指也收不到）。TrackPointView.hits 没人用了，删了。
    ⑤ 键上的字离切口多远（labelRoom）从 10dp 改成 10sp（Style.sp，KeyboardScreen.LABEL_ROOM_SP；KeyboardPlan 判断嵌不嵌得下、面板挪字用同一个值）：键上的字是 18sp / 13sp，
    系统字体调大后按 dp 算的字块比字小，挪开的字压进切口。系统字体大小一改 Activity 就重建，跟着重算。没改的：挪字靠加上下内边距，留给字的高 = 键高 − 2 × 挪的距离，
    18sp 的一行（连字体自带的上下留白）比 2 × 10sp 高一点，被挪的那几个键可能被自动缩字号缩小一点、比旁边的键小一号（字体放大前后都有，没量过）。
- **实体键盘的指点杆**（2026-11-01 用户提的，第 1B 批已做，见上面"实体键盘布局第 1B 批"）：实体键盘模拟时红点的位置不一样——真键盘最下一排比中间一排往右错半个键，
  红点在真实的 G、H、B 之间（B 骑在 G|H 那条缝下面，V 不挨着）；要切口的也不是 g、h、v、b，按实际挨着的键算（真 ThinkPad 是 G 的右下、H 的左下、B 的上沿正中）；
  实体键盘布局没有快捷行，三个键主要占空格那一排。电脑键盘不对称，空位和中键的位置要按那套键位重算（KeyboardPlan.stickHole 的规则可以照用）。
- **桌面模式的虚拟鼠标只留竖向滚轮倍率**（2026-11-01，排查"桌面模式下内核一直没来设倍率"）。主力机上服务日志只有"内核发来 START，+1ms""内核发来 OPEN，+1ms"，
  之后"建好 5 秒内核没来设 Feature 报告"，一个 GET / SET_REPORT 都没有。主线内核 hid-input.c 的
  __hidinput_change_resolution_multipliers：一个 Feature 报告里有不止一个字段（maxfield > 1）时要先 GET 当前值，设备带 HID_QUIRK_NO_INIT_REPORTS 就直接放弃、一个请求都不发；
  只有一个字段时不先 GET、直接 SET。三星内核为什么不发看不到，推测落在"放弃"那条路上（没核实）。
  改法：uhid 单独一份描述符 Hid.MOUSE_DESC_HIRES_V，和 MOUSE_DESC_HIRES 一样，只是 AC Pan 那个 Logical 集合里没有倍率——整个描述符只有一个 Feature 字段（竖向倍率 2 位 + 6 位补齐，
  1 个字节；补齐项没有用法，内核不算字段）；输入报文排布一个字节都没动（HidTest 逐字段比）。UhidMouse 只认竖向那两位（横向一直 ×1、照旧一格一格），问的时候回 1 个字节、只有竖向那两位。
  换算收在 Hid.WheelOut.setUhidFeature / uhidFeatureReport，UhidMouse 只调它们（UhidMouse 依赖 android.*、不进离线编译，HidTest 锁住的就是这两个方法，不在测试里另抄一份）。
  蓝牙那份（MOUSE_DESC_HIRES，跟着"连续滚动"开关）不动：蓝牙主机没报过这个问题，改了还得让每台主机重新配对。Uhid 读线程的诊断日志留着，下次连上看结果（见"进行中"）。
  代价：桌面模式下横向连续滚动也只能一格一格（Android 横向滚动本来用得少）。
  **主力机结果（2026-11-01 07:08，只留一个倍率字段那一版）**：还是只有"内核发来 START""内核发来 OPEN"，接着"建好 5 秒内核没来设"，一个 GET / SET 都没有。
  所以推测（"不止一个字段要先 GET、带 NO_INIT_REPORTS 就放弃"）不是原因：三星内核建 uhid 鼠标时根本不做设倍率这一步，桌面模式下连续滚动在三星手机上做不到。
  设置里"连续滚动"的说明（smooth_scroll_hint，九种语言）改成"桌面模式：三星手机上实测不支持，照旧一格一格"。只留竖向那份描述符留着（无害，别的手机的内核要是做这一步，单字段那份更不挑）；
  读线程的诊断日志也留着（以后换手机能一眼看出内核问没问）。
- **手柄页第一批：桌面模式端到端**（2026-11-01，用户："要一个手柄界面，独立于所有界面，不需要触控板也不需要键盘；可以选各种固定布局……也可以自定义加按键……
  震动也要；当一个额外功能加进来"；协调者的方案 design-gamepad.md 合成三份调研，要拍板的 D1–D9 照推荐做；协议 19）。
  - 输出：服务再造一个 uhid 手柄（"Douhua Gamepad"，Hid.GAMEPAD_DESC：Game Pad、15 键、Hat 1..8 / 0 回中、左摇杆 X/Y、右摇杆 Z/Rz、扳机 Brake/Accelerator），
    这台手机的 Android 当普通手柄（Generic.kl）→ Moonlight 认 → Sunshine 在电脑上造 Xbox 手柄，只认 XInput 的游戏也能玩。第 4 键 = 西（Xbox X）、第 5 键 = 北（Xbox Y）
    （Generic.kl 把 0x133 映成 BUTTON_X，Moonlight、SDL、scrcpy 都这么排）。扳机第一批只发轴（Moonlight 有轴时压掉数字 L2 / R2）。
  - 触摸：TouchRouter 加 REGION_GAMEPAD → GamepadTouch；三条路——服务接管（报区域）；桌面模式服务连着没接管 / 没开服务时**也走 App 本地分流**
    （localGesture、local.setRegions 的条件改成 btMode || gamepadOn），出的报文经 CMD_GAMEPAD 给服务、和服务那路合并；不让各控件 View 自己收（摇杆、十字键、搓键要跟手指，
    每个 View 写一遍多指就是把分流再抄一份）。测试机（服务找不到触摸屏）上也能出手柄。蓝牙模式第一批进不了手柄页（菜单里没有"手柄"，动作给提示）。
  - 页面：GamepadScreen 独立一页（盖住主界面、不透明），9 个预设（Xbox 默认、PlayStation、Switch Pro、SNES / DS、FC / NES、Game Boy（偏好竖屏）、GBA、Mega Drive 6 键、街机 8 键），
    GamepadPlan 按窗口自动排；⋯ 在顶上正中，按住 0.6 秒才开菜单（游戏里拇指乱碰）；菜单：预设：X（点一下轮换、菜单不关）/ 返回触控板 / 设置 / 旋转 / 退出。
    入口：主界面、键盘屏 ⋯ 菜单里"手柄"（桌面模式才有），动作清单 gamepad。手柄页开着时吞返回、左右边设排除区（没接管时系统手势还在）。屏幕边缘留白照样生效。
  - 反馈不加新种类：键按下（含簇里新按下的、十字键从中间推出一个方向）= 按键那个能打断的"嗒"（button DOWN → playKey）；十字键按着换方向、摇杆推到边 = 咔嗒（HAPTIC_TICK）；
    摇杆推动不震不发颗粒；按住 ⋯ 到点"嗒"。游戏震动不接（靠 Moonlight 的"用设备震动模拟"）。
  - 和方案不同 / 方案没写的：① 加了 gp_md6（"Mega Drive 6 键"，"键"要翻译），方案只列了 gp_arcade8；② 手柄页菜单的"退出手柄"叫"返回触控板"（gp_exit），另留着全局的"退出"；
    ③ 键盘屏 ⋯ 菜单也有"手柄"（方案只写主界面）；④ LS / RS 放在中间那块（横排：Select / Home / Start 下面一排两头；竖排：最下面那排两头），方案没定位置；
    ⑤ 面键簇的主键也加了 2dp 回差（停在几颗正中间时不来回换），十字键回中加了回差（推出去要到死区、回中要到死区的 0.8 倍）；⑥ 摇杆不报按下 / 松开（不"嗒"）；
    ⑦ CMD_GAMEPAD 只有不是中立时才触发建手柄；⑧ 惯性转着时碰手柄控件静默停（同键）；⑨ 桌面模式不在手柄页时本地分流的区域清空；⑩ TestClient 认得 MSG_GAMEPAD。
  - 已知：街机 8 键在手机竖屏放不进半边（两排各 4 颗），缩到最小值以下（约 29dp）；Mega Drive 6 键竖屏要 352dp 宽才不缩。第二批（设置页"手柄"标签、自定义勾组件、参数滑块）见下一条（原厂配色没做）、
    第三批（蓝牙手柄，开关默认关）没做。

- **手柄页第二批：设置页"手柄"标签 + 自定义 + 6 个预设**（2026-11-01，同一份方案 design-gamepad.md 的第 2 节"第二批"、第 4 节；用户："调研完了你就先做，做完我再检查"）。
  不改 Session、不升协议（GamepadConfig 的字段第一批就全发了；N64 的 C 键在 GamepadTouch 里换成轴，报文、描述符不变）。GamepadTouch、Proto 是两边共用的：
  服务接管时 C 键要新服务才认（旧服务不认识输出码 24–27，什么都不发），装完照样重启服务（协议号没变，App 按 hello 里的 APK 路径会自己重启它）。
  - 设置页第六个标签"手柄"（排在"空中鼠标"后面）：一块预览（选的那一套在 930 × 350dp 分屏下半边的样子，GamepadPainter 和手柄页同一份画法）、
    预设网格（15 个预设 + 自定义，每格一张小图）、自定义那一节（选中自定义才显示）、摇杆（推满距离、死区、抗死区、浮动中心、底座跟着走、推到边咔嗒）、
    十字键（8 方向、斜向宽度、中间多大不算方向、换方向咔嗒）、面键（D3 的滑动换键开关，开着才显示"两颗中间一起按的范围""滑出多远全松"）、一段"Moonlight 里建议这样设"。
    蓝牙模式下顶上一句"手柄目前只在桌面模式下能用"，下面照样能先设好。手柄页 ⋯ 菜单的"设置"直接停在这一页。改完回到主界面 applyPrefs 换样子、发参数（两路回中立）。
  - 自定义：从任一预设出发（第一次点"自定义"复制现在选的那个；"从某个预设重新开始…"整套换掉），勾十字键 / 左摇杆（两个都开时再选哪个在上）/ 面键排法（十种或不要）/
    字样（Xbox / 任天堂 / PlayStation / 世嘉 / 街机 / 数字）/ 右摇杆 / 肩键 / 扳机 / Select / Home / Start / 摇杆按下，自动排（GamepadPlan），存成一行（Prefs gp_custom，
    GamepadSpec.encode：取值写名字，认不出的跳过、缺的照 base 那个预设，坏数据不闪退）。⋯ 菜单"预设：…"轮换时存过自定义才轮到它（排在最后）。拖放自由摆位置是第四批。
  - 补的预设（顺序：Xbox、PlayStation、Switch Pro、Wii Classic、GameCube、N64、SNES / DS、FC / NES、Game Boy、GBA、MD 3 键、MD 6 键、Neo Geo、街机 6 键、街机 8 键）。
    约定（没有公认答案，照模拟器常见的默认，凭记忆，方案第 12 节第 7 条要定的）：N64 A 南、B 西（在 A 左上）、四颗 C 键 = 右摇杆推满（在面键簇里，能搓、能两颗斜着）、Z = 左扳机（左边外角）、
    只有 START；GameCube 按位置 A 南、B 西（左下）、X 东、Y 北，L / R = 扳机、Z = R1（R 的里边），有 C 摇杆、摇杆不能按；Wii 经典（初代）L / R 是扳机在外角、ZL / ZR 是肩键在里边，− ⌂ +；
    MD 3 键 A B C = 西 南 东；Neo Geo 照 Neo Geo CD 手柄 A 南 B 东 C 西 D 北（排成一道弧）；街机 6 键 = 8 键的前三列（上排 X Y RB、下排 A B RT）。
  - 和方案不同 / 方案没写的：① 肩键从"无 / 一对 / 两对"改成 L1 R1 LT RT 各自有没有（N64、GameCube 两边不一样），自定义里仍是"肩键""扳机"两个开关各管一对（开关上写"靠里那对 / 外角那对"和这一套里实际叫什么：Wii 经典里靠里那对叫 ZL ZR、外角那对叫 L R，写死字母正好说反，审查发现）；
    ② 字样加了"数字"（任务里点名的），并掉了第一批的"Switch"字样：任天堂字样有 Home 时中间是 − ⌂ +、没有是 SELECT START；N64、GameCube 的键帽不跟字样；
    ③ 自定义里左手、面键都能"不要"（方案没列），手区照样左右对称留着；④ 原厂配色（方案写"可选"）没做；⑤ 设置页滑块存整数（%、度、dp），Prefs.gamepadConfig 换算；
    ⑥ 预览只按 930 × 350 一种比例画（不画竖屏的样子）。
  - 已知：竖屏手机上 Neo Geo、街机 6 键和街机 8 键一样放不进半边，按比例缩到最小值以下（要横屏用）；随便勾出来的自定义（左手两个 + 8 颗街机 + 右摇杆……）在小窗里也会缩到最小值以下。
    N64 的 C 键、GameCube 的 B / X Y 比 44dp 小（全尺寸 43.5 / 49dp，手机竖屏约 35 / 37dp；fitted 照样为真，它们的最小值按比例）：也定 44 的下限的话 C 键的菱形压到 A、
    N64 在手机竖屏放不下、整页（连 A）缩到最小值以下，所以留着真机比例（审查发现 fitted 的说明对这几颗不成立，改了说明和测试）。

## 测试

- **测试设备**：用 USB 连的测试机（序列号见 `.private/NOTES.md`），命令里显式 `adb -s`。用户的主力机禁止拿来测试。
  测试机不是三星、没有 DeX，服务在它上面找不到触摸屏（见"想做的功能"里"服务支持缩放面板的手机"），桌面模式只能交给用户在主力机上试；`tools/build.sh --install`、`start-server.sh` 等脚本连着多台时会列出来让人选（tools/adb-device.sh），我自己跑时一律加 `-s <测试机>`。

- `tools/test.sh`：ThemeTest（十八套槽位齐全、先深色后浅色、纯黑和以前一样（功能键也是）、对比度、功能键和键帽 / 页面的色差，`java … dev.douhua.ThemeTest print` 打出取值和对比度表）、GestureEngine、MtDecoder、TouchDevice 解析、KeyFingers（键盘面板自己收触摸时的多指）、KeyRows（分裂键盘哪个键归哪半、摆放、哪些键算功能键，
  以及按面板报区域的方式交给真正的 TouchRouter / KeyFingers 逐点核对缝只在各自那一半里分、中间是触控板）、KeyboardPlan（布局按钮、触控板放哪一侧、门槛、
  调整栏按钮宽度、⋯ 放触控板顶上哪儿 / 菜单弹在哪 / 调整栏放上还是下（再扫一遍各种尺寸查 ⋯ 离边够远、在触控板里、菜单不出屏、旁边放得下时不压着 ⋯）、
  主界面的 ⋯ 放不下也照样放（padMenuTop，扫一遍查一定在触控板里）、菜单项多窗口矮时怎么排（menuGrid：先压矮再分列，扫一遍查排得下、没有空列、不出页面、高度够时一直一列），外加各种尺寸扫一遍查触控板不压键、够大、分裂了键够宽；分裂时对称调大小：默认格子喂回去不变、存过的格子怎么摆、拖法和约束、
  窗口变窄时撑开，存过格子再扫一遍查两半对称、够宽、不重叠、不出界、seam 对；KeyboardSnapTest 里有 applySplit）的离线测试；服务的会话交接部件（TouchGrabLock、SessionPolicy、
  RotationLock、GrabCondition）、LogThrottle、Shell（会真起 echo / sleep 子进程）、实体键（KeyDevices 解析 getevent 输出 / 挑设备、HardKeyGate 状态机）也在 ServerPartsTest 里。
  分屏透传（TouchSplit 的归属、InjectStream 的动作码 / 下标 / 手指集合、MtDecoder 的 cancel、MtDecoder → TouchSplit → InjectStream 整条路含替换 slot / 手掌 / 释放独占，
  加两组随机序列）在 PassthroughTest 里，每个事件都过一个照 AOSP input_verifier.rs 写的校验器（校验器本身也测了能认出坏流）。
  小红点：TrackPointTest（曲线的点、单调 / 平台 / 封顶、8 / 12 / 抖动的节拍积分、50ms 上限、灵敏度、负惯性起步更快 / 平台一样 / 不反向、轻点、颗粒、中键滚动的方向 / 主方向 /
  按格 / 连续、停、20 秒暂停）、TouchRouterTest（第二根手指不理、节拍开停配对、左键三处共用计数、中键单击 / 滚过不补 / 被取消不补、中键 + 触控板滚动、releaseAll / 换参数停、
  onTarget、nearestKey 不选、碰红点停惯性、按在红点 / 键盘里的鼠标键上不记触点、红点按正方形的内切圆认——圆里归红点、四个角归键）、GestureEngineTest（中键滚动）、
  KeyboardPlanTest（2026-11-01 第二次改写：小红点固定合并（布局 × 尺寸 × 存的格子扫一遍，都和合并一模一样）、底行和快捷行让出的空位（stickHole 的几种情况：对称 / 空格最短 / 不让）、
  三个键怎么铺（holeKeys：对称时中键正对红点、左右一样宽，2.2 : 1 : 2.2）、切口几何（cutShare 对公式、cuts、labelShift）、红点嵌在键里多大（每层每个键被切掉 ≤ 25%、字母层 ≤ 16%、字躲得开）、
  放外面、⋯ 回功能行；stickRouting 把红点、铺满空位的三个鼠标键、三层的键、键盘范围照 MainActivity 的顺序交给真正的 TouchRouter / KeyFingers 逐点核对：红点圆里归红点、空位里归那一格的鼠标键、
  别处和没有它们时一模一样、方块角上和切口外看得见的键就是那个键；再扫一遍各种尺寸 × 布局 × 指点 × 三键条 × 存的位置 × 有没有快捷行 / 一个快捷键 / 苹果底行，
  查空位规则、空格 1–1.5 份、鼠标键宽度、切口比例、字挪完不被吃掉、不出键帽、快捷行让不让位是 shortcutsSpread 给的——让了每个快捷键连缝 ≥ 32、没让的和指点关着时一模一样、
  三个键的下沿跟着走；stickHoleRules 里 shortcutsSpread 的几种宽度和 32 那条边界）、KeyRowsTest（stickPoint 正好在 g、h、v、b 交界，rows / layers；
  让位的行（底行 before / after、快捷行 spread、份数、holeTop、holeBottom、spreadLeft）、让位时的摆法（空位里没有键、?123 , . ↵ 宽度、空格伸缩、快捷行分到两边顺序不变、别的行不变；
  spread 为假时快捷行和不让位时一模一样）、capKeysCover（每个会显示的键都有键帽，照 KeyboardPanel 建键帽的范围核对））、
  KeyFingersTest（洞：红点按内切圆、空位整块）、
  ThemeTest（每套小红点对页面 / 触控板 ≥ 3:1、还是红的）、HidTest（CMD_CONFIG 56 字节往返）。
  触控板背景图：PadFitTest（填满盖满不变形、完整显示整张在里面、拉伸铺满、原大小补回解码缩的、平铺相位、焦点规则 / S = B 处连续 / 拖分隔条不跳、吸附、按形状挑哪套、
  解码尺寸的上限和"没撞上限不用再放大"（撞上限时打出最多放大几倍）、空的 / 坏的输入、每套主题 padFill 不透明）。
  惯性滚动 / 连续滚动：GestureEngineTest（起不起、闭式解对不对、抓住不算点击 / 长按、cancel / 滚轮 / stopSpin / Ctrl 停、方向、定时器晚到、15 秒兜底、连续模式的单位和咔嗒、双指锁轴、乱序序列）、
  TouchRouterTest（碰键静默停、碰按钮 / 空白 / 左键震刹住、缩放整格、锁 Ctrl 不起、换参数停）、HidTest（小 HID 解析器逐字段比新旧描述符、Feature 报告的样子、uhid 那份只有一个竖向倍率字段（照内核数 maxfield = 1）、没有 4 字节项、
  倍率字节（含 UhidMouse 设 / 问倍率直接调的 WheelOut.setUhidFeature / uhidFeatureReport）、WheelOut 换算 / 余数 / 拆帧、CMD_CONFIG 往返、ScrollInertia 对数值积分和方案的表）、ServerPartsTest（uhid 的 GET / SET 事件解析、回复逐字节；OneShotTimer 真起一个定时器线程：撤掉 / 取代后卡在锁上的那次不调、回调里再排接得上，
  加一条用真 TouchRouter 的"过期的惯性帧不被当成长按"）。
  屏幕边缘留白：EdgeMarginTest（哪几条边留——全屏、分屏上 / 下 / 左 / 右、小窗、差两个像素、超出屏幕；留多少——0、负数、窗口没量好、窗口小时按比例缩；edges；随机窗口；
  再把收进去的页面交给真正的 KeyboardPlan 扫一遍窗口 × 留白：菜单、⋯、调整栏、三键条、键盘外面的红点都在页面里、地方够时离窗口边至少 max(20, 留白)，打出每种真查到了几次）、
  KeyboardPlanTest 的 perSideEdges（各边不一样时 ⋯ / 菜单 / 菜单排法 / 调整栏 / 三键条的具体值，四边 20 的 withEdges 和原来一样）。
- 手柄页：GamepadTouchTest（单键引用计数 / 滑出照样按着 / 扳机是轴；面键簇最近的键、范围里没有死区、最多两颗、两颗中间一起按、回差、滑动换键先松后按、滑出 24dp 全松再回来、
  关掉滑动换键；十字键 8 个方向、斜向窄、边界回差、死区和回差、滑出只看角度、4 方向、第二根不理；摇杆浮动中心、Y 往下为正、封顶、底座跟着走 / 顶住、死区 + 抗死区、推到边咔嗒和上弦、
  一帧一份、第二根不理；抬起冲掉攒着的轴；releaseAll / 换参数后全零、迟到的事件不发；没用过不发；换布局按着的照用旧的；同一个 slot 又按下；
  N64 的 C 键：一颗推满、相邻两颗斜着、相反两颗回中、和右摇杆那根手指取远的、全松回中立；乱序随机序列 400 组 × 120 步）、
  GamepadPlanTest（930 × 350 的 Xbox 全尺寸、斜着错开、贴下沿；PlayStation 十字键在上；Game Boy 竖屏；报区域顺序、编号；十种面键排法（大小不一的照各自直径算缝）；
  N64 / GameCube / Wii 经典 / MD 3 键 / Neo Geo / 街机 6 键摆得对；自定义什么都不要；扫 15 预设 × 尺寸 × 3 种边距：
  不重叠、不出界、⋯ 离边、手区对称、贴下沿、够大时不缩到最小值以下（竖屏时面键簇最小也比半边宽的除外）、放得下时逐颗面键不小于各自的最小值（faceMin）；
  930 × 350 每个预设全尺寸、每颗面键按自己的倍数；再扫 240 套随机自定义 × 尺寸：不重叠、不出界、编号不重复）、
  GamepadSpecTest（15 个预设编号不重复、第一批 9 个的字和输出照旧；⋯ 轮换（存过自定义才轮到）；每种排法 × 每种字样的字和输出对得上；N64 / GameCube / Neo Geo / 世嘉 / 数字的约定；
  预设自己的肩键名只在字样没换时用；每个预设 × 每种字样四颗肩键全开名字互不相同、设置页开关上写的和键帽一样（Wii 靠里那对 ZL ZR）；自定义存成一行再读回来一样（每个预设 + 随机 2000 套）、坏数据 / 认不出的项和取值 / base 认不出都不闪退）、TouchRouterTest（REGION_GAMEPAD 走手柄、成员不被直接命中、按下时定归属、onTarget、
  releaseAll / setConfig / setGamepadConfig 全零、一帧一份、没用过不发、碰手柄控件静默停惯性）、HidTest（GAMEPAD_DESC 逐字段、报文逐字节、方向键对内核的 hat 换算、merge）、
  ServerPartsTest（GamepadMux，含设备晚建好补写之后松开写得出去；CMD_GAMEPAD / CMD_GAMEPAD_CONFIG 不算心跳、GamepadConfig 往返 / 长了跳过 / 短了用默认）。
  真机只读核对（手柄页进过一次以后）：`adb shell getevent -lp` 里 Douhua Gamepad 有 BTN_SOUTH…BTN_THUMBR、ABS_X / Y / Z / RZ / BRAKE / GAS / HAT0X / HAT0Y；
  `adb shell dumpsys input` 里它的 Classes 有 GAMEPAD | JOYSTICK、KeyLayoutFile 是 Generic.kl。
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
- 打字测试（盲打纠偏的数据）：手机装调试包、服务重启成同一版，`tools/type-log.sh`（蓝牙连这台电脑；桌面模式下加 `--desktop`，旧名 `--dex` 照样认），
  在打开的网页里看着手机打 30 句（英文小写、拼音各半）；完了 `tools/type-analyze.sh` 出误按率、每个键的落点偏移、
  缝里的触点新规则分对 / 分错几个。`tools/test.sh` 里的 `analyze.py --selftest` + `KeyReplay check` 只验证分析脚本本身。
- 实体键盘第 1B 批（`tools/test.sh`）：FixedLegendsTest（直接读 res/raw 的 kbd_win / kbd_mac / kbd_catalog：转义、画不画得出来、文件头的数据源版本 = sources.json、
  清单（美式 ANSI / ISO、德语只 ISO、小数点）、抽查值（mac:de ⌥L = @、⌥Q = «，win:de AltGr+Q = @、AltGr + Z 左边 = |，win:us 0x64 = \ |……）、不变量（26 个字母、0x64 有字、
  死键只在附加符号上、没有控制字符）、Mac 的 .iso 段正好对调、Mac 模式 ISO 发 0x64 / 0x35 查到 § / `、^ / <）、
  FixedLayoutTest（形状 × 尺寸 × 模式：键数、用法码各一次、0x64 只在 ISO、不发 0x32、Ins / PrtSc / Menu / 🌐 / 小键盘在哪种有；主键区每行铺满 15u、F 行位置、⋯ 槽；不重叠、在自己的区块里；
  ISO 回车 L 形；底行顺序和键名；范围（u）不重叠、每段恰好在一块同行同区块的范围里、↑ 上面和两边 / Mac 的 Ins 格不在任何范围里；摆到像素（六种窗口）不重叠、相邻键隔 2 × gap、
  每段恰好在一块范围里；真 TouchRouter 和 FixedPanel.keyAt 逐点一致（两百多万个点）、缝里只分给同一行这一段的键；钉死的点（↑ 正上方什么都没有、F4 | F5、空着的 ⋯ 槽、
  ISO 回车下半段左边的缝靠左给 #、靠右给回车、L 里面那条缝给回车）；键帽齐全（布局 × 形状 × 尺寸 × 模式 × ⋯ × 让位 × 窗口 × 八种锁着的修饰键）；键帽上的字（两层、角上、压暗、
  第四层、Mac 的两个 Option、死键、AltGr / Alt、放大提示；win:us 锁右 Alt 和锁左 Alt 一样不换层、layer 的规则、Mac 模式 altGr 一律假）；主次键帽；fixedFits 的 32dp 门槛；
  字表是 null 不拼布局（forLegends）；默认位置 / 触控板 / ⋯ / 存过的位置 / 三键条；默认位置守调大小的约束（defaultsKeepDragLimits：780 × 170 一格 32、平板竖屏按 MIN_HEIGHT，
  再扫约 12 万种 布局 × 窗口 × 触控板开关：不比 fixedMinWidth 窄、不比 MIN_HEIGHT 矮、一格 ≥ 32、贴底）；小红点（各种窗口：红点在 B 的中线、
  G / H 键帽下沿，空位对称、够宽、占空格那一排、中键正对红点、空格两段各 ≥ 1 份，嵌在键里时切到 G、H、B、N 不切、切掉的不超过四分之一、G 的角上小字挪走）；
  红点、三个鼠标键和键的分流逐点核对（同 KeyboardPlanTest.stickRouting））、BoardsTest（默认值、形状跟着布局、大小位置按尺寸 × 指点 × 横竖屏分开、键名不以 kb_ 开头；
  40 轮随机序列各 60 步后 kb_ 开头的键一个没变、只写了 kbd_ / kbx_）。原有的测试一个没改、输出逐字一样（KeyboardPlan 的 placeStick 拆出 placeStickAt、KeyRows.functionKey 扩规则都不影响豆花布局）。
  审查后加的：HidTest.modifierHelpers（Hid.ctrl 左右都算、modifierUsages 256 种掩码点完都切回 0）、TouchRouterTest.spinNotWithRightCtrlLatched（锁右 Ctrl 不起惯性、换参数后照样、
  转着时按右 Ctrl 停、连续滚动开着时照旧整格）；把这几处改回去跑过一遍，新加的检查都报错（右 Ctrl 3 项、Hid 1 项、实体键盘 9 项）。
  界面（FixedKeyboardView 画出来的样子、KeyboardScreen 换面板、设置页）离线编不到，没在真机上看过。
- 主机实测扫描（实体键盘布局的前提）：手机装调试包，`tools/kbd-scan.sh --host ipad|linux|… --layout us|de`（桌面模式 `--desktop`），
  主机上打开它给的地址点"开始"，扫完自动跑 compare.py；步骤见 tools/kbd/README.md。`tools/test.sh` 里的 KeyScanTest、`gen_layouts.py --selftest`、
  `check_tables.py`、`compare.py --selftest` 只验证工具和生成的数据本身，主机那边一格都没核过。

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

- **实体键盘第 1B 批：框架 + 美式 / 德语（2026-11-01 写完，没提交，待真机验证；不改协议，审查后改了 TouchRouter 一处（Ctrl 左右都算），装完要重启服务）**：见决策记录。只跑了离线测试
  （FixedLegendsTest 75 项、FixedLayoutTest 5485 项、BoardsTest 154 项，原有的全过、输出逐字不变；审查后 FixedLayoutTest 5511 项、触摸分流 238 项、HidTest 193 项）和编译，界面一次都没看过。要看：
  ① 豆花布局和以前一模一样：调过的大小位置、分裂格子、指点、快捷行、停在哪一层；⋯ → "键盘：豆花儿"点一下换成实体键盘、再点换回来，这些都还在；关掉豆花儿再开，当前是哪块还记着。
  ② Fold 外屏横屏（TKL）、内屏横屏（全尺寸）、内屏上下分屏下半边：键按不按得准、字号看不看得清、L 形回车、两层字和角上小字、死键的颜色；方向键上面那几格按了什么都没有；
  数字键盘（全尺寸）；没调过大小时触控板在上面 / 右边、⋯ 在触控板顶上，放不下时 ⋯ 在 Esc 和 F1 中间。
  ③ 手机竖屏 / Fold 外屏竖屏自动暂用豆花儿布局，⋯ 菜单写"这里太窄"；横过来自动回到实体键盘，转回去又是豆花儿布局（各自的状态都不变）。
  ④ 德语 Windows 模式接 Linux / 桌面模式（主机设成德语）：键帽画的和打出来的一致；锁住 AltGr（右 Alt）后角上的字亮，打得出 @ € {；死键 ´ + e 出 é；
  德语 Mac 模式接 iPad（iPad 实体键盘选德语）：左上角 ^、Z 左边 <（照真苹果 ISO 键盘发的 0x64 / 0x35，iPad 怎么认不知道，对调了就是主机的认法）；⌥（任一 Option）锁住后角上的字亮。
  美式 ISO 两种模式各看一下 1 左边、Z 左边那两个键出什么。
  ⑤ 三条触摸路径都试：桌面模式服务接管、蓝牙模式服务接管 / 没开服务（本地分流）、桌面模式服务连着没接管（面板自己收）；多指交替按、按在缝里、按在方向键上面的空格子里。
  ⑥ 指点换成小红点：红点在 G、H 之间、B 上方，G 的右下、H 的左下、B 的上沿正中切了圆弧（看着顺不顺、B 的字挪下去以后看不看得清）；三个鼠标键在空格那一排中间、中键在红点正下方，
  空格切成左右两段、两段都能打空格；红点、三个键的手感同豆花布局。实体键盘的指点和豆花布局的分开记（在实体键盘上换成小红点，切回豆花布局还是原来的）。
  ⑦ 设置 → 键盘：语言 / 形状（德语只有 ISO）/ 尺寸（键数）/ 模式改完回到键盘页就换；九种语言放不放得下；桌面模式下"打开手机的实体键盘设置"在三星上打不打得开、是不是那一页；
  设置 → 通用 → 开源许可能打开、能滚到底。⑧ 从触控板出发一直滑到键上还是在移光标；分屏透传时按在方向键上面那种空格子、键盘外圈的手势带里，系统手势照常。
  ⑨ 主题：十八套里功能键 / 普通键分主次、锁住的修饰键（左右八个）高亮、按下的样子、🌐 去色；按键边框开 / 关。⑩ 全尺寸一百多个键一起画，打快了跟不跟手（每按一下整块重画）。
  ⑪ 审查后改的（只跑了离线测试和编译）：蓝牙模式服务接管时，在空中鼠标页调出实体键盘、锁住 AltGr 或右 Ctrl，从 ⋯ 收起键盘，之后空中鼠标点击不带 AltGr / Ctrl（主机上看）；
  桌面模式锁住右 Ctrl 再从 ⋯ 进手柄页，Moonlight 里不再一直按着右 Ctrl；锁住右 Ctrl 后在触控板边缘拨一下不起惯性（不会一直缩放），解开后照常（服务要先重启成新代码）；
  Windows 美式锁住右边那个 Alt，键帽不再整块变暗（和锁左 Alt 一样）；又宽又矮的弹出式小窗（高 200dp 上下）里实体键盘一格不窄于 32dp、行比较矮，进调整模式拖左右边第一下不跳；
  平板竖屏（全尺寸）默认至少四分之一高、拖上边第一下不跳。字表读不出来的情况真机上碰不到（数据打在 APK 里），只离线测了"不拼布局"那条规则，⋯ 菜单的"数据读不出来"那句九种语言没看过。

- **小红点状态再改一轮：固定合并、三个键占空格行 + 快捷行、G / H / V / B 圆弧切口（2026-11-01 写完，没提交，待真机验证；不改协议，但 TouchRouter 两边共用，装完要重启服务）**：
  见决策记录。只跑了离线测试（KeyboardPlanTest 320 项含逐点分流核对约 102 万点、扫描 19 万种；KeyRowsTest 1118 项；KeyFingersTest 512 项；TouchRouterTest 231 项）和编译，界面一次都没看过。
  审查后又改了一轮（同一天，决策记录那条最后"审查后的修改"①–⑤；离线测试 KeyboardPlanTest 332 项、KeyRowsTest 1275 项，编译过）。要看：
  ⓪ **先看不闪退**：⋯ → 指点换成小红点，键盘页正常显示（第一版这里必闪退）；关掉豆花再开（Prefs 里存着小红点）照样能开；
  ⓪′ 手机竖屏 9 个默认快捷键：三个鼠标键只有空格那排高、快捷行照旧铺满整行；快捷行只留 7 个以下（411dp 宽）、手机横屏、大屏分屏下半边时三个键跨两行；
  这样自动退回能不能接受（不能的话另一条路是把三个鼠标键缩到 32dp 宽）；九种语言下快捷行、缩短的空格的字放不放得下，放不下的末尾是"…"、不是半截字；
  ⓪″ 系统字体调到 1.3 倍左右：大屏分屏下半边带快捷行切到 ?123 层，] < PrtSc 这些被挪开的字不压切口、比旁边的键小多少；
  ⓪‴ 桌面模式服务连着没接管（View 自己收触摸）：一根手指按住红点圆里，另一根按 g 被切掉的那个角（圆外、方块里），出 g；红点放在键盘外面时按着它再按键照样出字；
  ① 小红点状态下 ⋯ 菜单没有"布局"那一项，大屏分屏下半边、手机横屏都是合并的；换回触控板 / 关时"布局"那一项回来、还是原来选的；
  ② 底行 ?123 , 空格 │ 左 中 右 │ . ↵、快捷行分在两边：三个鼠标键和空格那排、快捷行等高（快捷行一个都不留、或者分开后太挤时只有空格那排高）、中键正对红点下面；
  宽窗口空格 1.5 份、手机竖屏空格 1–1.25 份——空格这么短打字顺不顺；快捷行分到两边时（宽窗口）还认不认得、按不按得准（太挤的已经自动不让位，见 ⓪′）；对称时 . ↵ 变宽一点看着别不别扭；
  连 iPad（🌐）时手机竖屏三个键只有约 37dp 宽、中键往右偏约 20dp，能不能接受（不能接受的话可以把 🌐 挪到空位右边）；
  ③ g、h、v、b 被圆弧切掉的样子（边框沿着圆弧、和红帽之间约 3dp 的缝）好不好看，按下、锁 Shift（g h 不受影响）、按键边框关掉、十八套主题下都对；
  切到 ?123 / Fn 层：] < ~ PgUp PrtSc 这些被切的键的样子，横屏 / 分屏下 ] 的字往上挪、< 往下挪了几 dp 看着别不别扭、字有没有被切口压到；
  ④ 红点变大了（手机竖屏直径约 40–46dp、帽约 37–43dp）好不好按；贴着切口按在键上出的是那个键，按在红帽上（连缝的里半圈）是红点；按着红点时缝里一圈 focus 色；
  ⑤ 三条路都试：桌面模式服务接管、蓝牙模式服务接管 / 没开服务（本地分流）、桌面模式服务连着没接管（View 自己收：按在红点方块的角上出键、红帽上什么都不出；三个键轻点能点、
  三个键之间的缝按了也算鼠标键不出字）；服务没重启时（旧服务按正方形认）方块的四个角会归红点，重启后才对；
  ⑥ 存过的小红点那份键盘大小位置（以前连着最下面那排）照用，键变高了；"调整大小"的框就是键盘（三个键在框里）；拖得很窄时三个键消失（空位让不出）、红点挪到键盘上面。
- **手柄页第二批（2026-11-01 写完，没提交，待真机验证；不升协议，但 GamepadTouch / Proto 两边共用，装完照样重启服务）**：见决策记录。只跑了离线测试
  （手柄组件清单 341 项、手柄页摆法 209033 项、手柄触摸 130 项、服务端部件 274 项）和编译，设置页、手柄页一次都没看过。要看：
  ① 设置 → 手柄：六个标签手机竖屏放不放得下（横着滑）、九种语言（德 / 法语长、阿拉伯文）；预览和网格小图（15 个预设 + 自定义，竖屏一行 3 格）认不认得出、名字放不放得下，
  外观里换了主题回来跟着变；② 网格里点一个预设，回到手柄页就是它；⋯ 菜单"预设：…"轮到最后是"自定义"（存过才有）；手柄页 ⋯ 菜单的"设置"直接停在"手柄"页；
  ③ 第一次点"自定义"从刚才选的预设复制；勾掉十字键 / 左摇杆、换面键排法 / 字样、肩键 / 扳机 / Select / Home / Start / L3 R3，预览马上变，回到手柄页一样；十字键和左摇杆都勾掉时左边空着；
  "从某个预设重新开始…"整套换掉；④ 新预设：N64（C 键在 A 右上；按 C↑ 电脑上右摇杆往上推满、压在 C↑ C→ 中间是斜着，N64 模拟器里 C 键能用；Z 是左扳机）、GameCube（A 大 B 小、Z 在 R 里边、
  模拟器里 A B X Y 对不对）、Wii 经典、MD 3 键、Neo Geo、街机 6 键；竖屏手机上 Neo Geo、街机 6 / 8 键缩得很小（要横屏用）；
  ⑤ 手感参数：推满距离、死区、抗死区（电脑上手柄测试页看刚推出死区时的值）、浮动中心关、底座跟着走关、8 方向关、斜向宽度、十字键中间多大、关掉滑动换键（两颗中间一起按也没了）、
  一起按的范围、滑出多远全松、两个咔嗒开关；改完回到手柄页马上生效、电脑上没有卡住的键；⑥ 蓝牙模式下"手柄"页顶上有那句提示、照样能设；"恢复默认设置"后回到 Xbox、自定义清掉；
  ⑦ "Moonlight 里建议这样设"那段：Moonlight 里那几个设置的实际名字我没核对，说法对不上就改。
  ⑧ 审查后改的（只跑了离线测试和编译）：自定义里"肩键 / 扳机"两个开关的字跟着这一套变（以 Wii 为底：肩键开关写 ZL / ZR、扳机开关写 L / R；换成 Xbox 字样变 LB RB / LT RT），
  九种语言放不放得下；以 GameCube 为底打开肩键，左边新出来那颗是 ZL；N64 的 C 键、GameCube 的 B 在全尺寸 43.5dp、手机竖屏约 35dp，拇指按不按得准（不准就整组放大、重新排 C 键的菱形）。
- **手柄页第一批（2026-11-01 写完，没提交，待真机验证；协议 19，装完要重启服务）**：见决策记录。只跑了离线测试（手柄触摸 121 项、手柄页摆法 86235 项、触摸分流 222 项、
  滚轮倍率 / 手柄描述符 184 项、服务端部件 270 项）和编译，界面一次都没看过。要看：
  ① 三星认不认 uhid 手柄（最要紧，先看）：进一次手柄页后跑只读的 `adb shell getevent -lp`、`adb shell dumpsys input`（看什么见"测试"一节），服务日志有"虚拟手柄已创建"；
  ② Moonlight 分屏串流：先点一下 Moonlight 那半边（手柄键只发给有焦点的窗口），再在手柄页按键；电脑上开手柄测试页（W3C Gamepad API 那种）看键、十字键、两个摇杆、扳机对不对，
  X / Y 有没有对调；Moonlight 设置里关掉"通过手柄模拟鼠标"（不然长按 Start 0.75 秒切鼠标模式）；③ 9 个预设在 930 × 350 那种分屏下半边、手机竖屏、横屏全屏各看一遍摆法、字、颜色（18 套主题），
  Game Boy 竖屏；④ 手感：拇指压在两颗中间一起按、从 B 滚到 A、十字键斜向好不好按、摇杆滑出区域还在推、反向推马上生效、推到边咔嗒、连打"嗒"跟不跟手；
  ⑤ 收尾：按着键时豆花退后台、拉通知栏、转屏、拖分隔条、返回触控板、换预设——电脑上没有卡住的键 / 摇杆；⑥ 开始串流前没进过手柄页时电脑上不多一个手柄，进过以后切回触控板页再进，
  游戏里不弹"手柄断开"；⑦ 焦点在豆花自己窗口上时按 B / Home，豆花不被关、不回桌面；⑧ 测试机（服务找不到触摸屏、走本地分流）同样 getevent 看得到手柄、按键有报文；
  ⑨ 按住 ⋯ 0.6 秒开菜单、短按不开；蓝牙模式下菜单里没有"手柄"、动作给提示；⑩ 开了 Moonlight 的"用设备震动模拟"后，游戏震动时按键"嗒"会不会把它打断。

- **桌面模式的虚拟鼠标只留竖向滚轮倍率（2026-11-01 写完，没提交，待主力机验证；不改协议，改了服务，装完要重启服务）**：见决策记录。只跑了离线测试（HidTest 131 项）和编译。
  要看：桌面模式连上后服务日志（`/data/local/tmp/douhua-server.log`）里 Douhua Touchpad 那几行——
  ① 出现"内核来设报告（编号 0，类型 0 = Feature，数据 01 或 00 01）… → 收下"和"内核设了滚轮倍率：竖 ×8（横向不带倍率，一直 ×1）"，没有"建好 5 秒内核没来设"：成了。
  接着看连续滚动开着时竖向滚得平不平滑（还要系统开关 high_resolution_scroll 开着，见下面"惯性滚动 + 连续滚动"①）、速度和以前一格对一格一样快（别快 8 倍）；横向照旧一格一格。
  ② 还是只有"内核发来 START""内核发来 OPEN"、之后"建好 5 秒内核没来设"：三星内核根本不做这件事。下一步把设置里"连续滚动"的说明改成桌面模式下不支持（只在蓝牙模式下平滑），
  九种语言一起改；描述符留着无害（输入报文没变）。③ 出现"内核来问报告"（GET）也记下回了什么：按理只有一个字段时内核不问。

- **屏幕边缘留白（2026-11-01 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（EdgeMarginTest 100995 项含扫描、
  KeyboardPlanTest 418 项）和编译，界面一次都没看过。要看：① 默认 8dp：主界面（触控板、四角按钮、数字键盘、自定义按键）、键盘页（合并 / 分裂 / 小红点、最下面那排鼠标键、⋯）、
  空中鼠标页四周都空出一小条页面底色，拿手机时手指压着边、碰手机自己的边缘操作（侧屏面板、侧滑返回）还误不误触；② 那一条按了没反应（桌面模式服务接管、蓝牙模式服务接管 / 本地分流、
  桌面模式服务连着没接管），从触控板划进那一条照样动光标，⋯ 菜单开着时点那一条菜单不关；③ 设置里拖到 0、48、左右和上下不一样，回主界面马上变；转屏后左右 / 上下跟着当前方向；
  ④ 分屏下半边：上沿（挨着分隔条）不留、左右下留；横屏左右分屏；小窗浮在中间不留、拖到贴着屏幕边那条才留（最多晚 1 秒）；⑤ ⋯、菜单、调整栏离屏幕边和以前一样
  （留白 8 时还是离屏幕边 20dp，不是 28），留白 48 时都在页面里；键盘调过的大小位置还在、按比例跟着变；⑥ 九种语言"屏幕边缘留白"一节放不放得下；
  ⑦ 转着的惯性碰到留白也停（同空白处），能不能接受。

- **三个鼠标键照真 ThinkPad 改样子（2026-11-01 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录"小红点放进键盘里"一条的"和真 ThinkPad 对照"。
  （键盘里那三个键 2026-11-01 已改成占底行、快捷行的空位、两行高，见"小红点状态再改一轮"；红线、小点的画法没变。）
  只跑了离线测试（KeyboardPlanTest 383 项）和编译，界面没看过。要看：① 键盘最下面那排、主界面 / 键盘页触控板的三键条：左右键下沿的红线（2dp、两头让开圆角）看不看得清、
  像不像 ThinkPad，三键条在触控板上沿时红线也在下沿顺不顺眼；② 中键上沿的 7 个小点（点距最多 5dp、半径 1.2dp）看不看得清，三个一样宽（整排很窄）时挤不挤；
  ③ 中键变窄（手机竖屏合并约 74dp、主界面三键条约 67dp，以前约 100 / 88dp）后还好不好按、拇指按中键滚动时会不会按到左右键；左键比右键宽（红点在 0.55 处）看着别不别扭；
  ④ 十八套主题（尤其浅色、高对比 2.5dp 边框）下红线、小点和边框挤不挤。

- **小红点放进键盘里、三个鼠标键放大（2026-11-01 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。
  （**已改**：分裂、最下面那排三个鼠标键、方块洞 2026-11-01 都推翻了，见最上面"小红点状态再改一轮"那条；下面 ①②③④⑦ 里和分裂、最下面那排、方洞有关的不用再看。）
  只跑了离线测试（KeyboardPlanTest 379 项含逐点分流核对和扫描、KeyRowsTest 393 项、KeyFingersTest 507 项、TouchRouterTest 191 项）和编译，界面一次都没看过。要看：
  ① 大屏分屏下半边、手机横屏（分裂）：中间那一列的红点（帽约 45dp）好不好按、两半（各约 410dp）打字顺不顺；红点竖着在 a–l / z–m 两行交界那么高合不合适；
  ② 手机竖屏（合并）：红点嵌在 g、h、v、b 中间的样子（键上挖的方洞（角上只圆 2dp）+ 帽约 35dp）好不好看、贴着洞边按在键上出的是那个键、按 g / h / v / b 的右下 / 左下 / 右上 / 左上角会不会误按到红点（离线算的每个键让出约 14%）；
  切到 ?123 / Fn 层红点不动，那里的 ] < 这些键还按不按得准（] 让出 25%）、字有没有被洞盖住一截（横屏合并时洞的上沿只比 ] 的中线低约 4dp，按字体估的，没看过）；强制分裂的手机竖屏红点在两半中间的缝上；
  ③ 键盘最下面那排三个鼠标键（48dp 高、中键对着红点；宽度 2026-11-01 改成约 2.2 : 1 : 2.2，见上面那条）：好不好点、拿手机时手掌会不会压到最下面的左 / 右键（这一排贴着屏幕下边，同原来的快捷行）；
  左键按住 + 推红点拖动、中键按住 + 推红点滚动、中键单击照旧；键比以前矮约 7dp 一行，打字受不受影响；
  ④ 调整大小：框连着最下面那排一起拖，分裂时两半的框也连着；把键盘拖得很矮时那排消失（面板不到 144dp）、拖得很窄时红点挪到键盘上面；"恢复默认"照旧；
  ⑤ 三条路都试：桌面模式服务接管、蓝牙模式服务接管 / 没开服务（本地分流）、桌面模式服务连着没接管（View 自己收：红点不动、三个键轻点能点、红点盖住的键角不出字）；
  ⑥ 主界面、键盘页触控板的三键条变成 48dp 高、中键窄，切了以后触控板还够不够大；⑦ 十八套主题里洞（页面底色）和键、红点分不分得开，浅色主题好不好看；
  ⑧ 换快捷行（设置里删到一个都不剩）、连 iPad（底行多 🌐）后红点跟着那两行的交界走。

- **主界面改版 + ⋯ 菜单固定在每块触控板上（2026-11-01 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。
  只跑了离线测试（KeyboardPlanTest 233 项，含 menuGrid / padMenuTop 和它们的扫描）和编译，界面一次都没在真机 / 模拟器上看过。要看：
  ① 升级上来：左边的按钮条没了，四个角都有按钮（左下是数字键盘），触控板顶上正中有 ⋯；② 主界面点 ⋯：菜单在 ⋯ 下面居中，键盘 / 设置 / 旋转 / 退出都对；
  点外面、按返回只关菜单（蓝牙模式下不会把豆花关掉）；菜单开着时点角上的按钮、触控板什么都不发生、不动光标；
  ③ 键盘屏的 ⋯ 七项：手机竖屏一列；手机横屏全屏一列、每项矮一点（约 38dp）还按不按得准；分屏下半边 / 很矮的小窗里分成两列；"布局"连点时菜单跟着 ⋯ 挪；
  从键盘屏点"设置"直接进设置页、返回还在键盘屏；④ 三条路都试（桌面模式服务接管、蓝牙模式服务接管 / 没开服务、桌面模式服务连着没接管）；
  ⑤ 设置 → 按钮与按键 打开"显示自定义按键"，回主界面左边多一列（没有退出 / 设置 / 收起），关掉就没了；阿拉伯文下那一列在右边、和触控板之间有缝；
  某个角设成"无"后那个角空着；⑥ 有背景图时主界面 ⋯ 下面垫底色；三键条放上沿时 ⋯ 在三键条下面；⑦ 横屏开数字键盘、窄的分屏小窗里 ⋯ 和左上 / 右上角按钮挤不挤；
  ⑧ 九种语言：菜单项（德 / 法语长名字折两行）、设置页"显示自定义按键""左下角"放不放得下。

- **实体键盘第 1A 批：主机实测扫描 + 键帽数据（2026-10-31 写完，没提交，待你跑扫描；不改协议、不改服务，只装调试包）**：见决策记录。
  只跑了离线测试（KeyScanTest 110 项、生成器自测 39 项、数据核对 4517 项、扫描分析自测 23 项）、编译，和本机浏览器里用不发 adb 的 `--dry-run` 点过页面
  （分步、失焦暂停、继续、卡住提示、保存）；手机发键、iPad / 桌面模式上的页面一次都没跑过，第一次多半要调。要跑：① iPad 德语、美式各一次
  （`tools/kbd-scan.sh --host ipad --layout de`，再 `--layout us`）；② 这台 Linux 德语；③ 桌面模式德语（`--desktop`，先在手机设置里给 Douhua Keyboard 选德语）。
  要看：页面能不能在 iPad Safari 上打开、Tab 能不能被拦住（不跳出框）、有没有 e.code、死键是走 key=Dead 还是输入法组合；扫描中点别处手机马上停；
  report.txt 里的结论（尤其 iPad 的 0x35 / 0x64、0x32、⌥ 层、Linux / 三星和数据差几格）。新克隆上 `gen_layouts.py` 第一次下载核不核得上 sha256 也没试过。
  无线 adb / iPad 省电这类慢的时候，终端里"发第 a–b 步"每段只出现一次（起点隔 6 个，不再出现紧挨着的两段），report.txt 的对齐那条成立。
  （1B 已做，设置 → 键盘里选实体键盘；扫描结果出来后要看 Mac 模式的 0x35 / 0x64、AltGr / Option 层对不对，不对再改。）

- **触控板背景图（2026-10-31 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（PadFitTest 23548 项）和编译，
  界面一次都没看过。要看：① 格式：带透明的 PNG（透明处露触控板底色）、竖着拍带 EXIF 旋转的 JPEG（是正的）、三星相机的 HEIC、WebP、动态 GIF（第一帧 + 提示）、AVIF、
  SVG（报错提示）、超过 50MB 的（"太大"）、2 亿像素的照片（不闪退、几百毫秒内出现，服务日志里没有"App 没有响应"）、照片选择器里的云端照片（慢的时候显示"正在读取"）；
  ② 形状：主界面竖屏 / 横屏、开关数字键盘、开三键条；键盘页合并 / 分裂 / 分屏下半边、拖分隔条、转屏、Fold 展开合上：焦点留在画面里、图不跳；分开设打开时 1:1 附近的跳变能不能接受；
  ③ 看不看得清：花的、亮的图上状态字、四角小按钮、⋯ 的底块看不看得清、好不好看（"像贴胶布"就换方案里的半透明块 + 亮字）；边缘滚动带的 3dp 底条好不好看（不好看就有图时不画线）；
  浅色、高对比主题；④ 小触控板上图缩得很小时有没有锯齿、闪烁（mipmap）；⑤ 设置 → 外观：三块预览跟着显示方式 / 焦点 / 不透明度 / 主题马上变，焦点十字拖得动（页面不跟着滚）、
  吸附点得准；"图片大小"只在平铺 / 原大小时出现；分开设开 / 关；九种语言放得下；⑥ 打字跟不跟手、"从触控板出发划出去照样动光标"（这两条不该被碰到，顺手确认）；
  ⑦ "移除图片""恢复默认设置"后图没了、files/padbg/ 空了（调试包 `adb shell run-as dev.douhua ls files/padbg`）；导入中转屏，回来照样"正在读取"、读完显示新图。
  ⑧ 选一张慢的云端照片，"正在读取"时按返回回到主界面：读完图就出现（不用切出去再回来）；选超过 50MB 的、读取中离开设置页，读完再进设置页，状态行照样提示"太大"。

- **小红点 + 左 / 中 / 右三键（2026-10-31 写完，没提交，待真机验证；协议号还是 18 但 CMD_CONFIG 多了 6 字节，装完要重启服务——和惯性滚动那一版的服务混着跑会读错）**：
  见决策记录。只跑了离线测试（小红点 56 项、触摸分流 186 项、手势 192 项、键盘布局 215 项、主题 1274 项、滚轮倍率 / CMD_CONFIG 107 项）和编译，界面一次都没看过。要看：
  ① 手感（最要紧）：⋯ → 指点换成小红点，拇指按住往外推，起步、平台好不好对准、推到底跑全屏顺不顺；"位移代替力"能不能接受；设置里灵敏度调到哪顺手；负惯性开 / 关各试；
  按住不动光标稳不稳（2.5dp 死区够不够）；想微调时会不会被当成轻点点出去。
  ② 左键按住 + 推红点拖动（持续轻震、没有颗粒）；中键按住 + 推 = 滚动，方向对不对（往下推往下翻）；中键点一下 = 中键单击（浏览器里新标签打开链接）；推到底时颗粒会不会糊成"嗡"。
  ③ （红点组的摆法 2026-11-01 已改成放进键盘里，这一项改看上面"小红点放进键盘里"那条）。
  ④ 三键条（设置 → 触控板最后一节）：主界面、键盘页触控板的下沿 / 上沿，离屏幕边 20dp；上沿时 ⋯ 在三键条正下方；切了以后角上小按钮、状态字、边缘滚动带还在触控板里；
  中键 + 单指在触控板上划 = 滚动。
  ⑤ 三条路都试：桌面模式服务接管（8ms 一拍，`--debug` 日志的鼠标包统计约 125 个 / 秒）、蓝牙模式服务接管（12ms）、蓝牙模式没开服务（本地分流）；桌面模式服务连着没接管时三个键轻点能点。
  ⑥ 十八套主题里红点是不是都看得出、像红点；三个键的红线（左右键下沿）、中键上沿 7 个小点看不看得清（2026-11-01 照真 ThinkPad 改过，见"三个鼠标键照真 ThinkPad 改样子"那条）；碰红点 / 三个键时转着的惯性马上停。
  ⑦ 对方电脑的鼠标加速叠在曲线上：Windows / macOS / KDE 上同一个灵敏度快慢差多少。
  ⑧ 审查后改的（只跑了离线测试（触摸分流 190 项）和编译）：按住三键条 / 红点组的中键时让豆花退后台、拉下通知栏、转屏，Linux 上不粘贴选区（主机没收到中键单击）；
  中键按着时手掌压到屏幕被系统取消也一样。

- **惯性滚动 + 连续滚动（2026-10-31 写完，没提交，待真机验证；协议 18，装完要重启服务）**：见决策记录。只跑了离线测试（手势 171 项、滚轮倍率 / 惯性物理 106 项、
  触摸分流 136 项、服务端部件 240 项）和编译，一次都没在真机上跑过。要看：
  ① 桌面模式：服务日志连上后几十毫秒内"内核设了滚轮倍率：竖 ×8"（2026-11-01 起 uhid 只带竖向倍率，日志后面是"横向不带倍率"，见上面"只留竖向滚轮倍率"那条），豆花连上后光标马上能动（没有晚 5 秒；"建好 5 秒内核没来设"那句不该出现）；
  `adb shell getevent -lp` 里 Douhua Touchpad 有 REL_WHEEL_HI_RES；会话结束 / 被顶掉时日志里没有"读线程 300ms 没退出"（这两条测试机上就能看：服务找不到触摸屏，虚拟鼠标照样建）。
  `adb shell aflags list | grep high_resolution_scroll` 看系统开关（开 = 桌面模式下连续滚动能平滑；aflags 是凭记忆的命令）。
  **主力机上第一次跑（2026-11-01）**：服务日志"建好 5 秒内核没来设 Feature 报告"，之后也没有"内核设了滚轮倍率"、没有读线程出错；`getevent -lp` 里 Douhua Touchpad 有 REL_WHEEL_HI_RES
  （hid-input 对所有滚轮都会报这个轴，不说明认了倍率）。按主线内核源码（hid-input.c 的 hidinput_change_resolution_multipliers、uhid.c，子代理逐字读的）：uhid 设备不带 HID_QUIRK_NO_INIT_REPORTS，
  一个报告里两个倍率字段时 probe 里应该先 GET 再 SET，各等 5 秒。三星内核是不是这样没法看。Uhid 读线程加了诊断日志（内核发来的 GET / SET / START / OPEN……和回了什么，每条带离建好多少毫秒），
  下次连上看日志就分得清"三星内核根本不问"还是"问了、我们回得不对"。顺带修了 Hid.featureByte：内核的 __hid_request 给没编号的报告也在最前面垫一个编号字节 0，uhid 原样转来，
  SET 的数据是 [00, 值]，以前会把 0 当倍率（HidTest 补了两项）。同一天接着把 uhid 的描述符改成只有一个竖向倍率字段，见上面"只留竖向滚轮倍率"那条。
  ② 惯性：正常滚几下不会溜；拨一下转几秒慢慢停；碰触控板马上停、不会点到东西、按住不变成拖动；碰键（照常打字、不震）、按钮条、空白处、分屏时另一边的应用都马上停；
  豆花退后台、拔外接屏、断开蓝牙都停；服务 `--debug` 日志的"边缘松手"速度：正常滚和拨各几次，看 0.6 的门槛合不合适；Moonlight 没抓鼠标时 Android 的滚轮加速会让惯性前段快很多，能不能接受。
  ③ 调试版设置 → 触控板 → 滚动最下面：放开 / 刹住各三种挑一种（标"不支持"的这台马达放不出）；转的时候不震是不是更好；阻尼默认 4 档是不是"转好久"，十档两头够不够。
  ④ 连续滚动：关着时和以前一模一样（含缩放、滚轮按钮）；蓝牙开着 → 豆花重新注册 → 在对方删掉手机重配：Linux `evtest` 看 REL_WHEEL_HI_RES 每次 15 的倍数、豆花日志"设了滚轮倍率"、
  滚得平滑、速度和以前一格对一格一样快、每滚过一格咔嗒一下；设置页显示"开了平滑滚动"；Windows 还认得出鼠标、断开再连速度不变；iPad / Mac 会不会来设、速度对不对（别快 8 倍）；
  没重配的主机一切照旧；关掉开关、重配回来一切照旧；双指滚动横向不抖。
  ⑤ 蓝牙模式下服务接管着时，按住配了"滚轮"的音量键：滚得和以前一样（现在经服务转发）。
  ⑥ 审查后改的（服务的定时器加令牌，只跑了离线测试（服务端部件 251 项）和编译）：轻点拖动、双击、长按拖动照旧；惯性转着时一只手按键、另一只手同时按住触控板中间不动，
  不会马上变成拖动（要等够长按的时间）；装完要重启服务。

- **设置页分标签页（2026-10-31 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了编译和离线测试（没有新的离线测试：全是界面代码），
  界面没在真机 / 模拟器上看过。要看：① 五个标签手机竖屏放不放得下（放不下能横着滑、选中的那个自动露出来），九种语言（德 / 法语长、阿拉伯文从右往左）；
  选中的样子明不明显；标题和标签不随页面滚；② 每页内容对（空中鼠标页桌面模式下只有一句"只在蓝牙模式下能用"）；③ 按钮条加删一个、换连接方式、空中鼠标换方向换算、
  恢复默认之后停在原来那一页、滚动位置差不多不动；切页滚到顶；④ 切到别的页、关掉设置再从 ⋯ 菜单点"设置"，停在上次那一页；转屏后还是那一页；
  ⑤ 停在"外观"页时，服务没开、点主界面的状态字：打开的是"通用"页最上面；配对通知、"去关掉无线调试"按钮同样（设置页开着时点通知也换过去）；
  ⑥ 配对进行中切到别的页再切回来，引导和状态都还在；空中鼠标页填分辨率时切页，输入法收起。
  审查后补的（同样只编译）：⑦ 在长的页（触控板）用力往下甩、还在滑的时候点别的标签，新页从最上面开始、不接着往下滑；⑧ 选中标签的字（#AECBFA）看不看得清、和没选中的分不分得开。

- **主题扩充 + 键帽分主次（2026-10-31 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（ThemeTest 1153 项、
  KeyRowsTest 358 项含功能键清单）和编译，界面没在真机 / 模拟器上看过。要看：① 纯黑和以前一模一样（功能键不变色）；② 十八套各看整屏键盘（字母层、?123、Fn 层、快捷行、
  分裂 / 合并）和数字键盘：功能键那一圈"一点点颜色"够不够、会不会太花；③ 锁 Shift / Ctrl、进 ?123 / Fn 层：锁住时底色退回普通键帽色 + 强调色边框，
  一眼看不看得出锁住了（按键边框开 / 关都看）；看不出可以改成锁住时反色（强调色底 + 键帽色字）；④ 按下功能键看得出来；🌐 在深色几套里看不看得清；
  ⑤ 设置 → 外观的色块网格：手机竖屏一行 3 格、平板 / 横屏更多，名字（"Catppuccin Latte""Solarized Light"、德 / 法语"Kirschblüte""Thèmes sombres"）放不放得下，
  选中框明不明显，纯黑那格在设置页深色底上看得出边，阿拉伯文下格子从右往左排；⑥ 以前选了"浅色"（Solarized Light）的，升级后还是它，名字变成 Solarized Light。
  ⑦ 审查后补的（离线测 ThemeTest 1187 项 + 编译）：Everforest、Solarized Dark、Solarized Light 切到 Fn 层再点 ⋯ → 调整大小，雾层更厚了（0xD0 / 0xE8 / 0xD0），提示字看得清、底下的键还认得出来。

- **主题（外观）（2026-10-30 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（ThemeTest 467 项）和编译，
  界面没在真机 / 模拟器上看过。要看：① 默认纯黑和以前一模一样；② 九套各看一遍整屏键盘（合并 / 分裂，中间的触控板和键盘分不分得开）、主界面触控板 + 角上小按钮、
  按钮条、数字键盘、空中鼠标页、贴字面板；锁 Shift / 换层的高亮、按下、放大提示、🌐 的颜色；③ 浅色主题下调整键盘大小时那层雾、框、把手、提示字、调整栏看不看得清；
  ④ 按键边框关掉：深色那几套靠底色分键看不看得清，纯黑 / 高对比的键帽只剩字，按钮条 / 空中鼠标页格子 / ⋯ 菜单 / 调整栏仍有边框；
  深色几套开着时键帽一圈中灰边框会不会太重；浅色主题贴字面板的光标、把手、选中高亮看不看得清；🌐 在深色几套 / 高对比里看不看得清；⑤ 设置页预览切主题马上变、回到主界面马上换（不用重启）；
  ⑥ 高对比 2.5dp 的边在键之间 2dp 的缝里挤不挤；⑦ 真机屏幕上颜色好不好看（对比度只是算出来的）。

- **键盘工具栏换成 ⋯ 菜单（2026-10-30 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（KeyboardPlanTest 152 项含 ⋯ / 菜单扫描、
  KeyRowsTest 131 项含带 ⋯ 的功能行）和编译，界面没在真机 / 模拟器上看过。要看：① 竖屏、横屏、大屏分屏下半边、Fold 外屏：⋯ 在触控板顶上正中（2026-10-31 从角上挪过来、去掉框）看不看得见、离屏幕边够不够远、
  拿手机时会不会碰到，挡没挡住触控板的状态字 / 边缘滚动带；触控板关掉后 ⋯ 出现在功能行最右边（分裂时右半最右），键宽还能按准；
  ② 点 ⋯ 弹菜单：位置贴着 ⋯、不出屏、字（九种语言，"布局：合并"这类长名字）放得下；"布局"连点三下轮换、菜单跟着 ⋯ 挪；"触控板"开关后 ⋯ 换地方；
  点菜单外面（键上、触控板上）只关菜单、不出字、不动光标；返回关菜单（蓝牙模式下不会把豆花关掉）；
  ③ 三条路都试：桌面模式服务接管、蓝牙模式服务接管 / 没开服务（本地分流）、桌面模式下服务连着没接管（View 自己收）；
  ④ 调整大小：栏在上 / 下、离边 20dp，键盘拖到栏那边时栏让到另一边，键盘占满高时栏叠在上面、上边的拖把手还拖得动；"完成""恢复默认"照常；
  ⑤ 键盘占满高时功能行贴着屏幕上边，拿手机会不会误触（以前有工具栏隔着）。
  审查后补的（同样只离线测 + 编译）：⑥ 调整模式里栏叠在键盘上时，点"完成""恢复默认"之间的缝、两头空处不出字；一根手指按着栏上的按钮、另一根点键盘也不出字，
  那个按钮也不算点；⑦ 桌面模式分屏 / 小窗开着菜单：从豆花窗口左右边往里划（返回）、从底边上划（回桌面）、拖小窗标题栏都照常生效，菜单不关；窗口中间点一下关菜单；
  ⑧ 小窗很矮（约 360 × 390dp）、触控板关着：菜单在 ⋯ 键左边，不压着它。

- **分裂时对称调大小（2026-10-30 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录"键盘布局按钮"一条最后。
  只跑了离线测试（KeyboardPlanTest 122 项含两次扫描、KeyboardSnapTest 27 项）和编译，界面没在真机 / 模拟器上看过。要看：
  ① 分裂时点"调整大小"：两半各一个框、四个拖把手，提示字（九种语言）折得下、看得清；② 拖左半 / 右半的内侧边，两半一起对称变宽变窄，中间触控板跟着变，
  窄到放不下（< 96dp）触控板跑到上 / 下方或者没了，合拢时轻震一下、只剩一条缝；拖外侧边两半一起往里收、贴回屏幕边时轻震；拖上下边、拖中间上下挪（左右不动）；
  拖到最窄 / 最矮停住；③ 抬手后打字：键位置对、两半拉开时中间空地按了不出字、合拢时缝里按出最近的键；④ 存的：转屏后各是各的，合并时的大小不受影响；
  只点一下不拖不存；"恢复默认"分裂时回到各三分之一 / 伸满，合并时回到合并的默认；⑤ 分屏拖分隔条把窗口拖窄，存过的两半自动撑开、还是对称；
  ⑥ 拖的手感（10dp 吸附、grab 28dp 的把手范围、两半合拢时两条内侧边的把手范围重叠，按离哪条近就算哪一半的内侧边（KeyboardPlan.onRightHalf，按中线分；原来先判左半，按右半内侧边往右拖不动、往左拖方向反了），按右半那条往右拖 = 两半一起变窄）。

- **键盘布局按钮 + 触控板开关（2026-10-30 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（KeyboardPlanTest 75 项、
  KeyRowsTest 115 项，含审查后补的：宽窗口触控板关 / 放不下时默认满宽、两半只隔一条缝时缝分给最近的键）和编译，界面没在真机 / 模拟器上看过。
  要看：① 工具栏已换成 ⋯ 菜单（见上面"键盘工具栏换成 ⋯ 菜单"那条的要看项）；调整栏两个按钮（完成 / 恢复默认）离边有空、长名字（法 / 德 / 意语、日文）折两行看得清；
  ② "布局"轮换：大屏分屏下半边 自动（分裂）→ 分裂 → 合并（键盘靠左三分之二、触控板在右；以前调过横屏大小的照自己的）→ 自动；手机竖屏选"分裂"：两半伸满、中间一条缝、
  触控板在上方，打字两手各一半顺不顺；切的时候按着的键不粘、不残留按下的样子 / 放大提示；关掉豆花再开，选的还在；
  ③ "触控板：关"：分裂时两半伸满、合并时空出来的地方空着、点了什么都不发生（服务接管 / 没开服务两条路都试，蓝牙模式下那块空地会不会被本地分流当别的）；
  ④ 合并时"调整大小"把键盘拖到右边 / 上半截，触控板跑到左边 / 下方，拖的时候实时跟；"恢复默认"回到宽窗口靠左三分之二；调整模式里只点一下键盘不拖、点"完成"，
  回到全屏横屏键盘还是满宽（没被存成三分之二）；没调过大小时宽窗口里关触控板，键盘变满宽、再开回三分之二；分裂 + 触控板关时 t|y 之间的缝按下去有字；
  ⑤ 以前剩一条的那种情况（用户说的）现在不显示触控板；96dp 的门槛是不是太严（该有的地方没了）；⑥ 分裂时进调整模式，提示文字（变长了）折成几行、字号看得清（德语手机竖屏）。

- **分裂键盘（2026-10-30 写完，没提交，待真机验证；不改协议、不改服务，只装 App 就行）**：见决策记录。只跑了离线测试（KeyRowsTest 99 项、KeyFingersTest 503 项、
  触摸分流 113 项）和编译，界面一次都没在真机 / 模拟器上看过。要看：① 大屏分屏下半边打开键盘页：分成两半、中间触控板和键盘一样高，键帽文字没被压扁、
  字号合适；拖分隔条把豆花拖高 / 拖矮，分裂和整块实时切换，切的时候按着的键不粘、不残留按下的样子 / 放大提示；② 手机横屏全屏（够 2 倍时）也是分裂的，
  这是不是用户想要的（不想要就改门槛，比如只在分屏 / 小窗时分裂）；Fold 内屏 / 外屏、转屏来回切；③ 打字：两手拇指各打一半，缝里、切口那边的空位
  （t 右边、y 左边那一格）分给的是这一半里的键，右半的 g、v 能打，中间触控板上的手指不会打出字；两个空格都能打、按住连发；锁 Shift、Ctrl 后两半的键帽 / 高亮对；
  "?123" / "Fn" 换层后两半都换、放大提示位置对；连 iPad 时 🌐 在左半；快捷行只留 1 个键时，右半最下面那块空白按了什么都不打；④ 中间触控板：移动、轻点、双指滚动、边缘滚动带、状态字能点（服务没开时）；
  桌面模式下和蓝牙模式（服务接管 / 没开服务两条路）、桌面模式下服务连着没接管时（面板自己收触摸）都试；⑤ 分裂时点"调整大小"：（已改成能对称调，见上面"分裂时对称调大小"）；
  调整模式中拖分隔条从整块变分裂（或反过来），正拖着的那一下作废；
  ⑥ 整块布局（竖屏）和以前一模一样（键盘从 LinearLayout 改成自己摆放，键的宽度算法略有不同：缝不再从行宽里先扣掉，看不出来才对）。

- **桌面模式分屏透传（2026-10-29 写完，没提交，待主力机验证；协议 17，装完要重启服务）**：见决策记录。只跑了离线测试（PassthroughTest 81 项）和编译。
  **先零成本验证三星认不认注入的手势**（豆花开不开都行，分屏状态下跑，坐标按实际屏改）：返回 `adb shell input swipe 5 <屏高/2> 400 <屏高/2> 250`、
  回桌面 `input swipe <屏宽/2> <屏高-5> <屏宽/2> <屏高/2> 250`、下拉 `input swipe <屏宽/2> 5 <屏宽/2> <屏高/2> 250`、拖分隔条
  `input swipe <x> <分隔条 y> <x> <分隔条 y ± 300> 600`、点 Moonlight `input tap <点>`；这些不行的话透传也不行。
  装上后要看：① Moonlight 在上、豆花在下：手指点 / 划 Moonlight 那半边当触摸用（Moonlight 那边触摸模式 / 鼠标模式各看一下）、拖分隔条、从左右边划返回、从底边上划回桌面、
  从顶边下拉，同时豆花那半边触控板照常动光标、键盘字进 Moonlight；② 一根手指在豆花触控板上、另一根同时点 Moonlight（多指各归各）；
  ③ 豆花那半边贴着屏幕边的地方轻点不会点到豆花按钮；从豆花这半边边缘划返回会怎样（预计关掉豆花，要不要吞由用户定）；
  ④ 豆花退到后台、方向变了、断开时 Moonlight 里不会留着一根按着的手指（看 Moonlight 有没有卡住的触摸）；之后 `adb shell input tap` 照常能用；
  ⑤ 服务日志里没有"分屏透传不可用"；有的话看原因，状态字应该显示透传不可用、触摸交还系统；⑥ 全屏时和以前一样（系统手势挡住）。
  服务用 `--debug` 起时日志里每次按下会写"→ 系统（分屏透传）"（手势带里的带"不挪焦点"）。
  2026-10-30 审查后的修改（只跑了离线测试（分屏透传 130 项、服务端部件 221 项、触摸分流 113 项）和编译）另要看：
  ⑦ 下拉通知栏后点屏幕下半的通知 / 快捷开关会怎样（预计点不到、变成触控板点击，见"留给用户定"③）；
  ⑧ 焦点：手指点一下 Moonlight，再在豆花触控板贴左 / 右边约 20dp 内轻点、碰一下键盘最右列外侧，接着打字，字还进 Moonlight（NO_FOCUS_CHANGE 在三星上生效；
  Tasker 无障碍开着时也看）；从豆花这半边边缘划返回，返回给了谁（预计 Moonlight）；
  ⑨ 手势带里的键：按空格偏下一点、按 q / p 偏外一点、点左上角小按钮（默认退出）的左半边，都照常生效；
  ⑩ 一根手指按住 Moonlight，另一根点键盘调整模式的"完成"（让服务开始接管）：状态字显示"屏幕上还有手指按着"、Moonlight 那根照常、抬起后接管，
  Moonlight 里没有卡住的按住（服务日志"系统眼里这块触摸屏还有 N 根手指按着"；dumpsys input 里 "Last Cooked Touch: pointerCount=" 这行三星上是不是这么写）；
  ⑪ 豆花开成弹出式小窗：接管后拖标题栏能挪窗口、关闭 / 最小化 / 全屏按钮点得到、标题栏下面的触控板照常动光标；用豆花自己的触控板经虚拟鼠标把小窗拖到别处，
  新位置上的按钮、触控板马上（最多 1 秒）能用；
  ⑫ 两指在 Moonlight 上捏合时停掉服务（build.sh --install 会先停），再起服务后分屏透传照常（日志"按残留记录清掉了系统里按着的注入手指（2 根……）"），`adb shell input tap` 照常。
  第二轮审查后的修改另要看：⑬ 在 Moonlight 里快速连点 / 打字时让豆花开始接管（比如收起键盘调整模式），Moonlight 里没有卡住的按住（日志"dumpsys 之后、独占之前有根手指按下"）；
  ⑭ 一根手指在 Moonlight 上拖着时豆花要接管：日志"内核那边看到有手指按着"、抬起后马上接管，拖动期间 Moonlight 不卡；
  ⑮ 全屏带着两根手指接管（双指滚动时 App 卡一下）、再切分屏：最多约 2 秒后照常接管（日志"当作旧状态"）；
  ⑯ 分屏下点进 Moonlight 的输入框后豆花重新接管（转一下屏），输入法不收起、豆花键盘打的字还进 Moonlight；
  ⑰ 弹出式小窗：拖标题栏、点关闭 / 最小化（decor 自带标题栏那种这回才可能好）；⑱ `adb shell input motionevent DOWN` 按住不放时在分屏里点 Moonlight：
  豆花这一下可能丢（日志"300ms 后重发收下了"或透传不可用），adb 那根不被取消（服务刚起、记录文件在时除外）。

- **蓝牙模式下分屏 / 小窗时不让服务独占触摸屏（2026-10-29 写完，没提交；桌面模式当天改回分屏照样独占）**：见决策记录。只跑了离线测试和编译。
  要看：① 桌面模式 Moonlight + 豆花分屏：和以前一样能用（服务接管、触控板动光标、键盘字进 Moonlight）；分屏里点旋转按钮只出提示、不转整块屏；
  ② 蓝牙模式分屏 / 弹出式小窗：服务日志释放触摸屏，另一边的应用、分隔条、系统手势能用，豆花这半边触控板、按钮、键盘照常（本地分流），**拖着小窗换位置后**还对得上；
  回到全屏自动重新接管；③ 蓝牙模式全屏豆花接管着时，把别的应用开成弹出式小窗：三星会不会给 onTopResumedActivityChanged(false)，小窗能点、能拖、能关；
  ④ 拖分隔条、Fold 展开 / 合上后接管状态对；⑤ 状态字放不下时末尾是省略号、开头看得见。

- **豆花自己拉起服务（2026-10-22 写完，没提交，待真机验证；协议 16：hello 末尾带服务的 APK 路径，装完要重启服务）**：见决策记录。只跑了离线测试（密码学 187 项、协议 61 项、命令 14 项）、
  BoringSSL 端到端和编译。真机上要看：① 配对：设置页"开始配对"→ 系统里"使用配对码配对设备"→ 通知横幅 / 下拉里输码（三星横幅能不能直接回复、
  弹窗会不会因为下拉通知关掉）；配对码错能重输；② 启动：无线调试关着时豆花自己打开、起服务、马上关（日志标签 douhua 里"打开了无线调试""关掉了…"）、
  关掉后服务还在；第一次会 pm grant WRITE_SECURE_SETTINGS；没信任的 Wi-Fi 上系统弹"是否允许"→ 选"始终允许"后能接着起来；
  ③ 自动启动：打开豆花时服务没在跑自动起；装新包后打开豆花会重启一次装包前就在跑的服务（电脑装包后再起的不动）；开机连上 Wi-Fi 后自动起（三星开机广播、JobScheduler 的 Wi-Fi 约束）；
  ④ 各项指示：开 / 关 Wi-Fi、开发者选项、USB 调试、无线调试时设置页实时变，按钮能跳到对的系统页面（三星认不认 `:settings:fragment_args_key` 的高亮）；
  ⑤ 7 天不用后提示重新配对（Conscrypt 下 adbd 拒绝时客户端是不是报 SSLException，只用 JDK 客户端对 BoringSSL 核对过）；
  配对码输错后放弃、设置页不显示"已配对"；没信任的 Wi-Fi 上放弃后 3 分钟内点"允许"能接着起来、用完关；在最近任务里划掉豆花后无线调试被关回去（下次打开时）；关掉 USB 调试 / 开发者选项后服务确实停掉（文案是这么写的）；⑥ 探测服务时每次都建一次虚拟设备，屏幕上会不会闪一下光标。
  ⑦ 公共网络 / 退避 / 提醒（2026-10-22 补，只跑了离线测试（退避 26 项）和编译）：在没信任的 Wi-Fi 上自动启动弹框后点取消 → 设置页出现"自动启动暂停了"、
  之后打开豆花不再弹框（日志"自动启动服务暂停中"）、手动点"启动服务"成功后那段消失；退避中自己打开无线调试再打开豆花，自动启动照常起来；
  配对成功的通知里有"无线调试还开着"和"去关掉无线调试"按钮，点了能到无线调试那一页（经设置页转一下，看会不会闪一下设置页、三星认不认高亮）；
  无线调试已经关着时通知里不提；设置页无线调试是自己开的时显示琥珀色提醒 + 按钮，豆花临时开的那几秒不显示。

- **桌面模式的音量键（2026-10-19 写完，2026-10-21 去掉电源键、改用 getevent；没提交，待真机验证；协议 15，装完要重启服务）**：见决策记录。
  只跑了离线测试（服务端部件 216 项：getevent 格式解析（样本照 getevent.c 的 printf 拼，不是真机输出）/ 白名单 / 旁观 / 看不到电源键不截 / 原因混合、HardKeyGate 透传 / 组合键（含补发排序）/ 冷却 / releaseAll / 竞态）和编译。
  待真机：**先跑只读的 `Server keys`**（getevent 在 One UI 上能不能打开 /dev/input/eventN、主力机的设备分组、音量下是不是和电源键在一起、
  Fold 合盖霍尔是不是和音量键分开）；再看 EVIOCGRAB 对音量键设备行不行、截屏组合键、通话时放开（含振动模式来电：服务查 Telecom 的反射在 One UI 上通不通，
  日志里有"来电响铃或通话中：实体键还给系统"）、桌面模式下音量键按住"左键"拖动、App 端的提示文字。

- **键盘的缝分给最近的键 + 打字测试工具（2026-10-19 写完，没提交，待真机验证；协议 15（随桌面模式音量键一起升到 15），装完要重启服务）**：见决策记录。
  只跑了离线测试（分流 87 项、打字分析自测 30 项、重放核对）和编译。没验证：服务进程写 logcat、桌面模式下各键自己收触摸那条路的兜底、
  缝里分到 ?123 / 快捷行动作时点击生效、tools/type-log.sh 和网页整套流程（一次都没跑过）。

- **空中鼠标页调出键盘（2026-10-19 写完，没提交，待主力机验证；协议不变，只改 App）**：见决策记录。
  只跑了离线测试（分流 95 项、空中鼠标 65 项）和编译。要在主力机上看：页上点"键盘"（格子 / 音量键）键盘盖上来、能打字、
  小触控板能挪光标；服务接管（relay）和没开服务两条路都试；键盘上锁住 Ctrl 后"收起键盘"，回到页面点击不带 Ctrl；
  暂停中音量键调音量、调出键盘的那一下松开时音量不变；调整键盘大小能拖；用小触控板挪过光标后再跟随先回正中，没挪过接着走。

- **服务卡住修复（2026-10-19 写完，没提交，待真机验证；协议 15（随桌面模式音量键一起升到 15），装完要重启服务）**：见决策记录"服务卡住却显示在运行"。
  只跑了离线测试和编译；测试机上的验证步骤见"测试"一节。主力机上那个卡住的旧服务（v13）被新 start-server.sh 认出来的前提是有连接在排队，
  部署时用 build.sh --install / deploy-phone.sh（先 stop 再 start）或 `start-server.sh --restart`。

- **版本号 0.2.0**（2026-10-15 用户定，单独一个提交）：包括 2026-10-14 蓝牙 HID + TouchRouter 重构 + DexFocus 等、
  2026-10-15 蓝牙模式由服务独占触摸屏两次提交。正式包（tools/package.sh）还没打，打包、打标签、推送都由人来。
- **主力机上装的就是最新提交的内容**（`.private/deploy-phone.sh` 装整个工作区，最近一次 2026-10-15 00:06）。装之前那个不带蓝牙的包
  备份在 `.private/main-phone-before-bt.apk`，`bash .private/deploy-phone.sh --rollback` 装回。旧的按段打补丁脚本
  `.private/deploy-phone-nobt.sh` 已经用不上，可以删。
  **桌面模式在 TouchRouter 重构后还没人完整试过**（只看到服务能接管、点按钮能认），用户还没在桌面模式下用过这一版。
- **蓝牙已验证**：Linux 电脑上用户实测各种操作都对（关掉电脑连着的通话 / 媒体音频之后）；测试机上用 evtest 核对过报文
  （滑动、轻点、字母、Shift 锁定、自带 Ctrl 的"复制"、⌫ ↵、边缘滚轮、返回）。量法：电脑上 `sudo setfacl -m u:$USER:r /dev/input/eventN`
  后 `evtest`（不独占）；独占前按名字和 uniq（手机蓝牙地址）核对节点，编号会变，手机断开再连节点会重建、权限要重给。
- **iPad 正常了**（2026-10-15）：光标、点击、滚轮、键盘都对。之前"只认光标移动"是 iPadOS 自己的毛病（当时别的鼠标也点不了），
  不是豆花的问题；iPad 连上时只发过"切到 report 协议"，没切 boot。排查时把描述符改成鼠标排第一，保留（Hid.combinedDescriptor）。
- **GUI 键改名（主力机上验证过：连 Linux 显示 Super、连 iPad 显示 ⌘）**：功能行原来写 Win，改成 Super；蓝牙连着苹果设备时显示 ⌘（BtHid.isApple：
  主机的服务列表里有苹果专用标识 00000000-deca-fade-deca-deafdecacafe，iPad 实测有）。自定义组合键起名、"显示桌面（Super+D）"
  也跟着改；已经存下的组合键名字不变。只改显示，发出去的键不变。
- **地球仪键 🌐（iPad 上验证过能切换输入法）**：键盘最下面一排"符"后面加 🌐（空格从 5 份缩到 4 份），动作清单加
  lang_switch（按钮条 / 手势 / 快捷行都能选）。蓝牙发 Consumer 0x029D（AC Next Keyboard Layout Select），桌面模式注入
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
    服务断开时松开经服务按住的空中鼠标来源、没开服务时每秒的重连失败不再打断本地按住、蓝牙重连不打断按钮 / 宽限期回来接上自动重连、桌面模式接管时停滚轮、
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
  只能点一下的地方滚一格。桌面模式经服务的虚拟鼠标（新命令 CMD_WHEEL，协议版本 13，部署后服务要重启），蓝牙模式直接发。
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
  （HardKeys；只设了点一下就按下当场算，设了按住就 0.3 秒为界）。只在蓝牙模式；桌面模式要服务去读按键，以后做。
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
  ② 怎么进出空中鼠标模式（打算做成一个动作，按钮条 / 手势都能放）；③ 桌面模式下音量键到不了豆花（键盘焦点在外接屏上），
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
- **截获音量键 / 电源键，短按、长按各配动作**（蓝牙模式的音量键、桌面模式的音量键已做，后者待真机验证，见"进行中"；电源键用户 2026-10-21 定了不要）（在触控板 / 键盘界面时）：音量键豆花在前台时能自己截（窗口要能收按键）；
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
  触摸屏释放时 router.releaseAll 会把实体键按着的"左键"（CMD_HOLD_LEFT）一起松掉；服务被直接杀掉时，透传中按着的音量键
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
