from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET,zipfile,re
p=(Path(__file__).resolve().parents[3] / 'verification/vs84/device/write-capable')
old=p.parent
closed=[]
for line in (old/'evidence.sha256').read_text(encoding='utf-8').splitlines():
    digest,name=line.split('  ',1)
    closed.append({'path':name,'unchanged':hashlib.sha256((old/name).read_bytes()).hexdigest()==digest})
assert all(x['unchanged'] for x in closed)
inputs=json.loads((p/'gate-inputs.json').read_text(encoding='utf-8-sig'))
for x in inputs:
    current=(Path(__file__).resolve().parents[3])/x['relativePath']
    assert hashlib.sha256(current.read_bytes()).hexdigest()==x['originalSha256']==x['copySha256']==x['archiveSha256']
(p/'closed-stage-integrity.json').write_text(json.dumps({'closedManifestSha256':hashlib.sha256((old/'evidence.sha256').read_bytes()).hexdigest(),'files':closed,'allUnchanged':True,'gateInputsStillExact':True},indent=2),encoding='utf-8')
a=p/'app-debug-verification.apk'
with zipfile.ZipFile(a) as z:
    assets={n:{'bytes':z.getinfo(n).file_size,'sha256':hashlib.sha256(z.read(n)).hexdigest()} for n in z.namelist() if n.startswith('assets/editor/')}
(p/'apk-assets.json').write_text(json.dumps(assets,indent=2),encoding='utf-8')
suites=[ET.parse(q).getroot() for q in (p/'native-xml').glob('TEST-*.xml')]
x=ET.parse(p/'lint-results-debug.xml').getroot()
log=(p/'current-build.log').read_text(encoding='utf-16')
out={'sourceSha256':re.search(r'editorSourceSha256=([a-f0-9]{64})',log).group(1),'apkSha256':hashlib.sha256(a.read_bytes()).hexdigest(),'apkBytes':a.stat().st_size,'generatedBuildConfigWritesEnabled':'EDITOR_WRITES_ENABLED = true' in (p/'BuildConfig.java').read_text(),'webTests':int(re.search(r'Tests\s+(\d+) passed',log).group(1)),'native':{'suites':len(suites),**{n:sum(int(s.get(n,0)) for s in suites) for n in ('tests','failures','errors','skipped')}},'lint':{n:sum(i.get('severity')==n for i in x.findall('issue')) for n in ('Error','Warning','Fatal')}}
out['device']={}
for folder in ['device-en','device-ru-dark-large']:
    f=p/folder/'instrumentation.txt'
    if f.exists():
        text=f.read_text(encoding='utf-16')
        out['device'][folder]={'passGroups':len(re.findall(r'^(?:INSTRUMENTATION_RESULT: stream=)?PASS ',text,re.M)),'failGroups':len(re.findall(r'^FAIL ',text,re.M)),'success':'failures=0' in text and 'INSTRUMENTATION_CODE: -1' in text}
out['assetsSameAsClosedStage']=assets==json.loads((old/'apk-assets.json').read_text(encoding='utf-8'))
(p/'build-summary.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
print(json.dumps(out,indent=2))
