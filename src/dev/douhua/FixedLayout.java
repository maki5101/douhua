package dev.douhua;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体键盘的几何（第 1B 批，2026-11-01），纯 Java（离线测，FixedLayoutTest）：每个键在哪、多宽、发哪个 HID 用法码，单位 u（1u = 一个字母键连缝）。
 * 豆花布局（KeyRows）每行按份数铺满，画不出实体键盘的 L 形回车、两行高的键、几个区块之间的空，所以另起一套，KeyRows 不动。
 *
 * 一块 = 形状（ANSI / ISO / ABNT2）× 尺寸（笔记本 / TKL / 全尺寸，第 3 批加 75% / 65% / 苹果紧凑）× 模式（Windows / Mac）：形状只管主键区五行（ABNT2 还管小键盘右列），模式只管最下一排、编辑区和几个键名，
 * 尺寸管有没有编辑区、数字小键盘（笔记本另有自己的 F 行和最下一排，见下面）。语言（键帽上的字）不在这里，在 FixedLegends（出字的键 name 是 null，字按用法码查表）。
 *
 * 竖着：F 行（第 0 行）、隔 0.25u、主键区五行（第 1–5 行：数字行、Q 行、A 行、Z 行、空格那一排），一共 6.25u 高（{@link #ROW_TOP}）。
 * 横着：主键区 0–15u、编辑区 15.25–18.25u（F 行那三个键、Ins Home PgUp / Del End PgDn、倒 T 方向键）、全尺寸再加小键盘 18.5–22.5u。
 * 主键区每行凑满 15u（宽度照主流机械键盘：Tab 1.5、Caps 1.75、左 Shift 2.25（ISO 1.25）、右 Shift 2.75、底行 1.25 × 3 + 空格 6.25 + 1.25 × 4，
 * 凭记忆，见方案 [F] §2）；F 行 Esc、空 1u、F1–F4、空 0.5u、F5–F8、空 0.5u、F9–F12。
 * Esc 和 F1 之间那 1u 是 ⋯ 槽（{@link #menu}）：小触控板上摆不出 ⋯ 时 ⋯ 放在这里（KeyRows.MENU_BUTTON_ID），不然那格空着、分给最近的 F 键。
 *
 * 发什么码（照真键盘）：
 * - Windows 模式（PC 惯例）：1 左边那个键 0x35，ISO 的 Z 左边那个键 0x64，ISO 回车左边那个键 0x31（不发 0x32：有 0x31 的键盘上从来不出现 0x32，
 *   各家主机都把两者当一回事，方案 §3 第 1 条）；底行 Ctrl · Super · Alt · 空格 · AltGr / Alt · Fn · ☰（Menu 0x65）· Ctrl（协议 24 起右 Super 换成 Fn，见下面）；
 *   F 行右边 PrtSc ScrLk Pause。连 Linux、安卓也用这个模式。
 * - Mac 模式（照苹果键盘，连 iPad 也用它）：底行 ⌃ · ⌥ · ⌘ · 空格 · ⌘ · ⌥ · fn🌐 · ⌃（Option 发 Alt 的用法码 0xE2 / 0xE6、Command 发 GUI 0xE3 / 0xE7，
 *   照真苹果键盘；fn🌐 协议 24 起取代原来的 🌐 键（KeyRows.GLOBE_BUTTON_ID）：点一下照旧切换输入法、按住是 Fn，同 75%）；PrtSc ScrLk Pause 换成 F13 F14 F15（苹果的全尺寸键盘就是 F13 起），
 *   Ins 那格空着（苹果带数字键盘的那款这格是 fn；那款第 3 批没做，Mac 模式全尺寸已经有 F13–F15 和 Clear）、没有 Menu 键，小键盘的 Num 写 Clear。
 *   **ISO 的那两个键照真苹果 ISO 键盘发**：1 左边那个键（§ / ^）发 0x64、Z 左边那个键（` / <）发 0x35，和 PC 惯例正好对调。
 *   依据：Linux 的 hid-apple 驱动给苹果 ISO 键盘专门把 KEY_GRAVE（0x35）和 KEY_102ND（0x64）对调回来（apple_iso_keyboard[] 表、
 *   模块参数 iso_layout "Swap the backtick/tilde and greater-than/less-than keys"，默认按国家码自动开，2026-11-01 读了 torvalds/linux 的 hid-apple.c），
 *   说明苹果 ISO 键盘这两个位置报的码和 PC 惯例相反；macOS 的虚拟键码 kVK_ISO_Section（0x0A，就是 § 那个键）在不对调时对应 HID 0x64
 *   （Chromium dom_code_data.inc，协调者核过）。主机怎么认（macOS 把豆花当 ANSI 还是 ISO、iPad 一律当美式）是主机的事，没在真机上验证。
 *
 * 巴西 ABNT2（{@link #ABNT2}，第 2 批，2026-11-01；尺寸照本机 xkb 的 geometry/pc 里的 abnt2，形态调研 kbd-form §2.4）：主键区第 1–3 行、回车同 ISO，
 * 第 4 行左 Shift 1.25 · Z 左边那个键（0x64，巴西是 \ |）· Z–M · , . ; · **「/ ?」键 1u（0x87 International1，和 JIS 的ろ同一个码）** · **右 Shift 只有 1.75u**；
 * 全尺寸的小键盘右列 + 不是两行高，拆成两个 1u：+（0x57）在 7 8 9 那一行、**0x85（Keypad Comma，微软表里写 "Brazilian Keypad ."）在 4 5 6 那一行**，
 * 键帽写清单的 kpco（巴西是 "."），左下的小数点（0x63）写 kpdl（巴西是 ","）。键数比 ISO 多两个（全尺寸 Windows 107，TKL / 笔记本多一个 0x87）。
 * **Mac 模式的 ABNT2 不对调 1 左边和 Z 左边那两个键**（照 PC 惯例 1 左边 0x35、Z 左边 0x64），和 ISO 不一样：苹果不做 ABNT2 键盘，没有"苹果键盘怎么发"可照；
 * CLDR 里 macOS 的 Brazilian-ABNT2 把 ' " 放在 0x35（键码 50）、\ | 放在 0x64（键码 10），不对调那段按 PC 惯例的码查出来正好是 ABNT2 键帽上印的字
 * （1 左边 ' "、Z 左边 \ |），和 Windows 模式一样；对调的话 1 左边会画成 \ |。主机怎么认（macOS 把豆花当哪种键盘）照旧是主机的事，没在真机上验证。
 *
 * 笔记本尺寸（{@link #LAPTOP}，2026-11-01，用户："没有 Insert 那些键的情况下，把方向键也得塞进来，就是有点类似于笔记本键盘那种"）：照 ThinkPad 排，
 * 尺寸从联想 TrackPoint Keyboard II 用户指南的整机插图上量的（方案 design-laptop §2.1；那块外接键盘就是 ThinkPad 笔记本的排法），15u 宽 = 主键区的宽，只有一个区块。
 * - F 行（1 行高，照 TKL）：Esc 1.125 · F1–F12 各 0.8125（四个一组，组间 0.1875）· 隔 0.1875 · Home End Ins 各 0.8125 · Del 1.125。
 *   真机 F 行只有约 0.7 个键高，这里照 TKL 用 1 行高（F 键本来就窄，再矮太难按），第 0–4 行的竖向排法和 TKL 一模一样。
 * - 第 1–4 行：和 TKL 的主键区一模一样（右 Shift 照旧 2.75u，不缩短：真 ThinkPad 的右 Shift 是满的，Z 那一行也挤不下 PgUp ↑ PgDn；
 *   ABNT2 同 TKL 的 ABNT2：右 Shift 1.75u、左边是 / ?）。
 * - 最下一排：一行、1.25 行高（{@link #height} 6.5）。Ctrl ·（⋯ 槽）· Super · Alt · 空格 5u（正好以红点为中线）· AltGr / Alt · PrtSc · Ctrl，
 *   右下角 2.8125u 是方向键，照真机分上下两层（{@link Key#y0} / {@link Key#y1}，各 0.625 行）：上层 PgUp ↑ PgDn、下层 ← ↓ →。
 *   真机下层比整排往下伸出一截，这里改成整排加高到 1.25 行，别的键跟着高、下面不留空着的机身。最下一排是一行，只有方向键竖着分两层：
 *   拆成两行的话 ⋯、🌐 会变成上下两段，跨段抬起就不算点击（TouchRouter.up 的按钮要 contains 或 same(hit)）。
 *   键盘范围：方向键以外一块（Ctrl–右 Ctrl），方向键上下两层各一块（{@link #ranges}：不分开的话两层之间那条缝全分给右 Ctrl）。
 * - 左下角放 Ctrl（1.25u），第二格是 Fn（协议 24 起；以前这一格是 ⋯ 槽，小触控板上摆出了 ⋯ 时左 Ctrl 伸过去占满 2.25u）。⋯ 槽挪到 F 行 Ins 那格（见下面"F1–F12 的第二功能"）。
 *   Ctrl 在角上：逐级缩时左手小指的位置和 TKL / 全尺寸一样；拿手机的手最容易碰到角，碰到 Ctrl 不会弹菜单。
 * - Mac 模式位置不变：⌃ · fn🌐 · ⌥ · ⌘ · 空格 · ⌘ · 🌐 · ⌃（PrtSc 那格照 1B 的"Menu 位 → 🌐"；ThinkPad 没有右 Super，Mac 也就没有右 ⌥，左 ⌥ 照样能锁第三层）；
 *   F 行 Ins 那格空着，声明成空格子（{@link #blanks}），⋯ 不在槽里时不在任何范围里、按了什么都不发生；⋯ 放在槽里时 ⋯ 占着它。
 * - 所有坐标都是 1/16u 的整数倍（浮点里是精确值，相邻两个键前一个的 x + w 和后一个的 x 是同一个数，摆到像素后一定共用边界）：
 *   右 Ctrl 和三个方向键各 0.9375，没取真机的 1 + 3 × 0.917。
 * - 键数：Windows 84 / 85 / 86（ANSI / ISO / ABNT2），Mac 83 / 84 / 85（协议 24 起多一个 Fn；以前 83 / 84 / 85、82 / 83 / 84）。比 TKL 少 ScrLk、Pause、右 Super、Menu
 *   （真机靠 Fn 组合键出，这里不做）。⋯ 放在槽里时 Windows 模式 Ins 换成 ⋯（键数不变），Mac 模式多一个 ⋯。
 *
 * 中文拼音（清单的 caps_mac，第 2 批）：Mac 模式 Caps 键帽写"中/英"（苹果中文键盘印的就是它），Windows 模式照旧写 Caps；发的码不变（0x39）。
 *
 * 第 3 批（2026-11-01，方案 design-batch3 §2）：75%、65%、苹果紧凑三种尺寸，都只有一个区块（主键区连 F 行、右边那一列、方向键），都有 Fn 键（{@link #FN_ID}）。
 * 行表每种尺寸自己的（{@link #rowTop} / {@link #rowBottom}）：TKL / 全尺寸 / 笔记本 / 75% 照 {@link #ROW_TOP}；65% 没有 F 行（第 0 行零高、数字行从 0 起，5u 高）；
 * 苹果紧凑的 F 行半行高、和数字行之间不隔（5.5u 高）。坐标都是 1/16u 的整数倍。
 * - **75%**（照 Keychron K2 / Q1 一类，16u × 6.25u）：F 行 Esc ·（⋯ 槽，同 TKL）· F1–F12 · PrtSc（Mac 模式 F13）· Del；第 1–4 行主键区同 TKL，右边多一列 1u：
 *   数字行 Home、Q 行 PgUp、A 行 PgDn；Z 行右 Shift 缩到 1.75u、后面 ↑ End；最下一排 Ctrl · Super · Alt · 空格 6.25 · AltGr / Alt · Fn · Ctrl · ← ↓ →
 *   （Mac：⌃ · ⌥ · ⌘ · 空格 · ⌘ · ⌥ · fn🌐 · ← ↓ →）。键数 ANSI 83、ISO 84、ABNT2 85（两种模式一样：Mac 的 F13 顶 PrtSc、fn🌐 顶 Fn，⌥ 顶右 Ctrl）。
 *   **ABNT2 的 Z 行和方案不一样**：方案写"右 Shift 1.75 + ↑ 在最右一列、没有 End，方向键整组靠右一格"，可 16u 里倒 T 的 ← ↓ → 靠右一格要到 17u、放不下，
 *   ↑ 在最右一列就对不上 ↓。要 ↑ 正对 ↓（14–15u），/ ? 键（0x87）左边的位置是定死的（左 Shift 1.25 + 0x64 + Z–; 到 12.25、/ ? 到 13.25），右 Shift 只剩 0.75u；
 *   取了"右 Shift 0.75u、↑ End 照旧"（左手的 Shift 1.25u 照旧；End 也照旧有，Fn+→ 照样出 End）。
 * - **65%**（照 Keychron K6 / KBD67 一类，16u × 5u）：没有 F 行，数字行 Esc（占 ` 的位置）· 1–0 - = · ⌫ 2u · Del，第 2–5 行同 75%（数字行右边是 Del 不是 Home，没有 Home 键）。
 *   ⋯ 槽在最下一排左下角 Ctrl 右边（以前同笔记本；协议 24 起笔记本的挪到了 F 行 Ins 那格，65% 没动）：小触控板上摆出了 ⋯ 时 Ctrl · Super · Alt 各 1.25u；摆不出时 **Ctrl 1 · ⋯ 0.75 · Super 1 · Alt 1**
 *   （Mac：⌃ · ⋯ · ⌥ · ⌘），空格不动——和方案不一样（方案写"空格缩 1u、⋯ 1u 插进去"）：小红点状态下 ⋯ 一定在槽里，空格缩成 4.75–10u 的话红点（6.75u）左边只剩 2u，
 *   三个鼠标键要面板八百多 dp 宽才让得出；修饰键缩到 1u、⋯ 0.75u 的话空格位置和 75% / TKL 一样，红点空位的规则一模一样。键数 ANSI 68、ISO 69、ABNT2 70。
 * - **苹果紧凑**（MacBook / 妙控键盘的比例，[F] §2.6，14.5u × 5.5u）：**只有 Mac 模式、只有 ANSI / ISO**（{@link #available}）。F 行半行高：Esc 1.5 · F1–F12 · 右上角那格（触控 ID）是 ⋯ 槽；
 *   数字行 ` 1–0 - = · ⌫ 1.5；Q 行 Tab 1.5 · Q–] · \ 1（ISO：回车上半 1u）；A 行 Caps 1.75 · A–' · 回车 1.75（ISO：0x31 · 回车下半 0.75，L 形）；
 *   Z 行 Shift 2.25 · Z–/ · Shift 2.25（ISO：Shift 1.25 · 0x35 · Z–/ · Shift 2.25；ISO 的那两个键照 1B 的苹果 ISO 对调）；
 *   最下一排 fn🌐 · ⌃ · ⌥ · ⌘ 1.25 · 空格 5 · ⌘ 1.25 · ⌥ · ← · ↑↓ · →，↑↓ 是一格里上下两个半高的键（{@link Key#y0} / {@link Key#y1}，同笔记本的方向键，范围上下层各一块），← → 整高。
 *   G|H 的缝 = B 的中线 = 6.75u（同 TKL），空格 4.25–9.25u 以红点为中线（同笔记本）。键数 ANSI 77、ISO 78。
 * Fn 层（方案 §3.2，{@link #fn} / {@link #fnKeys}）：哪个键按着 Fn 时发什么、键帽写什么，只是数据；按 Fn 的规则（按住 / 点一下 / 锁、按下那一刻定码）在 TouchRouter / KeyHolds
 * （协议 21）。报给分流的 id 由 {@link #keyId} 换：Fn 键 → Proto.FN_KEY / FN_GLOBE_KEY，带 Fn 层的键 → Proto.fnLayerKey(底层, Fn 层)，别的照旧。
 *
 * F1–F12 的第二功能（协议 24，2026-11-02，方案 design-fkeys，用户：五个问题全按推荐）：
 * - **TKL / 全尺寸 / 笔记本照真键盘加 Fn 键**（以前只有 75% / 65% / 苹果紧凑有）：TKL / 全尺寸 Windows 模式右 Super 换成 Fn
 *   （Ctrl · Super · Alt · 空格 · AltGr · **Fn** · ☰ · Ctrl，位置同 75% 的 Fn，紧跟 AltGr），Mac 模式 🌐 换成 fn🌐（点一下照旧切输入法，按住是 Fn，同 75%）；
 *   笔记本左下第二格（真 ThinkPad 的 Fn 位置，以前是 ⋯ 槽）给 Fn、左 Ctrl 固定 1.25u；⋯ 槽挪到 F 行 Ins 那格（小触控板上摆不出 ⋯ 时 ⋯ 放在这里：
 *   Windows 模式 Ins 换成 ⋯、Ins 改由 Fn+Del 出（同 75%）；Mac 模式那格本来空着，正好放 ⋯）。Mac 模式笔记本照旧有 PrtSc 那格的 🌐，左下第二格是 fn🌐。
 *   几何别处一点不变（键的位置、宽度、范围的规则都照旧）；键数 TKL / 全尺寸不变（一个换一个），笔记本多一个 Fn。
 * - **哪些尺寸上第二功能**（{@link #frowCapable}）：TKL / 全尺寸 / 笔记本 / 75% / 苹果紧凑；**65% 不上**（它的 F 行本来就藏在 Fn+数字里，要两样都有得有第二个 Fn，
 *   方案问题 5）。F 键配了第二功能（{@link #frow}，Boards 存的、按 FKeyFunctions 认）时报 {@link #keyId} = FKeyFunctions.regionId（Proto.frowKey：两面都在 id 里），
 *   哪一面在按下那一刻由 KeyHolds 按"Fn 起作用 ^ F 行翻转"定；没配的照旧报 F 键的用法码。
 * - Esc：这一块至少一个 F 键配了第二功能时是 F 行翻转开关（{@link #escFnLock}，Proto.FN_LOCK_KEY：Fn+Esc 翻转、Fn 不起作用时就是 Esc）；
 *   12 个都是"无"（"不用"那套预设）时照旧是 Esc（"就是现在的样子"，方案 §2.1；翻转了也没有东西可翻）。
 * - 这几种尺寸的 Fn 层只有 F 行和 Esc，笔记本另加 Fn+Del = Ins（Windows 模式；Mac 模式没有 Ins，同 75%）。不顺手加别的（笔记本真机靠 Fn 出的 ScrLk、Pause 不做）。
 * - 键帽：F 行放得下两层的尺寸（TKL / 全尺寸 / 75%）上下两面都印（FixedPanel.glyphsAt），放不下的（笔记本的 F 键只有 0.8125u 宽、苹果紧凑的 F 行半行高，
 *   {@link #frowTwoFaces}）只画会出的那一面。
 */
final class FixedLayout {
    static final String ANSI = "ansi", ISO = "iso", ABNT2 = "abnt2";
    /** 能拼的形状（JIS / 韩国第 5 批）。 */
    static final String[] SHAPES = {ANSI, ISO, ABNT2};
    static final String TKL = "tkl", FULL = "full", LAPTOP = "laptop";
    /** 第 3 批的三种尺寸（存在 kbx_size、大小位置的键名 kbx_<尺寸>_… 里，定了别改）。 */
    static final String P75 = "75", P65 = "65", APPLE = "apple";
    /** 第 1B 批和笔记本那三种，从大到小（1B / 笔记本那几组扫描按它跑）。全部尺寸见 {@link #ALL_SIZES}，逐级缩的顺序见 {@link #ladder}。 */
    static final String[] SIZES_DESC = {FULL, TKL, LAPTOP};
    /** 全部尺寸，从小到大（设置页的顺序；Boards.savedRects 按它取每个尺寸存的那份）。 */
    static final String[] ALL_SIZES = {APPLE, LAPTOP, P65, P75, TKL, FULL};
    static final String WIN = "win", MAC = "mac";

    /** 区块：主键区（连 F 行的 Esc–F12）、编辑区（连 F 行右边那三个、方向键）、数字小键盘。 */
    static final int MAIN = 0, EDIT = 1, NUMPAD = 2;

    /**
     * 标准的行表（每行的上沿，u）：第 0 行是 F 行，隔 0.25u 是主键区五行；TKL / 全尺寸 / 笔记本 / 75% 用它。每行 1u 高，只有笔记本的最下一排 1.25u 高。
     * 65%、苹果紧凑另有自己的（{@link #rowTop} / {@link #rowBottom} 是每一块自己的，下沿、总高都按这一块取）。
     */
    static final float[] ROW_TOP = {0, 1.25f, 2.25f, 3.25f, 4.25f, 5.25f};
    static final int ROWS = ROW_TOP.length;
    /** 65% 的行表：没有 F 行（第 0 行零高，没有键），数字行从 0 起，一行接一行，5u 高。 */
    static final float[] ROW_TOP_65 = {0, 0, 1, 2, 3, 4}, ROW_BOTTOM_65 = {0, 1, 2, 3, 4, 5};
    /** 苹果紧凑的行表：F 行半行高（0–0.5），和数字行之间不隔，5.5u 高；最下一排的 ↑ ↓ 在 {@link #APPLE_ARROW_SPLIT} 上下各半。 */
    static final float[] ROW_TOP_APPLE = {0, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f}, ROW_BOTTOM_APPLE = {0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f};
    static final float APPLE_ARROW_SPLIT = 5;
    /** 空格那一排（三个鼠标键从它的上沿铺到面板下沿，红点的切口不过它的上沿）。 */
    static final int SPACE_ROW = 5;
    /** 笔记本最下一排多高（u）、方向键上下两层的分界（u，= 94/16）。 */
    static final float LAPTOP_BOTTOM_ROW = 1.25f, LAPTOP_ARROW_SPLIT = 5.875f;
    /** 各区块的左右边（u）。 */
    static final float MAIN_RIGHT = 15, EDIT_LEFT = 15.25f, EDIT_RIGHT = 18.25f, PAD_LEFT = 18.5f, PAD_RIGHT = 22.5f;

    /** ⋯ 菜单键、🌐（编号同豆花布局，MainActivity 一样处理）。 */
    static final int MENU_ID = KeyRows.MENU_BUTTON_ID;
    static final int GLOBE_ID = KeyRows.GLOBE_BUTTON_ID;
    /**
     * Fn 键（第 3 批，75% / 65% / 苹果紧凑；协议 24 起 TKL / 全尺寸 / 笔记本也有）在几何里的编号（KeyRows.FIXED_FN_BUTTON_ID，special：不是 HID 用法码）。报给分流、面板发给分流时
     * 换成 Proto.FN_KEY（Windows 模式）/ Proto.FN_GLOBE_KEY（Mac 模式的 fn🌐，单点 = 🌐），是 REGION_KEY、规矩在 KeyHolds（{@link #keyId}）。
     * 键名 Windows 模式 "Fn"、Mac 模式 "fn 🌐"（苹果现款键帽是 fn 加一个地球；FixedPanel.glyphs 画成上 fn、下 🌐 两个字）。
     */
    static final int FN_ID = KeyRows.FIXED_FN_BUTTON_ID;
    static final String FN_NAME = "Fn", FN_NAME_MAC = "fn 🌐";

    /** 空格的键名占位（界面语言的"空格"由 App 换上，FixedPanel.glyphs）。 */
    static final String SPACE_NAME = "Space";

    /** 一个键。 */
    static final class Key {
        /** HID 用法码；特殊键（⋯、🌐、Fn）是按钮编号（报给分流的 id 另见 {@link FixedLayout#keyId}：Fn 和带 Fn 层的键换成协议 21 的编码）。 */
        final int id;
        /** 特殊键：id 不是 HID 用法码（⋯、🌐 报成按钮；Fn 报成 Proto.FN_KEY / FN_GLOBE_KEY 这个键，{@link FixedLayout#regionType}）。 */
        final boolean special;
        final int cluster;
        /** 从第几行起（0–5），占几行（小键盘的 + 和 Enter 两行；ISO 回车另有 {@link #x2}）。 */
        final int row, rows;
        /** 第一行那段的左边、宽（u）。 */
        final float x, w;
        /** ISO 回车下半段（第 row + 1 行）的左边、宽；不是 L 形的宽是 0。 */
        final float x2, w2;
        /** 键帽上写的名字（功能键、小键盘、空格）；出字的键（字母、数字、标点）是 null，字从 FixedLegends 按用法码查。 */
        final String name;
        /** 同一个键的 KeyRows.Key（id、名字、宽、special）：KeyRows.functionKey 判主次、KeyboardPlan 算切口都按它。 */
        final KeyRows.Key face;
        /** 只占这一行竖着的一截（u，笔记本的方向键上下两层）；NaN = 占整行（别的键都是）。 */
        final float y0, y1;

        Key(int id, boolean special, int cluster, int row, int rows, float x, float w, float x2, float w2, String name) {
            this(id, special, cluster, row, rows, x, w, x2, w2, name, Float.NaN, Float.NaN);
        }

        Key(int id, boolean special, int cluster, int row, int rows, float x, float w, float x2, float w2, String name, float y0, float y1) {
            this.y0 = y0;
            this.y1 = y1;
            this.id = id;
            this.special = special;
            this.cluster = cluster;
            this.row = row;
            this.rows = rows;
            this.x = x;
            this.w = w;
            this.x2 = x2;
            this.w2 = w2;
            this.name = name;
            this.face = new KeyRows.Key(id, name == null ? "" : name, null, w, special);
        }

        boolean lShape() {
            return w2 > 0;
        }

        /** 这个键在第 r 行占的 {左, 右}（u）；不在这一行返回 null。 */
        float[] span(int r) {
            if (lShape()) {
                if (r == row) return new float[]{x, x + w};
                if (r == row + 1) return new float[]{x2, x2 + w2};
                return null;
            }
            return r >= row && r < row + rows ? new float[]{x, x + w} : null;
        }

        /** 是出字的键（字从 FixedLegends 来）。 */
        boolean charKey() {
            return name == null && !special;
        }

        @Override
        public String toString() {
            return name != null ? name : String.format("0x%02X", id);
        }
    }

    final String shape, size, mode;
    /** 不含 ⋯ 键（⋯ 槽里放不放它看小触控板上摆没摆出 ⋯，FixedPanel.setMenuKey）。键数、{@link #find} 都按这一份。 */
    final List<Key> keys;
    /** ⋯ 放在槽里时显示的键：TKL / 全尺寸是 keys 在 Esc 后面插一个 ⋯；笔记本（协议 24 起）Windows 模式 Ins 换成 ⋯、Mac 模式 ⋯ 跟在 End 后面。 */
    private final List<Key> keysMenu;
    /** ⋯ 槽里的那个键（TKL / 全尺寸：Esc 和 F1 之间那 1u；笔记本：F 行 Ins 那格 0.8125u，协议 24 起；以前在左下角 Ctrl 右边）。 */
    final Key menu;
    /** 总宽、总高（u）：TKL 18.25 / 全尺寸 22.5 / 笔记本 15；笔记本 6.5 高（最下一排 1.25 行），别的 6.25。 */
    final float width, height;
    /**
     * 声明过的空格子（每项 {行, 左, 右}，u）：两段之间的缝里有它就不并成一段（{@link #ranges}），格子不在任何范围里、按了什么都不发生。
     * 现在只有 Mac 模式笔记本 F 行的 Ins 那格（只有 0.8125u，不声明的话会被当成段内小缝并进范围，右半格分给 Del）。
     */
    final List<float[]> blanks;
    /**
     * Windows 模式下右 Alt 是 AltGr（这个布局在 Windows 上有第三层的字，键帽写 AltGr）：锁住它时键帽上第三层的字亮、别的压暗（FixedPanel.layer）。
     * 美式没有第三层，右 Alt 写 Alt，锁住它和锁左 Alt 一样什么都不变（两种 Alt + 字母都打不出字）。Mac 模式一律假（任一 Option 是第三层，不看它）。
     */
    final boolean altGr;
    /** 这一块的行表（每行的上沿、下沿，u）：标准的是 {@link #ROW_TOP}（笔记本最下一排 1.25 行高），65% / 苹果紧凑见 {@link #ROW_TOP_65} / {@link #ROW_TOP_APPLE}。 */
    private final float[] rowTops, rowBottoms;
    /** Fn 层（底层用法码 → 那一项，按键在列表里的顺序）；没有 Fn 层的尺寸是空的（F 行的第二功能另记在 {@link #frow}，不在这里）。 */
    private final Map<Integer, FnKey> fnLayer;
    /** F1–F12 配的第二功能（协议 24，下标 0 = F1；null = 没配、"无"，或者这种尺寸不上第二功能）。 */
    private final FKeyFunctions.Function[] frow = new FKeyFunctions.Function[12];

    private FixedLayout(String shape, String size, String mode, List<Key> keys, List<Key> keysMenu, Key menu, float width, float height,
                        boolean altGr, List<float[]> blanks, float[] rowTops, float[] rowBottoms, Map<Integer, FnKey> fnLayer) {
        this.shape = shape;
        this.size = size;
        this.mode = mode;
        this.keys = Collections.unmodifiableList(keys);
        this.keysMenu = Collections.unmodifiableList(keysMenu);
        this.menu = menu;
        this.width = width;
        this.height = height;
        this.altGr = altGr;
        this.blanks = Collections.unmodifiableList(blanks);
        this.rowTops = rowTops.clone();
        this.rowBottoms = rowBottoms.clone();
        this.fnLayer = Collections.unmodifiableMap(fnLayer);
    }

    boolean mac() {
        return MAC.equals(mode);
    }

    /** ISO 型（L 形回车、Z 左边多一个键 0x64）：ISO 和 ABNT2 都是。 */
    boolean iso() {
        return ISO.equals(shape) || ABNT2.equals(shape);
    }

    /** 巴西 ABNT2（比 ISO 多 / ? 键 0x87、全尺寸小键盘多 0x85）。 */
    boolean abnt2() {
        return ABNT2.equals(shape);
    }

    boolean full() {
        return FULL.equals(size);
    }

    boolean laptop() {
        return LAPTOP.equals(size);
    }

    /** 苹果紧凑（第 3 批）。 */
    boolean apple() {
        return APPLE.equals(size);
    }

    /** 有 Fn 键（第 3 批的 75% / 65% / 苹果紧凑；协议 24 起 TKL / 全尺寸 / 笔记本也有）。Fn 层（{@link #fnKeys}）可以是空的（TKL / 全尺寸只有 F 行的第二功能）。 */
    boolean hasFn() {
        return find(FN_ID) != null;
    }

    // ═══════════ F1–F12 的第二功能（协议 24） ═══════════

    /** 这种尺寸上不上 F 行的第二功能（{@link #frowCapable()}）：65% 不上（方案问题 5），别的都上。 */
    static boolean frowCapable(String size) {
        return !P65.equals(validSize(size));
    }

    boolean frowCapable() {
        return frowCapable(size);
    }

    /** 第 n 个 F 键（1–12）配的第二功能；没配 / "无" / 这种尺寸不上第二功能返回 null。 */
    FKeyFunctions.Function frow(int n) {
        return n >= 1 && n <= 12 ? frow[n - 1] : null;
    }

    /** 这个键是第几个 F 键（1–12，F 行上发 F1–F12 的那几个；65% 的 F 键在 Fn 层里、不算），不是返回 0。 */
    int frowIndex(Key k) {
        if (k.special || !frowCapable() || k.id < FKeyFunctions.F1 || k.id > FKeyFunctions.F1 + 11) return 0;
        return k.id - FKeyFunctions.F1 + 1;
    }

    /** 至少一个 F 键配了第二功能（Esc 是 F 行翻转开关）。 */
    boolean escFnLock() {
        for (FKeyFunctions.Function f : frow) if (f != null) return true;
        return false;
    }

    /**
     * 这一块的 F 行放不放得下两层字（第二功能在上、F1 在下，方案 §9）：TKL / 全尺寸 / 75% 放得下；笔记本的 F 键只有 0.8125u 宽、
     * 苹果紧凑的 F 行半行高，放不下，F 行（连 Esc 的 FnLock）只画会出的那一面、Fn 一按整片换字（同 Fn 层的整片换字）。
     */
    boolean frowTwoFaces() {
        return !laptop() && !apple();
    }

    /** 照 Boards 存的 12 个功能 id（下标 0 = F1，FKeyFunctions.get 认；null / 认不出的 / "无" 都当没配）填 {@link #frow}；不上第二功能的尺寸什么都不填。 */
    private FixedLayout withFrow(String[] ids) {
        if (ids == null || !frowCapable()) return this;
        for (int i = 0; i < 12 && i < ids.length; i++) {
            FKeyFunctions.Function f = FKeyFunctions.get(ids[i]);
            frow[i] = f == null || f.kind == FKeyFunctions.NONE ? null : f;
        }
        return this;
    }

    /** 几个区块：全尺寸 3（主键区、编辑区、小键盘）、TKL 2，笔记本、75%、65%、苹果紧凑 1。 */
    int clusterCount() {
        return full() ? 3 : TKL.equals(size) ? 2 : 1;
    }

    /** 这一块显示的键：menuKey = ⋯ 放在槽里（小触控板上没摆出 ⋯）。顺序就是报区域、画的顺序。 */
    List<Key> keys(boolean menuKey) {
        return menuKey ? keysMenu : keys;
    }

    /** 用法码 / 按钮编号是 id 的键（不含 ⋯）；没有返回 null。 */
    Key find(int id) {
        for (Key k : keys) if (k.id == id) return k;
        return null;
    }

    /** 区块的左右边（u）。 */
    static float clusterLeft(int cluster) {
        return cluster == MAIN ? 0 : cluster == EDIT ? EDIT_LEFT : PAD_LEFT;
    }

    static float clusterRight(int cluster) {
        return cluster == MAIN ? MAIN_RIGHT : cluster == EDIT ? EDIT_RIGHT : PAD_RIGHT;
    }

    /**
     * 第 r 行的上沿、下沿（u），按这一块自己的行表：标准的每行 1u 高、最下一排的下沿是总高（笔记本 6.5）；65% 的第 0 行零高（没有 F 行）；
     * 苹果紧凑的 F 行半行高。
     */
    float rowTop(int r) {
        return rowTops[r];
    }

    float rowBottom(int r) {
        return rowBottoms[r];
    }

    /** 键 k 在第 r 行那一段的上沿、下沿（u）：带竖向子区间的（笔记本的方向键）用它，别的占整行。 */
    float segTop(Key k, int r) {
        return Float.isNaN(k.y0) ? rowTop(r) : k.y0;
    }

    float segBottom(Key k, int r) {
        return Float.isNaN(k.y1) ? rowBottom(r) : k.y1;
    }

    /** 能选的值，认不出的换成默认的（ANSI、TKL、Windows）。 */
    static String validShape(String s) {
        return ISO.equals(s) ? ISO : ABNT2.equals(s) ? ABNT2 : ANSI;
    }

    static String validSize(String s) {
        for (String z : ALL_SIZES) if (z.equals(s)) return z;
        return TKL;
    }

    /**
     * 逐级缩（2026-11-01，用户："你设定一个最小的键帽的大小……只要小于这个，你就缩那个键盘的阵列。缩完了还不放不进去，你就再缩"）：
     * 从选的尺寸往下的几级（第 3 批，方案 design-batch3 §2.4）：
     * 全尺寸 → TKL → 75% → 笔记本；TKL → 75% → 笔记本；75% → 笔记本；65% → 笔记本；笔记本 → 笔记本；苹果紧凑 → 苹果紧凑。
     * 65% 和 75% 一样宽（放不进 75% 也放不进 65%），不进别人的梯子；苹果紧凑（14.5u）只比笔记本窄 3%，也不进别人的梯子（形状突然换成苹果的比例反而意外），
     * 它放不下就直接豆花布局。选的是起点，只缩不涨（选 TKL 的不会在宽窗口里换成全尺寸）；认不出的当 TKL。哪一级放得下由 KeyboardPlan.fixedLevel 判断。
     */
    static List<String> ladder(String selected) {
        List<String> out = new ArrayList<>();
        for (String s = validSize(selected); s != null; s = smaller(s)) out.add(s);
        return out;
    }

    /** 逐级缩的下一级（{@link #ladder}），没有了返回 null。 */
    private static String smaller(String size) {
        switch (size) {
            case FULL:
                return TKL;
            case TKL:
                return P75;
            case P75:
            case P65:
                return LAPTOP;
            default:
                return null;
        }
    }

    /**
     * 这个组合拼不拼得出（第 3 批）：苹果紧凑只有 Mac 模式、只有 ANSI / ISO（Windows 模式、ABNT2 形状下不可用：设置页灰掉，
     * 存着的组合正好不可用时 {@link #forEntry} 返回 null、键盘页照"没数据的模式"那条退回豆花布局）。别的尺寸都拼得出。
     */
    static boolean available(String shape, String size, String mode) {
        return !APPLE.equals(validSize(size)) || (MAC.equals(validMode(mode)) && !ABNT2.equals(validShape(shape)));
    }

    static String validMode(String s) {
        return MAC.equals(s) ? MAC : WIN;
    }

    /**
     * 实体键盘这一级能不能用小红点（2026-11-01 晚，用户："超过 78 键的那个模拟就不需要加指点杆了……实在是太丑了……直接关闭这个选项"、
     * "Mac 键盘的模拟也关掉那个小红点"，问过以后："那就 TKL 和全尺寸都不给，和 Mac 键盘不给，其他都有"）：
     * 笔记本、75%、65% 的 Windows 模式能；TKL、全尺寸、所有 Mac 模式不能（苹果紧凑只有 Mac 模式，所以也不能）。豆花布局不受影响。
     * 按**显示着的那一级**算（逐级缩：选 TKL、窗口窄到缩成 75% 时能用；大小位置本来就按显示着的尺寸存）。规则只有这一处：
     * KeyboardPlan.planFixed（存着小红点、这一级不给时照触控板摆）、Boards.pointer(尺寸)（照触控板那份存取大小位置）、⋯ 菜单"指点"轮换都问它。
     */
    static boolean stickAllowed(String size, String mode) {
        String s = validSize(size);
        return WIN.equals(validMode(mode)) && (LAPTOP.equals(s) || P75.equals(s) || P65.equals(s));
    }

    /** 这一块能不能用小红点（{@link #stickAllowed(String, String)}）。 */
    boolean stickAllowed() {
        return stickAllowed(size, mode);
    }

    /**
     * 照字表拼一块（FixedData.ladder 用，逐级缩的每一级各拼一块）：右 Alt 写不写 AltGr 看字表有没有第三层。字表是 null（键帽数据读不出来，或者这个布局这种模式没有那一段）
     * 就不拼、返回 null——KeyboardScreen 拿不到布局就照样显示豆花布局（⋯ 菜单写明），不显示一块出字的键全是空白的实体键盘。
     */
    static FixedLayout forLegends(String shape, String size, String mode, FixedLegends.Table legends, String kpdl) {
        return legends == null || !available(shape, size, mode) ? null : build(shape, size, mode, legends.hasThird(), kpdl);
    }

    /**
     * 同 {@link #forLegends}，小键盘的字、Mac 模式的 Caps 键帽照布局清单的这一行（小数点按模式取 Layout.kpdl(mode)——Windows 模式照微软布局表的 kpdl_win、
     * Mac 模式照 xkb 的 kpdl；kpco、caps_mac；entry 是 null 时照默认的：小数点 "."、Caps）。FixedData.ladder 用这个。
     * 不可用的组合（{@link #available}：苹果紧凑 × Windows 模式 / ABNT2）同样返回 null，键盘页退回豆花布局。
     */
    static FixedLayout forEntry(String shape, String size, String mode, FixedLegends.Table legends, FixedLegends.Layout entry) {
        return forEntry(shape, size, mode, legends, entry, null);
    }

    /**
     * 同上，另给 F1–F12 配的第二功能（协议 24：Boards.fkeys(模式) 存的 12 个功能 id，下标 0 = F1；null = 都没配）。FixedData.ladder 用这个，
     * 逐级缩的每一级都照同一份配（65% 不上第二功能，给了也不填）。
     */
    static FixedLayout forEntry(String shape, String size, String mode, FixedLegends.Table legends, FixedLegends.Layout entry, String[] fkeys) {
        if (legends == null || !available(shape, size, mode)) return null;
        FixedLayout l = entry == null ? build(shape, size, mode, legends.hasThird(), ".")
                : build(shape, size, mode, legends.hasThird(), entry.kpdl(mode), entry.kpco, entry.capsMac);
        return l.withFrow(fkeys);
    }

    /**
     * 拼一块键盘。altGr = 右 Alt 写 AltGr（这个布局在 Windows 上有第三层的字；美式没有，写 Alt），Mac 模式不看；
     * kpdl = 小键盘小数点出的字（布局清单的 kpdl，德语是 ","）。
     */
    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl) {
        return build(shape, size, mode, altGr, kpdl, null, null);
    }

    /**
     * 同上，另给 kpco = ABNT2 小键盘多的那个键（0x85）写的字（清单的 kpco，巴西是 "."；空 / null 写 "."，别的形状没有这个键），
     * capsMac = Mac 模式 Caps 键帽写什么（清单的 caps_mac，中文拼音是"中/英"；空 / null 照旧写 Caps，Windows 模式不看）。
     */
    /** 同上，另给 F1–F12 配的第二功能（同 {@link #forEntry(String, String, String, FixedLegends.Table, FixedLegends.Layout, String[])} 的 fkeys；测试用）。 */
    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl, String kpco, String capsMac, String[] fkeys) {
        return build(shape, size, mode, altGr, kpdl, kpco, capsMac).withFrow(fkeys);
    }

    static FixedLayout build(String shape, String size, String mode, boolean altGr, String kpdl, String kpco, String capsMac) {
        shape = validShape(shape);
        size = validSize(size);
        mode = validMode(mode);
        if (P75.equals(size) || P65.equals(size)) return buildSixteen(shape, size, mode, altGr, capsMac);
        if (APPLE.equals(size)) {
            // 苹果紧凑只有 Mac 模式、ANSI / ISO：给了 Windows / ABNT2 时照 Mac / ISO 拼（设置页给灰掉的那一项算键数用）；
            // App 拼键盘走 forEntry，不可用的组合在那里就返回 null，不会显示出来
            return buildApple(ABNT2.equals(shape) ? ISO : shape, capsMac);
        }
        boolean mac = MAC.equals(mode), abnt2 = ABNT2.equals(shape), iso = ISO.equals(shape) || abnt2;
        boolean full = FULL.equals(size), laptop = LAPTOP.equals(size);
        // 1 左边、Z 左边那两个键照真苹果 ISO 键盘对调：只在 Mac 模式的 ISO（ABNT2 不对调，见类注释）
        boolean appleSwap = mac && iso && !abnt2;
        List<Key> k = new ArrayList<>();
        List<float[]> blanks = new ArrayList<>();

        // ── 第 0 行：F 行 ──
        if (laptop) {
            // 笔记本（照 ThinkPad）：Esc 1.125 · F1–F12 各 0.8125、四个一组、组间 0.1875 · 隔 0.1875 · Home End Ins 各 0.8125 · Del 1.125
            float f = 0.8125f, sep = 0.1875f;
            k.add(key(0x29, MAIN, 0, 0, 1.125f, "Esc"));
            float x = 1.125f;
            for (int i = 0; i < 12; i++) {
                if (i == 4 || i == 8) x += sep;
                k.add(key(0x3A + i, MAIN, 0, x, f, "F" + (i + 1)));
                x += f;
            }
            x += sep;   // 11.4375
            k.add(key(0x4A, MAIN, 0, x, f, "Home"));
            k.add(key(0x4D, MAIN, 0, x + f, f, "End"));
            // Mac 模式没有 Ins：那格空着、声明成空格子（不在任何范围里，见 blanks）
            if (mac) blanks.add(new float[]{0, x + 2 * f, x + 3 * f});
            else k.add(key(0x49, MAIN, 0, x + 2 * f, f, "Ins"));
            k.add(key(0x4C, MAIN, 0, x + 3 * f, MAIN_RIGHT - (x + 3 * f), "Del"));
        } else {
            k.add(key(0x29, MAIN, 0, 0, 1, "Esc"));
            for (int i = 0; i < 12; i++) {
                float x = i < 4 ? 2 + i : i < 8 ? 6.5f + (i - 4) : 11 + (i - 8);
                k.add(key(0x3A + i, MAIN, 0, x, 1, "F" + (i + 1)));
            }
            if (mac) {
                for (int i = 0; i < 3; i++) k.add(key(0x68 + i, EDIT, 0, EDIT_LEFT + i, 1, "F" + (13 + i)));
            } else {
                k.add(key(0x46, EDIT, 0, EDIT_LEFT, 1, "PrtSc"));
                k.add(key(0x47, EDIT, 0, EDIT_LEFT + 1, 1, "ScrLk"));
                k.add(key(0x48, EDIT, 0, EDIT_LEFT + 2, 1, "Pause"));
            }
        }

        // ── 第 1 行：数字行 ──
        // 1 左边那个键：ANSI 两种模式都是 0x35；ISO 时 Windows 0x35、Mac 照真苹果 ISO 键盘发 0x64（见类注释）；ABNT2 两种模式都是 0x35
        k.add(key(appleSwap ? 0x64 : 0x35, MAIN, 1, 0, 1, null));
        for (int i = 0; i < 10; i++) k.add(key(0x1E + i, MAIN, 1, 1 + i, 1, null));
        k.add(key(0x2D, MAIN, 1, 11, 1, null));
        k.add(key(0x2E, MAIN, 1, 12, 1, null));
        k.add(key(0x2A, MAIN, 1, 13, 2, "⌫"));
        if (!laptop) {   // 编辑区（笔记本没有：Home End Ins Del 在 F 行，PgUp PgDn 在方向键旁边）
            if (!mac) k.add(key(0x49, EDIT, 1, EDIT_LEFT, 1, "Ins"));
            k.add(key(0x4A, EDIT, 1, EDIT_LEFT + 1, 1, "Home"));
            k.add(key(0x4B, EDIT, 1, EDIT_LEFT + 2, 1, "PgUp"));
        }

        // ── 第 2 行：Q 行 ──
        k.add(key(0x2B, MAIN, 2, 0, 1.5f, "Tab"));
        letters(k, 2, 1.5f, "qwertyuiop");
        k.add(key(0x2F, MAIN, 2, 11.5f, 1, null));
        k.add(key(0x30, MAIN, 2, 12.5f, 1, null));
        if (iso) {
            // L 形回车：上半 1.5u（第 2 行），下半 1.25u（第 3 行）
            k.add(new Key(0x28, false, MAIN, 2, 1, 13.5f, 1.5f, 13.75f, 1.25f, "↵"));
        } else {
            k.add(key(0x31, MAIN, 2, 13.5f, 1.5f, null));
        }
        if (!laptop) {
            k.add(key(0x4C, EDIT, 2, EDIT_LEFT, 1, "Del"));
            k.add(key(0x4D, EDIT, 2, EDIT_LEFT + 1, 1, "End"));
            k.add(key(0x4E, EDIT, 2, EDIT_LEFT + 2, 1, "PgDn"));
        }

        // ── 第 3 行：A 行 ──
        k.add(key(0x39, MAIN, 3, 0, 1.75f, mac && capsMac != null && !capsMac.isEmpty() ? capsMac : "Caps"));
        letters(k, 3, 1.75f, "asdfghjkl");
        k.add(key(0x33, MAIN, 3, 10.75f, 1, null));
        k.add(key(0x34, MAIN, 3, 11.75f, 1, null));
        if (iso) k.add(key(0x31, MAIN, 3, 12.75f, 1, null));   // 回车左边那个键（德语 # '）
        else k.add(key(0x28, MAIN, 3, 12.75f, 2.25f, "↵"));

        // ── 第 4 行：Z 行 ──
        if (iso) {
            k.add(key(0xE1, MAIN, 4, 0, 1.25f, "⇧"));
            k.add(key(appleSwap ? 0x35 : 0x64, MAIN, 4, 1.25f, 1, null));   // Z 左边那个键：Windows 0x64、Mac 0x35（见类注释）；ABNT2 两种模式都是 0x64
        } else {
            k.add(key(0xE1, MAIN, 4, 0, 2.25f, "⇧"));
        }
        letters(k, 4, 2.25f, "zxcvbnm");
        k.add(key(0x36, MAIN, 4, 9.25f, 1, null));
        k.add(key(0x37, MAIN, 4, 10.25f, 1, null));
        k.add(key(0x38, MAIN, 4, 11.25f, 1, null));
        if (abnt2) {
            // ABNT2：右 Shift 左边多一个 / ?（0x87），右 Shift 只剩 1.75u
            k.add(key(0x87, MAIN, 4, 12.25f, 1, null));
            k.add(key(0xE5, MAIN, 4, 13.25f, 1.75f, "⇧"));
        } else {
            k.add(key(0xE5, MAIN, 4, 12.25f, 2.75f, "⇧"));
        }
        if (!laptop) k.add(key(0x52, EDIT, 4, EDIT_LEFT + 1, 1, "↑"));

        // ── 第 5 行：空格那一排 ──
        float m = 1.25f;
        if (laptop) {
            // 笔记本（照 ThinkPad）：一行、1.25 行高。Ctrl 1.25u · Fn 1u（协议 24 起；以前这一格是 ⋯ 槽）· Super · Alt · 空格 5u · AltGr / Alt · PrtSc · Ctrl，
            // 右下角是方向键两层（上 PgUp ↑ PgDn、下 ← ↓ →）。Mac 模式位置不变：⌃ · fn🌐 · ⌥ · ⌘ · 空格 · ⌘ · 🌐 · ⌃
            k.add(key(0xE0, MAIN, 5, 0, m, mac ? "⌃" : "Ctrl"));
            k.add(new Key(FN_ID, true, MAIN, 5, 1, m, 1, 0, 0, mac ? FN_NAME_MAC : FN_NAME));
            if (mac) {
                k.add(key(0xE2, MAIN, 5, 2.25f, 1, "⌥"));
                k.add(key(0xE3, MAIN, 5, 3.25f, 1, "⌘"));
            } else {
                k.add(key(0xE3, MAIN, 5, 2.25f, 1, "Super"));
                k.add(key(0xE2, MAIN, 5, 3.25f, 1, "Alt"));
            }
            k.add(key(0x2C, MAIN, 5, 4.25f, 5, SPACE_NAME));
            if (mac) {
                k.add(key(0xE7, MAIN, 5, 9.25f, 1, "⌘"));
                k.add(new Key(GLOBE_ID, true, MAIN, 5, 1, 10.25f, 1, 0, 0, "🌐"));
                k.add(key(0xE4, MAIN, 5, 11.25f, 0.9375f, "⌃"));
            } else {
                k.add(key(0xE6, MAIN, 5, 9.25f, 1, altGr ? "AltGr" : "Alt"));
                k.add(key(0x46, MAIN, 5, 10.25f, 1, "PrtSc"));
                k.add(key(0xE4, MAIN, 5, 11.25f, 0.9375f, "Ctrl"));
            }
            float ax = 12.1875f, aw = 0.9375f, top = ROW_TOP[5], mid = LAPTOP_ARROW_SPLIT, bottom = ROW_TOP[5] + LAPTOP_BOTTOM_ROW;
            k.add(arrow(0x4B, ax, aw, top, mid, "PgUp"));
            k.add(arrow(0x52, ax + aw, aw, top, mid, "↑"));
            k.add(arrow(0x4E, ax + 2 * aw, aw, top, mid, "PgDn"));
            k.add(arrow(0x50, ax, aw, mid, bottom, "←"));
            k.add(arrow(0x51, ax + aw, aw, mid, bottom, "↓"));
            k.add(arrow(0x4F, ax + 2 * aw, aw, mid, bottom, "→"));
        } else if (mac) {
            // 协议 24 起 🌐 那格是 fn🌐（点一下照旧切输入法、按住是 Fn，同 75%）
            k.add(key(0xE0, MAIN, 5, 0, m, "⌃"));
            k.add(key(0xE2, MAIN, 5, m, m, "⌥"));
            k.add(key(0xE3, MAIN, 5, 2 * m, m, "⌘"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE7, MAIN, 5, 10, m, "⌘"));
            k.add(key(0xE6, MAIN, 5, 10 + m, m, "⌥"));
            k.add(new Key(FN_ID, true, MAIN, 5, 1, 10 + 2 * m, m, 0, 0, FN_NAME_MAC));
            k.add(key(0xE4, MAIN, 5, 10 + 3 * m, m, "⌃"));
        } else {
            // 协议 24 起右 Super 换成 Fn（照 Ducky One TKL、Keychron K8 这类，凭记忆；位置同 75% 的 Fn，紧跟 AltGr）
            k.add(key(0xE0, MAIN, 5, 0, m, "Ctrl"));
            k.add(key(0xE3, MAIN, 5, m, m, "Super"));
            k.add(key(0xE2, MAIN, 5, 2 * m, m, "Alt"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE6, MAIN, 5, 10, m, altGr ? "AltGr" : "Alt"));
            k.add(new Key(FN_ID, true, MAIN, 5, 1, 10 + m, m, 0, 0, FN_NAME));
            k.add(key(0x65, MAIN, 5, 10 + 2 * m, m, "☰"));
            k.add(key(0xE4, MAIN, 5, 10 + 3 * m, m, "Ctrl"));
        }
        if (!laptop) {
            k.add(key(0x50, EDIT, 5, EDIT_LEFT, 1, "←"));
            k.add(key(0x51, EDIT, 5, EDIT_LEFT + 1, 1, "↓"));
            k.add(key(0x4F, EDIT, 5, EDIT_LEFT + 2, 1, "→"));
        }

        // ── 数字小键盘（全尺寸）──
        if (full) {
            float p = PAD_LEFT;
            k.add(key(0x53, NUMPAD, 1, p, 1, mac ? "Clear" : "Num"));
            k.add(key(0x54, NUMPAD, 1, p + 1, 1, "/"));
            k.add(key(0x55, NUMPAD, 1, p + 2, 1, "*"));
            k.add(key(0x56, NUMPAD, 1, p + 3, 1, "-"));
            String[] digits = {"7", "8", "9", "4", "5", "6", "1", "2", "3"};
            int[] usages = {0x5F, 0x60, 0x61, 0x5C, 0x5D, 0x5E, 0x59, 0x5A, 0x5B};
            for (int i = 0; i < 9; i++) k.add(key(usages[i], NUMPAD, 2 + i / 3, p + i % 3, 1, digits[i]));
            if (abnt2) {
                // ABNT2：右列的 + 只有一行高，下面 4 5 6 那一行是 0x85（巴西写 "."）
                k.add(key(0x57, NUMPAD, 2, p + 3, 1, "+"));
                k.add(key(0x85, NUMPAD, 3, p + 3, 1, kpco == null || kpco.isEmpty() ? "." : kpco));
            } else {
                k.add(new Key(0x57, false, NUMPAD, 2, 2, p + 3, 1, 0, 0, "+"));
            }
            k.add(new Key(0x58, false, NUMPAD, 4, 2, p + 3, 1, 0, 0, "↵"));
            k.add(key(0x62, NUMPAD, 5, p, 2, "0"));
            k.add(key(0x63, NUMPAD, 5, p + 2, 1, kpdl == null || kpdl.isEmpty() ? "." : kpdl));
        }
        // ⋯ 槽：TKL / 全尺寸在 Esc 和 F1 之间（F 行）；笔记本在 F 行 Ins 那格（协议 24 起，左下第二格给了 Fn）。
        // ⋯ 在槽里时显示的那份：TKL / 全尺寸在 Esc 后面插 ⋯；笔记本 Windows 模式 Ins 换成 ⋯（Ins 改由 Fn+Del 出），Mac 模式 ⋯ 放进本来空着的那格（跟在 End 后面）
        float insX = 11.4375f + 2 * 0.8125f;
        Key menu = laptop ? new Key(MENU_ID, true, MAIN, 0, 1, insX, 0.8125f, 0, 0, "⋯") : new Key(MENU_ID, true, MAIN, 0, 1, 1, 1, 0, 0, "⋯");
        List<Key> km = new ArrayList<>(k);
        if (laptop) {
            if (mac) km.add(indexOf(km, 0x4D) + 1, menu);
            else km.set(indexOf(km, 0x49), menu);
        } else {
            km.add(1, menu);   // 跟在 Esc 后面
        }
        float width = full ? PAD_RIGHT : laptop ? MAIN_RIGHT : EDIT_RIGHT;
        float height = ROW_TOP[ROWS - 1] + (laptop ? LAPTOP_BOTTOM_ROW : 1);
        return new FixedLayout(shape, size, mode, k, km, menu, width, height, !mac && altGr, blanks, ROW_TOP, standardBottoms(height),
                fnLayer(k, size, mac, appleSwap ? 0x64 : 0x35));
    }

    /** 标准行表的下沿：每行 1u 高，最下一排的下沿是总高（笔记本 6.5）。 */
    private static float[] standardBottoms(float height) {
        float[] b = new float[ROWS];
        for (int r = 0; r < ROWS; r++) b[r] = r == ROWS - 1 ? height : ROW_TOP[r] + 1;
        return b;
    }

    /**
     * 75% / 65%（第 3 批，见类注释）：16u 宽、一个区块。主键区第 2–4 行的位置、用法码同 TKL（1 左边、Z 左边照 ISO 的苹果对调规矩），右边多一列 1u；
     * 75% 有 F 行（同 TKL 的 ⋯ 槽在 Esc 和 F1 之间），65% 没有 F 行、Esc 占 1 左边那个键的位置、⋯ 槽在左下角 Ctrl 右边。
     */
    private static FixedLayout buildSixteen(String shape, String size, String mode, boolean altGr, String capsMac) {
        boolean p75 = P75.equals(size), mac = MAC.equals(mode), abnt2 = ABNT2.equals(shape), iso = ISO.equals(shape) || abnt2;
        boolean appleSwap = mac && iso && !abnt2;
        int grave = appleSwap ? 0x64 : 0x35;   // 1 左边那个键发的码（同 TKL）
        float right = 15;   // 右边那一列的左边
        List<Key> k = new ArrayList<>();

        // ── 第 0 行：F 行（只有 75%）：Esc ·（⋯ 槽）· F1–F12 · PrtSc / F13 · Del ──
        if (p75) {
            k.add(key(0x29, MAIN, 0, 0, 1, "Esc"));
            for (int i = 0; i < 12; i++) k.add(key(0x3A + i, MAIN, 0, 2 + i, 1, "F" + (i + 1)));
            k.add(mac ? key(0x68, MAIN, 0, 14, 1, "F13") : key(0x46, MAIN, 0, 14, 1, "PrtSc"));
            k.add(key(0x4C, MAIN, 0, right, 1, "Del"));
        }

        // ── 第 1 行：数字行（65% 的 Esc 占 1 左边那个键的位置），右边 Home（75%）/ Del（65%）──
        k.add(p75 ? key(grave, MAIN, 1, 0, 1, null) : key(0x29, MAIN, 1, 0, 1, "Esc"));
        for (int i = 0; i < 10; i++) k.add(key(0x1E + i, MAIN, 1, 1 + i, 1, null));
        k.add(key(0x2D, MAIN, 1, 11, 1, null));
        k.add(key(0x2E, MAIN, 1, 12, 1, null));
        k.add(key(0x2A, MAIN, 1, 13, 2, "⌫"));
        k.add(p75 ? key(0x4A, MAIN, 1, right, 1, "Home") : key(0x4C, MAIN, 1, right, 1, "Del"));

        // ── 第 2 行：Q 行，右边 PgUp ──
        k.add(key(0x2B, MAIN, 2, 0, 1.5f, "Tab"));
        letters(k, 2, 1.5f, "qwertyuiop");
        k.add(key(0x2F, MAIN, 2, 11.5f, 1, null));
        k.add(key(0x30, MAIN, 2, 12.5f, 1, null));
        if (iso) k.add(new Key(0x28, false, MAIN, 2, 1, 13.5f, 1.5f, 13.75f, 1.25f, "↵"));
        else k.add(key(0x31, MAIN, 2, 13.5f, 1.5f, null));
        k.add(key(0x4B, MAIN, 2, right, 1, "PgUp"));

        // ── 第 3 行：A 行，右边 PgDn ──
        k.add(key(0x39, MAIN, 3, 0, 1.75f, mac && capsMac != null && !capsMac.isEmpty() ? capsMac : "Caps"));
        letters(k, 3, 1.75f, "asdfghjkl");
        k.add(key(0x33, MAIN, 3, 10.75f, 1, null));
        k.add(key(0x34, MAIN, 3, 11.75f, 1, null));
        if (iso) k.add(key(0x31, MAIN, 3, 12.75f, 1, null));
        else k.add(key(0x28, MAIN, 3, 12.75f, 2.25f, "↵"));
        k.add(key(0x4E, MAIN, 3, right, 1, "PgDn"));

        // ── 第 4 行：Z 行，右 Shift 缩短，后面 ↑ End（↑ 在 14–15，正对最下一排的 ↓）──
        if (iso) {
            k.add(key(0xE1, MAIN, 4, 0, 1.25f, "⇧"));
            k.add(key(appleSwap ? 0x35 : 0x64, MAIN, 4, 1.25f, 1, null));
        } else {
            k.add(key(0xE1, MAIN, 4, 0, 2.25f, "⇧"));
        }
        letters(k, 4, 2.25f, "zxcvbnm");
        k.add(key(0x36, MAIN, 4, 9.25f, 1, null));
        k.add(key(0x37, MAIN, 4, 10.25f, 1, null));
        k.add(key(0x38, MAIN, 4, 11.25f, 1, null));
        if (abnt2) {
            // ABNT2：/ ?（0x87）占掉 1u，右 Shift 只剩 0.75u（↑ 要正对 ↓，见类注释）
            k.add(key(0x87, MAIN, 4, 12.25f, 1, null));
            k.add(key(0xE5, MAIN, 4, 13.25f, 0.75f, "⇧"));
        } else {
            k.add(key(0xE5, MAIN, 4, 12.25f, 1.75f, "⇧"));
        }
        k.add(key(0x52, MAIN, 4, 14, 1, "↑"));
        k.add(key(0x4D, MAIN, 4, right, 1, "End"));

        // ── 第 5 行：Ctrl · Super · Alt · 空格 6.25 · AltGr / Alt · Fn · Ctrl · ← ↓ →（Mac：⌃ · ⌥ · ⌘ · 空格 · ⌘ · ⌥ · fn🌐 · ← ↓ →）──
        float m = 1.25f;
        if (mac) {
            k.add(key(0xE0, MAIN, 5, 0, m, "⌃"));
            k.add(key(0xE2, MAIN, 5, m, m, "⌥"));
            k.add(key(0xE3, MAIN, 5, 2 * m, m, "⌘"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE7, MAIN, 5, 10, 1, "⌘"));
            k.add(key(0xE6, MAIN, 5, 11, 1, "⌥"));
            k.add(new Key(FN_ID, true, MAIN, 5, 1, 12, 1, 0, 0, FN_NAME_MAC));
        } else {
            k.add(key(0xE0, MAIN, 5, 0, m, "Ctrl"));
            k.add(key(0xE3, MAIN, 5, m, m, "Super"));
            k.add(key(0xE2, MAIN, 5, 2 * m, m, "Alt"));
            k.add(key(0x2C, MAIN, 5, 3 * m, 6.25f, SPACE_NAME));
            k.add(key(0xE6, MAIN, 5, 10, 1, altGr ? "AltGr" : "Alt"));
            k.add(new Key(FN_ID, true, MAIN, 5, 1, 11, 1, 0, 0, FN_NAME));
            k.add(key(0xE4, MAIN, 5, 12, 1, "Ctrl"));
        }
        k.add(key(0x50, MAIN, 5, 13, 1, "←"));
        k.add(key(0x51, MAIN, 5, 14, 1, "↓"));
        k.add(key(0x4F, MAIN, 5, right, 1, "→"));

        // ⋯ 槽：75% 同 TKL（Esc 和 F1 之间）；65% 在左下角 Ctrl 右边，放进去时 Ctrl 1 · ⋯ 0.75 · Super 1 · Alt 1（Mac：⌃ · ⋯ · ⌥ · ⌘），空格不动
        List<Key> km = new ArrayList<>(k);
        Key menu;
        if (p75) {
            menu = new Key(MENU_ID, true, MAIN, 0, 1, 1, 1, 0, 0, "⋯");
            km.add(1, menu);
        } else {
            menu = new Key(MENU_ID, true, MAIN, 5, 1, 1, 0.75f, 0, 0, "⋯");
            int c = indexOf(km, 0xE0);
            int second = mac ? 0xE2 : 0xE3, third = mac ? 0xE3 : 0xE2;
            km.set(c, key(0xE0, MAIN, 5, 0, 1, km.get(c).name));
            km.set(indexOf(km, second), key(second, MAIN, 5, 1.75f, 1, km.get(indexOf(km, second)).name));
            km.set(indexOf(km, third), key(third, MAIN, 5, 2.75f, 1, km.get(indexOf(km, third)).name));
            km.add(c + 1, menu);
        }
        float[] tops = p75 ? ROW_TOP : ROW_TOP_65, bottoms = p75 ? standardBottoms(ROW_TOP[ROWS - 1] + 1) : ROW_BOTTOM_65;
        return new FixedLayout(shape, size, mode, k, km, menu, 16, bottoms[ROWS - 1], !mac && altGr, new ArrayList<>(), tops, bottoms,
                fnLayer(k, p75 ? P75 : P65, mac, grave));
    }

    /**
     * 苹果紧凑（第 3 批，只有 Mac 模式，见类注释）：14.5u × 5.5u、一个区块，F 行半行高；ISO 的 1 左边 / Z 左边照 1B 的苹果 ISO 对调（发 0x64 / 0x35）。
     * 右上角那格（触控 ID / 锁屏）是 ⋯ 槽：小触控板上摆出了 ⋯ 时空着，算在 F 行那一段里（{@link #ranges}）。
     */
    private static FixedLayout buildApple(String shape, String capsMac) {
        boolean iso = ISO.equals(shape);
        int grave = iso ? 0x64 : 0x35;
        List<Key> k = new ArrayList<>();
        // ── 第 0 行：F 行（半行高）：Esc 1.5 · F1–F12 ·（⋯ 槽）──
        k.add(key(0x29, MAIN, 0, 0, 1.5f, "Esc"));
        for (int i = 0; i < 12; i++) k.add(key(0x3A + i, MAIN, 0, 1.5f + i, 1, "F" + (i + 1)));
        // ── 第 1 行：` 1–0 - = · ⌫ 1.5 ──
        k.add(key(grave, MAIN, 1, 0, 1, null));
        for (int i = 0; i < 10; i++) k.add(key(0x1E + i, MAIN, 1, 1 + i, 1, null));
        k.add(key(0x2D, MAIN, 1, 11, 1, null));
        k.add(key(0x2E, MAIN, 1, 12, 1, null));
        k.add(key(0x2A, MAIN, 1, 13, 1.5f, "⌫"));
        // ── 第 2 行：Tab 1.5 · Q–] · \ 1（ISO：回车上半 1u）──
        k.add(key(0x2B, MAIN, 2, 0, 1.5f, "Tab"));
        letters(k, 2, 1.5f, "qwertyuiop");
        k.add(key(0x2F, MAIN, 2, 11.5f, 1, null));
        k.add(key(0x30, MAIN, 2, 12.5f, 1, null));
        if (iso) k.add(new Key(0x28, false, MAIN, 2, 1, 13.5f, 1, 13.75f, 0.75f, "↵"));   // L 形：上半 1u、下半 0.75u，右边对齐
        else k.add(key(0x31, MAIN, 2, 13.5f, 1, null));
        // ── 第 3 行：Caps 1.75 · A–' · 回车 1.75（ISO：0x31 · 回车下半）──
        k.add(key(0x39, MAIN, 3, 0, 1.75f, capsMac != null && !capsMac.isEmpty() ? capsMac : "Caps"));
        letters(k, 3, 1.75f, "asdfghjkl");
        k.add(key(0x33, MAIN, 3, 10.75f, 1, null));
        k.add(key(0x34, MAIN, 3, 11.75f, 1, null));
        if (iso) k.add(key(0x31, MAIN, 3, 12.75f, 1, null));
        else k.add(key(0x28, MAIN, 3, 12.75f, 1.75f, "↵"));
        // ── 第 4 行：Shift 2.25 · Z–/ · Shift 2.25（ISO：Shift 1.25 · 0x35）──
        if (iso) {
            k.add(key(0xE1, MAIN, 4, 0, 1.25f, "⇧"));
            k.add(key(0x35, MAIN, 4, 1.25f, 1, null));
        } else {
            k.add(key(0xE1, MAIN, 4, 0, 2.25f, "⇧"));
        }
        letters(k, 4, 2.25f, "zxcvbnm");
        k.add(key(0x36, MAIN, 4, 9.25f, 1, null));
        k.add(key(0x37, MAIN, 4, 10.25f, 1, null));
        k.add(key(0x38, MAIN, 4, 11.25f, 1, null));
        k.add(key(0xE5, MAIN, 4, 12.25f, 2.25f, "⇧"));
        // ── 第 5 行：fn🌐 · ⌃ · ⌥ · ⌘ 1.25 · 空格 5 · ⌘ 1.25 · ⌥ · ← · ↑↓ · → ──
        k.add(new Key(FN_ID, true, MAIN, 5, 1, 0, 1, 0, 0, FN_NAME_MAC));
        k.add(key(0xE0, MAIN, 5, 1, 1, "⌃"));
        k.add(key(0xE2, MAIN, 5, 2, 1, "⌥"));
        k.add(key(0xE3, MAIN, 5, 3, 1.25f, "⌘"));
        k.add(key(0x2C, MAIN, 5, 4.25f, 5, SPACE_NAME));
        k.add(key(0xE7, MAIN, 5, 9.25f, 1.25f, "⌘"));
        k.add(key(0xE6, MAIN, 5, 10.5f, 1, "⌥"));
        float top = ROW_TOP_APPLE[5], mid = APPLE_ARROW_SPLIT, bottom = ROW_BOTTOM_APPLE[5];
        k.add(key(0x50, MAIN, 5, 11.5f, 1, "←"));
        k.add(arrow(0x52, 12.5f, 1, top, mid, "↑"));
        k.add(arrow(0x51, 12.5f, 1, mid, bottom, "↓"));
        k.add(key(0x4F, MAIN, 5, 13.5f, 1, "→"));
        // ⋯ 槽：右上角那格，放在 F12 后面
        Key menu = new Key(MENU_ID, true, MAIN, 0, 1, 13.5f, 1, 0, 0, "⋯");
        List<Key> km = new ArrayList<>(k);
        km.add(indexOf(km, 0x45) + 1, menu);
        return new FixedLayout(shape, APPLE, MAC, k, km, menu, 14.5f, ROW_BOTTOM_APPLE[ROWS - 1], false, new ArrayList<>(), ROW_TOP_APPLE,
                ROW_BOTTOM_APPLE, fnLayer(k, APPLE, true, grave));
    }

    private static Key key(int id, int cluster, int row, float x, float w, String name) {
        return new Key(id, false, cluster, row, 1, x, w, 0, 0, name);
    }

    /** 笔记本的方向键（PgUp / PgDn 也算）：第 5 行里竖着只占 y0–y1 那一截（上下两层）。 */
    private static Key arrow(int id, float x, float w, float y0, float y1, String name) {
        return new Key(id, false, MAIN, 5, 1, x, w, 0, 0, name, y0, y1);
    }

    /** 列表里用法码是 id 的第一个键的下标，没有 -1。 */
    private static int indexOf(List<Key> ks, int id) {
        for (int i = 0; i < ks.size(); i++) if (ks.get(i).id == id) return i;
        return -1;
    }

    /** 一行字母键（a–z 的用法码 0x04 起，按字母算），从 x 起一个挨一个。 */
    private static void letters(List<Key> k, int row, float x, String s) {
        for (int i = 0; i < s.length(); i++) k.add(key(0x04 + (s.charAt(i) - 'a'), MAIN, row, x + i, 1, null));
    }

    // ═══════════ Fn 层（第 3 批的数据；按 Fn 的规则在 TouchRouter / KeyHolds） ═══════════

    /**
     * Fn 层的一项：底层发 base 的那个键，按着（或锁着）Fn 时发 usage，键帽换成 name（出字的键是 null：字按 usage 查 FixedLegends，
     * 现在只有 65% 的 Esc → 1 左边那个键）。Fn 层的码都是 HID 键盘页的普通键（不是修饰键、不是豆花的按钮编号），和底层不一样。
     * 有的码底层另有一个键也发（75% 的 Fn+↑ = PgUp 和右边那一列的 PgUp、Windows 模式 Fn+P = PrtSc 和 F 行的 PrtSc；65% 的 Fn+← 和 Fn+PgUp 都是 Home）：
     * 照方案原样，{@link #fnSharedUsages} 列得出来。两个键同时按着时 KeyHolds 按定下来的码计数，HID 照普通键的老规矩：先松的那个就松开这个码
     * （同两个空格副本），后松的那个什么都不多出——每个按下都配了松开，不会卡键（KeyHoldsTest、FixedCompactTest.fnPaths 锁住）。
     */
    static final class FnKey {
        final int base, usage;
        final String name;

        FnKey(int base, int usage, String name) {
            this.base = base;
            this.usage = usage;
            this.name = name;
        }

        boolean charKey() {
            return name == null;
        }

        @Override
        public String toString() {
            return String.format("0x%02X→%s", base, name != null ? name : String.format("0x%02X", usage));
        }
    }

    /** 底层发 base 的那个键在 Fn 层出什么；没有 Fn 层（按着 Fn 照旧出底层的码）返回 null。 */
    FnKey fn(int base) {
        return fnLayer.get(base);
    }

    /** 这个键是 Fn 键（Windows 模式的 Fn、Mac 模式的 fn🌐；75% / 65% / 苹果紧凑，协议 24 起 TKL / 全尺寸 / 笔记本也有）。 */
    static boolean fnKey(Key k) {
        return k.special && k.id == FN_ID;
    }

    /** 这个键报给分流时的区域类型：⋯、🌐 是按钮（App 执行）；Fn 和别的键是键（REGION_KEY，按住 / 点一下 / 锁的规矩在 KeyHolds）。 */
    static int regionType(Key k) {
        return k.special && !fnKey(k) ? Proto.REGION_BUTTON : Proto.REGION_KEY;
    }

    /**
     * 这个键报给分流（服务、本地分流）、面板自己收触摸时发给分流的 id（协议 21）：Fn 键 → Proto.FN_KEY（Windows 模式）/ Proto.FN_GLOBE_KEY（Mac 模式，
     * 单点 = 🌐）；带 Fn 层的键 → Proto.fnLayerKey(底层用法码, Fn 层用法码)（按下那一刻 KeyHolds 按 Fn 定发哪个）；协议 24 起配了第二功能的 F 键 →
     * Proto.frowKey（FKeyFunctions.regionId）、Esc 在至少一个 F 键配了第二功能时 → Proto.FN_LOCK_KEY；别的照旧是用法码 / 按钮编号。
     * App 收到的按下（MSG_BUTTON 的 KEY_ID_BASE + id）也是这个 id，画按下的样子按它对回键（FixedPanel.index 只认几何里的编号）。
     */
    int keyId(Key k) {
        if (fnKey(k)) return mac() ? Proto.FN_GLOBE_KEY : Proto.FN_KEY;
        if (k.special) return k.id;
        // 协议 24：配了第二功能的 F 键 → Proto.frowKey（两面都在 id 里，哪一面由 KeyHolds 按下那一刻按"Fn 起作用 ^ F 行翻转"定）；
        // 至少一个 F 键配了时 Esc 是 F 行翻转开关（Proto.FN_LOCK_KEY）。没配的 F 键照旧是用法码
        int n = frowIndex(k);
        if (n > 0 && frow(n) != null) return FKeyFunctions.regionId(n, frow(n), mode);
        if (k.id == FKeyFunctions.ESC && frowCapable() && escFnLock()) return FKeyFunctions.escId(true);
        FnKey f = fnLayer.get(k.id);
        return f == null ? k.id : Proto.fnLayerKey(k.id, f.usage);
    }

    /** Fn 层全部的项（按键在列表里的顺序）；没有 Fn 键的尺寸是空的。 */
    List<FnKey> fnKeys() {
        return new ArrayList<>(fnLayer.values());
    }

    /**
     * Fn 层出的码里，底层另有键发同一个码的、或者 Fn 层里不止一个键出它的（方案定的，不是错，见 {@link FnKey}）：给 Fn 层的机制（按码计数松开）和测试用。
     * 按码排好。
     */
    List<Integer> fnSharedUsages() {
        List<Integer> out = new ArrayList<>();
        Map<Integer, Integer> n = new LinkedHashMap<>();
        for (FnKey f : fnLayer.values()) n.merge(f.usage, 1, Integer::sum);
        for (Map.Entry<Integer, Integer> e : n.entrySet()) {
            if (e.getValue() > 1 || find(e.getKey()) != null) out.add(e.getKey());
        }
        Collections.sort(out);
        return out;
    }

    /** Fn 层的码写在键帽上的名字（同底层那些键的写法）；出字的键（1 左边那个键 0x35 / 0x64）返回 null，字按码查 FixedLegends。 */
    static String fnName(int usage) {
        if (usage >= 0x3A && usage <= 0x45) return "F" + (usage - 0x3A + 1);
        if (usage >= 0x68 && usage <= 0x6A) return "F" + (usage - 0x68 + 13);
        switch (usage) {
            case 0x46:
                return "PrtSc";
            case 0x47:
                return "ScrLk";
            case 0x48:
                return "Pause";
            case 0x49:
                return "Ins";
            case 0x4A:
                return "Home";
            case 0x4B:
                return "PgUp";
            case 0x4C:
                return "Del";
            case 0x4D:
                return "End";
            case 0x4E:
                return "PgDn";
            case 0x65:
                return "☰";
            default:
                return null;
        }
    }

    /**
     * Fn 层（方案 design-batch3 §3.2；没列的键按着 Fn 照旧出底层的码）：
     * - 75%：Del → Ins（Mac 模式没有 Ins，Del 不换）；P [ ] → PrtSc ScrLk Pause（Mac 模式 → F13 F14 F15）；↑ ↓ ← → → PgUp PgDn Home End（照苹果 fn+方向）；
     *   Windows 模式右 Ctrl → Menu（0x65）。
     * - 65%：75% 那几条之外，1–0 - = → F1–F12；Esc → 1 左边那个键（grave：这块键盘上 1 左边那个键本来发的码，ISO 的 Mac 模式是 0x64、别的 0x35，
     *   65% 上那个位置被 Esc 占了，所以字照它查）；PgUp → Home、PgDn → End（65% 没有 Home 键）。
     * - 苹果紧凑（照 macOS）：fn+↑ ↓ → PgUp / PgDn、fn+← → → Home / End，fn+delete（⌫）→ 向前删除（0x4C）。
     * - 协议 24 起：笔记本 Windows 模式 Del → Ins（⋯ 放在 F 行 Ins 那格时 Ins 只有这一处，⋯ 不在槽里时 F 行另有 Ins 键，同 75% 的 Fn+↑ 和 PgUp 键）；
     *   TKL / 全尺寸、笔记本 Mac 模式没有（它们的 Fn 层只有 F 行的第二功能和 Esc 的 F 行翻转，另记在 {@link #frow} / {@link #escFnLock}，不在这里）。
     * 只放这一块里真有的底层键（k 里找得到的）。
     */
    private static Map<Integer, FnKey> fnLayer(List<Key> k, String size, boolean mac, int grave) {
        List<int[]> pairs = new ArrayList<>();
        int[][] arrows = {{0x52, 0x4B}, {0x51, 0x4E}, {0x50, 0x4A}, {0x4F, 0x4D}};
        if (APPLE.equals(size)) {
            pairs.addAll(Arrays.asList(arrows));
            pairs.add(new int[]{0x2A, 0x4C});
        } else if (LAPTOP.equals(size)) {
            if (!mac) pairs.add(new int[]{0x4C, 0x49});
        } else if (P75.equals(size) || P65.equals(size)) {
            if (P65.equals(size)) {
                pairs.add(new int[]{0x29, grave});
                for (int i = 0; i < 10; i++) pairs.add(new int[]{0x1E + i, 0x3A + i});
                pairs.add(new int[]{0x2D, 0x44});
                pairs.add(new int[]{0x2E, 0x45});
                pairs.add(new int[]{0x4B, 0x4A});
                pairs.add(new int[]{0x4E, 0x4D});
            }
            if (!mac) pairs.add(new int[]{0x4C, 0x49});
            pairs.add(new int[]{0x13, mac ? 0x68 : 0x46});
            pairs.add(new int[]{0x2F, mac ? 0x69 : 0x47});
            pairs.add(new int[]{0x30, mac ? 0x6A : 0x48});
            pairs.addAll(Arrays.asList(arrows));
            if (!mac) pairs.add(new int[]{0xE4, 0x65});
        }
        Map<Integer, int[]> byBase = new LinkedHashMap<>();
        for (int[] p : pairs) byBase.put(p[0], p);
        Map<Integer, FnKey> out = new LinkedHashMap<>();
        for (Key key : k) {
            int[] p = key.special ? null : byBase.get(key.id);
            if (p != null) out.put(p[0], new FnKey(p[0], p[1], fnName(p[1])));
        }
        return out;
    }

    // ═══════════ 键盘范围（每区块每行一块） ═══════════

    /** 一块键盘范围（u）：哪个区块、哪一行，{左, 上, 右, 下}。 */
    static final class Range {
        final int cluster, row;
        final float left, top, right, bottom;

        Range(int cluster, int row, float left, float top, float right, float bottom) {
            this.cluster = cluster;
            this.row = row;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public String toString() {
            return "范围 区块" + cluster + " 第" + row + "行 [" + left + ", " + right + "]";
        }
    }

    /** 同一行里两个键之间隔着不超过这么宽（u）还算同一段（F 行 Esc 和 F1 之间那 1u 的 ⋯ 槽、F4 | F5 的半格）。 */
    static final float SEGMENT_GAP = 1.01f;

    /**
     * 键盘范围（REGION_KEYBOARD），每个区块的每一行各一块（方案 §5.3，[F] / [I] 说法不一时定的）：按在缝里只分给同一行这一段里最近的键。
     * 整块一块的话，按在 TKL 方向键 ↑ 正上方那格空白里，主键区同一行的回车 / ' 竖着的距离是 0，会赢过近得多的 ↑。
     * - 横着：从这一段最左的键到最右的键；这一段挨着区块的边时伸到和相邻区块之间空隙的中线，最外面的伸到面板边。
     * - 竖着：伸到和上下行之间的中线，最上、最下伸到面板边（行和行是挨着的，F 行和数字行之间的 0.25u 各分一半）。
     * - 一行里没有键的地方不在任何范围里（方向键上面那几格、↑ 两边、Mac 模式 Ins 那格），按了什么都不发生，和真键盘一样。
     * - 一段之内的小缝（F4 和 F5 之间那半格、空着的 ⋯ 槽）在范围里，分给最近的键；声明过的空格子（{@link #blanks}，Mac 模式笔记本 F 行的 Ins 那格）不并，
     *   格子两边各是一段。
     * - 行的上下沿、区块数按这一块取（笔记本最下一排 1.25 行高、只有一个区块）。
     * - **只占一行里竖着一截的键（笔记本的方向键两层）单独报，每层一块**（2026-11-01 审查后改的）：缝里交给 TouchRouter.nearestKey 先比竖向，
     *   和整行高的键（右 Ctrl、空格……）放在同一块里的话，上下两层之间那条缝里整行高的键竖向距离是 0、两层的方向键都大于 0，缝全分给最近的右 Ctrl，
     *   一个都分不到方向键（按在 ↑ / ↓ 交界附近 = 按了 Ctrl；按住方向键 0.4–1 秒松手还会把 Ctrl 锁住）。所以同一行里竖向子区间一样的键
     *   （{@link Key#y0} / {@link Key#y1}）自成一组，照同样的规则分段；组的上下沿伸到和上下相邻那组之间的中线，最上、最下的伸到这一行的上下沿
     *   （笔记本：上层 5.25–5.875、下层 5.875–6.5，横着从和右 Ctrl 共用的那条边 12.1875 到面板边）。整行高的键照旧一组（最下一排其余的键一块，
     *   ⋯、🌐 照旧只有一段）。两种键在一行里交错时（第 3 批苹果紧凑：↑↓ 夹在整行高的 ← 和 → 中间），整行高那一组不跨过另一组的键并成一段
     *   （← 那段到 ↑↓ 的左边为止、→ 自成一段），不然两组的范围会重叠——测试查不重叠。
     * menuKey 同 {@link #keys(boolean)}（槽里放没放 ⋯，结果一样）：⋯ 槽算在它那一行整行高的那一组里（TKL / 全尺寸 / 75% 的槽在 Esc 和 F1 中间，
     * 65% 的在左 Ctrl 右边，本来就在这一段里；苹果紧凑的在 F 行最右，空着时照样算在 F 行那一段里、分给 F12；笔记本的在 F 行 Ins 那格，Windows 模式那格平时是 Ins）。
     * 唯一的例外（协议 24）：⋯ 槽正好是声明过的空格子（Mac 模式笔记本 F 行 Ins 那格）时，⋯ 不在槽里那格照旧是空格子、不在任何范围里，
     * ⋯ 在槽里时 ⋯ 占着它、F 行并成一段——Mac 模式笔记本的范围放没放 ⋯ 不一样（不放 9 块、放 8 块）。
     */
    List<Range> ranges(boolean menuKey) {
        List<Range> out = new ArrayList<>();
        List<Key> ks = keys(menuKey);
        int clusters = clusterCount();
        for (int c = 0; c < clusters; c++) {
            // 只有一个区块时右边就是这一块的右边（笔记本 15 = 主键区；75% / 65% 16、苹果紧凑 14.5）
            float cl = clusterLeft(c), cr = clusters == 1 ? width : clusterRight(c);
            float outLeft = c == 0 ? 0 : (clusterRight(c - 1) + cl) / 2;
            float outRight = c == clusters - 1 ? width : (cr + clusterLeft(c + 1)) / 2;
            for (int r = 0; r < ROWS; r++) {
                float top = r == 0 ? 0 : r == 1 ? (rowBottom(0) + rowTop(1)) / 2 : rowTop(r);
                float bottom = r == 0 ? (rowBottom(0) + rowTop(1)) / 2 : r == ROWS - 1 ? height : rowBottom(r);
                // 整行高的键一组；只占一截的键按 (y0, y1) 分组，组按上沿排
                List<float[]> spans = new ArrayList<>();
                List<float[]> bands = new ArrayList<>();
                List<List<float[]>> bandSpans = new ArrayList<>();
                for (Key k : ks) {
                    if (k.cluster != c) continue;
                    float[] s = k.span(r);
                    if (s == null) continue;
                    if (Float.isNaN(k.y0)) {
                        spans.add(s);
                        continue;
                    }
                    int b = 0;
                    while (b < bands.size() && !(bands.get(b)[0] == k.y0 && bands.get(b)[1] == k.y1)) b++;
                    if (b == bands.size()) {
                        bands.add(new float[]{k.y0, k.y1});
                        bandSpans.add(new ArrayList<>());
                    }
                    bandSpans.get(b).add(s);
                }
                // ⋯ 槽算在它那一行整行高的那一组里：放没放 ⋯ 结果一样（TKL / 75% 的槽在 Esc 和 F1 中间、笔记本 / 65% 的在左 Ctrl 右边，本来就在一段里；
                // 苹果紧凑的在 F 行最右，空着时照样算在 F 行那一段里，分给 F12）
                if (menu.cluster == c && menu.span(r) != null && (menuKey || !blankAt(menu))) spans.add(menu.span(r));
                // 别的组的键占着的地方：同一行里只占一截的键夹在整行高的键中间（苹果紧凑的 ↑↓ 在 ← 和 → 之间）时，整行高那一组不跨过它们并成一段
                List<float[]> partial = new ArrayList<>();
                for (List<float[]> b : bandSpans) partial.addAll(b);
                addSegments(out, c, r, spans, top, bottom, cl, cr, outLeft, outRight, partial);
                Integer[] order = new Integer[bands.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Float.compare(bands.get(a)[0], bands.get(b)[0]));
                for (int i = 0; i < order.length; i++) {
                    float[] band = bands.get(order[i]);
                    float bt = i == 0 ? top : (bands.get(order[i - 1])[1] + band[0]) / 2;
                    float bb = i == order.length - 1 ? bottom : (band[1] + bands.get(order[i + 1])[0]) / 2;
                    addSegments(out, c, r, bandSpans.get(order[i]), bt, bb, cl, cr, outLeft, outRight, spans);
                }
            }
        }
        return out;
    }

    /**
     * 一组键（同一区块、同一行、竖着占同一截）的范围：从左到右并成几段（隔着 ≤ {@link #SEGMENT_GAP} 的小缝、中间没有声明过的空格子就并），
     * 每段一块 [top, bottom]；挨着区块边的伸到 outLeft / outRight。
     */
    private void addSegments(List<Range> out, int c, int r, List<float[]> spans, float top, float bottom,
                             float cl, float cr, float outLeft, float outRight, List<float[]> others) {
        if (spans.isEmpty()) return;
        spans.sort((a, b) -> Float.compare(a[0], b[0]));
        float segL = spans.get(0)[0], segR = spans.get(0)[1];
        for (int i = 1; i <= spans.size(); i++) {
            if (i < spans.size() && spans.get(i)[0] - segR <= SEGMENT_GAP && !blankBetween(r, segR, spans.get(i)[0])
                    && !occupied(others, segR, spans.get(i)[0])) {
                segR = Math.max(segR, spans.get(i)[1]);
                continue;
            }
            float l = Math.abs(segL - cl) < 1e-3f ? outLeft : segL;
            float rr = Math.abs(segR - cr) < 1e-3f ? outRight : segR;
            out.add(new Range(c, r, l, top, rr, bottom));
            if (i < spans.size()) {
                segL = spans.get(i)[0];
                segR = spans.get(i)[1];
            }
        }
    }

    /** [left, right] 这条缝里有没有别的组的键（others 是同一行里另一组键占的 {左, 右}）。 */
    private static boolean occupied(List<float[]> others, float left, float right) {
        for (float[] o : others) if (o[0] < right - 1e-4f && o[1] > left + 1e-4f) return true;
        return false;
    }

    /** 键 k（只看它第一行那段）正好是一个声明过的空格子（Mac 模式笔记本 F 行 Ins 那格的 ⋯ 槽）。 */
    private boolean blankAt(Key k) {
        float[] s = k.span(k.row);
        for (float[] b : blanks) if ((int) b[0] == k.row && Math.abs(b[1] - s[0]) < 1e-4f && Math.abs(b[2] - s[1]) < 1e-4f) return true;
        return false;
    }

    /** 第 r 行 [left, right] 这条缝里有没有声明过的空格子。 */
    private boolean blankBetween(int r, float left, float right) {
        for (float[] b : blanks) if ((int) b[0] == r && b[1] >= left - 1e-4f && b[2] <= right + 1e-4f) return true;
        return false;
    }

    /** 小红点那一点在 u 里的横坐标：G 的右边（= G|H 那条缝 = B 的中线，实体键盘最下一排右错半个键，B 正好骑在 G|H 下面）。 */
    float stickU() {
        Key g = find(0x0A);
        return g.x + g.w;
    }
}
