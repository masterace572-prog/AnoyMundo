"""Render each layer alone with rlottie; compare per-frame opacity and movement
# usage: pip install rlottie-python numpy; python verify.py anoy_splash.json 13.0 27.84
with SplashActivity.applyTime() (ported verbatim below)."""
import sys, json, copy, tempfile, os
import numpy as np
from rlottie_python import LottieAnimation

def bez(x1,y1,x2,y2):
    def f(p):
        if p<=0: return 0.0
        if p>=1: return 1.0
        lo,hi=0.0,1.0
        for _ in range(60):
            t=(lo+hi)/2; x=3*(1-t)**2*t*x1+3*(1-t)*t*t*x2+t**3
            lo,hi=(t,hi) if x<p else (lo,t)
        t=(lo+hi)/2; return 3*(1-t)**2*t*y1+3*(1-t)*t*t*y2+t**3
    return f
FOSI=bez(.4,0,.2,1); DIS=bez(.45,0,.55,1)
lin=lambda t,s,d: 0.0 if t<=s else 1.0 if t>=s+d else (t-s)/d
win=lambda p,a,b: 0.0 if p<=a else 1.0 if p>=b else (p-a)/(b-a)
prog=lambda t,s,d,e: e(lin(t,s,d))

def app(t, moveDy, dx):   # SplashActivity.applyTime, t in ms
    arrive=prog(t,0,400,FOSI); leave=prog(t,1150,520,FOSI)
    name=prog(t,120,400,FOSI); swap=lin(t,1560,450)
    ny=prog(t,1200,520,FOSI)*moveDy; nx=DIS(swap)*dx
    return {"system_mark":(1-prog(t,0,220,FOSI),0,0),
            "kicker":(arrive*(1-leave),0,-leave*8),
            "anoy":(name,nx,ny),
            "loader":(name*(1-DIS(win(swap,.3,1))),nx,ny),
            "hax":(name*DIS(win(swap,0,.7)),nx,ny)}

def layer_only(data, nm):
    d=copy.deepcopy(data); d["layers"]=[l for l in d["layers"] if l["nm"]==nm]
    for l in d["layers"]:   # opacity/position measured separately: freeze one
        pass
    return d

def measure(data, nm, frames, fix_opacity):
    d=layer_only(data,nm)
    if fix_opacity: d["layers"][0]["ks"]["o"]={"a":0,"k":100}
    p=tempfile.mktemp(suffix=".json"); json.dump(d,open(p,"w"))
    a=LottieAnimation.from_file(p); out=[]
    for f in frames:
        im=np.asarray(a.render_pillow_frame(frame_num=f,width=360*4,height=800*4))[...,3].astype(float)/255
        m=im.max(); 
        if fix_opacity:
            ys,xs=np.nonzero(im>0); w=im[ys,xs]
            out.append(((xs*w).sum()/w.sum()/4,(ys*w).sum()/w.sum()/4))
        else: out.append(m)
    os.remove(p); return out

data=json.load(open(sys.argv[1])); moveDy=-float(sys.argv[2]); dx=float(sys.argv[3])
frames=list(range(0,data["op"]+1))
names=[l["nm"] for l in data["layers"] if l["ty"]==4]
worst={}
for nm in names:
    op=measure(data,nm,frames,False); pos=measure(data,nm,frames,True)
    x0,y0=pos[0]; eo=ep=0; at=(0,0)
    for f,o,(x,y) in zip(frames,op,pos):
        t=f*1000/60; ao,ax,ay=app(t,moveDy,dx)[nm]
        do=abs(o-ao); dp=max(abs((x-x0)-ax),abs((y-y0)-ay)) if o>0.01 or nm!="hax" else 0
        if do>eo: eo,at=do,(f,at[1])
        if dp>ep: ep=dp; at=(at[0],f)
    worst[nm]=(eo,ep,at)
    print(f"{nm:12s} max opacity error {eo*100:5.2f}% (frame {at[0]})   max position error {ep:5.2f}px (frame {at[1]})")
