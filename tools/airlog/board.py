# 白板的线：在折返点切段，按走向统计沿途偏离（鼓包）。用法：python3 tools/airlog/board.py .private/airlog/<时间>（要 numpy）
import json, sys, math
import numpy as np
d=sys.argv[1]
B=json.load(open(d+'/board.json'))
def segments(pts, minrun=60):
    """线类：沿整笔主方向投影，在折返点切段（走回头超过 minrun 像素才算折返）。"""
    P=np.array([[p[1],p[2]] for p in pts]); T=np.array([p[0] for p in pts])/1000
    c=P.mean(0); u,s,vt=np.linalg.svd(P-c,full_matrices=False); ax=vt[0]
    q=(P-c)@ax
    segs=[]; start=0; ext=0; direction=0
    for i in range(1,len(q)):
        if direction==0:
            if abs(q[i]-q[start])>minrun: direction=np.sign(q[i]-q[start]); ext=i
        else:
            if (q[i]-q[ext])*direction>0: ext=i
            elif (q[ext]-q[i])*direction>minrun:
                segs.append((start,ext)); start=ext; direction=-direction; ext=i
    if direction!=0: segs.append((start,ext if (q[-1]-q[ext])*direction<=0 else len(q)-1))
    return P,T,segs
rows=[]
for s in B['strokes']:
    if s['cat'] not in ('竖线','横线','斜线'): continue
    P,T,segs=segments(s['points'])
    for a,b in segs:
        if b-a<15: continue
        A,Bp=P[a],P[b]; ch=Bp-A; L=np.linalg.norm(ch)
        if L<120: continue
        t=ch/L; n=np.array([-t[1],t[0]])   # n：屏幕坐标 y 向下，往右走时 n 指向下 = 走向的右手边
        seg=P[a:b+1]; tt=T[a:b+1]
        along=(seg-A)@t; perp=(seg-A)@n
        # 偏离按走过的比例分 10 段
        prof=[np.mean(perp[(along>=L*k/10)&(along<L*(k+1)/10)]) if np.any((along>=L*k/10)&(along<L*(k+1)/10)) else np.nan for k in range(10)]
        ang=math.degrees(math.atan2(t[1],t[0]))   # 0 = 往右，90 = 往下
        v=np.gradient(along,tt); 
        rows.append(dict(cat=s['cat'],ang=ang,L=L,dur=tt[-1]-tt[0],prof=prof,maxdev=perp[np.argmax(abs(perp))],rms=float(np.sqrt(np.mean(perp**2))),
                         mid=(A+Bp)/2, speed=L/(tt[-1]-tt[0])))
print(f"线段 {len(rows)} 段")
def dirname(a):
    names=['→右','↘右下','↓下','↙左下','←左','↖左上','↑上','↗右上']
    return names[int(((a+22.5)%360)//45)]
from collections import defaultdict
g=defaultdict(list)
for r in rows: g[dirname(r['ang'])].append(r)
print("按走向：每段沿途偏到走向哪边多少像素（10 段平均），正 = 右手边；最大偏离、RMS")
for k in ['→右','←左','↓下','↑上','↘右下','↖左上','↙左下','↗右上']:
    if k not in g: continue
    rs=g[k]; prof=np.nanmean(np.array([r['prof'] for r in rs]),0)
    print(f"  {k:4s} {len(rs):3d} 段 长 {np.mean([r['L'] for r in rs]):4.0f}px 速度 {np.mean([r['speed'] for r in rs]):4.0f}px/s | "+' '.join(f"{x:+4.0f}" for x in prof)+f" | RMS {np.mean([r['rms'] for r in rs]):4.1f}")
json.dump([{k:(v.tolist() if isinstance(v,np.ndarray) else v) for k,v in r.items()} for r in rows],open(d+'/segments.json','w'))
