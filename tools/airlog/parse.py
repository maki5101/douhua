import re, sys, json, statistics as st
D = sys.argv[1]
# ---- clock: phone wall - pc wall, take min RTT
best=None
for line in open(D+'/clock.txt'):
    p=line.split()
    t0=int(p[0]); ph=int(p[1]); t1=int(p[-1])
    rtt=t1-t0; off=ph-(t0+t1)/2
    if best is None or rtt<best[0]: best=(rtt,off)
rtt,off=best
print(f"clock: phone-pc offset {off/1e6:.1f} ms (rtt {rtt/1e6:.1f} ms)")
OFF=off/1e9  # seconds, phone = pc + OFF
# ---- marks
marks=[(int(l.split()[0])/1e9, l.split(None,1)[1].strip()) for l in open(D+'/marks.txt')]
# ---- phone log
G=[]; S=[]; F=[]; C=[]; B=[]
for line in open(D+'/phone.log',errors='replace'):
    m=re.match(r'\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+douhua-air:\s+(.*)',line)
    if not m: continue
    tw=float(m.group(1))-OFF  # in PC clock
    body=m.group(2).split()
    k=body[0]
    if k=='g': G.append((tw,int(body[1]),float(body[2]),float(body[3]),float(body[4]),float(body[5])))
    elif k=='s': S.append((tw,int(body[1]),body[2],int(body[3]),int(body[4])))
    elif k=='f': F.append((tw,int(body[1]),int(body[2]),' '.join(body[3:])))
    elif k=='c': C.append((tw,))
    elif k=='b': B.append((tw,int(body[2])))
# elapsed->pc wall mapping from s lines: wall_pc = elapsed*1e-9 + E
E=min(s[0]-s[1]/1e9 for s in S)  # min: log written right after send
# ---- evdev
EV=[]; cur={}
for line in open(D+'/evdev.log',errors='replace'):
    m=re.match(r'Event: time (\d+\.\d+), type (\d+) \((\w+)\), code (\d+) \((\w+)\), value (-?\d+)',line)
    if m:
        t=float(m.group(1)); typ=m.group(3); code=m.group(5); val=int(m.group(6))
        cur[code]=val; cur['t']=t
        continue
    m=re.match(r'Event: time (\d+\.\d+), -------------- SYN_REPORT',line)
    if m:
        cur['t']=float(m.group(1)); EV.append(dict(cur)); 
        for k in ('REL_X','REL_Y','REL_WHEEL','REL_HWHEEL'): cur.pop(k,None)
# ---- cursor
CU=[]
for line in open(D+'/cursor.log'):
    m=re.match(r'(\d+\.\d+) .* douhua-cur (-?\d+) (-?\d+)',line)
    if m: CU.append((float(m.group(1)),int(m.group(2)),int(m.group(3))))
json.dump(dict(OFF=OFF,E=E,marks=marks,G=G,S=S,F=F,C=C,B=B,EV=EV,CU=CU),open(D+'/parsed.json','w'))
print(len(G),'g',len(S),'s',len(F),'f',len(EV),'evframes',len(CU),'cursor')
print('elapsed->wall E',E)
