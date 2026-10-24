from pathlib import Path
import json,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[1]
r=root/'artifacts/jadx/resources/res'
strings={e.attrib['name']:''.join(e.itertext()) for e in E.parse(r/'values/strings.xml').getroot() if e.tag=='string'}
arrays={e.attrib.get('name'):[''.join(x.itertext()) for x in e] for e in E.parse(r/'values/arrays.xml').getroot()}
def resolve(v):
 if v.startswith('@string/'): return strings.get(v[8:],v)
 if v.startswith('@array/'): return arrays.get(v[7:],v)
 return v
rows=[]
for p in sorted((r/'xml').glob('prefs_*.xml')):
 for e in E.parse(p).getroot().iter():
  attrs={k.rsplit('}',1)[-1]:v for k,v in e.attrib.items()}
  if 'key' not in attrs: continue
  rows.append({'file':str(p.relative_to(root)),'component':e.tag,'raw':attrs,'resolved':{k:resolve(v) for k,v in attrs.items()}})
(root/'evidence/ui/preferences.json').write_text(json.dumps(rows,indent=2))
print('Preference declarations:',len(rows))
