#!/usr/bin/env python3
"""主机实测扫描的分析：拿 tools/kbd-scan.sh 记下的一次（scan.json）和生成的键帽数据（res/raw/kbd_*.txt）逐格比，
告诉你方案里那几条最没把握的前提成不成立：
  - 主机的布局是不是选的那个（和每份数据比，最像哪份）；
  - 苹果主机把豆花当 ANSI 还是 ISO 键盘（0x35 / 0x64 对没对调，决定"互换"开关的默认值）；
  - 0x32 是不是和 0x31 出一样的字（回车左边那个键发 0x31 行不行）；
  - 第三层（AltGr / Option）、死键、小键盘小数点对不对；iPad 是不是和同名 macOS 布局一样、Linux 是不是 xkb 默认变体、三星是不是 AOSP 的 KCM；
  - 每一步收到的 e.code 对不对得上（丢键 / 错位时整份结果不可信）。
输出：终端 + <记录目录>/report.txt、compare.json。
用法：python3 tools/kbd/compare.py <记录目录>
     python3 tools/kbd/compare.py --selftest    用生成的数据造几份"假主机"的扫描结果测判断（tools/test.sh 跑，不联网）
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kbdtable as kt  # noqa: E402

MOD_KEYS = {'Shift', 'Alt', 'AltGraph', 'Control', 'Meta', 'CapsLock', 'OS', 'Fn', 'Hyper', 'Super', 'Tab', ' '}
# 死键 + 空格各家组合出来的字不一样（Linux 的 Compose：´ + 空格 = '、¨ + 空格 = "），这些都算同一个死键
DEAD_EQUIV = {'´': {'´', "'"}, '¨': {'¨', '"'}, '^': {'^', 'ˆ'}, 'ˆ': {'^', 'ˆ'}, '~': {'~', '˜'}, '˜': {'~', '˜'},
              '˚': {'˚', '°'}, '`': {'`'}}


# ═══════════ 一步收到了什么 ═══════════

def interpret(rec):
    """一步的事件 → {ch, dead, code, text}；什么字都没出时 ch = None。"""
    if rec is None:
        return None
    ev = rec.get('ev', [])
    kd = [e for e in ev if e.get('t') == 'd' and e.get('key') not in MOD_KEYS and e.get('code') not in ('Tab', 'Space')]
    comp = [e for e in ev if e.get('t') in ('cs', 'cu', 'ce')]
    ce = [e['data'] for e in ev if e.get('t') == 'ce' and e.get('data')]
    text = rec.get('text', '')
    if text.endswith(' '):
        text = text[:-1]   # 我们发的那个空格
    first = kd[0] if kd else None
    dead = bool(first and first.get('key') == 'Dead') or bool(comp)
    if dead:
        ch = ce[-1] if ce else text
    elif first and len(first.get('key', '')) == 1:
        ch = first['key']
    else:
        ch = text
    return {'ch': ch or None, 'dead': dead, 'code': first.get('code') if first else None, 'text': text}


def same(got, exp):
    """收到的和期望的算不算一样：死键只比死不死（组合出什么字、组合不出字都不管：附加符号的写法另在死键那条里说），普通字逐字比。"""
    if exp is None:
        return None
    if got is None:
        return False
    text, dead = exp
    if dead:
        # 主机认出了死键、"死键 + 空格"却组合不出字（Linux 的 Compose 里没有这一条，比如 dead_belowmacron）也算对
        return got['dead']
    return got['ch'] is not None and not got['dead'] and got['ch'] == text


def dead_accent_ok(got, exp):
    return got['ch'] in DEAD_EQUIV.get(exp[0], {exp[0]})


# ═══════════ 分析 ═══════════

def cell_for(table, step):
    u = step['usage']
    return table.get(u, [None] * 4)[step['layer']] if table else None


def score(gots, steps, table, layers):
    """在给定的几层上，期望有字的格里对了几个。0x32、小键盘单独核，不算进来。"""
    hit = n = 0
    for s, g in zip(steps, gots):
        if s['layer'] not in layers or s['usage'] in (0x32, kt.KEYPAD_DECIMAL) or g is None:
            continue
        exp = cell_for(table, s)
        if exp is None:
            continue
        n += 1
        hit += bool(same(g, exp))
    return hit, n


def pct(h, n):
    return '%d%%' % round(100 * h / n) if n else '—'


def analyze(scan, tables, catalog):
    meta, steps = scan['meta'], scan['steps']
    host, platform, layout = meta['host'], meta['platform'], meta['layout']
    by_i = {r['i']: r for r in scan.get('records', [])}
    gots = [interpret(by_i.get(s['i'])) for s in steps]
    received = sum(1 for s in steps if s['i'] in by_i)
    layers = sorted({s['layer'] for s in steps})
    names = kt.layer_names(platform)
    res = {'host': host, 'platform': platform, 'layout': layout, 'steps': len(steps), 'received': received,
           'complete': meta.get('complete', False) and received == len(steps), 'premises': [], 'mismatches': [],
           'ranking': []}

    def premise(key, status, text):
        res['premises'].append({'key': key, 'status': status, 'text': text})

    # ── 和每份数据比（普通 + Shift 层，各家都有），最像哪份 ──
    ranking = []
    for p, secs in tables.items():
        for name, t in secs.items():
            h, n = score(gots, steps, t, (0, 1))
            if n:
                ranking.append((h / n, h, n, p, name))
    ranking.sort(key=lambda r: (-r[0], r[3] != platform, r[4] != layout))
    res['ranking'] = [{'table': '%s:%s' % (r[3], r[4]), 'hit': r[1], 'n': r[2]} for r in ranking[:6]]

    # ── 主表：选的布局；Mac 有 ANSI 型 / ISO 型两段，取全部层对得多的那段 ──
    cands = [layout] + ([layout + '.iso'] if platform == 'mac' else [])
    cands = [c for c in cands if c in tables.get(platform, {})]
    if not cands:
        premise('layout', 'bad', '数据里没有 %s:%s，没法比' % (platform, layout))
        return res
    best = max(cands, key=lambda c: score(gots, steps, tables[platform][c], layers)[0])
    main = tables[platform][best]
    res['main'] = '%s:%s' % (platform, best)
    h01, n01 = score(gots, steps, main, (0, 1))
    if received == 0:
        premise('layout', 'unknown', '一步都没收到：手机没在发（看终端 / 手机日志），或者页面没拿到焦点')
        return res
    top = ranking[0] if ranking else None
    if top and (top[3], top[4]) != (platform, best) and top[0] > h01 / max(n01, 1) + 0.05:
        premise('layout', 'bad', '主机布局：选的是 %s，可收到的字最像 %s:%s（%s），而 %s 只有 %s。主机设置里的布局多半没设对、开着输入法，或者 %s 的数据和这台主机不一样'
                % (layout, top[3], top[4], pct(top[1], top[2]), res['main'], pct(h01, n01), kt.PLATFORM_NAMES[platform]))
    else:
        premise('layout', 'ok' if n01 and h01 / n01 >= 0.9 else 'bad',
                '主机布局：普通 + Shift 层 %d/%d 对（%s），对照的是 %s' % (h01, n01, pct(h01, n01), res['main']))

    def got_of(usage, layer):
        for s, g in zip(steps, gots):
            if s['usage'] == usage and s['layer'] == layer:
                return g
        return None

    def show(g):
        if g is None:
            return '（没收到）'
        if g['ch'] is None:
            return '（死键，组合不出字）' if g['dead'] else '（没出字）'
        return kt.visible_text(g['ch']) + ('（死键）' if g['dead'] else '')

    def show_cell(c):
        return '（没字）' if c is None else kt.visible_text(c[0]) + ('（死键）' if c[1] else '')

    # ── 苹果：0x35 / 0x64 对没对调 ──
    if platform == 'mac':
        ansi, iso = tables['mac'].get(layout), tables['mac'].get(layout + '.iso')
        a_ok = i_ok = n = 0
        for li in (0, 1):
            for u in (0x35, 0x64):
                g = got_of(u, li)
                if g is None:
                    continue
                n += 1
                a_ok += bool(same(g, ansi.get(u, [None] * 4)[li]))
                i_ok += bool(same(g, iso.get(u, [None] * 4)[li]))
        codes = '，网页看到的 e.code：0x35 → %s、0x64 → %s' % ((got_of(0x35, 0) or {}).get('code'), (got_of(0x64, 0) or {}).get('code'))
        if n == 0:
            premise('swap', 'unknown', '0x35 / 0x64：没收到这两个键' + codes)
        elif ansi.get(0x35) == ansi.get(0x64):
            premise('swap', 'unknown', '0x35 / 0x64：这个布局两个键出的字一样，看不出对没对调' + codes)
        elif i_ok == n:
            premise('swap', 'ok', '0x35 / 0x64：主机把豆花当 ISO 键盘——发 0x35 出左上角的 %s、发 0x64 出 Z 左边的 %s，按 PC 惯例发正好对，"互换"开关默认关'
                    % (show(got_of(0x35, 0)), show(got_of(0x64, 0))) + codes)
        elif a_ok == n:
            premise('swap', 'bad', '0x35 / 0x64：主机把豆花当 ANSI 键盘——发 0x35 出 %s、发 0x64 出 %s，和苹果 ISO 键帽（左上角 %s、Z 左边 %s）正好反了。ISO 形状时要打开"互换"开关'
                    % (show(got_of(0x35, 0)), show(got_of(0x64, 0)), show_cell(iso.get(0x35, [None])[0]), show_cell(iso.get(0x64, [None])[0])) + codes)
        else:
            premise('swap', 'bad', '0x35 / 0x64：两种都对不上——发 0x35 出 %s、0x64 出 %s；当 ISO 键盘该是 %s / %s，当 ANSI 键盘该是 %s / %s'
                    % (show(got_of(0x35, 0)), show(got_of(0x64, 0)), show_cell(iso.get(0x35, [None])[0]), show_cell(iso.get(0x64, [None])[0]),
                       show_cell(ansi.get(0x35, [None])[0]), show_cell(ansi.get(0x64, [None])[0])) + codes)

    # ── 0x32 和 0x31 一样吗 ──
    same32, diff32 = 0, []
    for li in layers:
        g31, g32 = got_of(0x31, li), got_of(0x32, li)
        if g31 is None or g32 is None:
            continue
        if (g31['ch'], g31['dead']) == (g32['ch'], g32['dead']):
            same32 += 1
        else:
            diff32.append('%s：0x31 出 %s，0x32 出 %s' % (names[li], show(g31), show(g32)))
    if not same32 and not diff32:
        premise('0x32', 'unknown', '0x32：没收到')
    elif diff32:
        premise('0x32', 'bad', '0x32 和 0x31 不一样（%s）。回车左边那个键发哪个码要重新定' % '；'.join(diff32))
    else:
        premise('0x32', 'ok', '0x32：%d 层都和 0x31 出一样的字，回车左边那个键发 0x31 没问题' % same32)

    # ── 第三层 ──
    if not meta.get('third'):
        premise('third', 'skip', '第三层：没扫（这个布局在%s上没有第三层；美式布局的右 Alt 只是 Alt）' % kt.PLATFORM_NAMES[platform])
    else:
        h23, n23 = score(gots, steps, main, (2, 3))
        l3_recv = sum(1 for s in steps if s['layer'] >= 2 and s['i'] in by_i)
        if l3_recv == 0:
            premise('third', 'unknown', '第三层（%s）：没扫到（中途停了，或者普通层对不上没往下扫）' % names[2])
        else:
            premise('third', 'ok' if n23 and h23 == n23 else 'bad', '第三层（%s / %s）：%d/%d 对（%s）' % (names[2], names[3], h23, n23, pct(h23, n23)))

    # ── 死键 ──
    exp_dead = got_dead = 0
    accent_notes = []
    wrong_dead = []
    for s, g in zip(steps, gots):
        exp = cell_for(main, s) if s['usage'] not in (0x32, kt.KEYPAD_DECIMAL) else None
        if g is None or exp is None:
            continue
        if exp[1]:
            exp_dead += 1
            if g['dead']:
                got_dead += 1
                if g['ch'] and not dead_accent_ok(g, exp):
                    accent_notes.append('%s %s：期望 %s，组合出 %s' % (kt.usage_label(s['usage']), names[s['layer']], kt.visible_text(exp[0]), kt.visible_text(g['ch'])))
            else:
                wrong_dead.append('%s %s 该是死键 %s，收到 %s' % (kt.usage_label(s['usage']), names[s['layer']], kt.visible_text(exp[0]), show(g)))
        elif g['dead']:
            wrong_dead.append('%s %s 不该是死键（期望 %s），主机上是死键' % (kt.usage_label(s['usage']), names[s['layer']], kt.visible_text(exp[0])))
    if exp_dead == 0 and not wrong_dead:
        premise('dead', 'skip', '死键：扫到的层里没有死键')
    else:
        def some(items):
            return '；'.join(items[:6]) + ('；……共 %d 处' % len(items) if len(items) > 6 else '')
        premise('dead', 'bad' if wrong_dead else 'ok', '死键：期望 %d 个，主机上 %d 个是死键%s%s'
                % (exp_dead, got_dead, '；' + some(wrong_dead) if wrong_dead else '',
                   '（附加符号写法不同、不算错：%s）' % some(accent_notes) if accent_notes else ''))

    # ── 小键盘小数点 ──
    gk = got_of(kt.KEYPAD_DECIMAL, 0)
    kpdl = kt.keypad_decimal(catalog.get(layout, {}), platform)
    rec_k = by_i.get(next((s['i'] for s in steps if s['usage'] == kt.KEYPAD_DECIMAL), -1))
    keys_k = [e.get('key') for e in (rec_k or {}).get('ev', []) if e.get('t') == 'd']
    if gk is None:
        premise('kpdl', 'unknown', '小键盘小数点：没收到')
    elif 'Delete' in keys_k or (gk['ch'] is None):
        premise('kpdl', 'unknown', '小键盘小数点：主机的 NumLock 关着（收到 Delete / 没出字），看不出')
    else:
        premise('kpdl', 'ok' if gk['ch'] == kpdl else 'bad', '小键盘小数点：期望 %r（%s），收到 %s'
                % (kpdl, '照微软布局表' if platform == 'win' else '取自 xkb', show(gk)))

    # ── 对齐：每一步的 e.code 对不对 ──
    bad_codes = []
    for s, g in zip(steps, gots):
        if g is None or not g['code']:
            continue
        want = {kt.HID_TO_CODE.get(s['usage'])}
        if s['usage'] in (0x35, 0x64):
            want |= {'Backquote', 'IntlBackslash'}   # 苹果当 ISO 键盘时 code 跟着对调
        if s['usage'] == 0x32:
            want |= {'IntlHash', 'Backslash'}
        if g['code'] not in want:
            bad_codes.append(s['i'])
    coded = sum(1 for g in gots if g and g['code'])
    if coded == 0:
        premise('align', 'unknown', '对齐：网页没给 e.code，没法核对有没有错位')
    elif len(bad_codes) > max(3, coded // 10):
        premise('align', 'bad', '对齐：%d/%d 步的 e.code 和发的键对不上（第 %s 步……），多半丢了键、错位了，上面的结论不可信，重扫一次'
                % (len(bad_codes), coded, '、'.join(str(i + 1) for i in bad_codes[:5])))
    else:
        premise('align', 'ok', '对齐：%d 步里 %d 步 e.code 对得上%s' % (coded, coded - len(bad_codes),
                                                                   '（对不上的：第 %s 步）' % '、'.join(str(i + 1) for i in bad_codes) if bad_codes else ''))

    # ── 和同名数据一样吗（iPad = macOS、Linux = xkb 默认变体、三星 = AOSP KCM） ──
    hall, nall = score(gots, steps, main, layers)
    what = {'ipad': 'iPadOS 和同名 macOS 布局（CLDR osx）', 'mac': '这台 Mac 和 CLDR osx（macOS 10.9 的数据）',
            'linux': '这台 Linux 和 xkb 默认变体', 'android': '这台手机和 AOSP 的 KCM', 'win': '这台 Windows 和 CLDR windows'}[host]
    premise('same', 'ok' if nall and hall == nall else 'bad', '%s：%d/%d 格一样（%s）' % (what, hall, nall, pct(hall, nall)))

    # ── 不对的格 ──
    for s, g in zip(steps, gots):
        if s['usage'] in (0x32, kt.KEYPAD_DECIMAL):
            continue
        exp = cell_for(main, s)
        if exp is None or g is None:
            continue
        if not same(g, exp):
            res['mismatches'].append({'i': s['i'], 'key': kt.usage_label(s['usage']), 'layer': names[s['layer']],
                                      'exp': show_cell(exp), 'got': show(g)})
    return res


MARK = {'ok': '✓', 'bad': '✗', 'unknown': '?', 'skip': '·'}


def report(res):
    out = ['扫描：%s · 布局 %s · %d 步，收到 %d 步%s' % (kt.HOST_NAMES[res['host']], res['layout'], res['steps'], res['received'],
                                                 '（完整）' if res['complete'] else '（不完整）'),
           '对照数据：%s' % res.get('main', '—'), '', '结论（✓ 成立 / ✗ 不成立 / ? 看不出 / · 没扫）：']
    for p in res['premises']:
        out.append('  %s %s' % (MARK[p['status']], p['text']))
    if res['mismatches']:
        out += ['', '对不上的格（%d 个，期望 → 收到）：' % len(res['mismatches'])]
        for m in res['mismatches'][:60]:
            out.append('  第 %3d 步  %-10s %-8s %s → %s' % (m['i'] + 1, m['key'], m['layer'], m['exp'], m['got']))
        if len(res['mismatches']) > 60:
            out.append('  ……还有 %d 个，见 compare.json' % (len(res['mismatches']) - 60))
    if res['ranking']:
        out += ['', '和每份数据比（普通 + Shift 层）：' + ' · '.join('%s %s' % (r['table'], pct(r['hit'], r['n'])) for r in res['ranking'])]
    return '\n'.join(out)


def run(d):
    with open(os.path.join(d, 'scan.json'), encoding='utf-8') as f:
        scan = json.load(f)
    res = analyze(scan, kt.load_tables(), {r['id']: r for r in kt.read_catalog()})
    text = report(res)
    with open(os.path.join(d, 'report.txt'), 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    with open(os.path.join(d, 'compare.json'), 'w', encoding='utf-8') as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    print(text)
    print('\n（存在 %s/report.txt、compare.json）' % d)


# ═══════════ 自测：拿生成的数据造"假主机" ═══════════

def simulate(tables, catalog, host, layout, source=None, iso_codes=False, tweak=None):
    """照某份数据（默认就是选的那份）当一台"完全照数据出字"的主机，造一份 scan.json。
    iso_codes：Mac 当 ISO 键盘时 0x35 / 0x64 的 e.code 也对调；tweak(step, rec) 可以改某一步。"""
    platform = kt.HOSTS[host]
    third = kt.has_third_layer(tables, platform, layout)
    steps = kt.scan_plan(host, layout, third)
    src_p, src_s = source or (platform, layout)
    t = tables[src_p][src_s]
    records = []
    for s in steps:
        u = 0x31 if s['usage'] == 0x32 else s['usage']
        code = kt.HID_TO_CODE[s['usage']]
        if iso_codes and u in (0x35, 0x64):
            code = 'IntlBackslash' if u == 0x35 else 'Backquote'
        tail = [{'t': 'd', 'key': ' ', 'code': 'Space'}, {'t': 'd', 'key': 'Tab', 'code': 'Tab'}]
        if u == kt.KEYPAD_DECIMAL:
            ch = kt.keypad_decimal(catalog[layout], platform)
            rec = {'ev': [{'t': 'd', 'key': ch, 'code': code}] + tail, 'text': ch + ' '}
        else:
            c = t.get(u, [None] * 4)[s['layer']]
            if c is None:
                rec = {'ev': tail, 'text': ' '}
            elif c[1]:
                rec = {'ev': [{'t': 'd', 'key': 'Dead', 'code': code}] + tail, 'text': c[0]}
            else:
                rec = {'ev': [{'t': 'd', 'key': c[0], 'code': code}] + tail, 'text': c[0] + ' '}
        rec['i'] = s['i']
        if tweak:
            rec = tweak(s, rec)
        if rec is not None:
            records.append(rec)
    return {'meta': {'host': host, 'platform': platform, 'layout': layout, 'third': third, 'complete': True},
            'steps': steps, 'records': records}


def selftest():
    tables = kt.load_tables()
    catalog = {r['id']: r for r in kt.read_catalog()}
    ok = [0]
    fails = []

    def check(cond, what):
        if cond:
            ok[0] += 1
        else:
            fails.append(what)

    def status(res, key):
        return next((p['status'] for p in res['premises'] if p['key'] == key), None)

    # 一台完全照数据出字的 Linux（德语）：每条都成立
    r = analyze(simulate(tables, catalog, 'linux', 'de'), tables, catalog)
    for key in ('layout', '0x32', 'third', 'dead', 'kpdl', 'align', 'same'):
        check(status(r, key) == 'ok', 'Linux 德语完美主机：%s 应该成立，实际 %s' % (key, status(r, key)))
    check(not r['mismatches'] and r['ranking'][0]['table'] == 'linux:de', 'Linux 德语：没有不对的格、最像 linux:de')
    report(r)

    # iPad 当 ISO 键盘 / 当 ANSI 键盘
    r = analyze(simulate(tables, catalog, 'ipad', 'de', ('mac', 'de.iso'), iso_codes=True), tables, catalog)
    check(status(r, 'swap') == 'ok' and r['main'] == 'mac:de.iso', 'iPad 当 ISO：认出 ISO 型（%s）' % r.get('main'))
    check(status(r, 'align') == 'ok', 'iPad 当 ISO：e.code 对调不算错位')
    r = analyze(simulate(tables, catalog, 'ipad', 'de', ('mac', 'de')), tables, catalog)
    check(status(r, 'swap') == 'bad' and r['main'] == 'mac:de', 'iPad 当 ANSI：认出两个键反了')
    check(any('ANSI' in p['text'] for p in r['premises'] if p['key'] == 'swap'), 'iPad 当 ANSI：说清楚是 ANSI')

    # 0x32 什么都不出
    def no32(s, rec):
        if s['usage'] == 0x32:
            return dict(rec, ev=rec['ev'][-2:], text=' ')
        return rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=no32), tables, catalog)
    check(status(r, '0x32') == 'bad', '0x32 不出字：认出来')

    # 主机其实是美式
    r = analyze(simulate(tables, catalog, 'linux', 'de', ('linux', 'us')), tables, catalog)
    check(status(r, 'layout') == 'bad' and r['ranking'][0]['table'] in ('linux:us', 'android:us', 'win:us'), '主机布局没设对：认出来更像美式（%s）' % r['ranking'][:1])

    # 错位：中间丢一步、后面整体挪一位
    def shift(s, rec):
        if 20 <= s['i'] < 60:
            nxt = simulate(tables, catalog, 'linux', 'de')['records'][s['i'] + 1]
            return dict(nxt, i=s['i'])
        return rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=shift), tables, catalog)
    check(status(r, 'align') == 'bad', '错位：认出来')

    # 死键成了普通字；组合出的字写法不同（Linux 的 ´ + 空格 = '）不算错
    def nodead(s, rec):
        if s['usage'] == 0x2E and s['layer'] == 0:
            return dict(rec, ev=[{'t': 'd', 'key': '´', 'code': 'Equal'}] + rec['ev'][-2:], text='´ ')
        return rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=nodead), tables, catalog)
    check(status(r, 'dead') == 'bad', '死键成了普通字：认出来')

    def apostrophe(s, rec):
        if s['usage'] == 0x2E and s['layer'] == 0:
            return dict(rec, text="'")
        return rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=apostrophe), tables, catalog)
    check(status(r, 'dead') == 'ok', "´ + 空格组合出 ' 不算错")

    # 死键 + 空格组合不出字（Linux 德语 AltGr+Shift+<> 是 dead_belowmacron，本机 Compose 里没有它 + 空格）：死键认出来了就算对，
    # 第三层、"和同名数据一样吗"不能因为它打 ✗
    deads = [s['i'] for s in kt.scan_plan('linux', 'de', True)
             if s['usage'] != 0x32 and (tables['linux']['de'].get(s['usage'], [None] * 4)[s['layer']] or ('', False))[1]]
    nothing = deads[-1] if deads else -1

    def no_compose(s, rec):
        return dict(rec, text='') if s['i'] == nothing else rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=no_compose), tables, catalog)
    check(nothing >= 0 and all(status(r, k) == 'ok' for k in ('dead', 'third', 'same')) and not r['mismatches'],
          '死键组合不出字不算错：%s' % [(p['key'], p['status']) for p in r['premises']])

    # iPad 式的死键：没有 key = Dead，走输入法组合
    def comp(s, rec):
        if rec['ev'] and rec['ev'][0].get('key') == 'Dead':
            return dict(rec, ev=[{'t': 'd', 'key': 'Unidentified', 'code': rec['ev'][0]['code']}, {'t': 'cs', 'data': ''},
                                 {'t': 'ce', 'data': rec['text']}] + rec['ev'][1:])
        return rec
    r = analyze(simulate(tables, catalog, 'mac', 'de', ('mac', 'de.iso'), iso_codes=True, tweak=comp), tables, catalog)
    check(status(r, 'dead') == 'ok' and status(r, 'same') == 'ok', '输入法组合式的死键也认得')

    # NumLock 关着
    def numoff(s, rec):
        if s['usage'] == kt.KEYPAD_DECIMAL:
            return dict(rec, ev=[{'t': 'd', 'key': 'Delete', 'code': 'NumpadDecimal'}] + rec['ev'][-2:], text=' ')
        return rec
    r = analyze(simulate(tables, catalog, 'linux', 'de', tweak=numoff), tables, catalog)
    check(status(r, 'kpdl') == 'unknown', 'NumLock 关着：看不出、不算错')

    # 美式在 Windows 上：不扫第三层
    r = analyze(simulate(tables, catalog, 'win', 'us'), tables, catalog)
    check(status(r, 'third') == 'skip' and r['steps'] == 2 * len(kt.SCAN_USAGES) + 1, '美式 Windows：第三层不扫')
    check(simulate(tables, catalog, 'ipad', 'us')['meta']['third'], '美式 iPad：扫 ⌥ 层')

    # 一步都没收到
    r = analyze(simulate(tables, catalog, 'android', 'de', tweak=lambda s, rec: None), tables, catalog)
    check(status(r, 'layout') == 'unknown', '一步都没收到：说看不出')

    if fails:
        for f in fails:
            print('  不对：' + f, file=sys.stderr)
        print('扫描分析自测：%d 项检查，%d 项失败' % (ok[0] + len(fails), len(fails)), file=sys.stderr)
        return False
    print('扫描分析自测：%d 项检查，0 项失败' % ok[0])
    return True


def main():
    if sys.argv[1:] == ['--selftest']:
        sys.exit(0 if selftest() else 1)
    if len(sys.argv) != 2:
        sys.exit('用法：python3 tools/kbd/compare.py <记录目录> | --selftest')
    run(sys.argv[1])


if __name__ == '__main__':
    main()
