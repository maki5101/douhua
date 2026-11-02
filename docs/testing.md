# 测试

从 CLAUDE.md 挪过来（2026-11-02 瘦身）：各组离线测试测了什么、真机怎么测。测试设备的规矩留在 CLAUDE.md。



- `tools/test.sh`：ThemeTest（十八套槽位齐全、先深色后浅色、纯黑和以前一样（功能键也是）、对比度、功能键和键帽 / 页面的色差；⋯ 的四个槽位（2026-11-01，ThemeTest.menu：十八套含纯黑，⋯ 键对页面 / 键帽 / 功能键 ≥ 3:1、和功能键 / 键帽色差 ≥ 8，字对 ⋯ 键 ≥ 4.5、对按下 ≥ 3、按下看得出来，不带框的 ⋯ 对触控板 / 页面 ≥ 4.5、对 pressed ≥ 3、和原来的暗字分得开，还带着强调色的色相、本来够的强调色不推、字从页面色 / 正文色里挑；纯黑写死的四个值；reachAll），`java … dev.douhua.ThemeTest print` 打出取值和对比度表）、GestureEngine、MtDecoder、TouchDevice 解析、KeyFingers（键盘面板自己收触摸时的多指）、KeyRows（分裂键盘哪个键归哪半、摆放、哪些键算功能键，
  以及按面板报区域的方式交给真正的 TouchRouter / KeyFingers 逐点核对缝只在各自那一半里分、中间是触控板）、KeyboardPlan（布局按钮、触控板放哪一侧、门槛、
  调整栏按钮宽度、⋯ 放触控板顶上哪儿 / 菜单弹在哪 / 调整栏放上还是下（再扫一遍各种尺寸查 ⋯ 离边够远、在触控板里、菜单不出屏、旁边放得下时不压着 ⋯）、
  主界面的 ⋯ 放不下也照样放（padMenuTop，扫一遍查一定在触控板里）、菜单项多窗口矮时怎么排（menuGrid：先压矮再分列，扫一遍查排得下、没有空列、不出页面、高度够时一直一列），外加各种尺寸扫一遍查触控板不压键、够大、分裂了键够宽；分裂时对称调大小：默认格子喂回去不变、存过的格子怎么摆、拖法和约束、
  窗口变窄时撑开，存过格子再扫一遍查两半对称、够宽、不重叠、不出界、seam 对；KeyboardSnapTest 里有 applySplit）的离线测试；服务的会话交接部件（TouchGrabLock、SessionPolicy、
  RotationLock、GrabCondition）、LogThrottle、Shell（会真起 echo / sleep 子进程）、实体键（KeyDevices 解析 getevent 输出 / 挑设备、HardKeyGate 状态机）也在 ServerPartsTest 里。
  分屏透传（TouchSplit 的归属、InjectStream 的动作码 / 下标 / 手指集合、MtDecoder 的 cancel、MtDecoder → TouchSplit → InjectStream 整条路含替换 slot / 手掌 / 释放独占，
  加两组随机序列）在 PassthroughTest 里，每个事件都过一个照 AOSP input_verifier.rs 写的校验器（校验器本身也测了能认出坏流）。
  修饰键 / Caps / 合并键（2026-11-01，协议 20）：KeyHoldsTest（全经真 TouchRouter，看主机收到的键盘报文 [修饰键|按着的键]、报给界面的锁住 / 按着 / 快锁了、震动、两个定时器按截止时刻依次到点：
  单点、0.4–1 秒锁、超过 1 秒不锁、定时器卡住时按截止时刻补上；按着时按普通键 / "@" / 别的修饰键 / Caps / 合并键、触控板轻点 / CMD_CLICK / 滚轮 / 三指手势 / "左键"按钮 / CMD_HOLD_LEFT / 开合都不锁、
  只挪光标照锁；锁着时再按解开；⇧ 和功能行的 Shift 两根手指；被取消的不锁；被取消的修饰键松开不结算待定的 Caps / 合并键（两种先后 × 两条路 × 再 releaseAll）、被取消的普通键照样松开；打完普通键解开、按着的不松；unlatch 只清锁定；方案审查补的几条（先长按拖动再按修饰键、按着修饰键时开始拖、
  先按住 a 再按 Shift、两个修饰键一起按 / 一个一个锁、Shift 先抬起时合并键 / Caps 先结算、锁着 Shift 快打 "I."、开合开始前结算、"@" 带的 Shift）；Caps 点一下 / 按住 / 滚着打 / 被取消 / 锁着 Shift；
  合并键点一下 / 按住只发一次 / 滚着打 / 被取消 / 两根手指 / 落在缝里照分给它；releaseAll 之后迟到的松开、两个空格副本、定时器只在有待定项时挂着、乱序 / 重复 / 早到的回调；
  区域那一路和 key() 那一路同样的序列报文一模一样；换参数不丢按着的键；400 组随机序列查 armed ⊆ held、latched ∩ held = ∅、HID 修饰键字节、用过的永远不锁、震动次数、全部抬起后（releaseAll 之前）没有按着的键、收尾归零），
  TouchRouterTest（原来锁"点一下锁定"的几条改成按住 0.4–1 秒、加单点 / 超过 1 秒 / 用过不锁），ServerPartsTest（协议 20 的编号、CMD_HID_KEY / CMD_UNLATCH 不算心跳、合并键 id 编解码、
  真 OneShotTimer + 真 TouchRouter："到点卡在锁上时松开，拿到锁的那次不震不锁""真到点震一下、0.5 秒松开锁住"），KeyFingersTest（remap），KeyRowsTest（底行 ?123 [,.] 空格 │ │ 空格 ↵、
  合并键 / 右空格有键帽、capText / lit），KeyboardPlanTest（让位扫描找合并键、右空格），FixedLayoutTest（Caps：Windows ⊕ / Mac ∨、ä ö ü 跟着、ß 数字第三层不跟、放大提示、Caps 键高亮，
  键帽齐全那一扫加了 Caps 开 / 关）。
  小红点：TrackPointTest（曲线的点、单调 / 平台 / 封顶、8 / 12 / 抖动的节拍积分、50ms 上限、灵敏度、负惯性起步更快 / 平台一样 / 不反向、轻点、颗粒、中键滚动的方向 / 主方向 /
  按格 / 连续、停、20 秒暂停；中键滚动杆模式：不看 scroll 参数一直滚、和按着中键推红点出的量一模一样（负惯性开 / 关 × 按格 / 连续）、方向 / 主方向 / 封顶 / 灵敏度、
  先挪出轻点容差才开始、开始以后挪回死区停 / 再推出死区接着、不算轻点、不发颗粒、咔嗒、停、暂停；左键杆模式（2026-11-01 晚）：离按下点容差 + m 和推红点 m 出的位移一样
  （负惯性开 / 关 × 8 / 12ms × 灵敏度 × 四个方向 × 五种位移共 160 种，顺着轴逐计数相等、斜着差不过 1）、dragLeverSpeed、封顶、先挪出轻点容差才开始、位移从容差那一圈起算、
  挪回容差以内停 / 再推接着（不用再挪出容差一次）、点左键时指肚一滚（挪出容差、还在容差 + 死区以内，负惯性开 / 关 × 节拍 × 灵敏度 × 方向 × 滚多远 × 多快滚到共 1920 种）一个计数都不出、
  不算轻点、颗粒开着也不发、scroll 参数给 true 也只移动、停、暂停）、TouchRouterTest（第二根手指不理、节拍开停配对、左键三处共用计数、中键单击 / 滚过不补 / 被取消不补、中键 + 触控板滚动、releaseAll / 换参数停、
  onTarget、nearestKey 不选、碰红点停惯性、按在红点 / 键盘里的鼠标键上不记触点、红点按正方形的内切圆认——圆里归红点、四个角归键；中键滚动杆：往上 / 下挪滚的方向、
  和"按住中键推红点"一样多、死区里 / 轻点容差里松开 = 中键单击（连续模式也一格不滚，开着轻点 = 左键也只出中键单击）、挪出容差就不补单击、滚过不补、被取消 / releaseAll 不补、
  滑出中键 / 滑到红点上照样是滚动杆、三键条的中键挪进触控板不动光标（密度 2）、第二根手指不当滚动杆、留下的那根不接手、和红点交错开停、随机交错 300 组 × 80 步
  （节拍开着 ⇔ 红点 / 滚动杆按着、开停配对、都没按着时节拍不出东西）、按格 / 连续、和红点 / 触控板叠加、惯性转着时按中键先停、换参数停、20 秒暂停再动接着；滚动杆让位：按着中键、拇指往右下漂 0 / 4 / 7 / 10 / 30dp 的同时推红点 / 划触控板，
  滚轮和拇指不漂时一模一样、一点横向都没有，另一根松了以后拇指再挪也不滚、节拍停，红点先按、拇指后按中键再漂也一样，开合 / 滚轮动作也让位；先用滚动杆、后加红点照旧叠加，
  挪出了容差、按格还没攒满一格时加红点也不让位；随机交错的模型也带上让位；左键杆（2026-11-01 晚）：按下马上按住左键、轻点 = 单击、连点 = 双击、轻点容差里挪着按 1 秒不动光标（按格 / 连续、
  开着轻点 = 左键也不多点）、挪出容差 = 带着左键的光标位移（量照 dragLeverSpeed、往上 / 往右、挪回按下点附近停、再推接着、不震不发颗粒不滚）、和推红点出的量一样（48 种）、
  滑出左键 / 滑到红点上 / 挪进触控板照样是杆（红点不被按、触控板不当手势）、三键条的左键挪进触控板（密度 2）、第二根手指不当杆、留下的那根不接手（左键照旧按到两根都松）、
  "左键"按钮 / CMD_HOLD_LEFT / 右键不是杆、"左键"按钮和左键杆共用计数、被取消 / releaseAll / 换参数（节拍停、左键松开、之后迟到的什么都不出）、碰左键停惯性、20 秒暂停再动接着；
  点左键时指肚滚出容差一点（容差 + 0.2 到容差 + 2.4dp、8–40ms 滚到，默认参数负惯性开，120 种）只是一次单击；实体键按着时 releaseAll / 换参数、之后左键杆拖着时才松开实体键：
  迟到的松开什么都不发、杆接着拖、位移都带着左键（leftLeverLateHoldLeftRelease；holdLeftCommand 也锁住多出来的松开不理、不减收尾以后才按下的"左键"按钮）；
  左键杆让位：拇指按左键往右下漂 0 / 4 / 7 / 10 / 30dp 的同时推红点（负惯性开 / 关）/ 划触控板，光标和拇指不漂时一模一样，另一根松了以后拇指再挪也不动、节拍停，红点先按、拇指后按也一样；
  先用左键杆、后加红点叠加，挪出容差还在死区里时加红点也不让位，中键按着红点滚动（滚轮）时不让位、之后左键杆照样推光标（中键按着也是移动）；
  三个来源随机交错 400 组 × 100 步（红点、中键、左键、触控板、"左键"按钮，相对按下点的小挪 / 大挪、CMD_HOLD_LEFT（收尾以后实体键照样当还按着，之后发迟到的松开）、收尾：节拍开着 ⇔ 有来源在推、开停配对、
  节拍只出红点 / 杆推的东西、红点没按着时节拍出的位移都带着左键、全部抬起后左键松开），原来的 leverTicksRandom 的模型也带上左键杆）、GestureEngineTest（中键滚动）、
  KeyboardPlanTest（2026-11-01 第二次改写：小红点固定合并（布局 × 尺寸 × 存的格子扫一遍，都和合并一模一样）、底行和快捷行让出的空位（stickHole 的几种情况：对称 / 空格最短 / 不让）、
  三个键怎么铺（holeKeys：对称时中键正对红点、左右一样宽，2.2 : 1 : 2.2）、切口几何（cutShare 对公式、cuts、labelShift）、红点嵌在键里多大（每层每个键被切掉 ≤ 25%、字母层 ≤ 16%、字躲得开）、
  放外面、⋯ 回功能行；stickRouting 把红点、铺满空位的三个鼠标键、三层的键、键盘范围照 MainActivity 的顺序交给真正的 TouchRouter / KeyFingers 逐点核对：红点圆里归红点、空位里归那一格的鼠标键、
  别处和没有它们时一模一样、方块角上和切口外看得见的键就是那个键；再扫一遍各种尺寸 × 布局 × 指点 × 三键条 × 存的位置 × 有没有快捷行 / 一个快捷键 / 苹果底行，
  查空位规则、空格 1–1.5 份、鼠标键宽度、切口比例、字挪完不被吃掉、不出键帽、快捷行让不让位是 shortcutsSpread 给的——让了每个快捷键连缝 ≥ 32、没让的和指点关着时一模一样、
  三个键的下沿跟着走；stickHoleRules 里 shortcutsSpread 的几种宽度和 32 那条边界）、KeyRowsTest（stickPoint 正好在 g、h、v、b 交界，rows / layers；
  让位的行（底行 before / after、快捷行 spread、份数、holeTop、holeBottom、spreadLeft）、让位时的摆法（空位里没有键、?123 , . ↵ 宽度、空格伸缩、快捷行分到两边顺序不变、别的行不变；
  spread 为假时快捷行和不让位时一模一样）、capKeysCover（每个会显示的键都有键帽，照 KeyboardPanel 建键帽的范围核对））、
  KeyFingersTest（洞：红点按内切圆、空位整块）、
  ThemeTest（每套小红点对页面 / 触控板 ≥ 3:1、还是红的）、HidTest（CMD_CONFIG 56 字节往返；协议 22 起 60 字节，多了 leverSpeed）。
  触控板背景图：PadFitTest（填满盖满不变形、完整显示整张在里面、拉伸铺满、原大小补回解码缩的、平铺相位、焦点规则 / S = B 处连续 / 拖分隔条不跳、吸附、按形状挑哪套、
  解码尺寸的上限和"没撞上限不用再放大"（撞上限时打出最多放大几倍）、空的 / 坏的输入、每套主题 padFill 不透明）。
  惯性滚动 / 连续滚动：GestureEngineTest（起不起、闭式解对不对、抓住不算点击 / 长按、cancel / 滚轮 / stopSpin / Ctrl 停、方向、定时器晚到、15 秒兜底、连续模式的单位和咔嗒、双指锁轴、乱序序列）、
  TouchRouterTest（碰键静默停、碰按钮 / 空白 / 左键震刹住、缩放整格、锁 Ctrl 不起、换参数停）、HidTest（小 HID 解析器逐字段比新旧描述符、Feature 报告的样子、uhid 那份只有一个竖向倍率字段（照内核数 maxfield = 1）、没有 4 字节项、
  倍率字节（含 UhidMouse 设 / 问倍率直接调的 WheelOut.setUhidFeature / uhidFeatureReport）、WheelOut 换算 / 余数 / 拆帧、CMD_CONFIG 往返、ScrollInertia 对数值积分和方案的表）、ServerPartsTest（uhid 的 GET / SET 事件解析、回复逐字节；OneShotTimer 真起一个定时器线程：撤掉 / 取代后卡在锁上的那次不调、回调里再排接得上，
  加一条用真 TouchRouter 的"过期的惯性帧不被当成长按"）。
  屏幕边缘留白：EdgeMarginTest（哪几条边留——全屏、分屏上 / 下 / 左 / 右、小窗、差两个像素、超出屏幕；留多少——0、负数、窗口没量好、窗口小时按比例缩；edges；随机窗口；
  再把收进去的页面交给真正的 KeyboardPlan 扫一遍窗口 × 留白：菜单、⋯、调整栏、三键条、键盘外面的红点都在页面里、地方够时离窗口边至少 max(20, 留白)，打出每种真查到了几次）、
  KeyboardPlanTest 的 perSideEdges（各边不一样时 ⋯ / 菜单 / 菜单排法 / 调整栏 / 三键条的具体值，四边 20 的 withEdges 和原来一样）。
- 实体键盘 F1–F12 的第二功能、F 行翻转（2026-11-02，协议 24，`tools/test.sh`）：FKeysTest（全经真 TouchRouter / KeyHolds，借 KeyHoldsTest 的 H：平时出 F 键本身、按住 Fn 出第二功能（多媒体 / 系统键 / 组合键）、
  没配的照旧、翻转着反过来、Fn 层别的键不跟翻转；单次锁用掉、永久锁一直、永久锁 + 翻转 = F 键；Fn+Esc 翻转（不发 HID、报 FROW_FLIP、当场不震、FLIP_BUZZ_MS 后震一下、翻完马上 releaseAll 不震、连翻两次只震后一次、早到 / 过期的定时器回调不多震，Fn 当组合用了不单次锁）、没按 Fn 时 Esc 照旧、
  单次锁 + Esc 用掉单次锁但锁住的 Shift 不解开、永久锁着 Esc 一直是开关、fn🌐 + Esc 不出 🌐；按着 F 键时翻转 / 换设置，松开照样松按下时的码（区域、key() 两条路）；系统键、组合键的报文顺序；
  两个多媒体键一次报一个、两个槽各报各的；第二功能解开锁住的修饰键、让按着的修饰键算用过；releaseAll 发 0、迟到的松开不再多发；翻转是设置（releaseAll / unlatch 不清、setConfig 不报界面）；
  被取消的照样松开；同一个 F 键两根手指；两条路输出一样；400 组随机序列查"按下出的面 = Fn 起作用 ^ 翻转、两个槽 = 模型、全部抬起后是 0、收尾后翻转还在、震动次数"）、
  FKeyFunctions（功能表的码在描述符范围里、抽查值、"key:XXXX"、预设和 presetOf、默认值）、Boards 的存储（kbx_ 开头、两种模式各一套、默认值）；
  HidTest（系统键描述符逐字段、和方案附录照 QMK 写的那串逐字节一样、已有的描述符冻结在 f6b905d 的长度 + CRC32（frozenDescriptors，审查后加的：四种蓝牙描述符、键盘、多媒体、三种鼠标、绝对坐标、手柄）、
  蓝牙描述符末尾加报告 6 而前面是冻结的那串、Controls 描述符 = 蓝牙那两段、报文字节、UsageSlot 含随机序列、
  CMD_CONFIG 69 字节且前 68 字节和协议 23 的编码一样（CRC32））、ServerPartsTest.protocol24（版本、MSG_CONSUMER 编号、FROW_FLIP 位、F 行键 id 的范围和别的几种 id 不重叠、KEY_ID_BASE + id 不溢出）。
  关键规则逐个改坏跑过（scratchpad 里一次性的），都有测试报错。UhidControls、Session、Link、BtHid、MainActivity 的接线离线编不到，只编译过。
- 实体键盘 F1–F12 的第二功能：几何和键帽那一半（2026-11-02，协议 24，`tools/test.sh`）：FixedFKeysTest（从仓库根目录跑，读 res/raw）——
  FKeyFaces（每个能选的功能两种模式下都有图标或字、多媒体 / 系统键那一组都有图标、27 个图标的笔画都认得、参数个数对、坐标在 24 × 24 的方格里、彼此不一样；
  组合键的写法 Windows "Super+P" "Super+Shift+S" "Super+Shift+F23"、Mac "⌘P" "⇧⌘4" "⌃C"、修饰键的顺序、右边的修饰键折到左边、F13 / F24、键名）；
  报的 id（每种 尺寸 × 形状 × 模式 × 七套配法——没配 / 不用 / ThinkPad / 苹果 / 自定义一套各种发法加认不出的 id / 两套固定种子的随机——：65% 不上、配了的 F 键 Proto.frowKey 两面都取得回来、
  没配的报用法码、至少一个配了时 Esc 报 FN_LOCK_KEY、一块面板里 id 各不相同、KEY_ID_BASE + id 是正数；forEntry 带 / 不带 fkeys）；
  几何不跟配法变（键和范围一模一样）；TKL / 全尺寸 / 笔记本的 Fn 在哪、笔记本 ⋯ 槽、Fn+Del = Ins；Boards（默认 Windows ThinkPad 平时 F1–F12、Mac 苹果平时第二功能，spec 带 fkeys、
  改了比得出来、toString 不变，存的都是 kbx_ / kbd_ 开头）；键帽扫描（尺寸 × 形状 × 模式 × 配法 × ⋯ × 让位 × 三种窗口 × 十种 Fn 状态 × 五种修饰键 × Caps：每个键都有字，
  F 键两面都印时上第二功能 / 下 F1、哪面亮照"Fn 起作用 ^ 翻转"，放不下两层只画会出的那一面，Esc 的 FnLock，别的键不看翻转、和旧的 glyphs 一样，F 行的键是功能键、不放大）和钉死的几个键帽；
  三条触摸路径（真 TouchRouter + FixedPanel.regions 和面板 keyAt → keyId 隔 4px 逐点同一个 id；每个 F 键、Esc 两种起始翻转各一遍：单按、按着 Fn、Fn+Esc 翻转后单按，
  区域那一路和 key() 那一路报文一样、出的是该出的那一面——多媒体 "c 码"、系统键 "s 码"、组合键先修饰键再键——、翻转（FLIP_BUZZ_MS 后）震一下报 FROW_FLIP、最后全松）；
  键帽上的字（textFit，审查后加的：fitText 的规则——放得下不动、先缩、到 7sp 放得进 hard 不动、组合键折两行优先、两行放不下才三行、Mac 写法在修饰键符号后折、折不下 / 只许一行的位置末尾省略到刚好、
  只剩"…"、一个字不省略、不放大；textRooms 每个位置 room ≤ hard ≤ 键帽宽、只有正中和上半许折行、上半折行的范围和 F1 不重叠；再扫每种尺寸在一格 32dp 和两种常见窗口 × ⋯ × 七套配法 × 十种 Fn 状态 × 四种修饰键
  的每个字七百多万个：按估的字宽（偏宽的 Noto Sans 近似，行高 1.36）每行不出键帽、几行不超过 vroom、折行拼回去是原字、省略的是开头 + "…"、一行放得下的绝不折不省略；钉死 F11 "Super+Shift+S" 在 933 宽的 TKL 上折成两行）；
  小红点照旧（笔记本 / 75% / 65% 的 Windows 模式有红点和三个鼠标键，Fn 键不在空位里；TKL / 全尺寸 / Mac 模式不给）。FixedLayoutTest / FixedCompactTest 跟着几何改了写着右 Super、
  TKL 的 🌐、笔记本 ⋯ 槽 / Ctrl 宽度 / 键数（84 / 85，ABNT2 86）/ Mac 笔记本范围（放 ⋯ 时 8 块）的那几条，别的扫描原样过。键帽改坏六处跑过（见决策记录），都有测试报错。
  FixedKeyboardView 的图标画法离线编不到，只编译过。
- 实体键盘 F1–F12 的第二功能：设置页那一半（2026-11-02，协议 24，`tools/test.sh`）：FKeyChoicesTest（从仓库根目录跑）——动作清单照真的 Actions.java 源码解析（hid(…) 和 apple(…) 两种行，苹果设备的码照 Actions.hid 的规则；解析出的太少就报错，提醒源码写法变了）；列表的样子（两种模式："无"第一、"自定义组合键…"最后且只有一项、分组标题从小到大各一个、每个标题下面至少一项、组里的项都属于这一组、发的码不重、快捷键存 key:XXXX、动作清单的截屏不重复列、复制 Windows Ctrl+C / Mac ⌘C；码为 0 / 重复的快捷键不列）；每一项存进去读回来是它自己（describe / selected），null / 认不出的 / "无"标在"无"上；Mac 模式少的正好是 Super+P、Super+N、Copilot 键，存着的照样认得出、列表里不标，两套预设的每一项都认得出；自定义组合键（canonical：Windows Super+P = display、Mac ⌘P 存组合、两种截屏工具、PrtSc、F13；动作清单里有的认回快捷键、没有的是不在列表里的自定义、标在"自定义组合键…"上；行上写组合本身）；预设那一行（三项 / 自定义时四项、"不用"时藏、Boards.fkeyPreset 两种模式各算各的、逐个改成 12 个"无"认成"不用"、自定义组合键拼出 Super+P 预设照样认得出、只写 kbx_）；端到端（两种模式 × 列表里每一项加一个自定义组合键和 Mac 模式存着的 Super+P：写进 Boards、Spec 带着它、照 FKeyFunctions.regionId 拼 id、经真 TouchRouter / KeyHolds 按下松开——翻转开时多媒体 / 系统键那两个槽报那个码再报 0、组合键主机收到 [修饰键|键] 再全松、行上写的组合就是发的；翻转关时出 F1；"无"两面都是 F1——共 310 次）；随机改（30 轮 × 80 步：挑预设、逐个配、自定义组合键、翻"平时按出"、换模式——预设那一行永远是 12 个现算的、12 行每一行都认得出、只写 kbx_、豆花布局的 kb_ 一个没变）；名字（FKeyFunctions 里每个有名字的功能 SettingsActivity 里有 R.string.fkf_<id>、用了每个分组标题，九个 strings.xml 都有、这一节的条目一一对应、占位符一样、不重名）。SettingsActivity 本身离线编不到，只编译过。
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
- 声音第一批（2026-11-02，`tools/test.sh`）：SoundTest（10616 项，第 ③ 步加了晚放的以后 10624 项）——合成：两套 × 每个槽 × 每个候选 × 子声音 × 4 个变体（332 个声音）在 44.1 / 48 / 96 kHz 下各查一遍：峰值 1、没有 NaN、
  第一个采样不是 0、1 ms 内到峰值的 5%（开头没有静音）、长度和主体（−20 dB 那一点）在方案的表里、结尾淡到 0、2.5–5.5 kHz 的最高处比 300–2500 Hz 的主峰低 ≥ 6 dB、150 Hz 以下低 ≥ 20 dB、
  200 Hz 以下能量 < 2%（打出每个采样率下最差的那个）；同一个种子逐个一样、种子不同不一样、SplitMix64 钉了头两个值；收尾滤波本身（100 / 150 Hz 压多少、1 kHz 只动一点、3.7 kHz 的凹）。
  清单：每类 2–4 个候选、代号 A–D、默认的出声、触控板是 TAPTIC 且两套共用、木与枝的木块 / 树枝 / 竹筒 / 松开不出声、存的字认得回来、子声音的配方（右键高、中键低闷、触控板松开高短）；
  电平的先后（松开轻 6–12、鼠标轻约 2、触控板比鼠标再轻、滚轮轻 10–14、刹住更轻）；建一份：关是 null、只算默认 / 全算 / 只算选的、建两次逐个一样。
  变化规则：类别（含组合键、合并键、Fn 层、F 行键、Fn 键）、行和音分、随机的范围（按键 / 鼠标 / 滚轮、随机幅度 0 / 2 / 封顶 3）、变体不连着重复且都用得到、打得快（间隔 300 … 0.02 ms：每下都出声，
  ≥ 125 不变、中间轻 1.5–4、≤ 60 轻 4、短尾巴，越快越轻，第二下不算、停 2 秒重新数、松开不另外变）、滚轮合并（一秒 100 格放约 25 格、间隔 ≥ 40 ms、轻 3 dB，一秒 10 格一格不合并）、互斥的组。
  混音：请求打包往返、下一块第 0 帧出声且等于软限幅(REF × 电平 × 变体)、同键 6 ms 淡完、不同键不打断、松开打断同键按下的尾巴、最多 8 个 / 池子 16 / 抢最老的 / 一毫秒一个用不着硬切 / 一块里 20 个硬切只切在淡的、
  软限幅的形状、叠满不溢出、确定性、音量（0 / 50 / 100、减半输出减半）、指定 / 设置里选 / 没算退回默认的候选、再轻 6 dB 和音量偏移、不出声的候选不起声也不打断按下、短尾巴真短了、闲着的判断、换一份 / reset 清空。
  队列：先进先出、满了放不进、绕 1000 圈、0 不能放；4 个线程各写 20000 个、一个线程读：放进去的恰好读到一次、每个线程的先后不乱。另打出混 5 秒（8 个一直响）多久、全算一套多久。
  改坏跑过（去掉一节高通、去掉凹、开头留 2 ms 静音、不互斥、不抢占、变体可以重复、不短尾巴、滚轮不合并、不出声的也打断、队列写错、硬切切到活的、音量翻倍、行反了、再轻不生效），都有检查报错。
  Sounds（AudioTrack 那层）离线编不到，只编译过（-Xlint:all 没有警告）、逐行走过一遍闪退路径。
- 声音第一批第 ③ 步：事件层 + 协议 25（2026-11-02，`tools/test.sh`）：SoundEventsTest——声音关着（四个开关 × 震动开关 64 种）一个都不放、每种震动从哪来都照旧震、
  wantTicks = 震动开关；方案 2.3 的表一条条（键、被取消、特殊键只配按过的 / 同一个按两次 / 收尾 reset / 记满了、鼠标键、点击类动作的一对、滚轮动作、手柄、
  分流来的每种震动 × 从哪来）；四个开关各管各的；"滚轮出声不震"的 8 种组合 × 每种震动 × 从哪来；特殊键的发声 id（功能键、最下一排、各不相同、不和 Fn / F 行键撞）；
  面板那一路和分流那一路同一个键出的一样（普通、组合、合并、Fn、Fn 层、F 行键；审查后改成经 panelDown / panelUp 比——以前比的是 keyDown / keyUp，routerButton 里就是调它们，按构造必过；
  面板交出的 special、canceled = !tap 仍然测不到）；真 TouchRouter + GestureEngine 跑：单指轻点（抬起时一对、定时器松左键不再出声、声音关着时震动一模一样）、
  双指轻点、双击（每下一对）、长按拖动、双指滚动（声音震动都开 / 只出声不震 / 都关引擎不发）、惯性（放开不出声、刹住一声、碰键静默停）、键（被取消、releaseAll、迟到的抬起）、
  Shift 按住到点和合并键出句号不出声、三个鼠标键（被取消、releaseAll）、手柄页（开关开 / 关）。
  TouchRouterTest.protocol25Flags：正常按下 / 抬起都不带标记、被取消的抬起都带（按钮还在里面照旧 TAP）、按下从来不带、换参数留着的键之后正常抬起不带、
  手柄五种收法（正常 / 被取消 / releaseAll / setConfig / setGamepadConfig）和收完再按一下、按住到点报 HAPTIC_HOLD 而轻点报 HAPTIC_CLICK；原来锁"释放时报松开"的几条改成带标记的。
  ServerPartsTest.protocol25：版本、HAPTIC_HOLD 和别的种类不撞、BUTTON_CANCELED 和 DOWN / UP / TAP 不撞且去得掉、MSG_BUTTON / MSG_HAPTIC 的字节排布照旧；真 OneShotTimer 那条改看 HAPTIC_HOLD。
  KeyHoldsTest / FKeysTest 数的"按住到点那一下"改成 HAPTIC_HOLD（触控板轻点不再算进去，另查它报的是 HAPTIC_CLICK）。
  SoundTest 加了晚放的那一个：延后写得进取得回（截到 0–255）、正好在第 delay × 采样率 / 1000 帧开始、一对的前 45 ms 和只放"咔"逐个一样、等着时算有声音、
  被同一组新来的淡掉就不放、不同组的叠着、确定性。
  改坏跑过 15 处（scratchpad 里一次性的脚本）：键的松开 / releaseAll 不带标记、手柄收尾不带标记、按住到点改回 HAPTIC_CLICK、被取消的也出松开声、轻点只放一声、
  wantTicks 不看声音、只为出声发的咔嗒照样震、按住到点出声、特殊键松开不配对、声音关着照样放、晚放的也淡掉同组、等着的被淡掉照样放、不理延后、触控板开始拖动不出声，都有检查报错。
  MainActivity / Sounds / 面板的挂点离线编不到，只编译过、逐行走过闪退路径。
- 声音第一批第 ④ 步：设置页的"声音"一节（2026-11-02，`tools/test.sh`）：SoundBenchTest（4688 项）——试听台的一排键（类别：⇧ ⌫ 大键、空格、回车，主次键帽同 KeyRows.functionKey，经事件层按下 / 松开各一声、被取消不出声）；
  候选区每个槽点一下 / 连打 16 下的每一下：正在试的那一半经 SoundVary 落在这个槽、配对的落在别的槽、第一个不延后、延后写得进请求；真 SoundMixer 上"指定候选试听"和"选了这个候选正常放"逐个采样一样
  （两套 × 每个槽 × 每个候选 × 点一下 / 连打）、不出声的候选点了没声音、别的都有；连打每秒 8 下 2 秒（审查后改成 7 下）、字母在三排上不连着同一个、大键都落在大键那个槽；每个槽的震动同主界面；
  打乱顺序（是排列、同一个种子一样、4 个候选 24 种排法都出现、第一个按钮背后是 A 的约三分之一；不打乱就是原来的顺序）；点一项放的那一小段（按时间排好、不到 1.5 秒、按下都配了松开，
  两套在真混音器上每个按下开始那 2 毫秒就有声音）；那一小块触控板（轻点的容差 / 时长边界、挪出容差就是划、按路长每 step 一格、来回也算、被取消、第二次按下从头来、随机走 400 步格数一直是 路长 / step 取整）；
  微调滑块的范围同 SoundMixer / SoundVary。改坏跑过 7 处（大键用普通键、不打乱、轻点不看容差、那一小段少一个松开、连打的候选标反、挪出容差那一刻不从按下点算、滚轮槽的震动写错），都有检查报错。
  SettingsActivity、Haptics 的延后、Sounds.ready 离线编不到，只编译过、逐行走过闪退路径。
- 声音第一批审查后（2026-11-02，`tools/test.sh`）：SoundTest 12196 项（原来 10624）——合成那一扫加了每个声音 500 Hz 以下的能量 ≤ 40%（三种采样率最多 33%）、
  树枝头 2 ms 里到峰值的 80% 以上（现在全部 100%）；新的 loudness（44.1 / 48 / 96 kHz × 两套 × 每个候选 × 子声音）：拿算好的波形另写一份量法（Bank.gain × 采样、800 Hz 二阶高通、整段总能量、四个变体平均）——
  和电平表差 ≤ 0.6 dB（现在 ≤ 0.1）、默认候选之间鼠标比普通键轻 1–3 dB、触控板比鼠标再轻 3–5、松开轻 6–12、鼠标 / 触控板松开轻 ≥ 4、滚轮轻 10–14、刹住比滚轮轻，
  单个声音（音量 100%、不随机）的峰值在软限幅拐点 0.5 以下（现在最多 0.47）；随机音高的范围改了（按键 ±12、鼠标 ±10、滚轮 ±8，加上变体之间的 ±16 都 ≤ 30）、随机幅度 0 时变体一律第 0 个、
  同一个键连按每一下逐个采样一样；打得快的回差（快了以后 130 ms 还算快、平均到 145 ms 恢复；每秒 8 下按块对齐、抖 0–8 ms 跑 200 串 × 16 下，快 / 不快最多变一次）；混音那几条改看 Bank.gain（REF 删了）。
  SoundBenchTest 4502 项：连打每秒 7 下 2 秒 14 下、间隔减 8 ms 抖动还在打得快的门槛以上、200 串按块对齐抖着放一下都不算快。
  SoundEventsTest 107 项（原来 91）：震动表加了 HAPTIC_DRAG_CANCEL（从哪来都不出声）；真 TouchRouter 跑长按拖动时手指被取消 / releaseAll / 换参数只有开始那一声、震动照旧开始 / 结束拖动；
  "左键"按钮（REGION_HOLD_LEFT）松手一对、被取消 / releaseAll 只有按下声；面板那一路的映射 panelDown / panelUp 和分流那一路一样（普通键、特殊键，特殊键只给按过的出松开声）。
  GestureEngineTest 206 项（原来 192）：dragCanceledKind（长按拖动、轻点后拖 × 松手 / 手指被取消 / cancel：报 DRAG_END / DRAG_CANCEL，左键照样松开；第二根手指来了照旧 DRAG_END；没在拖不报）。
  TouchRouterTest 656 项：protocol25Flags 加了 releaseAll 时键、按钮、"左键"、鼠标键、红点报的松开都带被取消（"左键"是 b3:129）。
  AirMouseTest（HardKeys 那几条）：正常松手的结束和被取消的（系统取消、reset）分开记。ServerPartsTest 320 项：HARD_HOLD_CANCEL = 3、HAPTIC_DRAG_CANCEL = 9 不撞；HardKeyGate 的 releaseAll、电源键组合补的结束是 HOLD_CANCEL。
  改坏跑过 14 处（scratchpad 里一次性的脚本）："左键" releaseAll 不带标记、cancel 报 DRAG_END、被取消的抬起报 DRAG_END、增益照峰值算、触控板 A 换回 420 Hz、竹筒 C 换回 460 Hz、树枝不压后面的小裂、
  随机幅度 0 照样挑变体、打得快不回差、连打改回 8 下、HardKeys.reset 报正常结束、DRAG_CANCEL 出声、随机音高改回 ±20、面板的特殊键被取消也出声，都有检查报错。
  Sounds（管道那几处：只在低延迟时压缓冲、换套前停住、起步的断流单独记、查路由挪地方、坏了再开）离线编不到，只编译过、逐行走过闪退路径。
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
- 实体键盘第 2 批（数据，2026-11-01，`tools/test.sh`）：check_tables.py（34 个布局 × 四家：来源 / sha256 和 sources.json 一致、清单和 catalog.json / mac_names.json 对得上
  （modes 照有没有 Windows / Mac 数据、没数据的那家一定没有那一段、只有 Mac 有的正好是 MAC_ONLY 那两个、CLDR 旧名都写进了 mac_names.json、小写的区分词得是有翻译的）、不变量（字母键都有字、凑得齐 a–z、
  字母键正好是 a–z 的例外 LETTER_EXCEPTIONS、死键只在附加符号上 + DEAD_EXCEPTIONS、ISO / ABNT2 的 0x64、只有 ABNT2 有 0x87、Mac 的 .iso 对调、小键盘 kpdl / kpdl_win / kpco——两种小数点 34 个布局全锁住，KPDL / KPDL_WIN）、
  抽查值（方案 §6.4 第 2 批 + 凭常识写的各国 @ 在哪之类，和数据核对一致）、别名（win:at = de、拼音 = 美式……））；gen_layouts.py --selftest 加了 KCM 的 U+030D / U+030E、
  catalog.json 的自检、Mac 现名、xkb br 的小键盘和 0x87；FixedLegendsTest 加了清单按 #!columns 认栏（顺序随便、缺栏 / 栏数不对 / id 不对抛异常）、34 个布局的清单值、
  三个要翻译的区分词九个 strings.xml 都有、第 2 批抽查值、每个布局每种有数据的模式都过不变量（审查后 65 张表：意大利 QZERTY、瑞典旧版没有 Windows 的）、没数据的模式拼不出键盘
  （missingModes：拉美的 Mac，意大利 QZERTY、瑞典旧版的 Windows）、每个有 Mac 数据的布局 .iso 都对调、34 个布局的两种小数点和 kpdl(mode)（按模式取，认不出的模式当 Windows）；
  FixedLayoutTest.caps 照清单扫全部布局（只扫有数据的模式；巴西按 ABNT2 扫：多的 0x87 在字表里、小键盘 0x85 写清单的 kpco——界面那一半做了），
  keypadDecimal（审查后加的：每个布局 × 有数据的模式拼全尺寸，小键盘小数点写的是 kpdl(mode)；荷兰、冰岛、捷克、斯洛伐克 Windows 写 ","、Mac 写 "."）；
  gen_layouts.py --selftest 加了 check_catalog 认"只有 Mac 的数据""手写 kpdl_win"、拒"两种都没有""kpdl_win 不是 . ,""没有 Windows 数据却写 kpdl_win"。
- 实体键盘第 2 批（界面，2026-11-01，`tools/test.sh`）：FixedLayoutTest 的 1B / 笔记本扫描函数多了形状参数（原来那几次照旧传 ANSI / ISO，统计行和改之前逐字一样，拿改之前的输出 diff 过），
  ABNT2 另调一遍、单独打一行（标"（ABNT2）"）：rowsFill / noOverlap / rangesU / laptopRows / laptopNoOverlap / laptopRanges / placement（三种尺寸）/ routing（TKL + 全尺寸约 108 万点、笔记本约 34 万点）/
  laptopArrowSeam / specialOneSegment / stickPlan / stickRouting（三种尺寸，约 96 万点）；ABNT2 自己的（abnt2Geometry：键数 Windows 85 / 89 / 107、Mac 84 / 88 / 106，第 4 行顺序和宽度，
  两种模式 1 左边 0x35、Z 左边 0x64（对照 ISO 的 Mac 模式照旧对调），第 1–3 行和回车同 ISO，第 4 行、小键盘右列以外每个键和 ISO 同一个位置，小键盘 - + 0x85 ↵ 从上到下、+ 一行高，
  kpco / kpdl 写在键帽上、没给写 "."，清单里每个能选的形状 FixedLayout 都认得；abnt2Pins：; | /? | 右 Shift 两条缝、+ | 0x85 | ↵ 两条缝各归一半，真 TouchRouter 和 keyAt 两条路；
  abnt2Glyphs：win:br / mac:br 的 / ? 键、1 左边 ' "、Z 左边 \ |、回车左边 ] }、小键盘 . ,、主次键帽、放大提示）；capsLabels（拼音 Mac 模式三种尺寸 Caps 写 中/英、Windows 和美式照旧 Caps、照样跟着 Caps 显示高亮）；
  turkishI（土耳其 Q / F × 两种模式：i 那个键只画一个字、Shift / Caps 出 İ、放大提示 İ，ı 那个键出 I；别的 32 个布局每个键两种规则判断一样）。键帽齐全那一扫改用 forEntry 拼（巴西按 ABNT2、拼音写 中/英）。
  BoardsTest：abnt2Shape（巴西只能 ABNT2、存的值不动、清单里每个布局没存过形状时读出默认形状且 FixedLayout 认得）、modes（灰不灰和清单的 modes 一致、审查后三处：拉美的 Mac，
  意大利 QZERTY、瑞典旧版的 Windows（认不出的模式当 Windows 也灰）；没数据的组合存的值不动，意大利 QZERTY + Windows 不悄悄换成 Mac 或普通意大利）、names（九种界面语言 × 34 个布局：名字不重名、首字母不是小写、有区分词才接 ·；列表每个布局一项、按 Collator 排、同一种语言的挨在一起、Mac 模式下拉美灰掉；
  带地区的正好是英 / 法 / 德 / 西 / 葡那 14 个；英文界面钉了 13 个名字——JDK 的语言数据，Android 上是 ICU 的，写法可能略有不同）；随机序列改成在全部 34 个布局 + 认不出的 id、三种形状 + 认不出的形状里挑，
  每步查"读出来的形状是这个布局能选的"。新加的检查用变异测试核过（scratchpad 里的一次性脚本：去掉土耳其规则、ABNT2 的 Mac 模式也对调、kpco 写死、caps_mac 不管、名字一律带地区、不灰掉，各自都有检查报错）。
- 实体键盘笔记本尺寸 + 逐级缩（2026-11-01，`tools/test.sh`，都在 FixedLayoutTest / BoardsTest 里）：1B 的扫描（placement / routing / caps / 默认位置 / 小红点 / 小红点分流）带上尺寸参数，
  原来那次照旧 TKL / 全尺寸、统计行和改之前逐字一样（拿改之前的输出 diff 过），笔记本另调一次、单独打一行（标"（笔记本）"）。笔记本自己的：键数（Windows 83 / 84、Mac 82 / 83）、
  有哪些没有哪些、第 1–4 行和 TKL 逐段相等、F 行位置、最下一排上下两层各从 0 铺到 15、左 Ctrl 2.25 / 1.25、⋯ 槽、空格以红点为中线、坐标都是 1/16u、底行顺序和键名、
  横竖都看的不重叠、范围（Windows 8 块、Mac 9 块：最下一排方向键以外一块、方向键两层各一块，Ins 那格不在任何范围里）、方向键上下层隔 2 × gap、laptopArrowSeam（真 TouchRouter 和 keyAt 扫方向键两层之间的缝、右 Ctrl 旁的竖缝：上半给上层、下半给下层、按列、一个点都不给右 Ctrl，形状 × 模式 × ⋯ × 让位 × 五种窗口；把范围改回整排一块，这一组和范围的检查都报错）、钉死的点（↑ / ↓ / PgUp / ← 各格正中、左下角是 Ctrl、带 ⋯ 时第二格是 ⋯、
  空格正中竖着没有缝、Mac Ins 格两条路都是什么都没有）；specialOneSegment（三种尺寸的 ⋯ / 🌐 都只有一段）、buttonTap（真 TouchRouter：按下、挪到段边、抬起是 BUTTON_TAP）；
  levels（fixedLevel 的边界 720 / 719 / 584 / 583 / 480 / 479、只缩不涨、空 ladder，再扫 300–2600 每个宽度：挑的是第一个放得下的）；笔记本默认位置（自然比例按 6.5 行）；
  laptopStickHole（面板 480–2600 每个宽度都让得出空位、对称、键 ≥ 32、空格 ≥ 0.75 份，720 / 719 的 44dp 边界、545 起空格 ≥ 1 份；TKL 480 照旧让不出；
  页宽 480–2600 × 高 × 默认 / 随机存过的位置约 2.5 万种 planFixed 都有三个鼠标键、中键正对红点）；fitFixed（撑到一格 32、存的不改、扫一遍）；
  sizing（FixedSizing：宽度来回变、只点一下换回、拖了存、拖动中换级 / 换存的 / 换键盘作废、恢复默认，6000 步随机序列查"planInput 不是 null 时要么是存的那份要么正在拖、
  存进的一定是按下时显示的尺寸"）；把 FixedSizing 的三处改坏（换级不作废、没动也存、不从显示着的那份开始拖）跑过，都报错。
  BoardsTest：认 laptop、三个尺寸各存各的、恢复默认只清给的那个、savedRects 只含存过的、随机序列的尺寸从三个里挑。
  审查后加的：HidTest.modifierHelpers（Hid.ctrl 左右都算、modifierUsages 256 种掩码点完都切回 0——modifierUsages 那几条 2026-11-01 随协议 20 删了，只剩 ctrl / modifierBit）、TouchRouterTest.spinNotWithRightCtrlLatched（锁右 Ctrl 不起惯性、换参数后照样、
  转着时按右 Ctrl 停、连续滚动开着时照旧整格）；把这几处改回去跑过一遍，新加的检查都报错（右 Ctrl 3 项、Hid 1 项、实体键盘 9 项）。
  界面（FixedKeyboardView 画出来的样子、KeyboardScreen 换面板、设置页）离线编不到，没在真机上看过。
- 实体键盘第 3 批（几何那一半，2026-11-01，`tools/test.sh`）：新的 FixedCompactTest（计数借 FixedLayoutTest 的、扫描也借它的，FixedLayoutTest.load 读数据）：
  键数（75% 83 / 84 / 85、65% 68 / 69 / 70、苹果紧凑 77 / 78）、用法码各一次、有哪些没有哪些（65% 没有 F 行和 Home、1 左边那个键被 Esc 占了，苹果紧凑没有 PgUp 这几个和 Del、右 ⌃）、
  行表（标准的四种逐个值没变，65% / 苹果紧凑自己的）、一个区块 + 1/16u、每行铺满到面板边、最下一排和 Z 行的顺序和宽度（ABNT2 的右 Shift 0.75u）、主键区和 TKL 同位置同码、右边那一列、
  ↑ 正对 ↓（苹果紧凑 ↑↓ 上下两半）、横竖都看的不重叠、⋯ 槽（65% 放进去时 Ctrl 1 · ⋯ 0.75 · 1 · 1、空格不动）、范围（不重叠、每段恰好一块、铺满没有空格子、放没放 ⋯ 一样——1B / 笔记本也核了、
  苹果紧凑最下一排四块）、拼不拼得出（苹果紧凑 × Windows / ABNT2 的 forEntry / forLegends 是 null、build 照 Mac / ISO 拼）、六种尺寸的梯子只缩不涨、fixedFits 的门槛（512 / 464）、
  逐级缩挑哪一级（全尺寸 720 / 584 / 512 / 480 的边界，扫尺寸 × 形状 × 模式 × 宽度）、Fn 层照规格逐项对（每项的底层键在、码是键盘页的普通键、和底层不一样、名字同别的尺寸里那个码的键、
  65% 的 Fn+Esc 发这块键盘上 1 左边那个键本来的码、和底层或 Fn 层另一个键同码的正好是方案里那几个）、键帽上的字（fn🌐 上 fn 下 🌐、Fn 层 fnGlyphs 的字、苹果紧凑 ISO 的 L 形回车和对调）、
  苹果紧凑 ↑↓ 周围的缝（真 TouchRouter 和 keyAt：两层之间按上下分、两边竖缝按中线，一个点都不给 ← / →）、小红点空位（每种面板宽都让得出、对称、空格 ≥ 1 份 / 苹果紧凑 ≥ 0.75 份，
  页宽 × 高 × 默认 / 随机存过的位置约 2.3 万种摆法都有三个鼠标键、中键正对红点）。再借 FixedLayoutTest 的 placement / routing（约 232 万点，空格子 0）/ caps（1416 种，连 Fn 层 fnCaps）/
  specialOneSegment / buttonTap / defaultsScan / stickPlan / stickRouting（约 251 万点）把三种新尺寸各跑一遍、单独打一行。FixedLayoutTest 原来那些扫描函数多了"拼不出的组合跳过"、
  "有 Fn 层就查 Fn 层的字"、特殊键数照 Fn 键算，原有尺寸的输出（含计数）和改之前逐字一样（拿改之前的输出 diff 过；ladder 那条的期望值改成新梯子）。
  BoardsTest.batch3：六种尺寸存得进读得出、键名、savedRects 六份、sizeAvailable 和 FixedLayout.available 一致、存着的组合配不上时存的值不动、语言列表苹果紧凑时只有巴西因为尺寸灰掉
  （别的尺寸和不看尺寸时一样）、20 轮随机序列（尺寸在六种里挑）kb_ 一个没动、配不上的一定是苹果紧凑 + Windows / ABNT2。
- 实体键盘第 3 批（Fn 层的机制，协议 21，2026-11-01，`tools/test.sh`；**2026-11-01 晚起 Fn 的单点 / 按住 / 解开这几条已按协议 22 的新规矩改写**——Windows 单点 = 单次锁、按住 0.4 秒起永久锁、
  没有 1 秒上限、永久锁不被下一个键解开，见下面"上手后的一批"那段；这一段写的是协议 21 时的断言，组合、定码、先松 Fn、多指、收尾那些照旧）：KeyHoldsTest 的 Fn 一节（全经真 TouchRouter，屏幕上 Fn / fn🌐 / ↑ / 1 / 右 Ctrl / Shift / a / PgUp / Caps）：
  编号（🌐 的按钮编号同 KeyRows、底层 / Fn 层取得回来）、组合（按住 Fn 时 ↑ 出 PgUp、1 出 F1、a 照旧，松开 Fn 回底层，用过的 Fn 不锁、不出 🌐）、Windows 单点 Fn 什么都不做、
  Mac 单点 fn🌐 出 🌐（区域那一路也一样）、按住 0.4–1 秒锁 / 超过 1 秒 / 被取消 / 按下时别的键按着都不出 🌐、锁住只管下一个键（打完 PgUp 解开、打完没有 Fn 层的 a 也解开、锁着时按着 Shift 打 1 = Shift+F1）、
  再点一下解开、超过 1 秒不锁、定时器卡住时按截止时刻补、先松 Fn 再松键松的是 Fn 层的码（两条路、被取消的也一样）、按着键时才按 Fn 松的是底层的码、
  两根三根手指按同一个键（跟着第一根的码、每个按下都配了松开，两条路一模一样）、底层是修饰键（右 Ctrl：没按 Fn 时点一下 / 锁，按着 Fn 时出 Menu、修饰键状态不动，先按右 Ctrl 再按 Fn 松的是右 Ctrl）、
  Shift + Fn + 1 = Shift+F1、被取消、releaseAll（Fn 按着 / 锁着 / 快锁了归零，迟到的松开不出东西，之后先按同一个键重新定码）、换参数照旧按着 / 锁着、CMD_UNLATCH 解开锁着的 Fn、
  按着 Fn 用鼠标（点一下 / 滚轮 / 按住左键）不锁不出 🌐、按下时别的键 / 左键按着不锁、乱序的定时器回调什么都不做、区域那一路和 key() 那一路同样的序列一模一样；
  400 组 × 150 步随机序列（Fn、fn🌐、带 Fn 层的键、修饰键、普通键、两根手指、被取消、点鼠标、滚轮、乱序定时器、releaseAll、unlatch）查不变量（见不变量那条）。把 KeyHolds 的 Fn 逻辑改坏十一处跑过：十处报错，一处（Fn 不到点）让测试的时钟死循环、卡住不结束（修饰键那几组本来也是这样，test.sh 卡住同样看得出来）。
  ServerPartsTest.protocol21：版本、Fn 位、三种 id 互不重叠且取得回来（普通键 65536 个、合并键 hold 全部 × 四种 tap、带 Fn 层的键 255 × 768）、KEY_ID_BASE + id 都是正数；
  staleKeyTimerDoesNothing 加了 fn🌐：真 OneShotTimer 到点卡在锁上时松开，拿到锁的那次不震、不快锁、不锁，松开照单点出 🌐。
  FixedCompactTest.fnPaths：三种新尺寸 × 形状 × 模式 × ⋯ × 两种窗口共 56 块面板——报的区域每段类型 / id 对（Fn 键 FN_KEY / FN_GLOBE_KEY、带 Fn 层的 fnLayerKey、别的照旧，一块里各不相同），
  真 TouchRouter + 真报的区域和 keyAt → keyId 逐点同一个 id（约 140 万点），每个走分流的键区域那一路和 key() 那一路单按 / 按住 Fn 再按 / 先松 Fn 报文一模一样、单按出底层的码、按住 Fn 出 Fn 层的码，
  单点 Fn：Mac 出 🌐、Windows 什么都不出（协议 22 起 Windows 单点是单次锁、不发 HID，这一组跟着改了）；TKL Mac 的 🌐 键点一下报同一个按钮编号。FixedLayoutTest.fnCaps 加了 Fn 层显示着时的样子（每个键都有字、带 Fn 层的就是 fnGlyphs、
  别的和平时一样、字号 / 主次 / 放大提示照 Fn 层、Fn 键只看 Fn 的锁住 | 快锁了、Fn 层显示着时带 Fn 层的修饰键不亮），随 caps 那一扫跑 1416 种。
  FixedLayoutTest.regions（测试里拼的区域）类型改照 FixedLayout.regionType、buttonTap 只扫报成按钮的 ⋯ / 🌐（Fn 不再是按钮）；原有各组的输出除计数外和改之前逐字一样。
  改坏过两处跑过（去掉范围的 occupied、去掉苹果紧凑的空格最短 0.75 份那一步），新检查都报错。界面（设置页、⋯ 菜单、FixedKeyboardView 画 fn🌐）离线编不到，没在真机上看过。
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
- 分屏里豆花拿着焦点时交给另一半（协议 22，2026-11-01 晚，`tools/test.sh`）：FocusPickTest（RunningTaskInfo 按字段名读——16 那种字段全有、13 那种没有 isVisibleRequested /
  isTopActivityNoDisplay、没有 getWindowingMode、缺必须有的字段抛 NoSuchFieldException、topActivity 是 null；挑的规则——典型分屏交给 Moonlight、豆花没拿着焦点 / 没有豆花的任务 / 只有桌面看得见都不交，
  SystemUI、最近任务、助手、屏保、画中画、看不见、正要藏起来、noDisplay、没有 Activity、桌面一个个都不挑，类型 0 能挑，最底下是豆花也算豆花的任务，
  最近的优先、一样取靠前的，别的屏不挑，14–16 和 13 两种系统给的顺序；只在分屏时交（审查后）：豆花是小窗（浮在全屏 / 分屏 / 小窗上）不交、豆花的窗口模式是 0 / 1 / 2 / 3 / 4 / 7 / 读不出来不交，
  分屏时浮着的第三个小窗、后面的全屏、读不出模式的哪怕最近也不挑、另一半没有能挑的就不交；只读的那一问（pick(…, false)：豆花没拿着焦点时照"拿着"挑、也守分屏的规矩、ownNotFocused 只在没拿着焦点时标）；
  随机 5000 组列表（豆花偶尔是小窗）查挑出来的能挑、在这块屏、在分屏里、最近，不交的分四种都有）、
  FocusHandoffTest（六个条件 64 种组合只有一种发、同一次焦点只发一次、丢了焦点 / 重新连上算新的一次、1 秒间隔和等多久再问；照 MainActivity 用法的随机序列 400 组 × 200 步，
  查不满足时绝不发、两次至少隔 1 秒、同一次焦点同一条连接只发一次、满足后最多 1 秒内一定发出去）、ServerPartsTest.protocol22（协议 22 起、CMD_FOCUS_OTHER = 17 不撞、不算心跳）。
  把几条规则改坏（不看豆花有没有焦点、桌面也挑、不看最近、不看 isVisibleRequested、不守间隔、同一次焦点反复发、全屏也发；审查后又改坏五处：小窗也交、分屏时不只挑另一半、
  窗口模式读不出来也交、不认拿着焦点的那个豆花任务、只读那一问照样要焦点）各跑过一遍，都有检查报错。
  真机上只读核对反射通不通：`adb shell CLASSPATH=<apk 路径> app_process / dev.douhua.server.Server tasks <屏幕编号>`（列出这块屏的任务和各自的窗口模式、说会交给谁，不交）。
- 上手后的一批（2026-11-01 晚，协议 22，`tools/test.sh`）：KeyHoldsTest 的 Fn 部分照新规矩改写——Windows 模式单点 = 单次锁（只管下一个普通键、修饰键不消耗它、单次锁 → Shift → 1 = Shift+F1、
  右 Ctrl 出 Menu 也用掉它、399ms 还是单次锁）、fn🌐 单点 = 🌐、按住 0.4 秒起永久锁（没有 1 秒上限、按 2 秒照样锁、只震一次、到点后不再排定时器）、锁着时单点 / 按住 2 秒都是解开（不震）、
  锁着时当组合用（按住 Fn 打 ↑ / 点鼠标、按下那一刻 a 按着）锁照旧、单次锁被组合里那个键用掉、定时器卡住时按截止时刻补上（0.3 秒单次锁、0.5 / 1.3 秒永久锁）、releaseAll / CMD_UNLATCH / 换参数
  （含锁着时按着 Fn 的那一下）、两条路同样的序列一样；Fn 随机序列（400 组 × 150 步）旁边跑一份模型、每步比单次锁 / 永久锁；ServerPartsTest.protocol22 加了 FN_LOCKED = 8 那一条，
  按键定时器真线程那条也查"不出永久锁"；FixedCompactTest.fnPanel 查 Windows 单点 = 单次锁、两种模式按住 0.6 秒永久锁 + 再点一下解开、两条路一样；
  FixedLayoutTest 的 Fn 显示那一扫查单次锁画高亮、永久锁 / 快永久锁了画实底、只按着两样都不画，fnShown。
  小红点的规则：FixedLayoutTest.stickRule（全部 形状 × 尺寸 × 模式 × 窗口 × 默认 / 随机存过的位置：不给的那几级存着小红点时 planFixed 和触控板的键盘 / 触控板 / 三键条 / ⋯ 一模一样、
  没有红点和空位，给的那几级照旧没有触控板；fixedPointer、nextPointer(…, false)），stickPlan 里不给的组合查 sameAsPad，BoardsTest.stickRule（每个尺寸 × 模式：pointer(尺寸)、
  nextPointer(尺寸) 连点 6 下轮到哪几样、大小位置存进哪个键名、savedRects、恢复默认清哪份、存的指点一直没被改）。
  逗号句号：ThemeTest.soft（十八套 softText 对键帽 ≥ 3:1、对按下 ≥ 3:1——只有 Solarized Dark 为了分得出深浅掺淡过、2.95 ≥ 2.5——比正文淡、和正文色差 ≥ 8；`print` 多三行对比度）；
  KeyRowsTest 照 Key.pair 改名。中键滑动：TrackPointTest.leverSpeedSetting（默认 1 / 5 / 20、× 0.25 / 0.5 / 1.5 / 2 滚的量跟着变、按住中键推红点不变、100% 照 leverRate），
  HidTest.configRoundTrip（60 字节、leverSpeed 是最后 4 个字节、前 56 个字节排布不变、默认 1.0）。菜单：MenuOrderTest（编号的值照旧、每屏每种状态的顺序照协调者的表、
  设置第一 / 退出最后 / 手柄倒数第二 / 旋转紧挨在前）。把十二处规则逐个改坏（单点不单次锁、Fn 照 1 秒上限、组合照样解开、打完键也清永久锁、永久锁着按 Fn 不解开、小红点哪都给、
  取大小位置不按实际指点、句号不浅、旧菜单顺序、滑块不生效、服务不读滑块、永久锁画成单次锁的高亮）各跑过，都有检查报错。
  界面（PairKey 画出来的样子、Fn 实底、设置页的滑块、菜单）离线编不到，没在真机上看过。

- 小红点的加速 / 最快速度（协议 23，2026-11-01 晚，`tools/test.sh`）：TrackPointTest（默认值时 0–80dp 每 0.01dp 推光标的速度、左键杆的速度和以前的公式逐位一样，moveTop(1, 1200) 逐位是 1200；
  一串随机序列——红点 / 左键杆 / 中键滚动杆 × 灵敏度 × 负惯性 × 轻点 × 颗粒 × 中键滑动速度 × 咔嗒 × 8 / 12ms 节拍、抖动、卡顿、20 秒暂停 × 中键按着 / 鼠标键按着 / 连续滚动 × 抬起 / 取消，600 段、
  总位移 276726 计数——出的每样东西（位移、滚轮、点击、震动、tick 的返回值、started）揉成的 FNV 校验和等于拿 77f6ec7 的 TrackPoint 跑同一段代码算的 f000b060d8299a8a（goldenRun；以后有意改曲线要跟着重算）；
  加速 0% 平台以后是平的、最快速度不起作用；滑块每一格（21 × 26）速度随位移 / 加速 / 最快速度不减、平台以前那一段不变、推到底 = 平台 + 加速 × (最快 − 平台)；8 / 12 / 抖动节拍积分、50ms 上限照旧；
  灵敏度照乘；左键杆 = 红点（新参数下逐计数一样、走的是新曲线）；按住中键推红点、中键滚动杆出的滚轮不变；收到坏数截住；设置页说明的数——默认 2.7 倍 / 1.6 秒、0% 时 1 倍 / 4.3 秒、
  最快 3000 时 6.7 倍 / 0.6 秒、灵敏度 2 / 0.5 时 0.8 / 3.2 秒，滑块每一格 × 灵敏度 0.3–3.0：倍数 ≥ 1 且随两个滑块不减、秒数是正数且随灵敏度不增），
  TouchRouterTest（stickAccelThroughRouter：经真 ConfigCodec 往返后进真 TouchRouter，六组设置下红点 / 左键杆推到底 1 秒走 平台 + 加速 × (最快 − 平台)、推一半照旧是平台，默认值经 CMD_CONFIG 出的报文和默认 Config 逐条一样；
  stickAccelLeavesScrollAndPad：换了这两项，按住中键推红点、中键滑动杆、触控板出的报文逐条一样），HidTest.configRoundTrip（68 字节、前 60 字节和协议 22 的编码逐字节一样——CRC32 是拿 77f6ec7 的 ConfigCodec 编同一组值算的、
  最后 12 字节依次是 leverSpeed / stickAccel / stickMaxSpeed、默认 100% / 1200），ServerPartsTest.protocol23。把 TrackPoint 改坏三处（推满速度多 0.01、节拍里不看新参数、加速乘 0.999）各跑过，都有检查报错。
  审查后加的（TrackPointTest）：accelDefaultsUnchanged 多查 Prefs 用的那几个常量——accelFraction(ACCEL_PCT_DEFAULT)、(float) TOP_SPEED_DEFAULT 逐位是 1 / MOVE_MAX、等于 Config 的默认值，
  accelFraction 在 -50–150 每个整数截到 0–100 再除以 100（Prefs 自己离线编不到，engineConfig 接没接上这两项测不到）；accelClamp 多查 ±无穷大、极大极小和 NaN 两两组合推满的速度都在 平台–3000、
  真 TrackPoint 跑 154 种怪数（推满 0.2 秒再退回平台 1 秒）每拍出的位移不超过 50ms × 3000、退回平台照样 1 秒走 450；accelHintTexts：倍数（ratioText）默认 2.7、5% / 500 写 1.01、45% / 500 写 1.05、
  加速 0 / NaN / 快不到千分之五是 null，加速 > 0 的 520 格都写得出比 1 大的数、没有"1.0"、和真实倍数差不过最后一位的一半、写两位的正好 15 格、随两个滑块不减；
  实际推满（topSpeedText）50% / 1200 是 825、加速 0% / 100% 不写、中间 494 格都写且等于 平台 + 加速 × (最快 − 平台) 取整、比滑块上的数小、和秒数对得上。
  把四处改回去（不截无穷大、倍数只写一位、默认加速 95%、加速 100% 也写实际推满）各跑过，都有检查报错。
  交焦点的诊断日志：FocusPickTest.describeVisibleOnly / describeRandom（只列看得见的和挑中的、"另有 N 个"的个数对、全看得见时没有那一行、Server tasks 全列，随机 2000 组）。
  设置页的两个滑块、灰掉、说明文字（SettingsActivity）离线编不到，没在真机上看过。
