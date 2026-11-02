#!/usr/bin/env python3
"""实体键盘键帽数据的共用部分（只用标准库）：
  - HID 用法码 ↔ ISO 9995 位置名 / Linux evdev 键码 / 网页 KeyboardEvent.code 的对照表；
  - res/raw/kbd_*.txt 的读写（格式见 tools/kbd/README.md「数据格式」；死键组合 kbd_dead_<平台>.txt 另一种格式，见 write_dead_file）；
  - 主机实测扫描的计划（豆花按什么顺序发哪些键）。
gen_layouts.py（生成）、check_tables.py（离线核对）、scan_server.py（扫描）、compare.py（对照）都用它。

用法码一律按 PC 惯例的位置：0x35 = 1 左边那个键，0x64 = 左 Shift 和 Z 之间多出来的键（ISO），
0x31 = ANSI 回车上面 / ISO 回车左边那个键（豆花发 0x31，不发 0x32，理由见方案 §3 第 1 条）。
"""
import os
import re
import unicodedata

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
RAW_DIR = os.path.join(ROOT, 'res', 'raw')

PLATFORMS = ('mac', 'win', 'linux', 'android')
PLATFORM_NAMES = {'mac': 'macOS / iPadOS（CLDR osx）', 'win': 'Windows（CLDR windows）',
                  'linux': 'Linux（xkeyboard-config）', 'android': '安卓（AOSP KCM）'}
# 扫描时的主机系统 → 用哪家的数据（iPad 假设和同名 macOS 布局一样，扫描就是来核这一条的）
HOSTS = {'mac': 'mac', 'ipad': 'mac', 'win': 'win', 'linux': 'linux', 'android': 'android'}
HOST_NAMES = {'mac': 'Mac', 'ipad': 'iPad', 'win': 'Windows', 'linux': 'Linux', 'android': '安卓（桌面模式 = 手机自己）'}
LAYER_NAMES = {'mac': ('普通', '⇧', '⌥', '⌥⇧'), 'other': ('普通', 'Shift', 'AltGr', 'AltGr+Shift')}


def layer_names(platform):
    return LAYER_NAMES['mac' if platform == 'mac' else 'other']


# ═══════════ 位置对照 ═══════════

# ISO 9995 位置名 → HID 用法码。BKSL = 回车边上那个键（CLDR osx 叫 D13、windows 叫 C12，规范成同一个）。
ISO_TO_HID = {'E00': 0x35, 'B00': 0x64, 'BKSL': 0x31, 'E13': 0x89, 'B11': 0x87}
for _i, _u in enumerate([0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E], 1):
    ISO_TO_HID['E%02d' % _i] = _u
for _i, _u in enumerate([0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13, 0x2F, 0x30], 1):
    ISO_TO_HID['D%02d' % _i] = _u
for _i, _u in enumerate([0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x33, 0x34], 1):
    ISO_TO_HID['C%02d' % _i] = _u
for _i, _u in enumerate([0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10, 0x36, 0x37, 0x38], 1):
    ISO_TO_HID['B%02d' % _i] = _u

# HID 用法码 → Linux evdev 键码（内核 drivers/hid/hid-input.c 的 hid_keyboard[]；0x31 和 0x32 都是 43 KEY_BACKSLASH）。
# 安卓的 Generic.kl 也按这个键码查键名。
HID_TO_EVDEV = {0x2C: 57, 0x2D: 12, 0x2E: 13, 0x2F: 26, 0x30: 27, 0x31: 43, 0x32: 43, 0x33: 39, 0x34: 40, 0x35: 41,
                0x36: 51, 0x37: 52, 0x38: 53, 0x63: 83, 0x64: 86, 0x85: 121, 0x87: 89, 0x89: 124}
for _i, _e in enumerate([30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45,
                         21, 44]):
    HID_TO_EVDEV[0x04 + _i] = _e
for _i in range(10):
    HID_TO_EVDEV[0x1E + _i] = 2 + _i

# HID 用法码 → 网页 KeyboardEvent.code（W3C UI Events KeyboardEvent code 规范的名字；0x32 规范里也叫 Backslash）。
# Mac 把豆花当 ISO 键盘时会对调 0x35 / 0x64，那时网页看到的 code 也跟着对调（Chromium 按 Mac 虚拟键码给 code）。
HID_TO_CODE = {0x2B: 'Tab', 0x2C: 'Space', 0x2D: 'Minus', 0x2E: 'Equal', 0x2F: 'BracketLeft', 0x30: 'BracketRight',
               0x31: 'Backslash', 0x32: 'Backslash', 0x33: 'Semicolon', 0x34: 'Quote', 0x35: 'Backquote', 0x36: 'Comma',
               0x37: 'Period', 0x38: 'Slash', 0x63: 'NumpadDecimal', 0x64: 'IntlBackslash', 0x85: 'NumpadComma', 0x87: 'IntlRo',
               0x89: 'IntlYen'}
for _i in range(26):
    HID_TO_CODE[0x04 + _i] = 'Key' + chr(ord('A') + _i)
for _i in range(10):
    HID_TO_CODE[0x1E + _i] = 'Digit%d' % ((_i + 1) % 10)

# 给人看的位置名（美式键帽上的字）
USAGE_LABEL = {0x2D: '-', 0x2E: '=', 0x2F: '[', 0x30: ']', 0x31: '\\（0x31）', 0x32: '#（0x32）', 0x33: ';', 0x34: "'",
               0x35: '`（0x35，1 左边）', 0x36: ',', 0x37: '.', 0x38: '/', 0x63: '小键盘 .（0x63）', 0x85: '小键盘 ABNT2 多的那个键（0x85）',
               0x64: '<>（0x64，Z 左边）', 0x87: 'ろ（0x87）', 0x89: '¥（0x89）'}
for _i in range(26):
    USAGE_LABEL[0x04 + _i] = chr(ord('A') + _i)
for _i in range(10):
    USAGE_LABEL[0x1E + _i] = str((_i + 1) % 10)


def usage_label(u):
    return USAGE_LABEL.get(u, '0x%02X' % u)


# 数据表里有哪些键（出字的主键区）。0x32 不单列：所有查到的主机都把它和 0x31 当同一个键（方案 §3 第 1 条），扫描单独核。
TABLE_USAGES = list(range(0x04, 0x28)) + [0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x64,
                                          0x87, 0x89]

# 死键规范成"带间距的附加符号"（KCM 写成组合附加符号 U+0300…，xkb 写成 dead_* 键符，统一成 CLDR 的写法）
COMBINING_TO_SPACING = {'̀': '`', '́': '´', '̂': '^', '̃': '~', '̄': '¯', '̆': '˘',
                        '̇': '˙', '̈': '¨', '̊': '˚', '̋': '˝', '̌': 'ˇ', '̧': '¸',
                        '̨': '˛',
                        # AOSP english_us_intl 的 ' 键用 U+030D / U+030E 当死键，安卓自己把它们认成 ACCENT_APOSTROPHE / ACCENT_QUOTATION_MARK
                        # （KeyCharacterMap.java 的 sCombiningToAccent，凭记忆；KCM 里这个键的 label 就是 '）
                        '\u030d': "'", '\u030e': '"'}
# 死键允许是哪些字（不变量：死键只在附加符号上）。没有带间距形式的（比如下加点）留组合附加符号本身。
DEAD_OK = set(COMBINING_TO_SPACING.values()) | {"'", '"', '°', '̣', '̱', 'ˆ', '˜',
                                                 # xkb 那几个没有带间距形式的（gen_layouts.XKB_DEAD）：斜线、下逗号、钩、角
                                                 '\u0335', '\u0326', '\u0309', '\u031b'}
# 死键不是附加符号、但数据里就是这么写的（个别布局，写明理由；check_tables.py 和 FixedLegendsTest 照这张表放过）：
#   (平台, 布局): {字: 理由}
DEAD_EXCEPTIONS = {
    ('mac', 'cz'): {',': 'Mac 捷克 ⌥- 是写成 , 的死键（, + g = ģ，下加逗号 / 软音符）', '-': 'Mac 捷克 ⌥= 是写成 - 的死键（- + a = ā，长音符）'},
    ('mac', 'sk'): {',': 'Mac 斯洛伐克同捷克', '-': 'Mac 斯洛伐克同捷克'},
    ('mac', 'hu'): {'–': 'Mac 匈牙利 ⌥⇧, 是写成 – 的死键（– + l = ł，斜线）'},
    ('linux', '*'): {'¤': 'xkb 的 dead_currency（死键 + e = €）', 'µ': 'xkb 的 dead_greek（死键 + 字母 = 希腊字母）'},
}


def dead_ok(platform, layout, text):
    """这一格死键的字说得过去吗：附加符号，或者 DEAD_EXCEPTIONS 里写了理由的。"""
    return (text in DEAD_OK or text in DEAD_EXCEPTIONS.get((platform, layout), {})
            or text in DEAD_EXCEPTIONS.get((platform, '*'), {}))


# ═══════════ 一格的写法 ═══════════
# 一格 = (字, 是否死键) 或 None（没有字）。文件里：空 = 没有字；\* 开头 = 死键；\\ = 反斜杠；
# 看不见的字（控制 / 格式 / 私用区 / 空白 / 组合附加符号）写成 \uXXXX（BMP 以外写成代理对，同 Java / JSON）。

def _visible(c):
    cat = unicodedata.category(c)
    return cat[0] in 'LNPS' and cat != 'Co'


def escape_text(s):
    out = []
    for c in s:
        if c == '\\':
            out.append('\\\\')
        elif _visible(c):
            out.append(c)
        else:
            cp = ord(c)
            if cp > 0xFFFF:
                cp -= 0x10000
                out.append('\\u%04X\\u%04X' % (0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)))
            else:
                out.append('\\u%04X' % cp)
    return ''.join(out)


def unescape_text(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c != '\\':
            out.append(c)
            i += 1
            continue
        if s.startswith('\\\\', i):
            out.append('\\')
            i += 2
        elif re.match(r'\\u[0-9A-Fa-f]{4}', s[i:i + 6]):
            out.append(chr(int(s[i + 2:i + 6], 16)))
            i += 6
        else:
            raise ValueError('认不出的转义：%r' % s[i:i + 6])
    # 代理对拼回去
    return ''.join(out).encode('utf-16', 'surrogatepass').decode('utf-16')


def visible_text(s):
    """给人看：看不见的字（组合附加符号、不换行空格、私用区……）写成 \\uXXXX，别的照写。"""
    return ''.join(c if _visible(c) else '\\u%04X' % ord(c) for c in s)


def escape_field(s):
    """清单里的名字之类：只转义反斜杠、tab、换行（空格照写）。读回来同样用 unescape_text。"""
    return s.replace('\\', '\\\\').replace('\t', '\\u0009').replace('\n', '\\u000A')


def format_cell(cell):
    if cell is None:
        return ''
    text, dead = cell
    return ('\\*' if dead else '') + escape_text(text)


def parse_cell(s):
    if s == '':
        return None
    dead = s.startswith('\\*')
    text = unescape_text(s[2:] if dead else s)
    if text == '':
        raise ValueError('空的死键格')
    return (text, dead)


# ═══════════ 文件读写 ═══════════

def write_table_file(path, header, sections, aliases=None):
    """header：注释行（不带 #）和 '#!' 元数据行（带 '! '）；sections：{名字: {用法码: [4 格]}}，按给的顺序写；
    aliases：{名字: 指向的名字}（完全一样的表合并成别名）。"""
    lines = []
    for h in header:
        lines.append('#' + h if h.startswith('!') else ('# ' + h if h else '#'))
    for name, table in sections.items():
        lines.append('')
        if aliases and name in aliases:
            lines.append('@%s = %s' % (name, aliases[name]))
            continue
        lines.append('@' + name)
        for u in sorted(table):
            cells = table[u]
            if all(c is None for c in cells):
                continue
            row = '%02x\t%s' % (u, '\t'.join(format_cell(c) for c in cells))
            lines.append(row.rstrip('\t'))   # 末尾没字的格不写
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


def read_table_file(path):
    """返回 (meta, sections)。meta：'#!' 行，{'platform': 值, 'source': [(id, 版本)], 'file': [(id, 路径, sha256)] …}；
    sections：{名字: {用法码: [4 格]}}，别名已经展开成同一个字典。"""
    meta = {}
    sections = {}
    aliases = {}
    cur = None
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if line.startswith('#!'):
                parts = line[2:].split()
                if parts:
                    meta.setdefault(parts[0], []).append(tuple(parts[1:]))
                continue
            if not line or line.startswith('#'):
                continue
            if line.startswith('@'):
                m = re.match(r'@([\w.-]+)(?:\s*=\s*([\w.-]+))?$', line)
                if not m:
                    raise ValueError('%s:%d 段名不对：%s' % (path, n, line))
                if m.group(2):
                    aliases[m.group(1)] = m.group(2)
                    cur = None
                else:
                    cur = sections.setdefault(m.group(1), {})
                continue
            if cur is None:
                raise ValueError('%s:%d 键不在任何段里' % (path, n))
            cols = line.split('\t')
            u = int(cols[0], 16)
            cells = [parse_cell(c) for c in cols[1:5]] + [None] * (5 - len(cols))
            cur[u] = cells[:4]
    for a, b in aliases.items():
        if b not in sections:
            raise ValueError('%s：别名 %s 指向不存在的 %s' % (path, a, b))
        sections[a] = sections[b]
    return meta, sections


def table_path(platform, raw_dir=None):
    return os.path.join(raw_dir or RAW_DIR, 'kbd_%s.txt' % platform)


def load_tables(raw_dir=None):
    """{平台: {段名: 表}}；某家的文件不在就跳过。"""
    out = {}
    for p in PLATFORMS:
        path = table_path(p, raw_dir)
        if os.path.exists(path):
            out[p] = read_table_file(path)[1]
    return out


# ═══════════ 死键组合（res/raw/kbd_dead_<平台>.txt，只有 CLDR 的两家：mac、win） ═══════════
# 一个布局一段（@<id>，Mac 不分 .iso：组合规则和键的位置无关），每行"死键的字、后面那个字、拼出来的字"三格（tab 分隔，转义同键帽字：
# 空格写  ），照 CLDR transforms 的先后；完全一样的写成别名。App 的"打出来"（贴字面板，TypeOut）拿它反查 é 这类字要先按哪个死键再按哪个键。

DEAD_PLATFORMS = ('mac', 'win')


def dead_path(platform, raw_dir=None):
    return os.path.join(raw_dir or RAW_DIR, 'kbd_dead_%s.txt' % platform)


def write_dead_file(path, header, sections, aliases=None):
    """header 同 write_table_file；sections：{名字: [(死键的字, 后面那个字, 拼出来的字)]}，按给的顺序写。"""
    lines = []
    for h in header:
        lines.append('#' + h if h.startswith('!') else ('# ' + h if h else '#'))
    for name, rows in sections.items():
        lines.append('')
        if aliases and name in aliases:
            lines.append('@%s = %s' % (name, aliases[name]))
            continue
        lines.append('@' + name)
        for row in rows:
            lines.append('\t'.join(escape_text(x) for x in row))
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


def read_dead_file(path):
    """返回 (meta, {名字: [(死键的字, 后面那个字, 拼出来的字)]})，别名已经展开成同一个列表。每行必须正好三格、每格不空。"""
    meta = {}
    sections = {}
    aliases = {}
    cur = None
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if line.startswith('#!'):
                parts = line[2:].split()
                if parts:
                    meta.setdefault(parts[0], []).append(tuple(parts[1:]))
                continue
            if not line or line.startswith('#'):
                continue
            if line.startswith('@'):
                m = re.match(r'@([\w.-]+)(?:\s*=\s*([\w.-]+))?$', line)
                if not m:
                    raise ValueError('%s:%d 段名不对：%s' % (path, n, line))
                if m.group(2):
                    aliases[m.group(1)] = m.group(2)
                    cur = None
                else:
                    cur = sections.setdefault(m.group(1), [])
                continue
            if cur is None:
                raise ValueError('%s:%d 组合不在任何段里' % (path, n))
            cols = line.split('\t')
            if len(cols) != 3 or any(c == '' for c in cols):
                raise ValueError('%s:%d 一行要正好三格、都不空：%r' % (path, n, line))
            cur.append(tuple(unescape_text(c) for c in cols))
    for a, b in aliases.items():
        if b not in sections:
            raise ValueError('%s：别名 %s 指向不存在的 %s' % (path, a, b))
        sections[a] = sections[b]
    return meta, sections


# kbd_catalog.txt 的栏（顺序 = 生成时写的顺序；文件头 '#!columns' 一行照写，读的时候按它认，以后加栏不用动读法）：
#   id、locale（起名字用的语言 + 地区）、variant（区分词：小写的是 strings.xml 里 kbd_var_<它> 的词，别的照写）、
#   shape / shapes（默认形状 / 能选的形状）、modes（有数据的模式：win、mac，至少一种）、kpdl / kpco（小键盘小数点 0x63、ABNT2 小键盘多的 0x85 出的字，取自 xkb）、
#   kpdl_win（Windows 的小键盘小数点：catalog.json 手写的、照微软布局表，没写的同 kpdl；没有 Windows 数据的空着）、
#   caps_mac（Mac 模式 Caps 键帽写什么，空 = 照旧）、name_mac（macOS 现名，mac_names.json 换的，凭记忆）、name_mac_cldr（CLDR 里的旧名）、
#   name_win / name_linux / name_android（各家主机设置里叫什么；没有那家数据的空着）
CATALOG_COLUMNS = ('id', 'locale', 'variant', 'shape', 'shapes', 'modes', 'kpdl', 'kpdl_win', 'kpco', 'caps_mac', 'name_mac', 'name_mac_cldr',
                   'name_win', 'name_linux', 'name_android')
CATALOG_LISTS = ('shapes', 'modes')   # 逗号分开的栏


def keypad_decimal(row, platform):
    """清单的一行在这家主机上小键盘小数点（0x63）出什么：Windows 照 kpdl_win（微软布局表），别家照 kpdl（xkb）。"""
    return (row.get('kpdl_win') or row.get('kpdl', '')) if platform == 'win' else row.get('kpdl', '')


def read_catalog(raw_dir=None):
    """kbd_catalog.txt → [{栏名: 值}]（shapes、modes 是列表）。栏名按文件头的 '#!columns' 认；没有这一行、或者缺 CATALOG_COLUMNS 里的栏就失败。"""
    rows = []
    columns = None
    with open(os.path.join(raw_dir or RAW_DIR, 'kbd_catalog.txt'), encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if line.startswith('#!columns'):
                columns = line.split()[1:]
                missing = [c for c in CATALOG_COLUMNS if c not in columns]
                if missing:
                    raise ValueError('kbd_catalog.txt 缺栏：%s' % ', '.join(missing))
                continue
            if not line or line.startswith('#'):
                continue
            if columns is None:
                raise ValueError('kbd_catalog.txt 没有 #!columns 那一行')
            cols = [unescape_text(c) for c in line.split('\t')]
            if len(cols) != len(columns):
                raise ValueError('kbd_catalog.txt 这一行 %d 栏，文件头写的是 %d 栏：%s' % (len(cols), len(columns), line))
            row = dict(zip(columns, cols))
            for c in CATALOG_LISTS:
                row[c] = row[c].split(',') if row[c] else []
            rows.append(row)
    return rows


def section_name(platform, layout, iso_type=False):
    """Mac 的表按主机怎么认键盘分两段：layout（macOS 当 ANSI 键盘，不对调）、layout.iso（当 ISO 键盘，对调 0x35 / 0x64）。"""
    return layout + '.iso' if platform == 'mac' and iso_type else layout


# ═══════════ 扫描计划 ═══════════

# 扫描发哪些键：主键区出字的全部位置，加 0x32（核它是不是和 0x31 一样）、0x64；普通层最后加一个小键盘的小数点（核小数点出 , 还是 .）。
SCAN_USAGES = list(range(0x04, 0x28)) + [0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x64]
KEYPAD_DECIMAL = 0x63
KEYPAD_COMMA = 0x85   # ABNT2 小键盘多的那个键（Brazil：出 .，0x63 出 ,）
MOD_LSHIFT, MOD_LALT, MOD_RALT = 0x02, 0x04, 0x40


def third_layer_mod(platform):
    """第三层用哪个修饰键：Mac / iPad 是 Option（左 Alt，0x04）；Windows / Linux / 安卓是 AltGr（右 Alt，0x40）。"""
    return MOD_LALT if platform == 'mac' else MOD_RALT


def has_third_layer(tables, platform, layout):
    """这家这个布局有没有第三层的字。没有就不扫第三层：美式布局在 Windows / Linux / 安卓上右 Alt 只是 Alt，
    Alt + 字母在浏览器里是菜单快捷键（方案 §9 第 1A 批）。"""
    t = tables.get(platform, {}).get(layout)
    if t is None:
        return False
    return any(cells[2] is not None or cells[3] is not None for cells in t.values())


def scan_plan(host, layout, third):
    """扫描的每一步：{i, usage, mods, layer, key}。key = 用法码 | 修饰键 << 8（同 Proto.REGION_KEY / CMD_HID_KEY）。
    按层排（先把普通层全扫完）：主机布局没设对时前面几十步就看得出来，第三层（可能碰到快捷键）放最后。"""
    platform = HOSTS[host]
    l3 = third_layer_mod(platform)
    layers = [0, MOD_LSHIFT] + ([l3, l3 | MOD_LSHIFT] if third else [])
    steps = []
    for li, mods in enumerate(layers):
        for u in SCAN_USAGES:
            steps.append({'usage': u, 'mods': mods, 'layer': li})
        if li == 0:
            steps.append({'usage': KEYPAD_DECIMAL, 'mods': 0, 'layer': 0})
    for i, s in enumerate(steps):
        s['i'] = i
        s['key'] = s['usage'] | (s['mods'] << 8)
    return steps
