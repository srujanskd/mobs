#!/usr/bin/env python3
"""Capture observed settings entries in the isolated emulator session."""
from pathlib import Path
import subprocess,xml.etree.ElementTree as E,json
adb='/Users/srujankashyapd/Library/Android/sdk/platform-tools/adb'
out=Path('evidence/runtime'); records=[]
def run(*args): return subprocess.run([adb,'-s','emulator-5580',*args],check=True,capture_output=True).stdout
for name,y in [('streamer',470),('bonding',670),('connections',850),('video',990),('audio',1130),('recording',1270),('display',1410),('overlays',1555),('import-export',1695),('advanced',1840)]:
 run('shell','input','tap','400',str(y))
 run('shell','uiautomator','dump','/sdcard/irlpro-window.xml')
 run('pull','/sdcard/irlpro-window.xml',str(out/(name+'.xml')))
 (out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'))
 labels=[{'text':n.get('text'),'description':n.get('content-desc'),'bounds':n.get('bounds')} for n in E.parse(out/(name+'.xml')).iter('node') if n.get('text') or n.get('content-desc')]
 records.append({'screen':name,'labels':labels})
 print(name, ':', ' | '.join(n['text'] or n['description'] for n in labels),flush=True)
 run('shell','input','keyevent','4')
(out/'settings-screens.json').write_text(json.dumps(records,indent=2))
