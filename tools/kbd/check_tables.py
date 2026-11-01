#!/usr/bin/env python3
"""离线核对生成好的键帽数据（res/raw/kbd_*.txt），不联网、不读原始数据（tools/test.sh 跑）。
  - 文件头记的数据源版本、每个原始文件的 sha256 和 tools/kbd/sources.json 一致；catalog.json 里的布局每家都生成了；
  - 不变量：26 个字母都在普通层；没有控制字符；死键只在附加符号上；ISO 形状的布局 0x64 有字；
    Mac 的 <id>.iso 正好是 <id> 对调 0x35 / 0x64（锁住对调），别的键两段一样；
  - 抽查值（方案 §6.4、调研 kbd-data.md §5 原型实际跑出来的；和微软 Learn 交互页核过的几条注明了）。
数据是 1B 才读的；这里只锁住"生成器没把表弄错"。
用法：python3 tools/kbd/check_tables.py [res/raw 目录]
"""
import json
import os
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
]
# 期望没有第三层的（扫描时这些不扫第三层）
NO_THIRD = [('win', 'us'), ('linux', 'us'), ('android', 'us')]
KPDL = {'us': '.', 'de': ','}


def main():
    raw = sys.argv[1] if len(sys.argv) > 1 else kt.RAW_DIR
    with open(os.path.join(HERE, 'sources.json'), encoding='utf-8') as f:
        sources = json.load(f)['sources']
    with open(os.path.join(HERE, 'catalog.json'), encoding='utf-8') as f:
        catalog = json.load(f)['layouts']
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
        names = [lid, lid + '.iso']
        for p in kt.PLATFORMS:
            for name in (names if p == 'mac' else names[:1]):
                t = tables[p].get(name)
                check(t is not None, '%s 缺 %s' % (p, name))
                if t is None:
                    continue
                letters = sorted(t[u][L0][0] for u in range(0x04, 0x1E) if t.get(u) and t[u][L0])
                check(letters == [chr(c) for c in range(ord('a'), ord('z') + 1)], '%s:%s 普通层不是 26 个字母：%s' % (p, name, letters))
                for u, cells in t.items():
                    for li, c in enumerate(cells):
                        if c is None:
                            continue
                        text, dead = c
                        check(not any(unicodedata.category(ch) == 'Cc' for ch in text),
                              '%s:%s %s 第 %d 层有控制字符' % (p, name, kt.usage_label(u), li))
                        check(not dead or text in kt.DEAD_OK, '%s:%s %s 第 %d 层的死键不是附加符号：%r' % (p, name, kt.usage_label(u), li, text))
                        check(len(text) <= 2, '%s:%s %s 一格太长：%r' % (p, name, kt.usage_label(u), text))
                if 'iso' in lay['shapes']:
                    check(bool(t.get(0x64) and t[0x64][L0]), '%s:%s 是 ISO 形状，0x64 却没有字' % (p, name))
        a, i = tables['mac'].get(lid), tables['mac'].get(lid + '.iso')
        if a and i:
            check(i.get(0x35) == a.get(0x64) and i.get(0x64) == a.get(0x35), 'mac:%s.iso 不是对调 0x35 / 0x64' % lid)
            check(all(a.get(u) == i.get(u) for u in set(a) | set(i) if u not in (0x35, 0x64)), 'mac:%s.iso 别的键和 %s 不一样' % (lid, lid))
        if lid in KPDL:
            check(rows[lid]['kpdl'] == KPDL[lid], '%s 的小键盘小数点应该是 %s' % (lid, KPDL[lid]))

    for p, name, u, li, text, dead, what in SPOTS:
        t = tables[p].get(name, {})
        got = t.get(u, [None] * 4)[li]
        check(got == (text, dead), '%s（%s:%s %s 第 %d 层：期望 %r，实际 %r）' % (what, p, name, kt.usage_label(u), li, (text, dead), got))
    for p, name in NO_THIRD:
        check(not kt.has_third_layer(tables, p, name), '%s:%s 不该有第三层（扫描会去扫它）' % (p, name))
    check(kt.has_third_layer(tables, 'mac', 'us'), 'mac:us 有 Option 层')

    if fails:
        for f in fails:
            print('  不对：' + f, file=sys.stderr)
        print('键帽数据核对：%d 项检查，%d 项失败' % (ok[0] + len(fails), len(fails)), file=sys.stderr)
        sys.exit(1)
    print('键帽数据核对：%d 项检查，0 项失败' % ok[0])


if __name__ == '__main__':
    main()
