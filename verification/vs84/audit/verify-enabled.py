"""Read-only cross-check of the enabled build and installed APK evidence."""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
V = ROOT / 'verification/vs84'
E = V / 'device/write-capable'


def text(path):
    b = path.read_bytes()
    return b.decode('utf-16' if b.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    b = json.loads(text(E / 'build-summary.json'))
    report = json.loads(text(ROOT / 'verification/editor-write-gate.json'))
    assert b['sourceSha256'] == report['sourceSha256']
    assert b['generatedBuildConfigWritesEnabled'] is True and b['webTests'] == 445
    assert b['native'] == {'suites': 25, 'tests': 362, 'failures': 0, 'errors': 0, 'skipped': 0}
    assert b['lint'] == {'Error': 0, 'Warning': 10, 'Fatal': 0}
    assert b['assetsSameAsClosedStage'] and 'EDITOR_WRITES_ENABLED = true;' in text(E / 'BuildConfig.java')
    apk = E / 'app-debug-verification.apk'
    assert sha(apk) == b['apkSha256'] and apk.stat().st_size == b['apkBytes']
    totals = {k: 0 for k in ('tests', 'failures', 'errors', 'skipped')}
    xml = list((E / 'native-xml').glob('TEST-*.xml'))
    assert len(xml) == 25
    for p in xml:
        suite = ET.parse(p).getroot()
        for k in totals:
            totals[k] += int(suite.get(k, '0'))
    assert totals == {'tests': 362, 'failures': 0, 'errors': 0, 'skipped': 0}
    log = text(E / 'current-build.log')
    assert 'BUILD SUCCESSFUL' in log and '60 actionable tasks: 60 executed' in log
    for name in ('device-en', 'device-ru-dark-large'):
        s = text(E / name / 'instrumentation.txt')
        assert 'failures=0' in s and 'INSTRUMENTATION_CODE: -1' in s
        assert sum(line.startswith('PASS ') or line.startswith('INSTRUMENTATION_RESULT: stream=PASS ')
                   for line in s.splitlines()) == 16
        assert 'installed BuildConfig write gate is true' in s
        assert '10 clean open/close -> 0 update/PUT/DELETE' in s
        assert 'unsafe colored local IME -> Blocked/recovery, 0 update/PUT, no false Flush' in s
        assert 'updates=1; puts=1; controlled ENQUEUED only, NO ACK' in s
        assert 'CLOSED_RECOVERY_UNCONFIRMED' in s
        assert b['apkSha256'] in text(E / name / 'instrumentation-target.txt').lower()
    inputs = json.loads(text(E / 'gate-inputs.json'))
    assert len(inputs) == 2
    for item in inputs:
        expected = sha(ROOT / item['relativePath'])
        assert item['originalSha256'] == item['copySha256'] == item['archiveSha256'] == expected
        assert sha(E / 'gate-inputs' / item['relativePath']) == expected
    inventory = text(E / 'final-inventory.txt')
    assert 'OwnedListenerCount=0' in inventory and 'OwnedEmulatorProcessCount=0' in inventory
    before = json.loads(text(V / 'audit/studio-ports-observed.json'))
    after = json.loads(text(V / 'audit/studio-ports-after.json'))
    # Get-NetTCPConnection returned [] initially, then a pre-existing packaged
    # Studio listener. Inventory equality is NOT evidence of who started/stopped
    # a server. Preserve the discrepancy; validate our allocated ports instead.
    cleanup = json.loads(text(V / 'audit/final-checks.json'))
    assert cleanup['testPorts'] and all(p['closed'] and p['port'] not in (8765, 5173)
                                       for p in cleanup['testPorts'])
    result = {'task': 'VS-84', 'sourceSha256': b['sourceSha256'], 'apkSha256': b['apkSha256'],
              'apkBytes': b['apkBytes'], 'writesEnabled': True, 'allBuildTasksExecuted': 60,
              'webTests': 445, 'native': totals, 'deviceGroupsPerConfiguration': 16,
              'deviceConfigurations': 2, 'acceptedInputsByteIdentical': True,
              'controlledTransportOnly': True, 'realRemoteDurabilityClaimed': False,
              'harnessDidNotAllocateStudioPorts': True,
              'studioListenerInventoryStable': before == after,
              'studioInventoryDiscrepancy': 'Initial [] is not proof of absence. Later packaged Studio PID 15276 predates VS-84; no task start/stop action was issued.',
              'ownedAVDStopped': True}
    (V / 'audit/enabled-artifact-checks.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
