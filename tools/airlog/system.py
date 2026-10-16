# 白板的系统性偏差（屏幕上看）：不碰边的线段角度（竖线该 90°、横线 0°）、带符号的平均鼓包、圆的椭圆拟合。
# 用法：python3 tools/airlog/system.py .private/airlog/<时间>
# 系统性偏差：线的角度（竖线该 90°、横线 0°）、斜线角度的一致性、带符号的平均鼓包；圆的椭圆拟合
import json, sys, math
import numpy as np
d=sys.argv[1]
B=json.load(open(d+'/board.json'))
Wv,Hv=B['strokes'][0]['viewport']; EDGE=6
def interior_runs(P):
    inside=(P[:,0]>EDGE)&(P[:,0]<Wv-EDGE)&(P[:,1]>EDGE)&(P[:,1]<Hv-EDGE)
    runs=[]; i=0
    while i<len(P):
        if not inside[i]: i+=1; continue
        j=i
        while j<len(P) and inside[j]: j+=1
        runs.append((i,j)); i=j
    return runs
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
segs={}
for s in B['strokes']:
    if s['cat'] not in ('竖线','横线','斜线'): continue
    P=np.array([[p[1],p[2]] for p in s['points']])
    for a,b in interior_runs(P):
        Pr=P[a:b]
        if len(Pr)<15: continue
        for c0,c1 in reversal_split(Pr):
            seg=Pr[c0:c1+1]
            if np.linalg.norm(seg[-1]-seg[0])<200: continue
            # 线的方向用整段拟合（不是两端连线），角度取 0..180（屏幕上 y 向下，转成数学角：往右上为正）
            c=seg.mean(0); u,sv,vt=np.linalg.svd(seg-c,full_matrices=False); ax=vt[0]
            ang=math.degrees(math.atan2(-ax[1],ax[0]))%180
            # 带符号鼓包：相对拟合直线，沿线分 10 段；符号统一成"往屏幕上方 / 右方为正"
            n=np.array([-ax[1],ax[0]])
            if n[1]>0 or (abs(n[1])<1e-6 and n[0]<0): n=-n   # n 指向屏幕上方（y 小）
            along=(seg-c)@ax; perp=(seg-c)@n
            if along[-1]<along[0]: along=-along   # 统一成从一头到另一头（不分来回）
            L=along.max()-along.min(); lo=along.min()
            prof=[np.mean(perp[(along>=lo+L*k/10)&(along<lo+L*(k+1)/10)]) for k in range(10)]
            segs.setdefault(s['cat'],[]).append((ang,prof,L,np.sqrt(np.mean(perp**2))))
print("线的角度（屏幕上，度；竖线应 90、横线应 0/180；数学角：往右上为正）")
for cat,v in segs.items():
    A=np.array([x[0] for x in v])
    if cat=='横线': A=np.where(A>90,A-180,A)
    if cat=='斜线':
        for name,m in (('"/"',A<90),('"\\\\"',A>=90)):
            a=A[m]; print(f"  斜线{name} {m.sum():3d} 段：平均 {a.mean():6.1f}°  标准差 {a.std():4.1f}°（屏幕对角线是 {math.degrees(math.atan2(Hv,Wv)):.1f}° / {180-math.degrees(math.atan2(Hv,Wv)):.1f}°）")
    else:
        print(f"  {cat} {len(A):3d} 段：平均 {A.mean():6.1f}°  标准差 {A.std():4.1f}°  → 系统性歪 {A.mean()-(90 if cat=='竖线' else 0):+.1f}°")
print("\n带符号的平均鼓包（像素，相对整段拟合直线；正 = 往屏幕上方（横、斜）/ 右方（竖），10 段从左到右或从上到下）")
for cat,v in segs.items():
    if cat=='斜线':
        groups=(('"/"',[x for x in v if x[0]<90]),('"\\"',[x for x in v if x[0]>=90]))
    else: groups=((cat,v),)
    for name,g in groups:
        Pm=np.nanmean(np.array([x[1] for x in g]),0); Ps=np.nanstd(np.array([x[1] for x in g]),0)
        print(f"  {name:6s} "+' '.join(f"{m:+5.1f}" for m in Pm)+f"   （笔与笔的差 ±{np.mean(Ps):.0f}，整段晃 RMS {np.mean([x[3] for x in g]):.1f}px）")
# 圆：拟合椭圆（二次曲线），和图案比
print("\n圆：")
def fit_ellipse(X,Y):
    D=np.stack([X*X,X*Y,Y*Y,X,Y,np.ones_like(X)],1)
    _,_,vt=np.linalg.svd(D,full_matrices=False); a,b,c,dd,e,f=vt[-1]
    M=np.array([[a,b/2],[b/2,c]]); ctr=np.linalg.solve(2*M,[-dd,-e])
    k=ctr@M@ctr-f; ev,evec=np.linalg.eigh(M/k)
    axes=1/np.sqrt(np.abs(ev)); ang=math.degrees(math.atan2(evec[1,1],evec[0,1]))
    return ctr,axes,ang,evec
for s in B['strokes']:
    if s['cat']!='圆': continue
    P=np.array([[p[1],p[2]] for p in s['points']])
    inside=(P[:,0]>EDGE)&(P[:,0]<Wv-EDGE)&(P[:,1]>EDGE)&(P[:,1]<Hv-EDGE); P=P[inside]
    tgt=np.array(s['target']); tc=tgt.mean(0); tr=np.mean(np.linalg.norm(tgt-tc,axis=1))
    ctr,axes,ang,evec=fit_ellipse(P[:,0],P[:,1])
    # 横向、竖向半径：椭圆在 x / y 方向的伸展
    Mx=np.array([[1/axes[0]**2,0],[0,1/axes[1]**2]]); Q=evec@Mx@evec.T
    rx=1/math.sqrt(Q[0,0]-Q[0,1]**2/Q[1,1]); ry=1/math.sqrt(Q[1,1]-Q[0,1]**2/Q[0,0])
    rr=np.linalg.norm(P-ctr,axis=1)
    print(f"  {len(P)} 点 {(s['points'][-1][0]-s['points'][0][0])/1000:4.0f}s：横向半径 {rx:5.0f} 竖向半径 {ry:5.0f}（图案 {tr:.0f}）→ 竖/横 {ry/rx:.2f}；长短轴 {max(axes):.0f}/{min(axes):.0f} 长轴朝 {ang%180:5.1f}°；圆心偏 ({ctr[0]-tc[0]:+.0f},{ctr[1]-tc[1]:+.0f})px")
