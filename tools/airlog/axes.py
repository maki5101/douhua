# 描红的手法分析：用手机日志里的原始陀螺仪 + 重力（g 行后六列），按描红图案该走的方向，
# 比较几种方向换算的方向误差，再拟合一套个人换算。用法：python3 tools/airlog/axes.py .private/airlog/<时间>（要 numpy）
import json, sys, math, re, bisect
import numpy as np
d=sys.argv[1]
T=json.load(open(d+'/trace.json')); plan=json.load(open(d+'/plan.json'))
names={r['label']:r['name'] for r in plan['rounds']}
# clock offset
best=None
for line in open(d+'/clock.txt'):
    p=line.split(); t0=int(p[0]); ph=int(p[1]); t1=int(p[-1])
    if best is None or t1-t0<best[0]: best=(t1-t0,ph-(t0+t1)/2)
OFF=best[1]/1e9
# phone g lines with raw w + gravity, phone log time -> PC clock
G=[]
for line in open(d+'/phone.log',errors='replace'):
    m=re.match(r'\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+douhua-air:\s+g (\d+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+) (\S+)',line)
    if not m: continue
    tw=float(m.group(1))-OFF; ts=int(m.group(2))
    w=[float(m.group(i)) for i in (7,8,9)]; g=[float(m.group(i)) for i in (10,11,12)]
    if g==[0,0,0]: continue
    G.append((tw,ts,w,g))
# sensor timestamp -> PC clock: logcat time minus delivery delay; use min(tw - ts) as mapping
E=min(x[0]-x[1]/1e9 for x in G)
Gt=np.array([x[1]/1e9+E for x in G]); Gw=np.array([x[2] for x in G]); Gg=np.array([x[3] for x in G])
print(f"手机样本 {len(G)}，对时偏差 {OFF*1000:.0f}ms（±{best[0]/2e6:.0f}）")
def nearest_tangent(x,y,tgt):
    bd=1e18; bt=0
    for i in range(1,len(tgt)):
        x0,y0=tgt[i-1]; x1,y1=tgt[i]; dx,dy=x1-x0,y1-y0; L2=dx*dx+dy*dy
        t=max(0,min(1,((x-x0)*dx+(y-y0)*dy)/L2)); px,py=x0+t*dx,y0+t*dy; dd=(x-px)**2+(y-py)**2
        if dd<bd: bd=dd; bt=math.atan2(dy,dx)
    return bd,bt
# samples: for each trace point: target tangent + cursor speed + time
S=[]
for r in T['results']:
    pts=r['points']
    for i in range(len(pts)):
        j=i
        while j<len(pts) and pts[j][0]-pts[i][0]<30: j+=1
        if j>=len(pts): break
        dt=(pts[j][0]-pts[i][0])/1000
        if dt<=0: continue
        vx=(pts[j][1]-pts[i][1])/dt; vy=(pts[j][2]-pts[i][2])/dt
        sp=math.hypot(vx,vy)
        if sp<60: continue   # px/s
        dd,tg=nearest_tangent(pts[i][1],pts[i][2],r['target'])
        if dd>60**2: continue
        S.append((pts[i][0]/1000, tg, sp, r['round'], r['shape'], vx, vy))
print('描红样本', len(S))
def wg_at(t):
    k=np.searchsorted(Gt,t); k=min(max(k,1),len(Gt)-1)
    if abs(Gt[k]-t)>0.02: return None
    return Gw[k],Gg[k]
# find latency: maximize correlation of |w| with cursor speed
best_lag=0;bc=-1
for lag in range(0,160,5):
    a=[];b=[]
    for s in S:
        x=wg_at(s[0]-lag/1000)
        if x is None: continue
        a.append(np.linalg.norm(x[0])); b.append(s[2])
    c=np.corrcoef(a,b)[0,1]
    if c>bc: bc=c; best_lag=lag
print(f"手机陀螺仪到电脑光标的延迟（相关最大）：{best_lag}ms（相关 {bc:.2f}）")
def spaces(w,g):
    u=g/np.linalg.norm(g); ux,uy,uz=u
    out={}
    # player
    wy=w[1]*uy+w[2]*uz; loc=math.hypot(w[1],w[2]); yaw=math.copysign(min(abs(wy)*1.41,loc),wy); out['玩家空间']=(yaw,w[0])
    # world
    q=np.array([1,0,0])-u*ux; qn=np.linalg.norm(q); out['世界空间']=(float(w@u), float(w@q/qn))
    # oblique
    pn=math.hypot(uy,uz); py,pz=uz/pn,-uy/pn; det=uy*-pz+uz*py
    yaw=(w[1]*-pz+w[2]*py)/det; cx,cy,cz=w[1]*pz-w[2]*py,-w[0]*pz,w[0]*py; out['斜基']=(yaw,(ux*cx+uy*cy+uz*cz)/det)
    # local (body) yaw about z, pitch about x
    out['手机本地轴']=(w[2],w[0])
    return out
rows=[]
for s in S:
    x=wg_at(s[0]-best_lag/1000)
    if x is None: continue
    rows.append((s,x[0],x[1]))
print('对上的样本', len(rows))
def angerr(a,b): return (a-b+math.pi)%(2*math.pi)-math.pi
res={}
for s,w,g in rows:
    for k,(yaw,pitch) in spaces(w,g).items():
        h=-yaw; v=-pitch   # 屏幕：x 右 = h，y 下 = v（同 AirPointer：v = -pitch，屏幕 y 向下取正）
        if math.hypot(h,v)<0.05: continue
        e=angerr(math.atan2(v,h),s[1])
        res.setdefault(k,[]).append((math.degrees(e),s[3],s[4]))
print("\n手的转动按各种换算得到的方向，和图案该走的方向差多少度（全部三轮的数据）：")
for k,v in res.items():
    a=np.array([x[0] for x in v]); 
    print(f"  {k:6s} 平均 {a.mean():+5.1f}°  绝对值中位 {np.median(abs(a)):4.1f}°  RMS {math.sqrt((a**2).mean()):4.1f}°")
# personal linear fit in gravity frame: basis u, h1 = horizontal part of phone x, h2 = u x h1
X=[];Y=[]
for s,w,g in rows:
    u=g/np.linalg.norm(g); h1=np.array([1,0,0])-u*u[0]; h1/=np.linalg.norm(h1); h2=np.cross(u,h1)
    wg=np.array([w@u,w@h1,w@h2])
    X.append(np.concatenate([wg,w])); Y.append([math.cos(s[1])*s[2]/37.5, math.sin(s[1])*s[2]/37.5])
X=np.degrees(np.array(X)); Y=np.array(Y)
for name,cols in (('重力坐标系(竖直/水平右/水平前)',[0,1,2]),('手机坐标系(右/上/屏幕法线)',[3,4,5]),('两者合起来',[0,1,2,3,4,5])):
    A=X[:,cols]; M,*_=np.linalg.lstsq(A,Y,rcond=None); P=A@M
    e=np.degrees(np.arctan2(P[:,1],P[:,0])-np.arctan2(Y[:,1],Y[:,0])); e=(e+180)%360-180
    print(f"  拟合 {name}: 方向误差 绝对值中位 {np.median(abs(e)):4.1f}°  RMS {math.sqrt((e**2).mean()):4.1f}°")
    if len(cols)==3: print('     系数（行：屏幕右、屏幕下；列：'+name+'）\n', np.round(M.T,3))
