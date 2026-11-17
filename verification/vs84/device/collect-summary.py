from pathlib import Path
import json,xml.etree.ElementTree as ET,hashlib,re
p=Path(__file__).resolve().parent
suites=[ET.parse(q).getroot() for q in (p/'native-xml').glob('TEST-*.xml')]
x=ET.parse(p/'lint-results-debug.xml').getroot()
a=p/'app-debug-verification.apk'
log=(p/'current-build.log').read_text(encoding='utf-16')
fp=re.search(r'editorSourceSha256=([a-f0-9]{64})',log).group(1)
web=int(re.search(r'Tests\s+(\d+) passed',log).group(1))
config=(p/'BuildConfig.java').read_text()
assert 'EDITOR_WRITES_ENABLED = false' in config
out={'sourceSha256':fp,'apkSha256':hashlib.sha256(a.read_bytes()).hexdigest(),'apkBytes':a.stat().st_size,'gateReportExists':(p.parents[1]/'editor-write-gate.json').exists(),'buildConfigWritesEnabled':False,'native':{'suites':len(suites),**{n:sum(int(s.get(n,0)) for s in suites) for n in ('tests','failures','errors','skipped')}},'lint':{n:sum(i.get('severity')==n for i in x.findall('issue')) for n in ('Error','Warning','Fatal')},'webTests':web,'device':{}}
for folder in ['device-en','device-ru-dark-large']:
    text=(p/folder/'instrumentation.txt').read_text(encoding='utf-16')
    out['device'][folder]={'passGroups':len(re.findall(r'^(?:INSTRUMENTATION_RESULT: stream=)?PASS ',text,re.M)),'failGroups':len(re.findall(r'^FAIL ',text,re.M)),'success':'failures=0' in text and 'INSTRUMENTATION_CODE: -1' in text}
(p/'build-summary.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
print(json.dumps(out,indent=2))
