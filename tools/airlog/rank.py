# 读 Replay 的 grid 输出，按"静止不抖、距离保留 ≥97%"筛，再按落后分档挑最稳的；给了 --base 就和那组比。
import csv, sys
path = sys.argv[1]
base = tuple(float(x) for x in sys.argv[sys.argv.index('--base') + 1].split(',')) if '--base' in sys.argv else None
PX = float(sys.argv[sys.argv.index('--px') + 1]) if '--px' in sys.argv else 37.5   # 1° 合多少像素（这次记录那台电脑、灵敏度 6）
rows = [{k: float(v) for k, v in r.items()} for r in csv.DictReader(open(path))]
key = lambda r: (r['cut'], r['beta'], r['scut'], r['dz'], r['path'], r['str'])
def show(n, r):
    print(f"{n:16s} 静止 {r['static']*PX:4.2f}px | 5Hz+ {r['r5']*PX:4.2f}px | 2Hz+ {r['r2']*PX:5.2f}px | 落后 {r['lag']:3.0f}ms | 距离 {r['len']:.0%}  {key(r)}")
if base:
    b = [r for r in rows if key(r) == base]
    if b: show('对照', b[0])
ok = [r for r in rows if r['static'] * PX < 0.05 and r['len'] >= 0.97]
for cap in (12, 20, 30, 45):
    c = [r for r in ok if r['lag'] <= cap]
    if c: show(f'落后≤{cap}ms 最稳', min(c, key=lambda r: r['r2'] + r['r5']))
