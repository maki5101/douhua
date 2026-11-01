# 实体键盘布局：键帽数据 + 主机实测扫描（第 1A 批）

两样东西：

- **主机实测扫描**：豆花儿逐键发给主机（每个出字的位置 × 普通 / Shift / 第三层），主机上开一个网页记下实际出了什么字，
  再和下面生成的数据逐格比。用来在你的 iPad、Linux 电脑、桌面模式上核实方案里最没把握的几条前提，结果决定第 1B 批怎么做。
- **键帽数据生成**：从 CLDR 43（Mac、Windows）、xkeyboard-config（Linux）、AOSP KCM（安卓）生成每家主机上每个位置出什么字，
  放 `res/raw/kbd_*.txt`。第 1B 批的实体键盘画键帽才读，现在没人读。第 1A 批只有美式（ANSI / ISO）和德语（ISO）。

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

- 数据源、版本、每个文件的 sha256 在 `sources.json`；生成哪些布局、每家用哪个文件在 `catalog.json`（手写）。
- 原始文件不进仓库，缓存在 `.cache/kbd/`（不进 git）。sha256 是 2026-10-31 调研时下载的那份算的，新克隆第一次下载要是对不上会直接失败。
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
- `kbd_catalog.txt`：布局清单（默认形状、能选的形状、小键盘小数点、各家主机设置里叫什么）。
- 读写在 `kbdtable.py`；1B 的 Java 读法照它写。

## 许可

数据文件头带着来源、版本和版权 / 许可声明：CLDR（Unicode 许可）、AOSP（Apache-2.0）、xkeyboard-config（MIT / HPND 类）。
三家的许可全文还没放进 APK，留给第 1B 批的"开源许可"页；**发正式包前要补上**。
