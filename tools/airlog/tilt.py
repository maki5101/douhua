# 白板：各种换算下竖 / 横线的平均歪斜和散布；按手转的角度拟合角度校正（两半交叉检验）；圆一圈一圈的椭圆、按旋转方向分开。
# AirPointer.defaultFix 的数就是这里拟合出来的。用法：python3 tools/airlog/tilt.py .private/airlog/<时间>（要 numpy）
# 各种拆法下（同一批原始陀螺仪），不碰边的竖线 / 横线段的角度：平均歪多少、笔与笔之间散多少（按屏幕上的像素比例算角度）
import json, sys, math, re
import numpy as np
exec(open(__import__('os').path.join(__import__('os').path.dirname(__file__), 'decoders.py')).read().split('def segments(')[0])
GXs=1092.2/32767*1126; GYs=1092.2*1.778/32767*776   # 屏幕上实际的每度像素（竖向多 23%）
Wv,Hv=B['strokes'][0]['viewport']; EDGE=6
exec(open(__import__('os').path.join(__import__('os').path.dirname(__file__), 'system.py')).read().split('segs={}')[0].split('Wv,Hv=')[0].replace('d=sys.argv[1]\nB=json.load(open(d+\'/board.json\'))',''))
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
def line_angle(X,Y):   # 屏幕 y 向下 → 数学角
    P=np.stack([X,-Y],1); c=P.mean(0); u,s,vt=np.linalg.svd(P-c,full_matrices=False); ax=vt[0]
    return math.degrees(math.atan2(ax[1],ax[0]))%180
# 收集段的手机帧下标
SEG=[]
for si,s in enumerate(B['strokes']):
    if s['cat'] not in ('竖线','横线'): continue
    P=np.array([[p[1],p[2]] for p in s['points']]); T=np.array([p[0] for p in s['points']])/1000
    for a,b in interior_runs(P):
        Pr=P[a:b]; Tr=T[a:b]
        if len(Pr)<15: continue
        for c0,c1 in reversal_split(Pr):
            if np.linalg.norm(Pr[c1]-Pr[c0])<200: continue
            k=np.where((Gt>=Tr[c0])&(Gt<=Tr[c1]))[0]
            if len(k)<15: continue
            SEG.append((si,s['cat'],k,line_angle(Pr[c0:c1+1,0],Pr[c0:c1+1,1])))
print(f"段：竖线 {sum(1 for x in SEG if x[1]=='竖线')}，横线 {sum(1 for x in SEG if x[1]=='横线')}")
def stats(angles_by_cat):
    out=[]
    for cat,target in (('竖线',90),('横线',0)):
        a=np.array(angles_by_cat[cat]); 
        if target==0: a=np.where(a>90,a-180,a)
        out.append((a.mean()-target,a.std()))
    return out
screen={'竖线':[], '横线':[]}
for si,cat,k,ang in SEG: screen[cat].append(ang)
r=stats(screen); print(f"  屏幕实际        竖线 歪 {r[0][0]:+5.1f}° 散 {r[0][1]:4.1f}° | 横线 歪 {r[1][0]:+5.1f}° 散 {r[1][1]:4.1f}°")
for sp in ('玩家空间','世界空间','斜基','手机本地轴'):
    h,v=rates(sp)
    X=np.cumsum(h*dt); Y=np.cumsum(v*dt)
    by={'竖线':[], '横线':[]}
    for si,cat,k,ang in SEG: by[cat].append(line_angle(X[k]*GXs,Y[k]*GYs))
    r=stats(by); print(f"  {sp:8s}      竖线 歪 {r[0][0]:+5.1f}° 散 {r[0][1]:4.1f}° | 横线 歪 {r[1][0]:+5.1f}° 散 {r[1][1]:4.1f}°")

# 校正：在"手转的角度"空间（横竖同一个像素/度，屏幕比例填对以后屏幕上就是这样）拟合 2×2 线性校正，让竖线 90°、横线 0°；按笔分两半交叉检验
print("\n按手转的角度算（屏幕比例填对以后的样子）：")
def iso_angles(sp, M=np.eye(2), subset=None):
    h,v=rates(sp); X=np.cumsum(h*dt); Y=np.cumsum(v*dt)
    by={'竖线':[], '横线':[]}
    for si,cat,k,ang in SEG:
        if subset is not None and si not in subset: continue
        P=np.stack([X[k],Y[k]],1)@M.T
        by[cat].append(line_angle(P[:,0],P[:,1]))
    return by
def fitM(by):
    # 竖线平均方向 → 正上，横线平均方向 → 正右：两个单位向量各自映射，解 M
    def mean_dir(a,target):
        a=np.array(a); a=np.where((target==0)&(a>90),a-180,a); m=math.radians(a.mean())
        return np.array([math.cos(m),-math.sin(m)])   # 回到屏幕坐标（y 向下）
    vdir=mean_dir(by['竖线'],90); hdir=mean_dir(by['横线'],0)
    A=np.stack([hdir,vdir],1); Bm=np.array([[1,0],[0,-1.]])   # 目标：横 → (1,0)，竖 → (0,-1)（屏幕上为负 y）
    if vdir[1]>0: vdir=-vdir; A=np.stack([hdir,vdir],1)
    return Bm @ np.linalg.inv(A)
strokes=sorted(set(x[0] for x in SEG)); half=[set(strokes[0::2]),set(strokes[1::2])]
for sp in ('玩家空间','世界空间','斜基'):
    r0=stats(iso_angles(sp))
    out=[]
    for tr,te in ((0,1),(1,0)):
        M=fitM(iso_angles(sp,subset=half[tr]))
        out.append(stats(iso_angles(sp,M,subset=half[te])))
    rc=[(np.mean([o[i][0] for o in out]),np.mean([o[i][1] for o in out])) for i in range(2)]
    M=fitM(iso_angles(sp))
    rot=math.degrees(math.atan2(M[1,0]-M[0,1],M[0,0]+M[1,1]))
    print(f"  {sp:6s} 校正前 竖 {r0[0][0]:+5.1f}°/散{r0[0][1]:4.1f}  横 {r0[1][0]:+5.1f}°/散{r0[1][1]:4.1f} → 校正后（另一半笔上检验）竖 {rc[0][0]:+5.1f}°/散{rc[0][1]:4.1f}  横 {rc[1][0]:+5.1f}°/散{rc[1][1]:4.1f}   校正 ≈ 转 {rot:+.1f}°，矩阵 {np.round(M,3).tolist()}")

print("\n圆（去掉碰边的点）：长短轴比、长轴朝向，按各种处理重算")
def ell(P):
    X,Y=P[:,0],P[:,1]
    D=np.stack([X*X,X*Y,Y*Y,X,Y,np.ones_like(X)],1); _,_,vt=np.linalg.svd(D,full_matrices=False); a,b,c,dd,e,f=vt[-1]
    Mq=np.array([[a,b/2],[b/2,c]]); ctr=np.linalg.solve(2*Mq,[-dd,-e]); k=ctr@Mq@ctr-f; ev,evec=np.linalg.eigh(Mq/k)
    ax=1/np.sqrt(np.abs(ev)); return max(ax)/min(ax), math.degrees(math.atan2(-evec[1,np.argmin(ev)],evec[0,np.argmin(ev)]))%180
Mw=fitM(iso_angles('世界空间'))
for s in B['strokes']:
    if s['cat']!='圆': continue
    P=np.array([[p[1],p[2]] for p in s['points']]); T=np.array([p[0] for p in s['points']])/1000
    inside=(P[:,0]>EDGE)&(P[:,0]<Wv-EDGE)&(P[:,1]>EDGE)&(P[:,1]<Hv-EDGE)
    k=np.where((Gt>=T[0])&(Gt<=T[-1]))[0]
    out=[f"屏幕实际 {ell(P[inside])[0]:.2f}@{ell(P[inside])[1]:3.0f}°"]
    for sp,M in (('玩家',np.eye(2)),('世界',np.eye(2)),('世界+校正',Mw)):
        h,v=rates('玩家空间' if sp=='玩家' else '世界空间'); X=np.cumsum(h*dt)[k]; Y=np.cumsum(v*dt)[k]
        Q=np.stack([X,Y],1)@M.T
        r,a=ell(Q); out.append(f"{sp}(按手角度) {r:.2f}@{a:3.0f}°")
    print('  '+' | '.join(out))

print("\n圆，一圈一圈算（按屏幕轨迹绕图案圆心每转满 360° 切一圈；手那几层不受屏幕边影响，屏幕层只算不碰边的圈）：")
res={k:[] for k in ('屏幕实际','玩家(手角度)','世界(手角度)','世界+校正(手角度)')}
for s in B['strokes']:
    if s['cat']!='圆': continue
    P=np.array([[p[1],p[2]] for p in s['points']]); T=np.array([p[0] for p in s['points']])/1000
    c=np.array(s['target']).mean(0)
    ang=np.unwrap(np.arctan2(P[:,1]-c[1],P[:,0]-c[0]))
    start=0
    while start<len(P)-10:
        rest=np.where(abs(ang[start:]-ang[start])>=2*math.pi)[0]
        if len(rest)==0: break
        end=start+rest[0]
        seg=P[start:end]; ts=T[start:end]
        inside=np.all((seg[:,0]>EDGE)&(seg[:,0]<Wv-EDGE)&(seg[:,1]>EDGE)&(seg[:,1]<Hv-EDGE))
        if inside: res['屏幕实际'].append(ell(seg))
        k=np.where((Gt>=ts[0])&(Gt<=ts[-1]))[0]
        if len(k)>50:
            for name,sp,M in (('玩家(手角度)','玩家空间',np.eye(2)),('世界(手角度)','世界空间',np.eye(2)),('世界+校正(手角度)','世界空间',Mw)):
                h,v=rates(sp); X=np.cumsum(h*dt)[k]; Y=np.cumsum(v*dt)[k]
                res[name].append(ell(np.stack([X,Y],1)@M.T))
        start=end
for k,v in res.items():
    if not v: print(f"  {k}: 没有完整的圈"); continue
    a=np.array(v); print(f"  {k:12s} {len(v):3d} 圈：长短轴比中位 {np.median(a[:,0]):.2f}（四分位 {np.percentile(a[:,0],25):.2f}–{np.percentile(a[:,0],75):.2f}），长轴朝向中位 {np.median(a[:,1]):4.0f}°")

print("\n按每圈的旋转方向分开（屏幕上看，顺时针 / 逆时针），世界空间按手角度：")
by={'顺时针':[], '逆时针':[]}
h,v=rates('世界空间'); Xw=np.cumsum(h*dt); Yw=np.cumsum(v*dt)
for s in B['strokes']:
    if s['cat']!='圆': continue
    P=np.array([[p[1],p[2]] for p in s['points']]); T=np.array([p[0] for p in s['points']])/1000
    c=np.array(s['target']).mean(0); ang=np.unwrap(np.arctan2(P[:,1]-c[1],P[:,0]-c[0]))
    start=0
    while start<len(P)-10:
        rest=np.where(abs(ang[start:]-ang[start])>=2*math.pi)[0]
        if len(rest)==0: break
        end=start+rest[0]; ts=T[start:end]
        k=np.where((Gt>=ts[0])&(Gt<=ts[-1]))[0]
        if len(k)>50:
            cw=ang[end]-ang[start]>0   # 屏幕 y 向下：角度增加 = 顺时针
            dur=ts[-1]-ts[0]
            by['顺时针' if cw else '逆时针'].append(ell(np.stack([Xw[k],Yw[k]],1))+(dur,))
        start=end
for k,v in by.items():
    if not v: print(f"  {k}: 没有"); continue
    a=np.array(v); print(f"  {k} {len(v):3d} 圈（每圈约 {np.median(a[:,2]):.2f}s）：长短轴比中位 {np.median(a[:,0]):.2f}，长轴朝向中位 {np.median(a[:,1]):4.0f}°")
