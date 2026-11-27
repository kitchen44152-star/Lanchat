#!/usr/bin/env python3
# usage: ui.py SERIAL tap "text"   | ui.py SERIAL texts | ui.py SERIAL type "text"
import subprocess, sys, re, xml.etree.ElementTree as ET, time
ser, cmd = sys.argv[1], sys.argv[2]
def adb(*a): return subprocess.run(['adb','-s',ser,*a],capture_output=True,text=True).stdout
def dump():
    for _ in range(4):
        adb('shell','uiautomator','dump','/sdcard/u.xml')
        try: return ET.fromstring(adb('exec-out','cat','/sdcard/u.xml'))
        except Exception: time.sleep(3)
    print('DUMPFAIL'); sys.exit(2)
def nodes(root):
    for n in root.iter('node'):
        yield n
if cmd=='texts':
    r=dump()
    for n in nodes(r):
        t=(n.get('text') or n.get('content-desc') or '').strip()
        if t: print(t.replace('\n',' | '))
elif cmd=='tap':
    want=sys.argv[3].lower(); idx=int(sys.argv[4]) if len(sys.argv)>4 else 0
    r=dump(); hits=[]
    for n in nodes(r):
        t=((n.get('text') or '')+' '+(n.get('content-desc') or '')).lower()
        if want in t and n.get('bounds'):
            hits.append(n)
    if len(hits)<=idx: print('NOTFOUND',want); sys.exit(1)
    m=re.findall(r'\d+',hits[idx].get('bounds')); x=(int(m[0])+int(m[2]))//2; y=(int(m[1])+int(m[3]))//2
    adb('shell','input','tap',str(x),str(y)); print('TAPPED',want,x,y)
elif cmd=='type':
    adb('shell','input','text',sys.argv[3].replace(' ','%s'))
elif cmd=='nodes':
    r=dump()
    for n in nodes(r):
        print(n.get('class'),'|',n.get('text'),'|',n.get('content-desc'),'|click=',n.get('clickable'),'|',n.get('bounds'))
elif cmd=='edit':
    r=dump()
    for n in nodes(r):
        if 'EditText' in (n.get('class') or ''):
            m=re.findall(r'\d+',n.get('bounds')); x=(int(m[0])+int(m[2]))//2; y=(int(m[1])+int(m[3]))//2
            adb('shell','input','tap',str(x),str(y)); print('EDIT',x,y); break
    else: print('NOEDIT'); sys.exit(1)
elif cmd=='tapdesc':
    want=sys.argv[3]
    r=dump()
    for n in nodes(r):
        if (n.get('content-desc') or '')==want and n.get('bounds'):
            m=re.findall(r'\d+',n.get('bounds')); x=(int(m[0])+int(m[2]))//2; y=(int(m[1])+int(m[3]))//2
            adb('shell','input','tap',str(x),str(y)); print('TAPDESC',want,x,y); break
    else: print('NODESC',want); sys.exit(1)
