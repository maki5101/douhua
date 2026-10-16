# 描红的直边：手机转动轴在手机坐标系 / 重力对齐坐标系里各自有多稳定、主轴是什么。用法：python3 tools/airlog/frames.py .private/airlog/<时间>
import json, sys, math, re
import numpy as np
d=sys.argv[1]
T=json.load(open(d+'/trace.json'))
best=None
for line in open(d+'/clock.txt'):
    p=line.split(); t0=int(p[0]); ph=int(p[1]); t1=int(p[-1])
    if best is None or t1-t0<best[0]: best=(t1-t0,ph-(t0+t1)/2)
OFF=best[1]/1e9
G=[]
for line in open(d+'/phone.log',errors='replace'):
    m=re.match(r'\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+douhua-air:\s+g (\d+) \S+ \S+ \S+ \S+ (\S+) (\S+) (\S+) (\S+) (\S+) (\S+)',line)
    if not m: continue
    g=[float(m.group(i)) for i in (6,7,8)]
    if g==[0,0,0]: continue
    G.append((float(m.group(1))-OFF,int(m.group(2)),[float(m.group(i)) for i in (3,4,5)],g))
E=min(x[0]-x[1]/1e9 for x in G)
Gt=np.array([x[1]/1e9+E for x in G]); Gw=np.array([x[2] for x in G]); Gg=np.array([x[3] for x in G])
LAG=0.025
def frames(w,g):
    u=g/np.linalg.norm(g)
    e2=np.array([1,0,0])-u*u[0]; e2/=np.linalg.norm(e2); e3=np.cross(u,e2)
    return w, np.array([w@u,w@e2,w@e3])   # 手机系；重力对齐系（竖直、水平右、水平前）
def dispersion(V):
    n=np.linalg.norm(V,axis=1); m=n>np.radians(4)   # 转得够快的
    if m.sum()<10: return None
    U=V[m]/n[m,None]
    # 正反方向算同一根轴（来回画）：主轴 = U^T U 的最大特征向量
    M=(U.T*n[m])@U; ev,evec=np.linalg.eigh(M); ax=evec[:,-1]
    ang=np.degrees(np.arccos(np.clip(abs(U@ax),0,1)))
    return float(np.average(ang,weights=n[m])), ax
res={'手机系':[], '重力对齐系':[]}
for r in T['results']:
    tgt=r['target']; pts=r['points']
    if r['shape'] not in ('横线','竖线','方','三角'): continue
    # 每条边：按离哪条边最近分点
    for i in range(1,len(tgt)):
        x0,y0=tgt[i-1]; x1,y1=tgt[i]; L=math.hypot(x1-x0,y1-y0)
        ts=[]
        for p in pts:
            t=((p[1]-x0)*(x1-x0)+(p[2]-y0)*(y1-y0))/L**2
            if 0.15<t<0.85 and abs(((p[1]-x0)*(y1-y0)-(p[2]-y0)*(x1-x0))/L)<50: ts.append(p[0]/1000)
        if len(ts)<20: continue
        a,b=min(ts)-LAG,max(ts)-LAG
        k=(Gt>=a)&(Gt<=b)
        if k.sum()<20: continue
        Vb=[];Vg=[]
        for w,g in zip(Gw[k],Gg[k]):
            fb,fg=frames(np.array(w),np.array(g)); Vb.append(fb); Vg.append(fg)
        db=dispersion(np.array(Vb)); dg=dispersion(np.array(Vg))
        if db and dg:
            res['手机系'].append((db[0],r['shape'],db[1])); res['重力对齐系'].append((dg[0],r['shape'],dg[1]))
print(f"直边 {len(res['手机系'])} 条：每条边上手机转动轴的散布（度，越小 = 这个参照系里轴越稳定）")
for k,v in res.items():
    a=np.array([x[0] for x in v]); print(f"  {k:5s}：中位 {np.median(a):4.1f}°  平均 {a.mean():4.1f}°")
better=sum(1 for b,g in zip(res['手机系'],res['重力对齐系']) if b[0]<g[0])
print(f"  手机系更稳定的边：{better}/{len(res['手机系'])}")
print("\n各条边的主轴（手机系：x 右 / y 顶边 / z 屏幕法线）和散布：")
for (db,sh,ab),(dg,_,ag) in zip(res['手机系'],res['重力对齐系']):
    print(f"  {sh:3s} 手机系 {np.round(ab*np.sign(ab[np.argmax(abs(ab))]),2)} 散{db:4.1f}° | 重力系(竖直/水平右/水平前) {np.round(ag*np.sign(ag[np.argmax(abs(ag))]),2)} 散{dg:4.1f}°")
