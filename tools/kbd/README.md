# 实体键盘布局：键帽数据 + 主机实测扫描（第 1A 批）

两样东西：

- **主机实测扫描**：豆花儿逐键发给主机（每个出字的位置 × 普通 / Shift / 第三层），主机上开一个网页记下实际出了什么字，
  再和下面生成的数据逐格比。用来在你的 iPad、Linux 电脑、桌面模式上核实方案里最没把握的几条前提，结果决定第 1B 批怎么做。
- **键帽数据生成**：从 CLDR 43（Mac、Windows）、xkeyboard-config（Linux）、AOSP KCM（安卓）生成每家主机上每个位置出什么字，
  放 `res/raw/kbd_*.txt`。第 1B 批起 App 的实体键盘读 kbd_win（Windows 模式）、kbd_mac 不对调那段（Mac 模式，照真苹果 ISO 键盘发码，见 FixedLayout）
  和 kbd_catalog；kbd_linux / kbd_android 留着没人读（扫描工具用）。第 1A / 1B 批只有美式（ANSI / ISO）和德语（ISO）；
  第 2 批（2026-11-01）补齐拉丁字母布局，共 34 个（清单见 `catalog.json`：苹果键盘语言里的拉丁字母部分 + US-International + 巴西 ABNT2 + 中文拼音）。
  用户不要求扫描（"主机怎么读那是主机的事"），第 2 批的布局一个都没扫过。

## 扫描怎么做

前提：手机装调试包（`tools/build.sh --install`；正式包里没有扫描）；豆花儿开着；键盘上别锁着 Shift / Ctrl。
扫描中别碰手机屏幕。一次大约 1–2 分钟。

| 扫哪台 | 先做 | 命令 |
|---|---|---|
| iPad | 豆花儿蓝牙模式连 iPad；iPad 设置 → 通用 → 键盘 → 实体键盘里选布局（路径凭记忆）；iPad 和电脑在同一个 Wi-Fi | `tools/kbd-scan.sh --host ipad --layout de`，再换成美式扫一次：`--layout us` |
| 这台 Linux 电脑 | 豆花儿蓝牙模式连这台电脑；系统设置里加 German 并切过去，fcitx / ibus 切到不带输入法的那个 | `tools/kbd-scan.sh --host linux --layout de` |
| 桌面模式（手机自己） | 服务开着；手机设置 → 实体键盘 → Douhua Keyboard 选 German（三星的路径没核）；浏览器和豆花儿分屏 | `tools/kbd-scan.sh --desktop --layout de` |

脚本会打出一个地址（带一串随机口令）。在主机的浏览器里打开它（iPad 用 Safari 打开电脑的局域网地址；桌面模式在手机的浏览器里打开，
端口已经用 adb reverse 转过去了），照页面上的提示点"开始"，光标落进框里后手机就开始发键。

- 页面一失去焦点（点到别处、锁屏、切走）就暂停并叫手机停下；点"继续"从当前这步接着来。
- 手机一次只发一小段（6 个键），页面收完才要下一段；脚本被 Ctrl+C 也会叫手机停。只发出字的位置和 Shift / Alt，不发 Ctrl / ⌘ / Enter。
- Windows / Linux / 安卓上扫第三层（AltGr）之前，页面先看普通层对不对得上：主机布局没设对时右 Alt 可能只是 Alt，Alt + 字母会碰到快捷键，
  这时停下来问你。美式布局在这几家没有第三层，不扫。
- 几秒没收到下一步就提示"卡住了"：多半是手机没在发（终端里有 adb 的报错，或者手机上豆花儿日志 `adb logcat -s douhua` 里有"按键扫描：…不发"的原因）。
- iPad 打不开地址：看这台电脑的防火墙有没有挡住那个端口。

扫完自动出分析（也可以 `python3 tools/kbd/compare.py <记录目录>` 重跑）。结果在 `.private/kbdscan/<时间>-<主机>-<布局>/`：
`scan.json`（原始记录）、`report.txt`（结论）、`compare.json`。结论逐条写成不成立：

- 主机布局是不是选的那个（和每份数据比，最像哪份）；
- 苹果主机把豆花当 ISO 还是 ANSI 键盘（0x35 / 0x64 有没有对调 → 决定"互换"开关的默认值）；
- 0x32 是不是和 0x31 一样（回车左边那个键发 0x31 行不行）；
- 第三层、死键、小键盘小数点（NumLock 关着看不出）；
- iPad 和同名 macOS 布局、Linux 和 xkb 默认变体、三星和 AOSP 的 KCM 是不是一样；
- 每步收到的 e.code 对不对（丢键 / 错位时整份不可信，重扫）。

把 `report.txt` 给我（或者告诉我目录），我据此定 1B。

## 生成数据

```
python3 tools/kbd/gen_layouts.py            # 缓存里没有的原始文件会下载（要联网），按 sha256 核对
python3 tools/kbd/gen_layouts.py --offline  # 不下载，缓存里没有就失败
python3 tools/kbd/check_tables.py           # 核对抽查值和不变量（tools/test.sh 也跑）
```

- 数据源、版本、每个文件的 sha256 在 `sources.json`；生成哪些布局、每家用哪个文件、起名字用的语言 / 区分词在 `catalog.json`（手写，开头的 `_说明` 写了每一栏）；
  CLDR 里的 macOS 旧名 → 现名在 `mac_names.json`（手写，**现名凭记忆，要在 iPad / Mac 上核对**）。
- 某家没有某个布局的数据：catalog.json 里那家写 `null`（拉美没有 Mac 的、爱尔兰 / 荷兰 / 罗马尼亚 / 奥地利没有安卓的），生成的那份文件里就没有那一段，
  清单的 `modes` 只写有数据的模式；设置页把它灰掉（选着那种模式时列表里这个布局灰掉、选着这个布局时那种模式灰掉），存着的组合正好没数据时键盘页退回豆花布局。
- 原始文件不进仓库，缓存在 `.cache/kbd/`（不进 git）。sha256：美式、德语那几个是 2026-10-31 调研时下载的那份算的，第 2 批新加的是 2026-11-01 这次下载算的
  （CLDR 从 raw.githubusercontent.com 的 release-43 下；AOSP 从 GitHub 镜像下、核了 git blob id）。下载那条路 2026-11-01 跑过（CLDR、AOSP 各删一个缓存文件重新生成，内容一样）。
  新克隆第一次下载要是对不上会直接失败。
- CLDR 钉 `release-43`（44 起老平台数据整个删了）；AOSP 按 git blob 钉（GitHub 镜像，不是官方源）；xkb 读本机，版本号要等于钉的 2.46。
- 苹果、微软的系统布局文件不碰（没有再发布的许可）；xkb 的 `mac` 变体不当 macOS 数据用（方案 §6.1）。

## 数据格式（`res/raw/kbd_<平台>.txt`）

```
# 注释（来源、版本、许可）
#!platform mac            机读的元数据：平台、数据源版本、每个原始文件的 sha256
@de                       一个布局一段
0f	l	L	@	ﬂ          HID 用法码（十六进制，PC 惯例的位置）、普通、Shift、第三层、第三层+Shift，tab 分隔
@de2 = de                 完全一样的表写成别名
```

- 空格子 = 没有字；`\*` 开头 = 死键（统一写成带间距的附加符号，比如 `\*´`）；`\\` = 反斜杠；看不见的字写 `\uXXXX`。
- 第三层：Mac 是 Option（左 Alt），Windows / Linux / 安卓是 AltGr（右 Alt）。
- Mac 每个布局两段：`<id>` = macOS 把豆花当 ANSI 键盘（不对调），`<id>.iso` = 当 ISO 键盘（对调 0x35 / 0x64）。
- `kbd_catalog.txt`：布局清单，文件头 `#!columns` 一行列出栏名（读的时候按它认栏，加栏不用改读法）：id、locale（起名字用的语言 + 地区）、
  variant（区分词：小写的 intl / legacy / pinyin 是 strings.xml 里 kbd_var_<它> 的词，别的照写）、shape / shapes（默认 / 能选的形状：ansi、iso、abnt2）、
  modes（有数据的模式：win、mac，至少一种——拉美只有 win，意大利 QZERTY、瑞典旧版只有 mac）、kpdl / kpco（小键盘 0x63、ABNT2 小键盘多的 0x85 出的字，取自 xkb，Mac 模式用）、
  kpdl_win（Windows 模式的小键盘小数点：catalog.json 手写的照微软布局表，荷兰、冰岛、捷克、斯洛伐克是逗号、xkb 是句点；没写的同 kpdl）、caps_mac（Mac 模式 Caps 键帽写什么，拼音写 中/英）、
  name_mac（macOS 现名）/ name_mac_cldr（CLDR 旧名）/ name_win / name_linux / name_android（没有那家数据的空着）。
- ABNT2（巴西）：字表里多 0x87（右 Shift 左边那个 / ? 键）一行；小键盘多的 0x85 不在字表里（CLDR 不含小键盘），在清单的 kpco 一栏。
- 读写在 `kbdtable.py`；App 的 Java 读法（`src/dev/douhua/FixedLegends.java`）照它写，FixedLegendsTest 直接读这几份文件。

## 许可

数据文件头带着来源、版本和版权 / 许可声明：CLDR（Unicode 许可）、AOSP（Apache-2.0）、xkeyboard-config（MIT / HPND 类）。
三家的许可全文在 `res/raw/licenses.txt`（设置 → 通用 → 开源许可，第 1B 批加的）；数据源换了要跟着改那份文件。
