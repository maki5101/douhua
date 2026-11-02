#!/usr/bin/env python3
"""实体键盘键帽数据的生成器：第 1A 批美式、德语，第 2 批（2026-11-01）补齐拉丁字母布局（catalog.json），× Mac / Windows / Linux / 安卓四家主机。

输入：
  sources.json   钉死的原始数据：CLDR release-43 的 keyboards/osx、keyboards/windows（按 sha256），
                 AOSP 的 KCM + Generic.kl（GitHub 镜像，按 git blob + sha256），本机的 xkeyboard-config（核对版本号）+ libxkbcommon。
  catalog.json   手写：生成哪些布局、每家用哪个源文件（mac / android 可以是 null = 那家没有这个布局）、起名字用的语言 / 区分词。
  mac_names.json 手写：CLDR 里的 macOS 旧名 → 现名（凭记忆，要用户在 iPad 上核对）。
输出（res/raw/，进 APK；App 读 kbd_win、kbd_mac 和 kbd_catalog，kbd_linux / kbd_android 只给扫描工具用）：
  kbd_mac.txt、kbd_win.txt、kbd_linux.txt、kbd_android.txt   每家一份：每个布局一段，每个键 4 层的字（普通 / Shift / 第三层 / 第三层+Shift）
  kbd_catalog.txt                                            布局清单（起名字的语言 / 区分词、形状、有数据的模式、小键盘、各家主机里叫什么）
  格式见 tools/kbd/README.md「数据格式」，读写在 kbdtable.py。

原始文件不进仓库：缓存在 .cache/kbd/（不进 git），每次都按 sha256 核对，对不上就失败；缓存里没有就下载（--offline 不下载、直接失败）。
xkb 读本机：/usr/share/pkgconfig/xkeyboard-config.pc 的版本号要等于 sources.json 钉的，不然失败（换版本先核对生成结果的差异再改 sources.json）。

用法：python3 tools/kbd/gen_layouts.py [--offline] [--out 目录]
     python3 tools/kbd/gen_layouts.py --selftest    不联网、不读原始数据：用 tools/kbd/testdata/ 的小样本测解析规则（tools/test.sh 跑）；
                                                    本机有 libxkbcommon 和 xkb 数据时再顺带测 xkb 取字，没有就跳过并说一声
"""
import base64
import ctypes
import hashlib
import json
import os
import re
import sys
import tempfile
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kbdtable as kt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


class GenError(Exception):
    pass


def load_json(name):
    with open(os.path.join(HERE, name), encoding='utf-8') as f:
        return json.load(f)


# ═══════════ 原始数据：缓存 + 下载 + 校验 ═══════════

def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git_blob_id(data):
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


def http_get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'douhua-kbd-gen'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def file_spec(src, rel):
    spec = src['files'][rel]
    return (spec, None) if isinstance(spec, str) else (spec['sha256'], spec.get('blob'))


def download(src, rel):
    """按 sources.json 的取法下载一个文件，返回内容（还没核 sha256）。"""
    if src['fetch'] == 'raw':
        url = src['base_url'] + rel
        print('  下载 %s' % url, file=sys.stderr)
        return http_get(url)
    if src['fetch'] == 'github-blob':
        _, blob = file_spec(src, rel)
        url = 'https://api.github.com/repos/%s/git/blobs/%s' % (src['repo'], blob)
        print('  下载 %s（%s）' % (url, rel), file=sys.stderr)
        data = base64.b64decode(json.loads(http_get(url))['content'])
        if git_blob_id(data) != blob:
            raise GenError('%s：下载到的内容和 git blob %s 对不上' % (rel, blob))
        return data
    raise GenError('不认识的取法 %s' % src['fetch'])


def source_file(sources, src_id, rel, offline):
    """原始文件的本地路径（缓存里没有就下载），sha256 每次都核。"""
    src = sources['sources'][src_id]
    if rel not in src['files']:
        raise GenError('catalog.json 用到的 %s/%s 不在 sources.json 里：先把它加进 sources.json（带 sha256）' % (src_id, rel))
    want, _ = file_spec(src, rel)
    path = os.path.join(kt.ROOT, sources['cache_dir'], src_id, rel)
    if not os.path.exists(path):
        if offline:
            raise GenError('缓存里没有 %s（--offline 不下载）。去掉 --offline 让脚本下载，或者手动放进 %s' % (rel, path))
        try:
            data = download(src, rel)
        except OSError as e:
            raise GenError('下载 %s/%s 失败：%s（要联网；GitHub 的接口不登录每小时限 60 次）' % (src_id, rel, e))
        if sha256(data) != want:
            raise GenError('下载到的 %s/%s 和 sources.json 钉的 sha256 不一样（%s）。上游变了的话：先核对生成结果的差异，'
                           '再改 sources.json' % (src_id, rel, sha256(data)))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'wb') as f:
            f.write(data)
    with open(path, 'rb') as f:
        got = sha256(f.read())
    if got != want:
        raise GenError('缓存里的 %s 和 sources.json 钉的 sha256 不一样（%s）：删掉它重新下载' % (path, got))
    return path


# ═══════════ 一格的规范化 ═══════════

def make_cell(text, dead=False):
    """(字, 死键) 或 None。控制字符（Ctrl 层的 \\u001C 之类）不要；单个组合附加符号的死键换成带间距的写法。"""
    if not text:
        return None
    if all(unicodedata.category(c) == 'Cc' for c in text):
        return None
    if dead and len(text) == 1 and text in kt.COMBINING_TO_SPACING:
        text = kt.COMBINING_TO_SPACING[text]
    return (text, dead)


# ═══════════ CLDR（Mac、Windows） ═══════════

# 四种修饰键状态（左手那一侧）。Mac：Option；Windows：AltGr = 右 Alt（CLDR 写成 altR+caps? ctrl+alt+caps?）
CLDR_STATES = {
    'osx': [set(), {'shiftL'}, {'optL'}, {'optL', 'shiftL'}],
    'windows': [set(), {'shiftL'}, {'altR'}, {'altR', 'shiftL'}],
}
# _platform.xml 里这几个键码必须是这些位置名，不然下面的位置换算就错了（osx 的键码是 Mac 虚拟键码，windows 的是扫描码）
PLATFORM_EXPECT = {
    'osx': {'50': 'E00', '10': 'B00', '42': 'D13'},        # kVK_ANSI_Grave / kVK_ISO_Section / kVK_ANSI_Backslash
    'windows': {'41': 'E00', '86': 'B00', '43': 'C12'},     # 扫描码 0x29 / 0x56 / 0x2B
}


def check_platform_xml(path, platform):
    hw = {m.get('keycode'): m.get('iso') for m in ET.parse(path).getroot().iter('map')}
    for kc, iso in PLATFORM_EXPECT[platform].items():
        if hw.get(kc) != iso:
            raise GenError('%s：键码 %s 应该是 %s，实际是 %s——位置换算的前提不成立' % (path, kc, iso, hw.get(kc)))


def ldml_covers(tok, mod):
    """LDML 修饰键名：不带 L / R 的（opt、shift、alt…）左右都算。"""
    return mod == tok or mod in (tok + 'L', tok + 'R')


def ldml_matches(combo, state):
    """一个组合（'optL+shiftL+caps?'）匹配一个修饰键状态吗：不带 ? 的都得按着，状态里按着的每个都得被组合里的某一项盖住。"""
    toks = [t for t in combo.split('+') if t]
    req = [t for t in toks if not t.endswith('?')]
    opt = [t[:-1] for t in toks if t.endswith('?')]
    if any(not any(ldml_covers(t, m) for m in state) for t in req):
        return False
    return all(any(ldml_covers(t, m) for t in req + opt) for m in state)


def keymap_layers(modifiers, states):
    combos = (modifiers or '').split()
    return [li for li, st in enumerate(states)
            if (not combos and not st) or any(ldml_matches(c, st) for c in combos)]


def cldr_decode(s):
    return re.sub(r'\\u\{([0-9A-Fa-f]+)\}', lambda m: chr(int(m.group(1), 16)), s)


def parse_cldr(path, platform):
    """CLDR v2 键盘文件 → (名字, {位置名: [4 格]})。同一状态多个 keyMap 都匹配时先写的那个算；
    死键 = 输出是某条 transform 的开头、又没标 transform="no"。"""
    root = ET.parse(path).getroot()
    name_el = root.find('names/name')
    name = name_el.get('value') if name_el is not None else ''
    starts = set()
    for t in root.iter('transform'):
        f = cldr_decode(t.get('from') or '')
        if f:
            starts.add(f[0])
    cells = {}
    taken = set()   # (位置, 层)：先匹配的 keyMap 写过就不再改（哪怕它给的是被丢掉的控制字符）
    for km in root.iter('keyMap'):
        layers = keymap_layers(km.get('modifiers'), CLDR_STATES[platform])
        for m in km.iter('map'):
            iso = m.get('iso')
            to = cldr_decode(m.get('to') or '')
            dead = len(to) == 1 and to in starts and m.get('transform') != 'no'
            row = cells.setdefault(iso, [None] * 4)
            for li in layers:
                if (iso, li) not in taken:
                    taken.add((iso, li))
                    row[li] = make_cell(to, dead)
    return name, cells


def cldr_dead_keys(path, cells):
    """CLDR 的 transforms（主机自己的死键组合表）→ [(死键的字, 后面那个字, 拼出来的字)]，照文件里的先后（同一对写了两次的，先写的算）。
    cells 是同一个文件 parse_cldr 出来的那一份：只收"这张表里标了死键的那个字 + 一个字 → 一个字"的（死键的字照 make_cell 规范成带间距的写法，
    同键帽字）。多个字的死键（Mac 克罗地亚 / 斯洛文尼亚的"不换行空格 + 组合附加符号"，parse_cldr 不把它标成死键）、拼出来不止一个字的不收。
    App 的"打出来"（贴字面板，TypeOut）照它反查 é 这类字：主机怎么组合以它为准，不拿 Unicode 分解去猜
    （猜的话德语 Windows 的 ´ + c 当成 ć，主机其实打出"´c"；波兰程序员的 ~ + a 是 ą 不是 ã）。"""
    root = ET.parse(path).getroot()
    dead = {c[0] for row in cells.values() for c in row if c and c[1]}
    out = []
    seen = set()
    for t in root.iter('transform'):
        f = cldr_decode(t.get('from') or '')
        to = cldr_decode(t.get('to') or '')
        if len(f) != 2 or len(to) != 1:
            continue
        d = make_cell(f[0], True)
        if d is None or d[0] not in dead or (d[0], f[1]) in seen:
            continue
        seen.add((d[0], f[1]))
        out.append((d[0], f[1], to))
    return out


def to_hid(cells, rename):
    t = {}
    for iso, row in cells.items():
        u = kt.ISO_TO_HID.get(rename.get(iso, iso))
        if u is not None:
            t[u] = list(row)
    return t


def mac_tables(cells):
    """CLDR osx 的位置名是按 Mac 虚拟键码起的（E00 = 键码 50、B00 = 键码 10、D13 = 键码 42）。
    macOS 当 ANSI 键盘：HID 0x35 → 键码 50、0x64 → 键码 10，不对调；当 ISO 键盘：0x35 ↔ 0x64 对调（方案 §7.3，Mozilla / QEMU 的注释）。
    返回 (ANSI 型, ISO 型) 两张 {用法码: [4 格]}。"""
    ansi = to_hid(cells, {'D13': 'BKSL'})
    iso = to_hid(cells, {'D13': 'BKSL', 'E00': 'B00', 'B00': 'E00'})
    return ansi, iso


def win_table(cells):
    """CLDR windows 的位置名按扫描码：C12 = 0x2B（ANSI 回车上面 / ISO 回车左边，HID 0x31 和 0x32 都转成它）。"""
    return to_hid(cells, {'C12': 'BKSL'})


# ═══════════ xkb（Linux） ═══════════

# dead_* 键符 → 规范写法（带间距的附加符号；没有带间距形式的留组合附加符号）。不认识的直接失败，免得悄悄画错。
XKB_DEAD = {'grave': '`', 'acute': '´', 'circumflex': '^', 'tilde': '~', 'diaeresis': '¨', 'abovering': '˚',
            'cedilla': '¸', 'caron': 'ˇ', 'macron': '¯', 'breve': '˘', 'abovedot': '˙', 'doubleacute': '˝',
            'ogonek': '˛', 'belowdot': '\u0323', 'belowmacron': '\u0331',
            # 第 2 批加的（没有带间距形式的留组合附加符号）：斜线（ø 那种）、下逗号（ș）、钩（ả）、角（ơ）、货币符号（AltGr+Shift 的 ¤ 死键）、希腊字母死键
            'stroke': '\u0335', 'belowcomma': '\u0326', 'hook': '\u0309', 'horn': '\u031B', 'currency': '¤', 'greek': 'µ'}


class Names(ctypes.Structure):
    _fields_ = [(n, ctypes.c_char_p) for n in ('rules', 'model', 'layout', 'variant', 'options')]


class Xkb:
    """用本机的 libxkbcommon（ctypes）编译布局、按修饰键状态取字：include 链、键类型都由库处理。"""

    def __init__(self, lib='libxkbcommon.so.0'):
        try:
            L = ctypes.CDLL(lib)
        except OSError as e:
            raise GenError('加载不了 %s（%s）：装 libxkbcommon0（Debian / Ubuntu）' % (lib, e))
        vp, u32, ci = ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int
        for fn, res, args in (
                ('xkb_context_new', vp, [ci]),
                ('xkb_keymap_new_from_names', vp, [vp, ctypes.POINTER(Names), ci]),
                ('xkb_keymap_new_from_string', vp, [vp, ctypes.c_char_p, ci, ci]),
                ('xkb_keymap_mod_get_index', u32, [vp, ctypes.c_char_p]),
                ('xkb_keymap_unref', None, [vp]),
                ('xkb_state_new', vp, [vp]),
                ('xkb_state_unref', None, [vp]),
                ('xkb_state_update_mask', ci, [vp, u32, u32, u32, u32, u32, u32]),
                ('xkb_state_update_key', ci, [vp, u32, ci]),
                ('xkb_state_key_get_one_sym', u32, [vp, u32]),
                ('xkb_state_key_get_layout', u32, [vp, u32]),
                ('xkb_state_key_get_level', u32, [vp, u32, u32]),
                ('xkb_keysym_to_utf32', u32, [u32]),
                ('xkb_keysym_get_name', ci, [u32, ctypes.c_char_p, ctypes.c_size_t])):
            f = getattr(L, fn)
            f.restype = res
            f.argtypes = args
        self.L = L
        self.ctx = L.xkb_context_new(0)
        if not self.ctx:
            raise GenError('xkb_context_new 失败')

    def keymap(self, layout, variant=''):
        names = Names(b'evdev', b'pc105', layout.encode(), variant.encode(), b'')
        km = self.L.xkb_keymap_new_from_names(self.ctx, ctypes.byref(names), 0)
        if not km:
            raise GenError('xkb 编译不了布局 %s(%s)' % (layout, variant))
        return km

    def _mask(self, km, name):
        idx = self.L.xkb_keymap_mod_get_index(km, name.encode())
        if idx == 0xFFFFFFFF:
            raise GenError('xkb 布局里没有修饰键 %s' % name)
        return 1 << idx

    def _sym_name(self, sym):
        buf = ctypes.create_string_buffer(64)
        self.L.xkb_keysym_get_name(sym, buf, 64)
        return buf.value.decode()

    def _cell(self, sym):
        name = self._sym_name(sym)
        if name.startswith('dead_'):
            if name[5:] not in XKB_DEAD:
                raise GenError('不认识的死键 keysym %s：在 gen_layouts.py 的 XKB_DEAD 里补上它的写法' % name)
            return (XKB_DEAD[name[5:]], True)
        c = self.L.xkb_keysym_to_utf32(sym)
        return make_cell(chr(c)) if c else None

    def table(self, km, usages):
        """{用法码: [4 格]}。四种状态照扫描时真按的键来：左 Shift（evdev 42）、右 Alt（evdev 100），由布局自己决定右 Alt 是什么
        （德语是 ISO_Level3_Shift，美式只是 Alt_R）。第三层只在键真的换到第 3 级以上时才算有字：美式布局按着右 Alt
        字母键还在第 1 级，那格算没有字（直接设 Mod5 不行：pc 里另有一个 LVL3 键，美式的 <> 键照样有第 3 级）。"""
        lfsh, ralt = 42 + 8, 100 + 8
        out = {}
        for li, held in enumerate(((), (lfsh,), (ralt,), (ralt, lfsh))):
            st = self.L.xkb_state_new(km)
            for kc in held:
                self.L.xkb_state_update_key(st, kc, 1)   # XKB_KEY_DOWN
            for u in usages:
                kc = kt.HID_TO_EVDEV[u] + 8
                sym = self.L.xkb_state_key_get_one_sym(st, kc)
                cell = None
                if sym:
                    level = self.L.xkb_state_key_get_level(st, kc, self.L.xkb_state_key_get_layout(st, kc))
                    if li < 2 or level >= 2:
                        cell = self._cell(sym)
                out.setdefault(u, [None] * 4)[li] = cell
            self.L.xkb_state_unref(st)
        return out

    def keypad(self, km, usage):
        """小键盘上这个键（NumLock 开着）出什么字。"""
        st = self.L.xkb_state_new(km)
        self.L.xkb_state_update_mask(st, 0, 0, self._mask(km, 'Mod2'), 0, 0, 0)
        sym = self.L.xkb_state_key_get_one_sym(st, kt.HID_TO_EVDEV[usage] + 8)
        self.L.xkb_state_unref(st)
        c = self.L.xkb_keysym_to_utf32(sym) if sym else 0
        return chr(c) if c else ''

    def kpdl(self, km):
        """小键盘小数点（0x63）出什么字。"""
        return self.keypad(km, kt.KEYPAD_DECIMAL)

    def kpco(self, km):
        """ABNT2 小键盘多出来的那个键（HID 0x85 → evdev KEY_KPCOMMA → xkb <KPPT>）出什么字。"""
        return self.keypad(km, kt.KEYPAD_COMMA)


def xkb_version(pc_path):
    try:
        with open(pc_path, encoding='utf-8') as f:
            for line in f:
                if line.startswith('Version:'):
                    return line.split(':', 1)[1].strip()
    except OSError:
        pass
    return None


def xkb_description(rules_path, layout, variant):
    """本机 evdev.xml 里这个布局 / 变体的描述（就是 Linux 设置里显示的英文名）。"""
    root = ET.parse(rules_path).getroot()
    for lay in root.iter('layout'):
        ci = lay.find('configItem')
        if ci is None or ci.findtext('name') != layout:
            continue
        if not variant:
            return ci.findtext('description') or layout
        for v in lay.iter('variant'):
            vci = v.find('configItem')
            if vci is not None and vci.findtext('name') == variant:
                return vci.findtext('description') or variant
    return layout + ('(%s)' % variant if variant else '')


# ═══════════ AOSP KCM（安卓） ═══════════

# AMETA_* 位（android/input.h）
M_SHIFT, M_ALT, M_SYM, M_FN = 0x1, 0x2, 0x4, 0x8
M_ALT_L, M_ALT_R, M_SHIFT_L, M_SHIFT_R = 0x10, 0x20, 0x40, 0x80
M_CTRL, M_CTRL_L, M_CTRL_R = 0x1000, 0x2000, 0x4000
M_META, M_META_L, M_META_R = 0x10000, 0x20000, 0x40000
M_CAPS, M_NUM, M_SCROLL = 0x100000, 0x200000, 0x400000
KCM_META = {'base': 0, 'shift': M_SHIFT, 'lshift': M_SHIFT_L, 'rshift': M_SHIFT_R, 'alt': M_ALT, 'lalt': M_ALT_L,
            'ralt': M_ALT_R, 'ctrl': M_CTRL, 'lctrl': M_CTRL_L, 'rctrl': M_CTRL_R, 'meta': M_META, 'lmeta': M_META_L,
            'rmeta': M_META_R, 'sym': M_SYM, 'fn': M_FN, 'capslock': M_CAPS, 'numlock': M_NUM, 'scrolllock': M_SCROLL}
EXACT_META = M_CTRL | M_CTRL_L | M_CTRL_R | M_ALT | M_ALT_L | M_ALT_R | M_META | M_META_L | M_META_R
# 四种状态时事件带的 meta：左 Shift、右 Alt（AltGr）
KCM_EVENT_STATES = [0, M_SHIFT | M_SHIFT_L, M_ALT | M_ALT_R, M_ALT | M_ALT_R | M_SHIFT | M_SHIFT_L]


def kcm_matches(event, behavior):
    """照 AOSP KeyCharacterMap::matchesMetaState：行为要求的 meta 都得有；事件里多出来的 Ctrl / Alt / Meta 必须被行为认领
    （行为写 alt 就认左右两个，写 ralt 就认那个总的 ALT_ON）。"""
    if event & behavior != behavior:
        return False
    unmatched = event & ~behavior & EXACT_META
    for on, left, right in ((M_CTRL, M_CTRL_L, M_CTRL_R), (M_ALT, M_ALT_L, M_ALT_R), (M_META, M_META_L, M_META_R)):
        if behavior & on:
            unmatched &= ~(left | right)
        elif behavior & (left | right):
            unmatched &= ~on
    return unmatched == 0


def parse_kl(path):
    """Generic.kl：扫描码（= Linux evdev 键码）→ 键名。'key usage …' 行不要。"""
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = re.match(r'key\s+(\d+)\s+(\w+)', line)
            if m:
                out[int(m.group(1))] = m.group(2)
    return out


_KCM_CHAR = re.compile(r"'((?:\\u[0-9a-fA-F]{4}|\\.|[^'\\]))'")


def kcm_char(lit):
    if lit.startswith('\\u'):
        return chr(int(lit[2:], 16))
    if lit.startswith('\\'):
        return {'n': '\n', 't': '\t', '0': '\0'}.get(lit[1], lit[1])
    return lit


def strip_comment(line):
    """去掉 # 注释（引号里的 # 是字，比如 '#' 那个键）。"""
    quoted = False
    i = 0
    while i < len(line):
        c = line[i]
        if c == '\\' and quoted:
            i += 2
            continue
        if c == "'":
            quoted = not quoted
        elif c == '#' and not quoted:
            return line[:i].strip()
        i += 1
    return line.strip()


def parse_kcm(path):
    """→ (map key 覆盖 {扫描码: 键名}, {键名: [(meta, 字或 None), …按文件里的顺序]})。label / number 不要；none / fallback 当没有字。"""
    maps, keys = {}, {}
    cur = None
    with open(path, encoding='utf-8') as f:
        for raw in f:
            line = strip_comment(raw)
            if not line:
                continue
            m = re.match(r'map key (\d+) (\w+)', line)
            if m:
                maps[int(m.group(1))] = m.group(2)
                continue
            m = re.match(r'key (\w+) \{', line)
            if m:
                cur = keys.setdefault(m.group(1), [])
                continue
            if line.startswith('}'):
                cur = None
                continue
            if cur is None:
                continue
            m = re.match(r'([\w+, ]+):\s*(.+)$', line)
            if not m:
                continue
            value = m.group(2).strip()
            cm = _KCM_CHAR.match(value)
            ch = kcm_char(cm.group(1)) if cm else None
            for group in m.group(1).split(','):
                toks = [t.strip() for t in group.split('+')]
                if toks[0] in ('label', 'number'):
                    break
                meta = 0
                for t in toks:
                    if t not in KCM_META:
                        raise GenError('%s：不认识的修饰键 %s' % (path, t))
                    meta |= KCM_META[t]
                cur.append((meta, ch))
    return maps, keys


def kcm_lookup(behaviors, event):
    """AOSP 解析时每个新行为插到最前面（KeyCharacterMap.cpp 的 push_front，凭记忆），所以后写的先比。"""
    for meta, ch in reversed(behaviors):
        if kcm_matches(event, meta):
            return ch
    return None


def android_table(kcm_path, kl_path):
    """{用法码: [4 格]}：HID → evdev 键码 → 键名（KCM 的 map key 优先于 Generic.kl）→ 这个键的行为。
    KCM 用单个组合附加符号（U+0300…）表示死键。KCM 没写的键（叠加布局里没有）这里算没有字。"""
    kl = parse_kl(kl_path)
    maps, keys = parse_kcm(kcm_path)
    out = {}
    for u in kt.TABLE_USAGES:
        scan = kt.HID_TO_EVDEV[u]
        beh = keys.get(maps.get(scan) or kl.get(scan))
        if not beh:
            continue
        row = []
        for ev in KCM_EVENT_STATES:
            ch = kcm_lookup(beh, ev)
            dead = ch is not None and len(ch) == 1 and unicodedata.category(ch) == 'Mn'
            row.append(make_cell(ch, dead))
        out[u] = row
    return out


def aosp_copyrights(paths):
    lines = []
    for p in paths:
        with open(p, encoding='utf-8') as f:
            for line in f:
                m = re.match(r'#\s*(Copyright .*)', line)
                if m and m.group(1).strip() not in lines:
                    lines.append(m.group(1).strip())
    return lines


# ═══════════ 生成 ═══════════

LAYER_COLUMNS = {'mac': '普通、Shift、Option、Option+Shift', 'win': '普通、Shift、AltGr、AltGr+Shift',
                 'linux': '普通、Shift、AltGr（第三级）、AltGr+Shift（第四级）', 'android': '普通、Shift、右 Alt、右 Alt+Shift'}


def header(platform, desc, extra):
    return ['kbd_%s.txt：豆花儿实体键盘的键帽字，主机是%s。自动生成，勿手改。' % (platform, desc),
            '生成：python3 tools/kbd/gen_layouts.py（数据源、版本、校验见 tools/kbd/sources.json；格式见 tools/kbd/README.md「数据格式」）。',
            '每行：HID 用法码（十六进制，PC 惯例的位置）、%s；空 = 没有字，\\* = 死键，\\\\ = 反斜杠，\\uXXXX = 看不见的字。' % LAYER_COLUMNS[platform],
            ] + extra + ['', '!platform ' + platform]


def dedupe(sections):
    """完全一样的表合并成别名（后出现的指向先出现的）。"""
    aliases = {}
    seen = []
    for name, t in sections.items():
        for prev in seen:
            if sections[prev] == t:
                aliases[name] = prev
                break
        else:
            seen.append(name)
    return aliases


def mac_name_now(names, cldr_name):
    """CLDR 里的 macOS 旧名 → 现名（mac_names.json；没写的照原样）。"""
    return names.get(cldr_name, cldr_name)


def check_catalog(catalog):
    """catalog.json 自己的规矩：id 不重复、能当段名；形状、模式认得；默认形状在能选的里面；Windows、Mac 至少有一种（只有 Mac 有的那一套 win 写 null）；
    手写的 Windows 小键盘小数点（kpdl_win）只能是 . 或 ,，而且要有 Windows 的数据。"""
    seen = set()
    for lay in catalog['layouts']:
        lid = lay.get('id', '')
        if not re.match(r'^[\w-]+$', lid) or lid in seen:
            raise GenError('catalog.json：id %r 重复或者不能当段名（只许字母、数字、_、-）' % lid)
        seen.add(lid)
        for key in ('locale', 'shape', 'shapes', 'linux'):
            if not lay.get(key):
                raise GenError('catalog.json：%s 缺 %s' % (lid, key))
        if not lay.get('win') and not lay.get('mac'):
            raise GenError('catalog.json：%s 的 win、mac 都没有（至少要有一种模式的数据）' % lid)
        if 'kpdl_win' in lay and (lay['kpdl_win'] not in ('.', ',') or not lay.get('win')):
            raise GenError('catalog.json：%s 的 kpdl_win %r 不对（只能是 . 或 ,，而且要有 Windows 的数据）' % (lid, lay['kpdl_win']))
        if any(sh not in SHAPES for sh in lay['shapes']) or lay['shape'] not in lay['shapes']:
            raise GenError('catalog.json：%s 的形状 %s / %s 不对（认得的：%s）' % (lid, lay['shape'], lay['shapes'], '、'.join(SHAPES)))


SHAPES = ('ansi', 'iso', 'abnt2')


def generate(out_dir, offline):
    sources = load_json('sources.json')
    catalog = load_json('catalog.json')
    mac_names = load_json('mac_names.json')['names']
    check_catalog(catalog)
    S = sources['sources']
    used = {'cldr': set(), 'aosp': set()}

    def src(src_id, rel):
        used[src_id].add(rel)
        return source_file(sources, src_id, rel, offline)

    check_platform_xml(src('cldr', 'osx/_platform.xml'), 'osx')
    check_platform_xml(src('cldr', 'windows/_platform.xml'), 'windows')

    xk = S['xkb']
    have = xkb_version(xk['pkgconfig'])
    if have != xk['version']:
        raise GenError('本机 xkeyboard-config 是 %s（%s），sources.json 钉的是 %s。换版本要先核对生成结果的差异，再改 sources.json 的 version'
                       % (have, xk['pkgconfig'], xk['version']))
    xkb = Xkb(xk['library'])
    kl_path = src('aosp', 'data/keyboards/Generic.kl')

    mac, win, linux, android, rows = {}, {}, {}, {}, []
    dead = {'mac': {}, 'win': {}}   # 死键组合（kbd_dead_<平台>.txt）：{布局: [(死键的字, 后面那个字, 拼出来的字)]}
    aosp_files = [kl_path]
    for lay in catalog['layouts']:
        lid = lay['id']
        modes = []
        win_name = ''
        if lay.get('win'):
            path = src('cldr', lay['win'])
            win_name, cells = parse_cldr(path, 'windows')
            win[lid] = win_table(cells)
            dead['win'][lid] = cldr_dead_keys(path, cells)
            modes.append('win')
        mac_cldr = mac_now = ''
        if lay.get('mac'):
            path = src('cldr', lay['mac'])
            mac_cldr, cells = parse_cldr(path, 'osx')
            mac[lid], mac[lid + '.iso'] = mac_tables(cells)
            dead['mac'][lid] = cldr_dead_keys(path, cells)
            mac_now = lay.get('name_mac') or mac_name_now(mac_names, mac_cldr)
            modes.append('mac')
        km = xkb.keymap(lay['linux']['layout'], lay['linux']['variant'])
        linux[lid] = xkb.table(km, kt.TABLE_USAGES)
        kpdl = xkb.kpdl(km)
        # Windows 模式的小键盘小数点：catalog.json 手写了（微软布局表核过和 xkb 不一样的）就用它，不然照 xkb；没有 Windows 的数据空着
        kpdl_win = (lay.get('kpdl_win') or kpdl) if lay.get('win') else ''
        kpco = xkb.kpco(km) if 'abnt2' in lay['shapes'] else ''
        xkb.L.xkb_keymap_unref(km)
        if lay.get('android'):
            kcm_path = src('aosp', lay['android'])
            if kcm_path not in aosp_files:
                aosp_files.append(kcm_path)
            android[lid] = android_table(kcm_path, kl_path)
        row = {'id': lid, 'locale': lay['locale'], 'variant': lay.get('variant', ''), 'shape': lay['shape'],
               'shapes': ','.join(lay['shapes']), 'modes': ','.join(modes), 'kpdl': kpdl, 'kpdl_win': kpdl_win, 'kpco': kpco,
               'caps_mac': lay.get('caps_mac', '') if lay.get('mac') else '', 'name_mac': mac_now, 'name_mac_cldr': mac_cldr,
               'name_win': (lay.get('name_win') or win_name) if lay.get('win') else '',
               'name_linux': xkb_description(xk['rules'], lay['linux']['layout'], lay['linux']['variant']),
               'name_android': lay.get('android_name', '') if lay.get('android') else ''}
        rows.append([row[c] for c in kt.CATALOG_COLUMNS])

    def file_lines(src_id, prefix=''):
        """'#!file 源 路径 sha256'：这份输出用到的每个原始文件（check_tables.py 拿它和 sources.json 核对）。"""
        return ['!file %s %s %s' % (src_id, rel, file_spec(S[src_id], rel)[0]) for rel in sorted(used[src_id])
                if rel.startswith(prefix)]

    cldr_notice = ['数据：%s %s（%s）。' % (S['cldr']['name'], S['cldr']['version'], S['cldr']['url']),
                   S['cldr']['copyright'], '许可：' + S['cldr']['license']]
    os.makedirs(out_dir, exist_ok=True)

    kt.write_table_file(os.path.join(out_dir, 'kbd_mac.txt'), header('mac', ' macOS / iPadOS（iPadOS 假设和同名 macOS 布局一样，待实测）', [
        'Mac 每个布局两段：<id> = macOS 把豆花当 ANSI 键盘（不对调）；<id>.iso = 当 ISO 键盘（对调 0x35 / 0x64，见方案 §7.3）。',
        'CLDR osx 的数据是 macOS 10.9（2013 年）的，现名和个别字可能变过；CLDR 里没有的布局（%s）这里没有那一段。'
        % '、'.join(l['id'] for l in catalog['layouts'] if not l.get('mac'))] + cldr_notice) + [
        '!source cldr ' + S['cldr']['version']] + file_lines('cldr', 'osx/'), mac, dedupe(mac))

    kt.write_table_file(os.path.join(out_dir, 'kbd_win.txt'), header('win', ' Windows（CLDR 的 Windows 10 数据）', [
        'Windows 没有的布局（只有 Mac 有的那一套：%s）这里没有那一段。' % '、'.join(l['id'] for l in catalog['layouts'] if not l.get('win'))]
        + cldr_notice) + [
        '!source cldr ' + S['cldr']['version']] + file_lines('cldr', 'windows/'), win, dedupe(win))

    for p, cldr_dir, desc in (('mac', 'osx/', ' macOS / iPadOS'), ('win', 'windows/', ' Windows')):
        kt.write_dead_file(kt.dead_path(p, out_dir), [
            'kbd_dead_%s.txt：豆花儿"打出来"（贴字面板）用的死键组合，主机是%s（CLDR transforms）。自动生成，勿手改。' % (p, desc),
            '生成：python3 tools/kbd/gen_layouts.py（数据源、版本、校验见 tools/kbd/sources.json；格式见 tools/kbd/README.md「数据格式」）。',
            '每个布局一段（Mac 不分 .iso），每行：死键的字（同 kbd_%s.txt 里标 \\* 的那一格）、后面那个字、主机拼出来的字；'
            '只收"一个死键 + 一个字 → 一个字"的，\\u0020 = 空格。' % p,
            '没写的组合主机拼不出来（多半是把死键的字和后面那个字一起打出来），"打出来"不用它们。',
        ] + cldr_notice + ['', '!platform ' + p, '!source cldr ' + S['cldr']['version']] + file_lines('cldr', cldr_dir),
            dead[p], dedupe(dead[p]))

    kt.write_table_file(os.path.join(out_dir, 'kbd_linux.txt'), header('linux', ' Linux（xkb，model pc105）', [
        '数据：%s %s（%s），用本机 libxkbcommon 编译取字。' % (S['xkb']['name'], S['xkb']['version'], S['xkb']['url']),
        S['xkb']['copyright'], '许可：' + S['xkb']['license']]) + [
        '!source xkb ' + S['xkb']['version']], linux, dedupe(linux))

    kt.write_table_file(os.path.join(out_dir, 'kbd_android.txt'), header('android', '安卓（AOSP 的外接键盘布局；三星改没改过不知道）', [
        '数据：%s（%s），%s。AOSP 没有的布局（%s）这里没有那一段。' % (S['aosp']['name'], S['aosp']['url'], S['aosp']['version'],
                                                         '、'.join(l['id'] for l in catalog['layouts'] if not l.get('android')))]
        + aosp_copyrights(aosp_files) + ['许可：' + S['aosp']['license']]) + [
        '!source aosp ' + S['aosp']['repo']] + file_lines('aosp'), android, dedupe(android))

    cat = ['kbd_catalog.txt：豆花儿实体键盘的布局清单。自动生成（tools/kbd/gen_layouts.py，手写的部分在 tools/kbd/catalog.json、mac_names.json），勿手改。',
           '每行一个布局，栏见下面 #!columns（tab 分隔，读的时候按这一行认栏）。shape = 默认形状，shapes = 能选的形状，modes = 有数据的模式（win / mac），',
           'kpdl / kpco = 小键盘小数点（0x63）、ABNT2 小键盘多的那个键（0x85）出的字（NumLock 开着，取自 xkb；不是 ABNT2 的 kpco 空着；Mac 模式用这两个），',
           'kpdl_win = Windows 模式的小键盘小数点（catalog.json 手写的 kpdl_win，照微软布局表；没写的同 kpdl；没有 Windows 数据的空着），',
           'caps_mac = Mac 模式 Caps 键帽写什么（空 = 照旧），name_mac = macOS 现名（mac_names.json，凭记忆）、name_mac_cldr = CLDR 里的旧名，',
           'name_win / name_linux / name_android = 各家主机设置里叫什么；variant 小写的是 strings.xml 里 kbd_var_<它> 的词，别的照写。']
    with open(os.path.join(out_dir, 'kbd_catalog.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join('# ' + c for c in cat) + '\n')
        f.write('#!columns ' + ' '.join(kt.CATALOG_COLUMNS) + '\n')
        for r in rows:
            f.write('\t'.join(kt.escape_field(c) for c in r) + '\n')
    return {'mac': mac, 'win': win, 'linux': linux, 'android': android, 'catalog': rows, 'dead': dead}


# ═══════════ 自测（不联网、不读原始数据） ═══════════

def selftest():
    ok = [0]
    fails = []

    def check(cond, what):
        if cond:
            ok[0] += 1
        else:
            fails.append(what)

    td = os.path.join(HERE, 'testdata')
    # LDML 修饰键匹配
    check(ldml_matches('opt', {'optL'}), 'opt 盖住 optL')
    check(not ldml_matches('optR', {'optL'}), 'optR 不盖 optL')
    check(ldml_matches('optL+shiftL+caps?', {'optL', 'shiftL'}), '可有可无的 caps')
    check(not ldml_matches('optL+shiftL', {'optL'}), '缺必须的 shiftL')
    check(not ldml_matches('opt', {'optL', 'shiftL'}), '状态里多出 shiftL 没被盖住')
    check(ldml_matches('caps?', set()), '只有可有可无的项也匹配基本层')
    check(not ldml_matches('caps', set()), 'caps 必须按着')
    check(ldml_matches('altR+caps?', {'altR'}) and not ldml_matches('ctrl+alt+caps?', {'altR'}), 'Windows AltGr 两种写法')
    check(keymap_layers(None, CLDR_STATES['osx']) == [0], '没写 modifiers = 基本层')

    # CLDR osx：位置换算、死键、控制字符、先写的算
    name, cells = parse_cldr(os.path.join(td, 'mini-osx.xml'), 'osx')
    check(name == 'Mini Mac', 'osx 名字')
    ansi, iso = mac_tables(cells)
    check(ansi[0x35] == [('^', True), ('°', False), None, None], 'osx E00 → ANSI 型 0x35，^ 是死键，控制字符丢掉：%r' % ansi.get(0x35))
    check(ansi[0x64] == [('<', False), ('>', False), ('\uf8ff', False), None], 'osx B00 → ANSI 型 0x64：%r' % ansi.get(0x64))
    check(iso[0x35] == ansi[0x64] and iso[0x64] == ansi[0x35], 'ISO 型对调 0x35 / 0x64')
    check(ansi[0x31] == [('#', False), ("'", False), None, None], 'osx D13 → 0x31')
    check(ansi[0x14] == [('q', False), ('Q', False), ('«', False), ('»', False)], '⌥ 层取先写的、⌥⇧ 层完整匹配：%r' % ansi.get(0x14))
    check(ansi[0x2E] == [('^', False), None, None, None], 'transform="no" 不算死键')
    check(0x2C not in ansi, '空格不进表')
    check(cldr_dead_keys(os.path.join(td, 'mini-osx.xml'), cells) == [('^', ' ', '^'), ('^', 'e', 'ê')],
          '死键组合：照先后、空格那条也收：%r' % cldr_dead_keys(os.path.join(td, 'mini-osx.xml'), cells))
    check(cldr_dead_keys(os.path.join(td, 'mini-osx.xml'), {}) == [], '死键组合：表里没有那个死键就不收')

    # CLDR windows
    name, cells = parse_cldr(os.path.join(td, 'mini-windows.xml'), 'windows')
    w = win_table(cells)
    check(w[0x31] == [('#', False), None, None, None], 'windows C12 → 0x31，Ctrl 层的控制字符不要')
    check(w[0x14] == [('q', False), ('Q', False), ('@', False), None], 'windows AltGr 层、只按 Caps 不算：%r' % w.get(0x14))
    check(w[0x64] == [('<', False), None, ('|', False), None], 'windows B00 → 0x64')
    check(w[0x35] == [('^', True), None, None, None], 'windows 死键')
    check(cldr_dead_keys(os.path.join(td, 'mini-windows.xml'), cells) == [('^', 'a', 'â')], 'windows 死键组合')

    # AOSP KCM
    a = android_table(os.path.join(td, 'mini.kcm'), os.path.join(td, 'mini.kl'))
    check(a[0x14] == [('q', False), ('Q', False), ('@', False), ('@', False)], 'KCM：右 Alt + Shift 没单写就用 ralt：%r' % a.get(0x14))
    check(a[0x35] == [('^', True), ('°', False), None, None], 'KCM 组合附加符号 = 死键，换成带间距的：%r' % a.get(0x35))
    check(a[0x31] == [('#', False), ("'", False), None, None], 'KCM：ralt: none = 没有字')
    check(a[0x64] == [('<', False), ('>', False), ('|', False), ('—', False)], 'KCM：map key 覆盖 Generic.kl、shift+ralt 后写先比：%r' % a.get(0x64))
    check(a[0x2D] == [('\\', False), ('\\', False), ('x', False), ('x', False)], 'KCM：没写 shift 时 Shift 出 base；alt 和 ralt 后写的先比：%r' % a.get(0x2D))
    check(kcm_matches(M_ALT | M_ALT_R, KCM_META['alt']) and kcm_matches(M_ALT | M_ALT_R, KCM_META['ralt']), 'alt / ralt 都认右 Alt')
    check(not kcm_matches(M_ALT | M_ALT_R, 0), '基本层不认右 Alt')
    check(not kcm_matches(M_ALT | M_ALT_R, KCM_META['lalt']), 'lalt 不认右 Alt')

    # 第 2 批：KCM 的 U+030D / U+030E 死键、catalog.json 的规矩、Mac 现名
    check(make_cell('\u030d', True) == ("'", True) and make_cell('\u030e', True) == ('"', True), "KCM 的 U+030D / U+030E 死键规范成 ' \"")
    check(make_cell('\u030d', False) == ('\u030d', False), '不是死键的组合附加符号不换')
    check(mac_name_now({'Swedish - Pro': 'Swedish'}, 'Swedish - Pro') == 'Swedish' and mac_name_now({}, 'Dutch') == 'Dutch',
          'Mac 现名：写了就换，没写照旧')
    good = {'id': 'xx', 'locale': 'en-US', 'shape': 'ansi', 'shapes': ['ansi'], 'win': 'w', 'linux': {'layout': 'us'}}
    for bad, what in ((dict(good, id='x y'), 'id 有空格'), (dict(good, shape='iso'), '默认形状不在能选的里面'),
                      (dict(good, shapes=['ansi', 'jis']), '不认识的形状'), (dict(good, win=None), 'Windows、Mac 的数据都没有'),
                      (dict(good, kpdl_win=';'), 'kpdl_win 不是 . 或 ,'), (dict(good, win=None, mac='m', kpdl_win=','), '没有 Windows 数据却写了 kpdl_win')):
        try:
            check_catalog({'layouts': [bad]})
            check(False, 'catalog.json 的规矩：%s 应该失败' % what)
        except GenError:
            check(True, what)
    try:
        check_catalog({'layouts': [good, dict(good)]})
        check(False, 'catalog.json 的规矩：id 重复应该失败')
    except GenError:
        check(True, 'id 重复')
    check_catalog({'layouts': [good, dict(good, id='br', shape='abnt2', shapes=['abnt2'], mac=None),
                               dict(good, id='mo', win=None, mac='m'), dict(good, id='kp', kpdl_win=',')]})
    check(True, 'abnt2、没有 Mac 数据、只有 Mac 数据（win 写 null）、手写 kpdl_win 都认')

    # 文件格式往返
    with tempfile.TemporaryDirectory(prefix='kbd-selftest-') as d:   # 用完就删（tools/test.sh 每跑一次都会走这里）
        sections = {
            'xx': {0x04: [('a', False), ('A', False), ('\\', False), None],
                   0x35: [('´', True), None, ('\u00a0', False), ('\U000E0001', False)],
                   0x31: [None, None, ('\uf8ff', False), ('\u0331', True)]},
            'yy': {0x04: [('a', False), ('A', False), ('\\', False), None],
                   0x35: [('´', True), None, ('\u00a0', False), ('\U000E0001', False)],
                   0x31: [None, None, ('\uf8ff', False), ('\u0331', True)]},
            'zz': {0x04: [('b', False), None, None, None]},
        }
        path = os.path.join(d, 'kbd_test.txt')
        kt.write_table_file(path, ['测试', '!platform test', '!file cldr a/b.xml 00'], sections, dedupe(sections))
        meta, back = kt.read_table_file(path)
        check(back == sections, '格式往返：%r' % back)
        check(meta.get('platform') == [('test',)] and meta.get('file') == [('cldr', 'a/b.xml', '00')], '元数据行')
        with open(path, encoding='utf-8') as f:
            text = f.read()
        check('@yy = xx' in text, '完全一样的表写成别名')
        check('\\u00A0' in text and '\\uF8FF' in text and '\\uDB40\\uDC01' in text and '\\*´' in text, '看不见的字转义、死键标记')
        check('\\\\' in text and '\t\n' not in text, '反斜杠转义、行尾不留空格')
        # 死键组合的文件：三格、空格写  、别名
        dsec = {'xx': [('´', ' ', '´'), ('´', 'e', 'é'), ('¨', '\\', 'x')], 'yy': [('´', ' ', '´'), ('´', 'e', 'é'), ('¨', '\\', 'x')],
                'zz': [('~', 'a', 'ą')]}
        dpath = os.path.join(d, 'kbd_dead_test.txt')
        kt.write_dead_file(dpath, ['测试', '!platform test'], dsec, dedupe(dsec))
        dmeta, dback = kt.read_dead_file(dpath)
        check(dback == dsec and dmeta.get('platform') == [('test',)], '死键组合的格式往返：%r' % dback)
        with open(dpath, encoding='utf-8') as f:
            dtext = f.read()
        check('´\t\\u0020\t´' in dtext and '@yy = xx' in dtext and '\\\\' in dtext, '死键组合：空格转义、别名、反斜杠')
        with open(dpath, 'a', encoding='utf-8') as f:
            f.write('´\te\n')
        try:
            kt.read_dead_file(dpath)
            check(False, '死键组合少一格应该失败')
        except ValueError:
            check(True, '死键组合少一格')
        # 清单：按 #!columns 认栏；没有这一行、缺栏、栏数不对都失败
        cols = list(kt.CATALOG_COLUMNS)
        row = {c: '' for c in cols}
        row.update(id='xx', locale='en-US', shape='ansi', shapes='ansi,iso', modes='win', kpdl='.', kpdl_win=',')
        cat = os.path.join(d, 'kbd_catalog.txt')

        def write_cat(columns, values):
            with open(cat, 'w', encoding='utf-8') as f:
                f.write('# 注释\n' + ('#!columns ' + ' '.join(columns) + '\n' if columns else '') + '\t'.join(values) + '\n')
        rev = cols[::-1]
        write_cat(rev, [row[c] for c in rev])
        back = kt.read_catalog(d)
        check(back[0]['id'] == 'xx' and back[0]['shapes'] == ['ansi', 'iso'] and back[0]['modes'] == ['win'], '清单按 #!columns 认栏（顺序随便）')
        for columns, values, what in ((None, [row[c] for c in cols], '没有 #!columns'),
                                      (cols[:-1], [row[c] for c in cols[:-1]], '缺一栏'),
                                      (cols, [row[c] for c in cols[:-1]], '栏数不对')):
            write_cat(columns, values)
            try:
                kt.read_catalog(d)
                check(False, '清单%s应该失败' % what)
            except ValueError:
                check(True, what)

    # xkb：要本机的 libxkbcommon 和 xkb 数据，没有就跳过
    skipped = None
    try:
        xkb = Xkb()
        us, de = xkb.keymap('us'), xkb.keymap('de')
        tu, td_ = xkb.table(us, kt.TABLE_USAGES), xkb.table(de, kt.TABLE_USAGES)
        check(all(r[2] is None and r[3] is None for r in tu.values()), 'xkb us：右 Alt 只是 Alt，没有第三层')
        check(td_[0x14][2] == ('@', False), 'xkb de：AltGr+Q = @')
        check(td_[0x2E][0] == ('´', True), 'xkb de：´ 是死键')
        check(td_[0x1C][0] == ('z', False) and td_[0x1D][0] == ('y', False), 'xkb de：QWERTZ')
        check(xkb.kpdl(de) == ',' and xkb.kpdl(us) == '.', 'xkb 小键盘小数点：de 逗号、us 句点')
        br = xkb.keymap('br')
        check(xkb.kpdl(br) == ',' and xkb.kpco(br) == '.', 'xkb br（ABNT2）：小键盘小数点 0x63 出逗号、多出来的 0x85 出句点')
        check(xkb.table(br, kt.TABLE_USAGES)[0x87][:2] == [('/', False), ('?', False)], 'xkb br：右 Shift 左边那个键（0x87）是 / ?')
    except (GenError, OSError) as e:
        skipped = str(e)
    if fails:
        for f in fails:
            print('  不对：' + f, file=sys.stderr)
        print('键帽数据生成器自测：%d 项检查，%d 项失败' % (ok[0] + len(fails), len(fails)), file=sys.stderr)
        return False
    print('键帽数据生成器自测：%d 项检查，0 项失败%s' % (ok[0], '（xkb 部分跳过：%s）' % skipped if skipped else ''))
    return True


def main():
    args = sys.argv[1:]
    if args == ['--selftest']:
        sys.exit(0 if selftest() else 1)
    offline = '--offline' in args
    out = os.path.join(kt.ROOT, 'res', 'raw')
    if '--out' in args:
        out = args[args.index('--out') + 1]
    try:
        r = generate(out, offline)
    except GenError as e:
        sys.exit('生成失败：%s' % e)
    for p in ('mac', 'win', 'linux', 'android'):
        print('%s：%s' % (p, '、'.join('%s（%d 键）' % (k, len(v)) for k, v in r[p].items())))
    for p in ('mac', 'win'):
        print('%s 死键组合：%d 条' % (p, sum(len(v) for v in r['dead'][p].values())))
    print('已写到 %s（kbd_mac / win / linux / android / catalog.txt、kbd_dead_mac / win.txt）。改完跑 python3 tools/kbd/check_tables.py 核对抽查值。' % out)


if __name__ == '__main__':
    main()
