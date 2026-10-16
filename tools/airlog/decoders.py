# 白板：同一批原始陀螺仪（g 行后六列）用几种方向换算 + 激光笔模型各算一遍，比每段线有多直；末尾量斜线左右 / 上下分量的时间差。
# 用法：python3 tools/airlog/decoders.py .private/airlog/<时间>（要 numpy；碰边的段没去掉，去掉碰边的看 interior.py）
# 同一批原始陀螺仪（手机坐标系）+ 重力，用不同的拆法算每段线的路径，看哪种最直
import json, sys, math, re
import numpy as np
d=sys.argv[1]
B=json.load(open(d+'/board.json'))
G=[];S=[]
for line in open(d+'/phone.log',errors='replace'):
    m=re.match(r'.*douhua-air:\s+g (\d+) \S+ \S+ \S+ \S+ (\S+) (\S+) (\S+) (\S+) (\S+) (\S+)',line)
    if m: G.append([int(m.group(1))]+[float(m.group(i)) for i in range(2,8)]); continue
    m=re.match(r'.*douhua-air:\s+s (\d+) A (\d+) (\d+)',line)
    if m: S.append((int(m.group(1)),int(m.group(2)),int(m.group(3))))
EV=[];cur={}
for line in open(d+'/evdev.log',errors='replace'):
    m=re.match(r'Event: time (\d+\.\d+), type 3 \(EV_ABS\), code \d+ \((ABS_[XY])\), value (\d+)',line)
    if m: cur[m.group(2)]=int(m.group(3)); continue
    m=re.match(r'Event: time (\d+\.\d+), -------------- SYN_REPORT',line)
    if m and 'ABS_X' in cur: EV.append((float(m.group(1)),cur['ABS_X'],cur['ABS_Y']))
j=0; lat=[]
for el,x,y in S:
    k=j
    while k<len(EV) and k<j+60 and not (abs(EV[k][1]-x)<=1 and abs(EV[k][2]-y)<=1): k+=1
    if k<len(EV) and k<j+60: lat.append(EV[k][0]-el/1e9); j=k+1
C=np.percentile(lat,5)
G=np.array(G); Gt=G[:,0]/1e9+C; W=G[:,1:4]; Gr=G[:,4:7]
ok=np.linalg.norm(Gr,axis=1)>1; Gt,W,Gr=Gt[ok],W[ok],Gr[ok]
dt=np.clip(np.diff(Gt,prepend=Gt[0]),0,0.05)
U=Gr/np.linalg.norm(Gr,axis=1)[:,None]
GX=1092.2/32767*1126; GY=GX   # 拆法比较时横竖用同一个像素/度（只看形状）
def rates(space):
    ux,uy,uz=U.T; wx,wy,wz=W.T
    if space=='玩家空间':
        wyaw=wy*uy+wz*uz; loc=np.hypot(wy,wz); yaw=np.sign(wyaw)*np.minimum(abs(wyaw)*1.41,loc); pitch=wx
    elif space=='世界空间':
        yaw=wx*ux+wy*uy+wz*uz; q=np.stack([1-ux*ux,-ux*uy,-ux*uz],1); q/=np.linalg.norm(q,axis=1)[:,None]; pitch=(W*q).sum(1)
    elif space=='斜基':
        pn=np.hypot(uy,uz); py,pz=uz/pn,-uy/pn; det=uy*-pz+uz*py
        yaw=(wy*-pz+wz*py)/det; cx,cy,cz=wy*pz-wz*py,-wx*pz,wx*py; pitch=(ux*cx+uy*cy+uz*cz)/det
    elif space=='手机本地轴':
        yaw=wz; pitch=wx
    return -np.degrees(yaw), -np.degrees(pitch)
paths={}
for sp in ('玩家空间','世界空间','斜基','手机本地轴'):
    h,v=rates(sp); paths[sp]=(np.cumsum(h*dt)*GX, np.cumsum(v*dt)*GY)
def expm(wv):
    th=np.linalg.norm(wv)
    if th<1e-12: return np.eye(3)
    a=wv/th; K=np.array([[0,-a[2],a[1]],[a[2],0,-a[0]],[-a[1],a[0],0]])
    return np.eye(3)+math.sin(th)*K+(1-math.cos(th))*K@K
def laser(k, axis):
    """激光笔：段起点的姿态当参照，算之后手机指向在虚拟墙上的落点（中心投影）。axis = 'top' 顶边 / 'fwd' 起点时水平朝前"""
    u0=U[k[0]]
    if axis=='top': f0=np.array([0,1.,0])
    else:
        pn=math.hypot(u0[1],u0[2]); f0=np.array([0,u0[2]/pn,-u0[1]/pn])
    up=u0-f0*(u0@f0); up/=np.linalg.norm(up); right=np.cross(f0,up)
    R=np.eye(3); X=[];Y=[]
    for i in k:
        f=R@f0   # 起点手机系里的当前指向
        X.append(math.degrees((f@right)/(f@f0))*GX); Y.append(-math.degrees((f@up)/(f@f0))*GY)
        R=R@expm(W[i]*dt[i])
    return np.array(X),np.array(Y)
def segments(pts, minrun=60):
    P=np.array([[p[1],p[2]] for p in pts]); T=np.array([p[0] for p in pts])/1000; c=P.mean(0); u,s,vt=np.linalg.svd(P-c,full_matrices=False); ax=vt[0]; q=(P-c)@ax
    segs=[]; start=0; ext=0; direction=0
    for i in range(1,len(q)):
        if direction==0:
            if abs(q[i]-q[start])>minrun: direction=np.sign(q[i]-q[start]); ext=i
        else:
            if (q[i]-q[ext])*direction>0: ext=i
            elif (q[ext]-q[i])*direction>minrun: segs.append((start,ext)); start=ext; direction=-direction; ext=i
    if direction!=0: segs.append((start,len(q)-1))
    return P,T,segs
def dev(X,Y):
    P=np.stack([X,Y],1); A=P[0]; Bp=P[-1]; L=np.linalg.norm(Bp-A)
    if L<150: return None
    t=(Bp-A)/L; n=np.array([-t[1],t[0]]); perp=(P-A)@n
    return abs(perp).max()/L*100, np.sqrt((perp**2).mean())/L*100   # 最大偏离、RMS，占线长的百分比
names=['玩家空间','世界空间','斜基','手机本地轴','激光笔·顶边','激光笔·水平朝前']
res={}
for cat in ('斜线','竖线','横线'):
    for s in B['strokes']:
        if s['cat']!=cat: continue
        P,T,segs=segments(s['points'])
        for a,b in segs:
            k=np.where((Gt>=T[a])&(Gt<=T[b]))[0]
            if len(k)<15: continue
            row=[]
            for nm in names:
                if nm.startswith('激光笔'): X,Y=laser(k,'top' if '顶边' in nm else 'fwd')
                else: X,Y=paths[nm][0][k],paths[nm][1][k]
                r=dev(X-X[0],Y-Y[0]); row.append(r)
            if None in row: continue
            res.setdefault(cat,[]).append(row)
print("每段线偏离直线：最大偏离 / RMS，都按线长的百分比（中位，越小越直）")
print("        " + "  ".join(f"{n:>12s}" for n in names))
for cat,rows in res.items():
    R=np.array(rows)   # 段 × 拆法 × 2
    print(f"  {cat} {len(rows):3d}段 " + "  ".join(f"{np.median(R[:,i,0]):5.1f}/{np.median(R[:,i,1]):4.1f}" for i in range(len(names))))

# 斜线整笔（来回）：左右速度 h(t) 和上下速度 v(t) 之间的时间差（互相关最大），按"/"和"\"分
print("\n斜线整笔：上下分量比左右分量晚多少毫秒（互相关最大处；正 = 上下晚）")
h,v=rates('玩家空间')
for s in B['strokes']:
    if s['cat']!='斜线': continue
    T=np.array([p[0] for p in s['points']])/1000
    k=np.where((Gt>=T[0])&(Gt<=T[-1]))[0]
    if len(k)<200: continue
    hh=h[k]-h[k].mean(); vv=v[k]-v[k].mean()
    sgn=np.sign(np.sum(hh*vv))   # "/"：往右时往上（v<0）→ 负相关；"\" 正相关
    lags=range(-40,41)   # 帧（约 4.3ms 一帧）
    cc=[np.sum(hh[max(0,-L):len(hh)-max(0,L)]*vv[max(0,L):len(vv)-max(0,-L)])*sgn for L in lags]
    L=lags[int(np.argmax(cc))]; fr=np.median(np.diff(Gt[k]))*1000
    corr=max(cc)/math.sqrt(np.sum(hh**2)*np.sum(vv**2))
    kind='\\' if sgn>0 else '/'
    print(f"  {kind}  {len(k)/ (1000/fr):4.1f}s  上下比左右晚 {L*fr:+5.0f}ms  相关 {corr:.2f}  来回频率 {np.argmax(abs(np.fft.rfft(hh))[1:])+1:.0f} 次/{len(k)*fr/1000:.0f}s")
