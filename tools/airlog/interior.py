# 白板：只用不碰屏幕边（离边 6px 以内的点剔除、在那里切断）、≥200px 的段，比屏幕实际和各种换算下每段线有多直。
# 用法：python3 tools/airlog/interior.py .private/airlog/<时间>
# 去掉碰边的部分：光标离屏幕边 EDGE 像素以内的点剔除、在那里切断；剩下的每段内部再按折返切；只用 ≥ MINLEN 的段
import json, sys, math, re
import numpy as np
exec(open(__import__('os').path.join(__import__('os').path.dirname(__file__), 'decoders.py')).read().split('def segments(')[0])   # 复用：读数据、对齐、各种拆法的路径、laser()
EDGE=6; MINLEN=200
Wv,Hv=B['strokes'][0]['viewport']
def interior_runs(pts):
    P=np.array([[p[1],p[2]] for p in pts]); T=np.array([p[0] for p in pts])/1000
    inside=(P[:,0]>EDGE)&(P[:,0]<Wv-EDGE)&(P[:,1]>EDGE)&(P[:,1]<Hv-EDGE)
    runs=[]; i=0
    while i<len(P):
        if not inside[i]: i+=1; continue
        j=i
        while j<len(P) and inside[j]: j+=1
        runs.append((i,j)); i=j
    return P,T,runs
def reversal_split(P, minrun=60):
    c=P.mean(0); u,s,vt=np.linalg.svd(P-c,full_matrices=False); ax=vt[0]; q=(P-c)@ax
    segs=[]; start=0; ext=0; direction=0
    for i in range(1,len(q)):
        if direction==0:
            if abs(q[i]-q[start])>minrun: direction=np.sign(q[i]-q[start]); ext=i
        else:
            if (q[i]-q[ext])*direction>0: ext=i
            elif (q[ext]-q[i])*direction>minrun: segs.append((start,ext)); start=ext; direction=-direction; ext=i
    if direction!=0: segs.append((start,len(q)-1))
    return segs
def dev(X,Y):
    P=np.stack([X,Y],1); A=P[0]; Bp=P[-1]; L=np.linalg.norm(Bp-A)
    if L<MINLEN*0.8: return None
    t=(Bp-A)/L; n=np.array([-t[1],t[0]]); perp=(P-A)@n
    return abs(perp).max()/L*100, np.sqrt((perp**2).mean())/L*100, perp[np.argmax(abs(perp))]
names=['玩家空间','世界空间','斜基','手机本地轴','激光笔·水平朝前']
res={}; scr={}; kept=0; total=0
for s in B['strokes']:
    if s['cat'] not in ('斜线','竖线','横线'): continue
    P,T,runs=interior_runs(s['points'])
    for a,b in runs:
        Pr=P[a:b]; Tr=T[a:b]
        if len(Pr)<15: continue
        for c0,c1 in reversal_split(Pr):
            seg=Pr[c0:c1+1]; total+=1
            if np.linalg.norm(seg[-1]-seg[0])<MINLEN: continue
            k=np.where((Gt>=Tr[c0])&(Gt<=Tr[c1]))[0]
            if len(k)<15: continue
            # 屏幕层就是这段页面轨迹
            sd=dev(seg[:,0]-seg[0,0],seg[:,1]-seg[0,1])
            row=[]
            for nm in names:
                if nm.startswith('激光笔'): X,Y=laser(k,'fwd')
                else: X,Y=paths[nm][0][k],paths[nm][1][k]
                row.append(dev(X-X[0],Y-Y[0]))
            if sd is None or None in row: continue
            kept+=1
            dy=seg[-1,1]-seg[0,1]; dx=seg[-1,0]-seg[0,0]
            key=s['cat']
            res.setdefault(key,[]).append(row); scr.setdefault(key,[]).append(sd)
print(f"不碰边、≥{MINLEN}px 的段：{kept}（共切出 {total} 段）")
print("偏离直线：最大 / RMS，占线长的百分比（中位，越小越直）")
print("              屏幕实际   " + "  ".join(f"{n:>12s}" for n in names))
for cat,rows in res.items():
    R=np.array([[x[:2] for x in r] for r in rows]); Sd=np.array([x[:2] for x in scr[cat]])
    print(f"  {cat} {len(rows):3d}段  {np.median(Sd[:,0]):5.1f}/{np.median(Sd[:,1]):4.1f}  " + "  ".join(f"{np.median(R[:,i,0]):6.1f}/{np.median(R[:,i,1]):4.1f}" for i in range(len(names))))
