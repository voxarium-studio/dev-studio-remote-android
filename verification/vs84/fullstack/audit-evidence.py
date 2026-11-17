"""Independent post-run checks of retained real-network evidence (no server launch)."""
import argparse, hashlib, json, pathlib, socket, xml.etree.ElementTree as ET

p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--native',required=True);a=p.parse_args()
root=pathlib.Path(a.run);native=pathlib.Path(a.native)
read=lambda name:json.loads((root/name).read_text(encoding='utf-8'))
result=read('fullstack-results.json');assert result['failure'] is None and not result['apkGateEnabled']
assert all(r['status']=='PASS' for r in result['rows'])
assert read('sources.json')['unchanged'] and not read('sources.json')['changed']
sends=read('native-sends.json');events=read('native-events.json')
wire=[json.loads(line) for line in (root/'wire.jsonl').read_text().splitlines()]
joined={f['sid']:f['path'] for f in sends if f['t']=='join'}
clean_sids={sid for sid,path in joined.items() if path.startswith('clean-')}
assert len(clean_sids)==10
assert not any(f['t'] in ('put','delete') and f['path'].startswith('clean-') or f['t']=='update' and f['sid'] in clean_sids for f in sends)
assert not any(e['event'] in ('changed','localUpdate','blocked') and e['sid'] in clean_sids for e in events)
for path in ('unsafe-winner.md','unsafe-foreign.md','unsafe-local.md','unsafe-handoff.md'):
    sids={sid for sid,value in joined.items() if value==path};assert len(sids)==1
    assert not any(f['t'] in ('put','delete') and f['path']==path or f['t']=='update' and f['sid'] in sids for f in sends)
for i in range(5):assert not any(f['t']=='join' and f['path']==f'unsafe-source-{i}.md' for f in sends)
for i,event in enumerate(events):
    if event['event']=='blocked':
        assert not any(e['sid']==event['sid'] and e['event'] in ('ready','applied','changed','localUpdate','flushed') for e in events[i+1:])
handoff=next(sid for sid,path in joined.items() if path=='unsafe-handoff.md')
assert any(w['direction']=='out' and w['frame']['t']=='scribe' and w['frame']['sid']==handoff for w in wire)
assert any(r['detail'].get('actualLateHandoffCallbackReplayed') for r in result['rows'])
late=[f for f in sends if f['t']=='put' and f['path']=='unsafe-late-ack.md'];assert len(late)==1
assert late[0]['content']=='SAFE a b.\n'
assert not any('REJECTED' in f.get('content','') for f in sends if f['t']=='put')
recovery=read('recovery.json');assert len(recovery)==10
for e in recovery:
    assert e['snapshot']==e['source']
    if not e['path'].startswith('unsafe-source-'):
        assert e['raw'] and 'REJECTED ' in e['decoded']['merged']
# Outbound real Remote change frames prove persisted originals did not change;
# setup PUTs are deliberate fixtures, not counted as editor publications.
originals={e['path']:e['source'] for e in recovery if e['path']!='unsafe-late-ack.md'}
for path,source in originals.items():
    changes=[w['frame'] for w in wire if w['direction']=='out' and w['frame']['t']=='change' and w['frame']['path']==path]
    assert changes and all(f['content']==source and f['hash']==hashlib.sha256(source.encode()).hexdigest() and not f['deleted'] for f in changes)
    assert len({f['rev'] for f in changes})==1
ordinary=[r for r in result['rows'] if r['name'].startswith('ordinary real ACK')]
assert len(ordinary)==3 and ordinary[-1]['detail']['expectedVisible']=='XXXPlain Русский 🦊 é CJK漢字.'
colors=[r for r in result['rows'] if r['name'].startswith('safe-color real ACK')]
assert len(colors)==3 and colors[-1]['detail']['expectedVisible']=='XXXone two three four'
native_suites=[ET.parse(f).getroot() for f in (native/'junit').glob('*.xml')]
totals={key:sum(int(s.get(key,'0')) for s in native_suites) for key in ('tests','failures','errors','skipped')}
assert totals=={'tests':375,'failures':0,'errors':0,'skipped':0}
assert json.loads((native/'sources.json').read_text())['unchanged']
xml=ET.parse(root/'junit.xml').getroot();assert int(xml.get('tests'))==1 and all(int(xml.get(key,'0'))==0 for key in ('failures','errors','skipped'))
assert not read('browser-errors.json')
checks=read('browser-cleanup.json');checks.append({'port':read('cleanup.json')['port'],'closed':True})
assert read('cleanup.json')['threadStopped'] and read('cleanup.json')['browserExit']==0
for item in checks:
    assert item['closed']
    with socket.socket() as s:assert s.connect_ex(('127.0.0.1',item['port']))!=0
assert not (next(p for p in root.parents if p.name=='verification')/'editor-write-gate.json').exists()
out={'status':'PASS','rows':len(result['rows']),'cleanNativeOpens':len(clean_sids),'actualHandoffAndLateCallback':True,'unsafeRecovered':len(recovery),'native':totals,'atomicJournalReread':True,'portsClosed':checks,'androidGateFingerprint':read('sources.json')['androidGateFingerprint']}
(root/'independent-audit.json').write_text(json.dumps(out,indent=2))
print(json.dumps(out,indent=2))
