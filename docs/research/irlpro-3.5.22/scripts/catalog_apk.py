#!/usr/bin/env python3
"""Catalog local JADX outputs; does not execute the app or contact endpoints."""
import collections, hashlib, json, pathlib, re, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent.parent
src=root/'artifacts/jadx/sources'; res=root/'artifacts/jadx/resources/res'
strings={}
for p in (res/'values').glob('*.xml'):
 try:
  for n in ET.parse(p).getroot():
   if n.tag=='string': strings[n.attrib.get('name')]=''.join(n.itertext())
 except ET.ParseError: pass
def resolve(v):
 if isinstance(v,str) and v.startswith('@string/'): return strings.get(v[8:],v)
 return v
android='{http://schemas.android.com/apk/res/android}'
prefs=[]
for p in sorted((res/'xml').glob('pref*.xml')):
 for n in ET.parse(p).iter():
  if android+'key' in n.attrib:
   prefs.append({'resource':str(p.relative_to(root)),'kind':n.tag,'key':resolve(n.get(android+'key')),'title':resolve(n.get(android+'title')),'default':resolve(n.get(android+'defaultValue')),'summary':resolve(n.get(android+'summary')),'attributes':n.attrib})
endpoints=[]; packages=collections.Counter(); files=[]; problems=[]
for p in sorted(src.rglob('*.java')):
 text=p.read_text(); rel=str(p.relative_to(root)); files.append({'path':rel,'lines':text.count('\n'),'sha256':hashlib.sha256(text.encode()).hexdigest()})
 match=re.search(r'^package ([^;]+);',text,re.M)
 if match: packages[match[1]]+=1
 for i,line in enumerate(text.splitlines(),1):
  for m in re.finditer(r'(?:https?|wss?|rtmps?|srtla|srt|rist)://[^\s"<>\\]+',line):
   endpoints.append({'value':m[0],'file':rel,'line':i})
  if 'JADX ERROR' in line or 'Code decompiled incorrectly' in line or 'Method not decompiled:' in line:
   problems.append({'file':rel,'line':i,'marker':line.strip()})
out=root/'evidence'; out.mkdir(exist_ok=True)
for name,data in [('preferences',prefs),('endpoint-literals',endpoints),('source-catalog',{'packages':dict(packages),'files':files,'decompiler_markers':problems})]:
 (out/(name+'.json')).write_text(json.dumps(data,indent=2))
print(json.dumps({'java_files':len(files),'packages':len(packages),'preference_entries':len(prefs),'url_literal_occurrences':len(endpoints),'decompiler_markers':len(problems),'app_packages':{p:n for p,n in packages.items() if p.startswith('com.wmspanel')}},indent=2))
