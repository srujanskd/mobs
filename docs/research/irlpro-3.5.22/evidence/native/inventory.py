from pathlib import Path
import hashlib, json, subprocess,re
root=Path('artifacts/unpacked/lib')
rows=[]
for p in sorted(root.glob('*/*.so')):
 symbols=subprocess.check_output(['/usr/bin/objdump','-T',str(p)],text=True)
 dynamic=subprocess.check_output(['/usr/bin/objdump','-p',str(p)],text=True)
 out=Path('evidence/native')/p.parent.name
 out.mkdir(exist_ok=True)
 (out/(p.name+'.symbols.txt')).write_text(symbols)
 (out/(p.name+'.dynamic.txt')).write_text(dynamic)
 defined=[l.split()[-1] for l in symbols.splitlines() if ' .text' in l or '\t.text' in l]
 rows.append({'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'exports_text_count':len(defined),'jni_exports':[s for s in defined if s.startswith('Java_') or s=='JNI_OnLoad'],'needed':re.findall(r'NEEDED\s+(\S+)',dynamic)})
Path('evidence/native/library-inventory.json').write_text(json.dumps(rows,indent=2))
print(json.dumps([r for r in rows if '/arm64-v8a/' in r['path']],indent=2))
