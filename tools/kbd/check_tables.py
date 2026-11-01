#!/usr/bin/env python3
"""离线核对生成好的键帽数据（res/raw/kbd_*.txt），不联网、不读原始数据（tools/test.sh 跑）。
  - 文件头记的数据源版本、每个原始文件的 sha256 和 tools/kbd/sources.json 一致；catalog.json 里的布局每家都生成了（那家没有数据的
    ——mac / win / android 写 null——一定没有那一段；只有 Mac 有的是 MAC_ONLY 那两个），清单的 modes、名字、小键盘（34 个布局的两种小数点全锁住）
    和 catalog.json / mac_names.json 对得上；
  - 不变量（每个布局每家都过；个别布局不成立的写在 LETTER_EXCEPTIONS / kbdtable.DEAD_EXCEPTIONS 里，带理由）：
    26 个字母键普通层都有字、普通层凑得齐 a–z；26 个字母键的普通层正好是 a–z（QWERTY / QWERTZ / AZERTY 只是换位置，例外见下）；
    没有控制字符；死键只在附加符号上；ISO / ABNT2 形状的布局 0x64 有字，ABNT2 的 0x87 也有字；
    Mac 的 <id>.iso 正好是 <id> 对调 0x35 / 0x64（锁住对调），别的键两段一样；
  - 抽查值（方案 §6.4、调研 kbd-data.md §5 原型实际跑出来的；和微软 Learn 交互页核过的几条注明了；第 2 批另加的是凭常识写的期望值，和数据核对一致）。
App 读 kbd_win、kbd_mac、kbd_catalog（FixedLegendsTest 另锁一遍）；这里锁住"生成器没把表弄错"。
用法：python3 tools/kbd/check_tables.py [res/raw 目录]
"""
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kbdtable as kt  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
L0, L1, L2, L3 = range(4)
# (平台, 段, 用法码, 层, 期望的字, 是否死键, 说明)
SPOTS = [
    ('mac', 'de', 0x0F, L2, '@', False, '德语 Mac：⌥L = @'),
    ('mac', 'de', 0x14, L2, '«', False, '德语 Mac：⌥Q = «'),
    ('win', 'de', 0x14, L2, '@', False, '德语 Windows：AltGr+Q = @（微软 Learn kbdgr 页核过）'),
    ('linux', 'de', 0x14, L2, '@', False, '德语 Linux：AltGr+Q = @'),
    ('android', 'de', 0x14, L2, '@', False, '德语安卓：AltGr+Q = @'),
    ('win', 'de', 0x64, L2, '|', False, '德语 Windows：AltGr + Z 左边 = |（kbdgr 页核过）'),
    ('linux', 'de', 0x64, L2, '|', False, '德语 Linux：AltGr + Z 左边 = |'),
    ('android', 'de', 0x64, L2, '|', False, '德语安卓：AltGr + Z 左边 = |（german.kcm 的 map key 86 PLUS）'),
    ('win', 'de', 0x2D, L2, '\\', False, '德语 Windows：AltGr+ß = \\（kbdgr 页核过）'),
    ('mac', 'de', 0x1F, L2, '“', False, '德语 Mac：⌥2 = “'),
    ('win', 'de', 0x1F, L1, '"', False, '德语 Windows：Shift+2 = "'),
    ('mac', 'de.iso', 0x35, L0, '^', True, '德语 Mac 当 ISO：左上角 = ^ 死键（CLDR 键码 10 的字，锁住对调）'),
    ('mac', 'de', 0x35, L0, '<', False, '德语 Mac 当 ANSI：0x35 出键码 50 的 <'),
    ('linux', 'de', 0x2E, L0, '´', True, '德语 Linux：´ 死键'),
    ('android', 'de', 0x2E, L0, '´', True, '德语安卓：KCM 的 U+0301 规范成 ´ 死键'),
    ('android', 'de', 0x35, L0, '^', True, '德语安卓：KCM 的 U+0302 规范成 ^ 死键'),
    ('win', 'de', 0x1C, L0, 'z', False, '德语：QWERTZ（0x1C = 美式 Y 的位置）'),
    ('win', 'us', 0x64, L0, '\\', False, '美式 Windows：0x64 = \\'),
    ('win', 'us', 0x64, L1, '|', False, '美式 Windows：Shift+0x64 = |'),
    ('mac', 'us.iso', 0x35, L0, '§', False, '美式 Mac 当 ISO：左上角 = §'),
    ('mac', 'us.iso', 0x64, L0, '`', False, '美式 Mac 当 ISO：Z 左边 = `'),
    ('mac', 'us', 0x35, L0, '`', False, '美式 Mac 当 ANSI：0x35 = `'),
    ('mac', 'us', 0x08, L2, '´', True, '美式 Mac：⌥E = ´ 死键'),
    ('linux', 'us', 0x31, L1, '|', False, '美式 Linux：Shift+\\ = |'),
    # ── 第 2 批：方案 §6.4 / 调研 §5 原型跑出来的 ──
    ('mac', 'fr', 0x64, L0, '@', False, '法语 Mac：左上角（键码 10，Mac 模式 ISO 发 0x64）= @'),
    ('mac', 'fr', 0x64, L1, '#', False, '法语 Mac：左上角 Shift = #'),
    ('mac', 'fr.iso', 0x35, L0, '@', False, '法语 Mac 当 ISO：0x35 = @（对调）'),
    ('win', 'fr', 0x27, L2, '@', False, '法语 Windows：AltGr+0 = @（微软 Learn 核过）'),
    ('mac', 'fr', 0x25, L0, '!', False, '法语 Mac：8 键普通层 = !'),
    ('win', 'fr', 0x25, L0, '_', False, '法语 Windows：8 键普通层 = _'),
    ('mac', 'se', 0x1F, L2, '@', False, '瑞典 Mac（CLDR Swedish - Pro，现名 Swedish）：⌥2 = @'),
    ('mac', 'se-legacy', 0x31, L2, '@', False, "瑞典 Mac（CLDR Swedish，现名 Swedish – Legacy）：⌥' = @"),
    ('mac', 'se-legacy', 0x1F, L2, '™', False, '瑞典 Mac 旧版：⌥2 = ™'),
    ('win', 'se', 0x1F, L2, '@', False, '瑞典 Windows：AltGr+2 = @（微软 Learn 核过）'),
    ('mac', 'gb', 0x1F, L1, '@', False, '英国 Mac：Shift+2 = @'),
    ('mac', 'gb', 0x1F, L2, '€', False, '英国 Mac：⌥2 = €'),
    ('mac', 'gb', 0x64, L0, '§', False, '英国 Mac：左上角（0x64）= §'),
    ('mac', 'gb', 0x35, L0, '`', False, '英国 Mac：Z 左边（0x35）= `'),
    ('win', 'gb', 0x34, L1, '@', False, "英国 Windows：Shift+' = @（微软 Learn 核过）"),
    ('win', 'gb', 0x1F, L1, '"', False, '英国 Windows：Shift+2 = "（微软 Learn 核过）'),
    ('win', 'gb', 0x35, L0, '`', False, '英国 Windows：左上角 = `'),
    ('win', 'gb', 0x64, L0, '\\', False, '英国 Windows：Z 左边 = \\'),
    ('win', 'gb', 0x08, L2, 'é', False, '英国 Windows：AltGr+E = é'),
    # ── 第 2 批：凭常识写的期望值（各国键盘上 @ 在哪之类），和生成的数据核对一致 ──
    ('win', 'ch-de', 0x1F, L2, '@', False, '瑞士德语 Windows：AltGr+2 = @'),
    ('mac', 'ch-de', 0x0A, L2, '@', False, '瑞士德语 Mac：⌥G = @'),
    ('win', 'ch-fr', 0x1F, L2, '@', False, '瑞士法语 Windows：AltGr+2 = @'),
    ('win', 'es', 0x1F, L2, '@', False, '西班牙 Windows：AltGr+2 = @'),
    ('mac', 'es', 0x1F, L2, '@', False, '西班牙 Mac（Spanish – ISO）：⌥2 = @'),
    ('win', 'latam', 0x14, L2, '@', False, '拉美 Windows：AltGr+Q = @'),
    ('win', 'it', 0x33, L2, '@', False, '意大利 Windows：AltGr+ò = @'),
    ('mac', 'it', 0x33, L2, '@', False, '意大利 Mac（Italian - Pro）：⌥ò = @'),
    ('mac', 'it-qzerty', 0x1A, L0, 'z', False, '意大利 Mac 旧版：QZERTY（W 的位置出 z）'),
    ('mac', 'it-qzerty', 0x1E, L1, '1', False, '意大利 Mac 旧版：数字在 Shift 层'),
    ('win', 'pt', 0x1F, L2, '@', False, '葡萄牙 Windows：AltGr+2 = @'),
    ('win', 'br', 0x1F, L1, '@', False, '巴西 ABNT2 Windows：Shift+2 = @'),
    ('win', 'br', 0x87, L0, '/', False, '巴西 ABNT2 Windows：右 Shift 左边那个键（0x87）= /'),
    ('win', 'br', 0x87, L1, '?', False, '巴西 ABNT2 Windows：0x87 Shift = ?'),
    ('mac', 'br', 0x87, L0, '/', False, '巴西 ABNT2 Mac：0x87 = /'),
    ('win', 'br', 0x33, L0, 'ç', False, '巴西 ABNT2 Windows：; 的位置 = ç'),
    ('win', 'tr-q', 0x14, L2, '@', False, '土耳其 Q Windows：AltGr+Q = @'),
    ('win', 'tr-q', 0x0C, L0, 'ı', False, '土耳其 Q：I 的位置出无点的 ı'),
    ('win', 'tr-q', 0x34, L0, 'i', False, "土耳其 Q：' 的位置出 i"),
    ('win', 'tr-f', 0x14, L0, 'f', False, '土耳其 F：Q 的位置出 f'),
    ('win', 'tr-f', 0x2F, L0, 'q', False, '土耳其 F：[ 的位置出 q'),
    ('win', 'pl', 0x04, L2, 'ą', False, '波兰 Windows（Programmers）：AltGr+A = ą'),
    ('mac', 'pl', 0x04, L2, 'ą', False, '波兰 Mac（Polish Pro）：⌥A = ą'),
    ('win', 'hu', 0x19, L2, '@', False, '匈牙利 Windows：AltGr+V = @'),
    ('win', 'cz', 0x19, L2, '@', False, '捷克 Windows：AltGr+V = @'),
    ('win', 'sk', 0x19, L2, '@', False, '斯洛伐克 Windows：AltGr+V = @'),
    ('win', 'hr', 0x19, L2, '@', False, '克罗地亚 Windows：AltGr+V = @'),
    ('win', 'hr', 0x14, L2, '\\', False, '克罗地亚 Windows：AltGr+Q = \\'),
    ('win', 'is', 0x14, L2, '@', False, '冰岛 Windows：AltGr+Q = @'),
    ('win', 'nl', 0x35, L0, '@', False, '荷兰 Windows：左上角 = @'),
    ('win', 'nl', 0x35, L1, '§', False, '荷兰 Windows：左上角 Shift = §'),
    ('win', 'be', 0x1F, L2, '@', False, '比利时 Windows：AltGr+2（é 键）= @'),
    ('win', 'dk', 0x1F, L2, '@', False, '丹麦 Windows：AltGr+2 = @'),
    ('win', 'no', 0x1F, L2, '@', False, '挪威 Windows：AltGr+2 = @'),
    ('win', 'fi', 0x1F, L2, '@', False, '芬兰 Windows：AltGr+2 = @'),
    ('win', 'ro', 0x1F, L1, '@', False, '罗马尼亚 Windows（Standard）：Shift+2 = @'),
    ('win', 'ro', 0x33, L0, 'ș', False, '罗马尼亚 Windows（Standard）：; 的位置 = ș（下加逗号，不是 ş）'),
    ('win', 'ie', 0x08, L2, 'é', False, '爱尔兰 Windows：AltGr+E = é'),
    ('win', 'ca-fr', 0x35, L0, '/', False, '加拿大法语 Windows（CMS）：左上角 = /'),
    ('mac', 'ca-fr', 0x64, L0, '/', False, '加拿大法语 Mac（CSA）：左上角（0x64）= /'),
    ('win', 'us-intl', 0x34, L0, "'", True, "US-International Windows：' 是死键"),
    ('mac', 'us-intl', 0x34, L0, "'", True, "US-International Mac：' 是死键"),
    ('android', 'us-intl', 0x34, L0, "'", True, "US-International 安卓：KCM 的 U+030D 规范成 ' 死键"),
    ('win', 'us-intl', 0x1E, L2, '¡', False, 'US-International Windows：AltGr+1 = ¡'),
    ('win', 'at', 0x14, L2, '@', False, '奥地利 Windows（就是德语布局）：AltGr+Q = @'),
    ('mac', 'at', 0x0F, L2, '@', False, '奥地利 Mac：⌥L = @'),
]
# 期望没有第三层的（扫描时这些不扫第三层；App 的 Windows 模式右 Alt 写 Alt 不写 AltGr）
NO_THIRD = [('win', 'us'), ('linux', 'us'), ('android', 'us'), ('win', 'cn-pinyin')]
# 小键盘小数点（0x63），34 个布局全锁住。KPDL = 清单的 kpdl（取自 xkb；Mac 模式、扫描 Linux / 安卓用）；
# KPDL_WIN = 清单的 kpdl_win（Windows 模式用）：照微软布局表（kbdlayout.info 按微软键盘 DLL 导出的 KLC 的 DECIMAL 一行，2026-11-01 逐个核过），
# 荷兰（KBDNE）、冰岛（KBDIC）、捷克（KBDCZ）、斯洛伐克（KBDSL）是逗号——xkb 这四个没引 kpdl(comma)、是句点，所以 catalog.json 手写了 kpdl_win；
# 只有 Mac 有的那一套（意大利 QZERTY、瑞典旧版）没有 Windows 数据，空着。
KPDL = {'us': '.', 'us-intl': '.', 'gb': '.', 'ie': '.', 'nl': '.', 'ro': ',', 'fr': '.', 'be': '.', 'ca-fr': '.', 'de': ',', 'at': ',',
        'ch-de': '.', 'ch-fr': '.', 'se': ',', 'se-legacy': ',', 'fi': ',', 'dk': ',', 'no': ',', 'is': '.', 'es': '.', 'latam': '.', 'pt': '.',
        'br': ',', 'it': '.', 'it-qzerty': '.', 'hr': ',', 'si': ',', 'cz': '.', 'sk': '.', 'hu': ',', 'pl': ',', 'tr-q': ',', 'tr-f': ',',
        'cn-pinyin': '.'}
KPDL_WIN = dict(KPDL, nl=',', **{'is': ',', 'cz': ',', 'sk': ',', 'it-qzerty': '', 'se-legacy': ''})
KPCO = {'br': '.'}
# 完全一样的（别名）：同一份数据在主机里叫两个名字
SAME = [('win', 'at', 'de'), ('win', 'cn-pinyin', 'us'), ('mac', 'cn-pinyin', 'us'), ('win', 'si', 'hr')]
# 只有 Mac 有的那一套（Windows 没有，Windows 模式下灰掉；不拿普通的意大利 / 瑞典顶上）
MAC_ONLY = {'it-qzerty', 'se-legacy'}
# 26 个字母键（0x04–0x1D）的普通层正好是 a–z——不成立的布局（每家都一样不成立），理由：
LETTER_EXCEPTIONS = {
    'fr': 'AZERTY：M 在 ; 的位置（0x33），M 的位置（0x10）出 ,',
    'be': '同法语 AZERTY',
    'it-qzerty': 'Mac 旧版意大利（QZERTY）同 AZERTY：M 在 0x33、0x10 出 ,（Linux 那份是 xkb 的 it(mac)，同样；Windows、安卓没有这一套）',
    'tr-q': '土耳其 Q：I 的位置（0x0C）出无点的 ı，i 在 \' 的位置（0x34）',
    'tr-f': '土耳其 F：整套按土耳其语字频重排，q w x 不在字母键上（Windows 在 [ ] \\ 的位置）',
}
# variant 小写的是要翻译的词，strings.xml 里有 kbd_var_<它>（FixedLegendsTest 核 strings.xml 九个文件都有）
VARIANT_WORDS = {'intl', 'legacy', 'pinyin'}


def main():
    raw = sys.argv[1] if len(sys.argv) > 1 else kt.RAW_DIR
    with open(os.path.join(HERE, 'sources.json'), encoding='utf-8') as f:
        sources = json.load(f)['sources']
    with open(os.path.join(HERE, 'catalog.json'), encoding='utf-8') as f:
        catalog = json.load(f)['layouts']
    with open(os.path.join(HERE, 'mac_names.json'), encoding='utf-8') as f:
        mac_names = json.load(f)['names']
    ok = [0]
    fails = []

    def check(cond, what):
        if cond:
            ok[0] += 1
        else:
            fails.append(what)

    tables = {}
    for p in kt.PLATFORMS:
        meta, sections = kt.read_table_file(kt.table_path(p, raw))
        tables[p] = sections
        check(meta.get('platform') == [(p,)], '%s：文件头的平台' % p)
        for src_id, version in meta.get('source', []):
            want = sources[src_id]['version'] if src_id != 'aosp' else sources[src_id]['repo']
            check(version == want, '%s：数据源 %s 的版本 %s ≠ sources.json 的 %s' % (p, src_id, version, want))
        for src_id, rel, sha in meta.get('file', []):
            spec = sources[src_id]['files'].get(rel)
            want = spec if isinstance(spec, str) else (spec or {}).get('sha256')
            check(sha == want, '%s：%s/%s 的 sha256 和 sources.json 不一致' % (p, src_id, rel))
        check(bool(meta.get('source')), '%s：文件头没记数据源' % p)

    rows = {r['id']: r for r in kt.read_catalog(raw)}
    check([l['id'] for l in catalog] == list(rows), '清单的布局和 catalog.json 一致')
    for lay in catalog:
        lid = lay['id']
        row = rows.get(lid, {})
        has = {'mac': bool(lay.get('mac')), 'win': bool(lay.get('win')), 'linux': True, 'android': bool(lay.get('android'))}
        check(row.get('modes') == (['win'] if has['win'] else []) + (['mac'] if has['mac'] else []) and row.get('modes'),
              '%s 的 modes 应该照有没有 Windows / Mac 数据写（至少一种）：%s' % (lid, row.get('modes')))
        check(has['win'] == (lid not in MAC_ONLY), '%s：只有 Mac 有的是 %s，Windows 数据%s' % (lid, '、'.join(sorted(MAC_ONLY)), '有' if has['win'] else '没有'))
        check(row.get('locale') == lay['locale'] and re.match(r'^[a-z]{2,3}(-[A-Z]{2}|-\d{3})?$', lay['locale'] or ''),
              '%s 的 locale 不对：%r' % (lid, row.get('locale')))
        v = lay.get('variant', '')
        check(v == row.get('variant') and (not v.islower() or v in VARIANT_WORDS),
              '%s 的 variant %r：小写的得是 strings.xml 里有的词（%s）' % (lid, v, '、'.join(sorted(VARIANT_WORDS))))
        if has['mac']:
            check(bool(row.get('name_mac')) and bool(row.get('name_mac_cldr')), '%s 有 Mac 数据，Mac 的名字不能空' % lid)
            check(lay.get('name_mac') or row.get('name_mac_cldr') in mac_names,
                  '%s 的 CLDR 旧名 %r 没写进 mac_names.json（现名要用户核对，不能悄悄漏掉）' % (lid, row.get('name_mac_cldr')))
        else:
            check(row.get('name_mac') == '' and row.get('caps_mac') == '', '%s 没有 Mac 数据，Mac 的名字、Caps 要空着' % lid)
        check(bool(row.get('name_win')) == has['win'], '%s 的 Windows 名字：有 Windows 数据才有、没有就空着：%r' % (lid, row.get('name_win')))
        names = [lid, lid + '.iso']
        for p in kt.PLATFORMS:
            for name in (names if p == 'mac' else names[:1]):
                t = tables[p].get(name)
                if not has[p]:
                    check(t is None, '%s 没有 %s 的数据，%s 里却有 %s 这一段' % (lid, p, p, name))
                    continue
                check(t is not None, '%s 缺 %s' % (p, name))
                if t is None:
                    continue
                normal = {u: c[L0][0] for u, c in t.items() if c[L0]}
                check(all(u in normal for u in range(0x04, 0x1E)), '%s:%s 有字母键普通层没有字' % (p, name))
                allchars = set(normal.values())
                check(all(chr(c) in allchars for c in range(ord('a'), ord('z') + 1)),
                      '%s:%s 普通层凑不齐 a–z：缺 %s' % (p, name, [chr(c) for c in range(ord('a'), ord('z') + 1) if chr(c) not in allchars]))
                letters = sorted(normal.get(u, '') for u in range(0x04, 0x1E))
                exact = letters == [chr(c) for c in range(ord('a'), ord('z') + 1)]
                exc = lid in LETTER_EXCEPTIONS
                check(exact != exc, '%s:%s 字母键普通层%s是 a–z：%s' % (p, name, '不' if not exact else '却', letters)
                      + ('（LETTER_EXCEPTIONS 写了它是例外，例外不成立了就删掉那一条）' if exc else ''))
                for u, cells in t.items():
                    for li, c in enumerate(cells):
                        if c is None:
                            continue
                        text, dead = c
                        check(not any(unicodedata.category(ch) == 'Cc' for ch in text),
                              '%s:%s %s 第 %d 层有控制字符' % (p, name, kt.usage_label(u), li))
                        check(not dead or kt.dead_ok(p, lid, text),
                              '%s:%s %s 第 %d 层的死键不是附加符号：%r' % (p, name, kt.usage_label(u), li, text))
                        check(len(text) <= 2, '%s:%s %s 一格太长：%r' % (p, name, kt.usage_label(u), text))
                if 'iso' in lay['shapes'] or 'abnt2' in lay['shapes']:
                    check(bool(t.get(0x64) and t[0x64][L0]), '%s:%s 是 ISO / ABNT2 形状，0x64 却没有字' % (p, name))
                if 'abnt2' in lay['shapes'] and p in ('mac', 'win'):
                    check(bool(t.get(0x87) and t[0x87][L0]), '%s:%s 是 ABNT2 形状，0x87 却没有字' % (p, name))
                if 'abnt2' not in lay['shapes'] and p in ('mac', 'win'):
                    check(not (t.get(0x87) and any(t[0x87])), '%s:%s 不是 ABNT2，却有 0x87 的字' % (p, name))
        a, i = tables['mac'].get(lid), tables['mac'].get(lid + '.iso')
        if a and i:
            check(i.get(0x35) == a.get(0x64) and i.get(0x64) == a.get(0x35), 'mac:%s.iso 不是对调 0x35 / 0x64' % lid)
            check(all(a.get(u) == i.get(u) for u in set(a) | set(i) if u not in (0x35, 0x64)), 'mac:%s.iso 别的键和 %s 不一样' % (lid, lid))
        check(row.get('kpdl') == KPDL.get(lid), '%s 的小键盘小数点（xkb）应该是 %r：%r' % (lid, KPDL.get(lid), row.get('kpdl')))
        check(row.get('kpdl_win') == KPDL_WIN.get(lid), '%s 的 Windows 小键盘小数点应该是 %r（微软布局表）：%r' % (lid, KPDL_WIN.get(lid), row.get('kpdl_win')))
        check(row.get('kpdl_win') == ((lay.get('kpdl_win') or row.get('kpdl')) if has['win'] else ''),
              '%s 的 kpdl_win 应该是 catalog.json 手写的、没写照 xkb、没有 Windows 数据空着' % lid)
        check(row.get('kpco') == KPCO.get(lid, ''), '%s 的小键盘 0x85 应该是 %r：%r' % (lid, KPCO.get(lid, ''), row.get('kpco')))
        check(row.get('kpdl') in ('.', ',') and row.get('kpdl_win') in (('.', ',') if has['win'] else ('',)),
              '%s 的小键盘小数点不是 . 或 ,：%r / %r' % (lid, row.get('kpdl'), row.get('kpdl_win')))

    for p, name, u, li, text, dead, what in SPOTS:
        t = tables[p].get(name, {})
        got = t.get(u, [None] * 4)[li]
        check(got == (text, dead), '%s（%s:%s %s 第 %d 层：期望 %r，实际 %r）' % (what, p, name, kt.usage_label(u), li, (text, dead), got))
    for p, name in NO_THIRD:
        check(not kt.has_third_layer(tables, p, name), '%s:%s 不该有第三层（扫描会去扫它）' % (p, name))
    check(kt.has_third_layer(tables, 'mac', 'us'), 'mac:us 有 Option 层')
    for p, a, b in SAME:
        check(tables[p].get(a) is not None and tables[p].get(a) == tables[p].get(b), '%s:%s 应该和 %s 一模一样' % (p, a, b))
    check(set(KPDL) == set(rows) and set(KPDL_WIN) == set(rows), '小键盘小数点每个布局都锁住了（KPDL / KPDL_WIN 和清单一致）')
    check(tables['linux'].get('it-qzerty') != tables['linux'].get('it'), 'linux:it-qzerty 是 xkb 的 it(mac)（QZERTY），不是普通意大利')
    check(rows.get('cn-pinyin', {}).get('caps_mac') == '中/英', '中文拼音 Mac 模式的 Caps 写 中/英')

    if fails:
        for f in fails:
            print('  不对：' + f, file=sys.stderr)
        print('键帽数据核对：%d 项检查，%d 项失败' % (ok[0] + len(fails), len(fails)), file=sys.stderr)
        sys.exit(1)
    print('键帽数据核对：%d 项检查，0 项失败（%d 个布局）' % (ok[0], len(catalog)))


if __name__ == '__main__':
    main()
