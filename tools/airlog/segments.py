import json, sys, math
D=sys.argv[1]; P=json.load(open(D+'/parsed.json'))
marks=[(t,n) for t,n in P['marks']]
done=[(t,n.split()[1]) for t,n in marks if n.startswith('done')]
G=P['G']; F=P['F']
# follow windows from f lines (PC-clock log time)
wins=[]; st=None
for f in F:
    if f[1]==1: st=f
    elif st: wins.append((st[0],f[0],st[3])); st=None
def group(t):
    prev=None
    for tm,lab in done:
        if t<tm: return lab
    return None
segs=[]
for a,b,info in wins:
    g=[x for x in G if a<=x[0]<=b]
    if len(g)<20: continue
    lab=group(b)
    segs.append(dict(label=lab,info=info,t=[x[1] for x in g],h=[x[2] for x in g],v=[x[3] for x in g],oh=[x[4] for x in g],ov=[x[5] for x in g]))
from collections import Counter
print(Counter(s['label'] for s in segs))
for s in segs[:3]+segs[-2:]:
    print(s['label'], len(s['t']), 'dur %.2fs'%((s['t'][-1]-s['t'][0])/1e9), s['info'][:40])
# intended path: integrate raw, zero-phase gaussian smooth sigma 50ms
def integ(t,h,v):
    x=[0.0];y=[0.0]
    for i in range(1,len(t)):
        dt=min((t[i]-t[i-1])/1e9,0.05)
        x.append(x[-1]+h[i]*dt); y.append(y[-1]+v[i]*dt)
    return x,y
def gauss(t,a,sig=0.05):
    out=[]; n=len(t); j0=0
    ts=[x/1e9 for x in t]
    for i in range(n):
        s=w=0.0
        k=i
        while k>=0 and ts[i]-ts[k]<3*sig: 
            ww=math.exp(-0.5*((ts[i]-ts[k])/sig)**2); s+=ww*a[k]; w+=ww; k-=1
        k=i+1
        while k<n and ts[k]-ts[i]<3*sig:
            ww=math.exp(-0.5*((ts[i]-ts[k])/sig)**2); s+=ww*a[k]; w+=ww; k+=1
        out.append(s/w)
    return out
with open(D+'/segs.csv','w') as f:
    for i,s in enumerate(segs):
        x,y=integ(s['t'],s['h'],s['v'])
        ix=gauss(s['t'],x); iy=gauss(s['t'],y)
        for k in range(len(s['t'])):
            f.write(f"{i},{s['label']},{s['t'][k]},{s['h'][k]:.4f},{s['v'][k]:.4f},{s['oh'][k]:.4f},{s['ov'][k]:.4f},{ix[k]:.5f},{iy[k]:.5f}\n")
print('wrote',len(segs),'segments')
