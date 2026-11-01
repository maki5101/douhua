#!/usr/bin/env python3
# 打字测试的分析（tools/type-log.sh 记下的一次，tools/type-analyze.sh 调）。
# 输入：clock.txt（对时）、phone.log（logcat，标签 douhua-keys 的 L/K/p/u 行，格式见 TouchRouter.Trace）、
#      typing.json（网页记的每句目标、开始 / 结束时间、电脑收到的每个按键）；用 --desktop（旧名 --dex）记的（页面开在手机上）多一个空文件 dex。
# 做法：手机的触点按时间分到句子里；每句按手机实际判定的键重放编辑（⌫ 删掉最后一个字），最终的字和目标做编辑距离对齐 →
#   对 / 按错没改（替换）/ 按空（目标里有、没打出来）/ 多打；被 ⌫ 删掉的字按"打它之前的字对应到目标哪里"推出想打的字 →
#   按错改过 / 删掉的对字。误按率 =（按空 + 按错没改 + 按错改过）/ 目标字数。
#   每个键的落点偏移 = 触点 − 想按的键的中心（像素，和占键宽 / 键高的比例）。电脑收到的按键只拿来核对（丢键 / 多键）。
# 输出：终端摘要、presses.csv（每个触点一行）、offsets.csv（每个键一行）、replay.txt（给 KeyReplay.java 用真正的 TouchRouter 重放）。
# 用法：python3 analyze.py <记录目录>
#      python3 analyze.py --selftest [目录]   用造好的小日志自测（tools/test.sh 跑）；给了目录就把造的文件和 replay.txt 留在那里
# 学习落点偏移、热力图这次都不做。只认一块键盘（每个 L 行开始一份新布局）。
import csv, json, os, re, statistics, sys, tempfile

REGION_BUTTON, REGION_KEY, REGION_KEYBOARD = 1, 3, 4
BACKSPACE, ENTER = 0x2A, 0x28
USAGE_CHAR = {0x04 + i: chr(ord('a') + i) for i in range(26)}
USAGE_CHAR.update({0x1E + i: str((i + 1) % 10) for i in range(10)})
USAGE_CHAR.update({0x2C: ' ', 0x2D: '-', 0x2E: '=', 0x2F: '[', 0x30: ']', 0x31: '\\', 0x33: ';', 0x34: "'",
                   0x35: '`', 0x36: ',', 0x37: '.', 0x38: '/'})
CHAR_USAGE = {c: u for u, c in USAGE_CHAR.items()}
OTHER_CHAR = '¤'   # 自带 Shift 的符号键之类：出了字，但不是测试句子里会有的
# 网页 KeyboardEvent.code → HID 用法码（物理键位，和电脑的键盘布局无关）
CODE_USAGE = {'Key' + chr(ord('A') + i): 0x04 + i for i in range(26)}
CODE_USAGE.update({'Digit%d' % ((i + 1) % 10): 0x1E + i for i in range(10)})
CODE_USAGE.update({'Space': 0x2C, 'Comma': 0x36, 'Period': 0x37, 'Backspace': BACKSPACE, 'Enter': ENTER,
                   'Minus': 0x2D, 'Equal': 0x2E, 'BracketLeft': 0x2F, 'BracketRight': 0x30, 'Backslash': 0x31,
                   'Semicolon': 0x33, 'Quote': 0x34, 'Backquote': 0x35, 'Slash': 0x38, 'Tab': 0x2B, 'Escape': 0x29})
MODIFIER_CODES = {'ShiftLeft', 'ShiftRight', 'ControlLeft', 'ControlRight', 'AltLeft', 'AltRight', 'MetaLeft', 'MetaRight',
                  'CapsLock', 'Fn', 'OSLeft', 'OSRight'}
SENTENCE_SLACK = 0.15   # 电脑收到 ↵ 之后这么久以内的触点还算这一句（手机先按、电脑后收；下一句的第一下不会这么快）
LOG_RE = re.compile(r'^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+douhua-keys\s*:\s?(.*)$')


# ═══════════ 读文件 ═══════════

def clock_offset(path):
    """手机 − 电脑（秒）：clock.txt 每行 '电脑发出ns 手机ns 手机EPOCHREALTIME 电脑收回ns'，取来回最快的那次。"""
    best = None
    for line in open(path):
        p = line.split()
        if len(p) < 3:
            continue
        t0, ph, t1 = int(p[0]), int(p[1]), int(p[-1])
        rtt, off = t1 - t0, ph - (t0 + t1) / 2
        if best is None or rtt < best[0]:
            best = (rtt, off)
    if best is None:
        raise SystemExit(f'{path} 里没有对时记录')
    return best[1] / 1e9, best[0] / 1e9


class Layout:
    def __init__(self, area):
        self.area = area   # (l, t, r, b) 或 None（没有键盘）
        self.keys = []     # [(type, id, l, t, r, b)]

    def find_rect(self, rect):
        for k in self.keys:
            if tuple(k[2:]) == rect:
                return k
        return None

    def find_usage(self, usage):
        for k in self.keys:
            if k[0] == REGION_KEY and k[1] == usage:
                return k
        return None


def parse_phone(path, off):
    """→ 触点列表（按记下的顺序），时间换成电脑的时钟（秒）。每个进程（App / 服务）各自记的布局分开算。"""
    layouts, presses, open_press = {}, [], {}
    for line in open(path, errors='replace'):
        m = LOG_RE.match(line)
        if not m:
            continue
        t = float(m.group(1)) - off
        pid = int(m.group(2))
        f = m.group(4).split()
        if not f:
            continue
        kind = f[0]
        if kind == 'L':
            layouts[pid] = Layout(tuple(int(v) for v in f[1:5]) if len(f) >= 6 else None)
        elif kind == 'K' and pid in layouts and len(f) >= 7:
            layouts[pid].keys.append((int(f[1]), int(f[2], 16)) + tuple(int(v) for v in f[3:7]))
        elif kind == 'p' and len(f) >= 11:
            p = dict(t=t, pid=pid, slot=int(f[2]), x=float(f[3]), y=float(f[4]), hit=f[5], near=f[6],
                     used=None if f[7] == '-' else tuple(int(v) for v in f[7:11]), layout=layouts.get(pid),
                     up_x=None, up_y=None)
            presses.append(p)
            open_press[(pid, p['slot'])] = p
        elif kind == 'u' and len(f) >= 5:
            p = open_press.pop((pid, int(f[2])), None)
            if p is not None:
                p['up_x'], p['up_y'] = float(f[3]), float(f[4])
    for p in presses:
        p['key'] = used_key(p)
        p['kind'], p['char'] = press_kind(p['key'])
    return presses


def used_key(p):
    """实际用的键 (type, id)；布局里找不到（日志开晚了）就按 hit / near 猜成普通键。"""
    if p['used'] is None:
        return None
    if p['layout'] is not None:
        k = p['layout'].find_rect(p['used'])
        if k is not None:
            return k[0], k[1]
    guess = p['hit'] if p['hit'] != '-' else p['near']
    return (REGION_KEY, int(guess, 16)) if guess != '-' else None


def press_kind(key):
    """→ (char / bs / enter / none, 出的字)。none = 没出字（没按到键、按到特殊键 / 修饰键 / 快捷键）。"""
    if key is None or key[0] != REGION_KEY:
        return 'none', None
    usage, mods = key[1] & 0xFF, key[1] >> 8
    if usage == BACKSPACE and mods == 0:
        return 'bs', None
    if usage == ENTER and mods == 0:
        return 'enter', None
    if usage in USAGE_CHAR:
        return 'char', USAGE_CHAR[usage] if mods == 0 else OTHER_CHAR
    return 'none', None


# ═══════════ 对齐 ═══════════

def align(a, b):
    """编辑距离对齐 → [(a 的下标或 None, b 的下标或 None)]；a、b 是字符串或列表。"""
    n, m = len(a), len(b)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j - 1] + (a[i - 1] != b[j - 1]), d[i - 1][j] + 1, d[i][j - 1] + 1)
    ops, i, j = [], n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + (a[i - 1] != b[j - 1]):
            ops.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif i > 0 and d[i][j] == d[i - 1][j] + 1:
            ops.append((i - 1, None))
            i -= 1
        else:
            ops.append((None, j - 1))
            j -= 1
    ops.reverse()
    return ops


def next_target(prefix, target):
    """已经打了 prefix，接下来想打的是目标的第几个字（打到头了返回 None）：取和 prefix 编辑距离最小的目标前缀。"""
    n, m = len(prefix), len(target)
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        for j in range(1, m + 1):
            cur[j] = min(prev[j - 1] + (prefix[i - 1] != target[j - 1]), prev[j] + 1, cur[j - 1] + 1)
        prev = cur
    best = min(range(m + 1), key=lambda j: (prev[j], abs(j - n)))
    return best if best < m else None


def classify(target, presses, end_t):
    """一句：按手机实际判定的键重放编辑，给每个触点标 cls / intended，返回 (最终的字, 这句的计数)。"""
    text = []   # [(字, 触点)]
    for p in presses:
        p['cls'], p['intended'] = {'bs': '⌫', 'enter': '↵', 'none': '没出字'}.get(p['kind'], ''), None
        if p['kind'] == 'char':
            prefix = ''.join(c for c, _ in text)
            p['_next'] = next_target(prefix, target)
            text.append((p['char'], p))
        elif p['kind'] == 'bs' and text:
            _, q = text.pop()
            q['_deleted'] = True
    final = ''.join(c for c, _ in text)
    count = dict.fromkeys(['对', '按错没改', '按错改过', '按空', '按空有触点', '多打', '删掉的对字'], 0)
    for p in presses:
        if p.get('_deleted'):
            ti = p['_next']
            p['intended'] = target[ti] if ti is not None else None
            p['cls'] = '删掉的对字' if p['intended'] == p['char'] else '按错改过'
            count[p['cls']] += 1
    ops = align(final, target)
    for fi, ti in ops:
        if fi is not None:
            p = text[fi][1]
            if ti is None:
                p['cls'] = '多打'
            else:
                p['intended'] = target[ti]
                p['cls'] = '对' if final[fi] == target[ti] else '按错没改'
            count[p['cls']] += 1
    # 按空：目标里有、没打出来。前后两个打出来的字之间要是有没出字的触点，算它按空了（想按的就是漏掉的字）
    times = {fi: text[fi][1]['t'] for fi in range(len(text))}
    for k, (fi, ti) in enumerate(ops):
        if fi is not None:
            continue
        count['按空'] += 1
        lo = max([times[f] for f, _ in ops[:k] if f is not None], default=float('-inf'))
        hi = min([times[f] for f, _ in ops[k + 1:] if f is not None], default=end_t)
        for p in presses:
            if p['kind'] == 'none' and p['cls'] == '没出字' and lo < p['t'] < hi:
                p['cls'], p['intended'] = '按空', target[ti]
                count['按空有触点'] += 1
                break
    return final, count


# ═══════════ 一次记录 ═══════════

def analyze(d, quiet=False):
    off, rtt = clock_offset(os.path.join(d, 'clock.txt'))
    presses = parse_phone(os.path.join(d, 'phone.log'), off)
    page = json.load(open(os.path.join(d, 'typing.json')))
    page_off = off if os.path.exists(os.path.join(d, 'dex')) else 0.0   # 页面开在手机（含它接的外接屏）上：它的时间也是手机的时钟
    sentences = [s for s in page.get('sentences', []) if s.get('end') is not None]
    total = dict.fromkeys(['对', '按错没改', '按错改过', '按空', '按空有触点', '多打', '删掉的对字'], 0)
    target_chars = lost = extra = mismatched = 0
    used, details = set(), []
    lo = None
    for i, s in enumerate(sentences):
        shown, end = s['shown'] / 1000 - page_off, s['end'] / 1000 - page_off
        if lo is None:
            lo = shown - 0.05
        hi = end + SENTENCE_SLACK
        mine = [p for p in presses if lo < p['t'] <= hi]
        lo = hi
        for p in mine:
            p['sentence'] = i + 1
            used.add(id(p))
        final, count = classify(s['target'], mine, end)
        for k in total:
            total[k] += count[k]
        target_chars += len(s['target'])
        if final.lower() != s.get('text', '').lower():
            mismatched += 1
        # 电脑核对：手机出的键 vs 电脑收到的键（都去掉修饰键、不算按住连发）
        phone_keys = [p['key'][1] & 0xFF for p in mine if p['kind'] != 'none']
        pc_keys = [CODE_USAGE.get(e['code'], e['code']) for e in s.get('events', [])
                   if e.get('type') == 'down' and not e.get('repeat') and e.get('code') not in MODIFIER_CODES]
        for a, b in align(phone_keys, pc_keys):
            if a is not None and (b is None or phone_keys[a] != pc_keys[b]):
                lost += 1
            if b is not None and (a is None or phone_keys[a] != pc_keys[b]):
                extra += 1
        if final != s['target']:
            details.append((i + 1, s['target'], final))
    outside = [p for p in presses if id(p) not in used]

    # 落点偏移：触点 − 想按的键的中心
    for p in presses:
        p['dx'] = p['dy'] = p['dx_rel'] = p['dy_rel'] = None
        if p.get('intended') is None or p['layout'] is None or p['intended'] not in CHAR_USAGE:
            continue
        k = p['layout'].find_usage(CHAR_USAGE[p['intended']])
        if k is None:
            continue
        l, t, r, b = k[2:]
        p['dx'], p['dy'] = p['x'] - (l + r) / 2, p['y'] - (t + b) / 2
        p['dx_rel'], p['dy_rel'] = p['dx'] / (r - l), p['dy'] / (b - t)
    offsets = {}
    for p in presses:
        if p['dx'] is not None:
            offsets.setdefault(p['intended'], []).append(p)
    gap = [p for p in presses if p['hit'] == '-' and id(p) in used]
    gap_known = [p for p in gap if p.get('intended') is not None]
    res = dict(off=off, rtt=rtt, sentences=len(sentences), target_chars=target_chars, presses=len(presses) - len(outside),
               outside=len(outside), counts=total, lost=lost, extra=extra, mismatched=mismatched, details=details,
               gap=len(gap), gap_right=sum(1 for p in gap_known if p['char'] == p['intended']),
               gap_wrong=sum(1 for p in gap_known if p['char'] is not None and p['char'] != p['intended']),
               gap_none=sum(1 for p in gap if p['char'] is None and p['kind'] == 'none'),
               errors=total['按空'] + total['按错没改'] + total['按错改过'])
    res['rate'] = res['errors'] / target_chars if target_chars else 0.0
    res['offsets'] = {c: summarize(ps) for c, ps in offsets.items()}
    allp = [p for ps in offsets.values() for p in ps]
    res['all_offset'] = summarize(allp) if allp else None
    res['replay_touches'] = write_outputs(d, presses, used, res)
    if not quiet:
        report(d, res)
    return res


def summarize(ps):
    def mean(v):
        return sum(v) / len(v)

    def std(v):
        return statistics.pstdev(v) if len(v) > 1 else 0.0
    dx, dy = [p['dx'] for p in ps], [p['dy'] for p in ps]
    return dict(n=len(ps), dx=mean(dx), dy=mean(dy), dx_std=std(dx), dy_std=std(dy),
                dx_rel=mean([p['dx_rel'] for p in ps]), dy_rel=mean([p['dy_rel'] for p in ps]))


def key_label(key):
    if key is None:
        return '-'
    t, kid = key
    if t == REGION_KEY and kid >> 8 == 0 and kid in USAGE_CHAR:
        return {' ': '空格'}.get(USAGE_CHAR[kid], USAGE_CHAR[kid])
    return {BACKSPACE: '⌫', ENTER: '↵'}.get(kid, f'{t}:{kid:x}')


def write_outputs(d, presses, used, res):
    with open(os.path.join(d, 'presses.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['sentence', 't', 'pid', 'slot', 'x', 'y', 'up_x', 'up_y', 'hit', 'near', 'key', 'char', 'class',
                    'intended', 'dx', 'dy', 'dx_rel', 'dy_rel', 'gap'])
        for p in presses:
            w.writerow([p.get('sentence', ''), f"{p['t']:.3f}", p['pid'], p['slot'], p['x'], p['y'], p['up_x'], p['up_y'],
                        p['hit'], p['near'], key_label(p['key']), p['char'] if p['char'] is not None else '',
                        p.get('cls', '句子外'), p.get('intended') or '', fmt(p['dx']), fmt(p['dy']), fmt(p['dx_rel'], 3),
                        fmt(p['dy_rel'], 3), int(p['hit'] == '-')])
    with open(os.path.join(d, 'offsets.csv'), 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['key', 'n', 'dx_mean', 'dy_mean', 'dx_std', 'dy_std', 'dx_rel_mean', 'dy_rel_mean'])
        for c, o in sorted(res['offsets'].items()):
            w.writerow([c, o['n'], fmt(o['dx']), fmt(o['dy']), fmt(o['dx_std']), fmt(o['dy_std']),
                        fmt(o['dx_rel'], 3), fmt(o['dy_rel'], 3)])
    # 给 KeyReplay：每份布局一段（L 范围 + K 键），后面是用这份布局的触点
    #   T x y 实际用的键(id 或 -) 实际键的 l t r b(或 - - - -) 想按的键(id 或 -)
    n, last = 0, None
    with open(os.path.join(d, 'replay.txt'), 'w') as f:
        f.write('# tools/typelog/analyze.py 导出，给 KeyReplay.java\n')
        for p in presses:
            lay = p['layout']
            if id(p) not in used or lay is None or lay.area is None:
                continue
            if lay is not last:
                f.write('L %d %d %d %d\n' % lay.area)
                for k in lay.keys:
                    f.write('K %d %x %d %d %d %d\n' % k)
                last = lay
            real = '-' if p['key'] is None else '%x' % p['key'][1]
            rect = '- - - -' if p['used'] is None else '%d %d %d %d' % p['used']
            want = '%x' % CHAR_USAGE[p['intended']] if p.get('intended') in CHAR_USAGE else '-'
            f.write(f"T {p['x']} {p['y']} {real} {rect} {want}\n")
            n += 1
    return n


def fmt(v, digits=1):
    return '' if v is None else f'{v:.{digits}f}'


def report(d, res):
    c = res['counts']
    tc = res['target_chars'] or 1
    print(f'== {d}')
    print(f"对时：手机 − 电脑 = {res['off'] * 1000:.1f} ms（来回 {res['rtt'] * 1000:.1f} ms）")
    print(f"{res['sentences']} 句，目标 {res['target_chars']} 字；句子里的手机触点 {res['presses']} 个，句子外 {res['outside']} 个")
    print(f"  对 {c['对']}，按错没改 {c['按错没改']}，按错改过 {c['按错改过']}，按空 {c['按空']}（其中有触点 {c['按空有触点']}），"
          f"多打 {c['多打']}，删掉的对字 {c['删掉的对字']}")
    print(f"  误按率 {res['rate'] * 100:.1f}%（按空 {c['按空'] / tc * 100:.1f}% + 按错没改 {c['按错没改'] / tc * 100:.1f}% "
          f"+ 按错改过 {c['按错改过'] / tc * 100:.1f}%；多打另算 {c['多打'] / tc * 100:.1f}%）")
    print(f"  按在缝里的触点 {res['gap']} 个：分对 {res['gap_right']}、分错 {res['gap_wrong']}、没出字 {res['gap_none']}"
          f"（旧规则下这些全都没反应；新旧规则逐个比见 KeyReplay）")
    print(f"电脑核对：手机出了键、电脑没收到 {res['lost']} 个（蓝牙丢键），电脑多收到 {res['extra']} 个；"
          f"网页上的字和手机重放对不上的 {res['mismatched']} 句")
    for i, target, final in res['details'][:20]:
        print(f'  第 {i} 句  目标 "{target}"  打出 "{final}"')
    o = res['all_offset']
    if o:
        print(f"落点偏移（触点 − 想按的键的中心，像素；正 = 偏右 / 偏下）：全体 {o['n']} 次，"
              f"dx {o['dx']:+.1f}（{o['dx_rel'] * 100:+.0f}% 键宽），dy {o['dy']:+.1f}（{o['dy_rel'] * 100:+.0f}% 键高）")
        print('  键   次数    dx     dy   ±dx   ±dy   dx/键宽 dy/键高')
        for ch, k in sorted(res['offsets'].items(), key=lambda e: -e[1]['n']):
            name = {' ': '空格'}.get(ch, ch)
            print(f"  {name:<4}{k['n']:>4} {k['dx']:+6.1f} {k['dy']:+6.1f} {k['dx_std']:5.1f} {k['dy_std']:5.1f}"
                  f"   {k['dx_rel'] * 100:+5.0f}%  {k['dy_rel'] * 100:+5.0f}%")
    print(f"写好了 {d}/presses.csv、offsets.csv、replay.txt（{res['replay_touches']} 个触点）")


# ═══════════ 自测 ═══════════

def selftest(d=None):
    """造一份小记录（布局照 KeyboardPanel：每格四边各缩 5、行高 100），覆盖 全对 / 按错没改 / 按空 / 按错后 ⌫ 改对 / 多打。"""
    d = d or tempfile.mkdtemp(prefix='typelog-selftest-')
    os.makedirs(d, exist_ok=True)
    keys = []

    def row(y, items):   # items: [(type, id, 宽几格)]，None id = 空位
        x = 0
        for typ, kid, w in items:
            if kid is not None:
                keys.append((typ, kid, x + 5, y + 5, int(x + w * 100) - 5, y + 95))
            x += w * 100

    def letters(s):
        return [(REGION_KEY, CHAR_USAGE[c], 1) for c in s]
    row(600, letters('qwertyuiop'))
    row(700, [(REGION_KEY, None, 0.5)] + letters('asdfghjkl') + [(REGION_KEY, None, 0.5)])
    row(800, [(REGION_KEY, 0xE1, 1.5)] + letters('zxcvbnm') + [(REGION_KEY, BACKSPACE, 1.5)])
    row(900, [(REGION_BUTTON, 300, 1.5), (REGION_KEY, 0x36, 1), (REGION_KEY, 0x2C, 5), (REGION_KEY, 0x37, 1),
              (REGION_KEY, ENTER, 1.5)])
    by_usage = {k[1]: k for k in keys if k[0] == REGION_KEY}
    OFF, SERVER, APP = 2.5, 4321, 1234   # 手机比电脑快 2.5 秒
    pc0 = 1760000000.0
    lines = []

    def log(t_pc, pid, msg):
        lines.append(f'{t_pc + OFF:.6f}  {pid:5d}  {pid:5d} D douhua-keys: {msg}')

    log(pc0 - 1, APP, 'L 0')   # App 自己的本地分流（没有键盘布局）：不能拿来给服务的触点用
    log(pc0 - 1, SERVER, 'L 0 600 1000 1000 %d' % len(keys))
    for k in keys:
        log(pc0 - 1, SERVER, 'K %d %x %d %d %d %d' % k)
    lines.append(f'{pc0 + OFF:.6f}  {APP:5d}  {APP:5d} I douhua  : 键盘触点调试日志开')

    t = [pc0]
    slot = [0]

    def press(x, y, used_key, gap=False):
        """used_key：分流实际用的键（keys 里的一项）；gap = 按在缝里（只看矩形没按到，分给了最近的 used_key）。"""
        t[0] += 0.3
        rect = '%d %d %d %d' % used_key[2:]
        near = '%x' % used_key[1]
        hit = '-' if gap else near
        log(t[0], SERVER, f'p {int(t[0] * 1000) % 100000} {slot[0]} {x:.1f} {y:.1f} {hit} {near} {rect}')
        log(t[0] + 0.08, SERVER, f'u {int(t[0] * 1000 + 80) % 100000} {slot[0]} {x:.1f} {y:.1f}')
        slot[0] = (slot[0] + 1) % 3
        return t[0]

    def center(k, dx=0, dy=0):
        return (k[2] + k[4]) / 2 + dx, (k[3] + k[5]) / 2 + dy

    def tap(c, dx=0, dy=0):
        k = by_usage[CHAR_USAGE[c] if isinstance(c, str) else c]
        return press(*center(k, dx, dy), k)

    sentences = []

    def sentence(target, typed, pc_codes, text):
        shown = t[0] + 0.2
        t[0] = shown
        for step in typed:
            step()
        end = t[0] + 0.05   # 电脑收到 ↵ 比手机按下晚 50ms
        events = []
        for i, code in enumerate(pc_codes):
            et = shown + 0.1 + i * 0.05
            events.append(dict(t=et * 1000, type='down', code=code, key='', repeat=False, mods=0))
            events.append(dict(t=et * 1000 + 30, type='up', code=code, key='', repeat=False, mods=0))
        sentences.append(dict(target=target, shown=shown * 1000, start=(shown + 0.1) * 1000, end=end * 1000,
                              text=text, events=events, backspaces=0))
        t[0] = end

    o_key, p_key = by_usage[CHAR_USAGE['o']], by_usage[CHAR_USAGE['p']]
    shift = by_usage[0xE1]
    # 1 全对；a 的落点偏右上 (10, -5)
    sentence('abc', [lambda: tap('a', 10, -5), lambda: tap('b'), lambda: tap('c'), lambda: tap(ENTER)],
             ['KeyA', 'KeyB', 'KeyC', 'Enter'], 'abc')
    # 2 按错没改：o 按在 o、p 之间的缝里（靠 o，分给 o），g 按成了 f；电脑那边丢了 o
    sentence('dog', [lambda: tap('d'), lambda: press(o_key[4] + 2, 650, o_key, gap=True), lambda: tap('f'), lambda: tap(ENTER)],
             ['KeyD', 'KeyF', 'Enter'], 'df')
    # 3 按空：想按 z，落在 ⇧ 左边的边距里（缝里分给 ⇧，没出字）；a 照样偏 (10, -5)
    sentence('zap', [lambda: press(2, 850, shift, gap=True), lambda: tap('a', 10, -5), lambda: tap('p'), lambda: tap(ENTER)],
             ['ShiftLeft', 'KeyA', 'KeyP', 'Enter'], 'Ap')
    # 4 按错后 ⌫ 改对：d 按成 w，删掉再按 d
    sentence('red', [lambda: tap('r'), lambda: tap('e'), lambda: tap('w'), lambda: tap(BACKSPACE), lambda: tap('d'),
                     lambda: tap(ENTER)], ['KeyR', 'KeyE', 'KeyW', 'Backspace', 'KeyD', 'Enter'], 'red')
    # 5 多打：u 按了两次
    sentence('sun', [lambda: tap('s'), lambda: tap('u'), lambda: tap('u'), lambda: tap('n'), lambda: tap(ENTER)],
             ['KeyS', 'KeyU', 'KeyU', 'KeyN', 'Enter'], 'suun')
    press(*center(p_key), p_key)   # 最后一句之后的触点：不算

    with open(os.path.join(d, 'phone.log'), 'w') as f:
        f.write('--------- beginning of main\n' + '\n'.join(lines) + '\n')
    with open(os.path.join(d, 'typing.json'), 'w') as f:
        json.dump(dict(sentences=sentences), f, ensure_ascii=False)
    with open(os.path.join(d, 'clock.txt'), 'w') as f:
        for rtt, err in ((0.040, 0.3), (0.004, 0.0), (0.020, -0.2)):   # 来回最快的那次才准
            t0 = int(pc0 * 1e9)
            ph = int((pc0 + rtt / 2 + OFF + err) * 1e9)
            f.write(f'{t0} {ph} {ph / 1e9:.6f} {t0 + int(rtt * 1e9)}\n')

    res = analyze(d, quiet=True)
    checks, fails = [0], []

    def eq(actual, expected, what):
        checks[0] += 1
        ok = abs(actual - expected) < 1e-6 if isinstance(expected, float) else actual == expected
        if not ok:
            fails.append(f'✗ {what}：期望 {expected}，实际 {actual}')

    c = res['counts']
    eq(round(res['off'], 6), 2.5, '对时取来回最快的那次')
    eq(next_target('ab', 'abcd'), 2, '打了 ab，接下来想打 c')
    eq(next_target('abxc', 'abcd'), 3, '打了 abxc（x 是错的），接下来想打 d')
    eq(next_target('abcd', 'abcd'), None, '打完了')
    eq(res['sentences'], 5, '五句')
    eq(res['target_chars'], 15, '目标字数')
    eq(res['presses'], 23, '句子里的触点')
    eq(res['outside'], 1, '最后一句之后的触点不算')
    eq(c['对'], 13, '对')
    eq(c['按错没改'], 1, '按错没改（g 按成 f）')
    eq(c['按空'], 1, '按空（漏了 z）')
    eq(c['按空有触点'], 1, '按空的那下找到了触点（缝里、没出字）')
    eq(c['按错改过'], 1, '按错改过（d 按成 w 又删掉）')
    eq(c['删掉的对字'], 0, '没删过对的字')
    eq(c['多打'], 1, '多打（u 两次）')
    eq(res['rate'], 3 / 15, '误按率 =（按空 + 按错没改 + 按错改过）/ 目标字数')
    a = res['offsets'].get('a', dict(n=0, dx=0.0, dy=0.0))
    eq(a['n'], 2, 'a 的落点有两次')
    eq(round(a['dx'], 6), 10.0, 'a 平均偏右 10')
    eq(round(a['dy'], 6), -5.0, 'a 平均偏上 5')
    eq(round(res['offsets']['o']['dx'], 6), (o_key[4] + 2) - (o_key[2] + o_key[4]) / 2, '缝里的 o：偏移按想按的 o 的中心算')
    eq('z' in res['offsets'], True, '按空的那下也算进 z 的落点')
    eq(res['offsets']['d']['n'], 3, 'd 三次："dog" 的 d、"red" 里按成 w 的那下（想按的是 d）、改过之后的 d')
    eq('w' in res['offsets'], False, '按错的 w 不算 w 的落点')
    eq(res['gap'], 2, '按在缝里的触点两个')
    eq(res['gap_right'], 1, '缝里分对一个（o）')
    eq(res['gap_none'], 1, '缝里没出字一个（分给了 ⇧）')
    eq(res['lost'], 1, '电脑丢了一个键（o）')
    eq(res['extra'], 0, '电脑没多收（Shift 不算）')
    eq(res['mismatched'], 1, '网页上的字和手机重放对不上的一句（丢键那句；大小写不算）')
    eq(res['replay_touches'], 23, 'replay.txt 导出全部句子里的触点')
    for f in fails:
        print(f, file=sys.stderr)
    print(f'打字分析自测：{checks[0]} 项检查，{len(fails)} 项失败', file=sys.stderr)
    return not fails


if __name__ == '__main__':
    if len(sys.argv) >= 2 and sys.argv[1] == '--selftest':
        sys.exit(0 if selftest(sys.argv[2] if len(sys.argv) > 2 else None) else 1)
    if len(sys.argv) != 2:
        raise SystemExit('用法：python3 analyze.py <记录目录> | --selftest [目录]')
    analyze(sys.argv[1].rstrip('/'))
