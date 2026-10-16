import json, sys
D=sys.argv[1]; P=json.load(open(D+'/parsed.json'))
marks=P['marks']; S=[s for s in P['S'] if s[2]=='A']; EV=[e for e in P['EV'] if 'ABS_X' in e]
rounds=[(t,n.split()[1]) for t,n in marks if n.startswith('start gap=')]+[(1e18,'')]
ev=[e['t'] for e in EV]
import math
for (t0,name),(t1,_) in zip(rounds,rounds[1:]):
    r=[t for t in ev if t0<=t<t1]
    if len(r)<50: continue
    best=None
    for per in [x/100 for x in range(1000,1300,5)]:
        p=per/1000
        c=sum(math.cos(2*math.pi*t/p) for t in r); s_=sum(math.sin(2*math.pi*t/p) for t in r)
        q=math.hypot(c,s_)/len(r)
        if best is None or q>best[0]: best=(q,per)
    slots={}
    for t in r: slots[int(t/(best[1]/1000))]=slots.get(int(t/(best[1]/1000)),0)+1
    h={}
    for v in slots.values(): h[v]=h.get(v,0)+1
    mx=0; i=0
    for j,t in enumerate(r):
        while r[i]<t-1: i+=1
        mx=max(mx,j-i+1)
    print(f"[{name}] 收报文的格子 {best[1]:.2f}ms（对齐程度 {best[0]:.2f}）；每格到几帧: {sorted(h.items())}；任意 1 秒最多 {mx} 帧")
