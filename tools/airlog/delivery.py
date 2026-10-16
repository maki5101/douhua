import json, sys, statistics as st
D=sys.argv[1]; P=json.load(open(D+'/parsed.json'))
marks=P['marks']; S=[s for s in P['S'] if s[2]=='A']; EV=[e for e in P['EV'] if 'ABS_X' in e]; CU=P['CU']
# 各轮的开始（标记 "start gap=…"），按时间分轮
rounds=[(t,n.split()[1]) for t,n in marks if n.startswith('start gap=')]
def round_of(t):
    name=None
    for t0,n in rounds:
        if t>=t0: name=n
    return name
# match sends to evdev frames in order by value
j=0; pairs=[]; miss=0
evs=EV
for s in S:
    tw,el,_,x,y=s
    k=j
    while k<len(evs) and k<j+40 and not (abs(evs[k].get('ABS_X',-9)-x)<=1 and abs(evs[k].get('ABS_Y',-9)-y)<=1): k+=1
    if k<len(evs) and k<j+40: pairs.append((tw,evs[k]['t'])); j=k+1
    else: miss+=1
print('matched',len(pairs),'of',len(S),'sends; unmatched',miss)
def pct(a,q): a=sorted(a); return a[min(len(a)-1,int(q*len(a)))]
for _,name in rounds:
    pr=[p for p in pairs if round_of(p[0])==name]
    if len(pr)<10: continue
    lat=[(b-a)*1000 for a,b in pr]; m=min(lat)
    rel=[l-m for l in lat]
    # only consecutive within motion (send gap < 50ms)
    sg=[];ag=[]
    for (a0,b0),(a1,b1) in zip(pr,pr[1:]):
        if a1-a0<0.05: sg.append((a1-a0)*1000); ag.append((b1-b0)*1000)
    bunch=sum(1 for g in ag if g<2)/len(ag)
    print(f"\n[{name}] sends {len(pr)}")
    print(f"  phone send interval ms: p10 {pct(sg,.1):.1f} med {pct(sg,.5):.1f} p90 {pct(sg,.9):.1f}")
    print(f"  PC arrival interval ms: p10 {pct(ag,.1):.1f} med {pct(ag,.5):.1f} p90 {pct(ag,.9):.1f} p99 {pct(ag,.99):.1f} max {max(ag):.1f}")
    print(f"  arrivals <2ms after previous (bunched): {bunch:.0%}")
    print(f"  extra latency over best (ms): med {pct(rel,.5):.1f} p90 {pct(rel,.9):.1f} p99 {pct(rel,.99):.1f}")
    # arrival interval histogram
    hist={}
    for g in ag: b=int(g//2)*2; hist[b]=hist.get(b,0)+1
    print('  arrival gap histogram (2ms bins):',' '.join(f"{k}:{v}" for k,v in sorted(hist.items()) if v>len(ag)*0.01))
# cursor vs evdev timing
EVt=[e['t'] for e in EV]; CUt=[c[0] for c in CU]
import bisect
d=[]
for t in CUt:
    i=bisect.bisect_right(EVt,t)-1
    if i>=0: d.append((t-EVt[i])*1000)
print('\ncursor update after latest evdev frame (ms): med %.1f p90 %.1f'%(pct(d,.5),pct(d,.9)))
