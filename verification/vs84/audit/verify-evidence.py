"""VS-84 explicit cross-suite decision inputs; never creates the write report.

The Studio-only no-loss assertion MUST remain a real FAIL with the exact lost
spaces. No generic 'ignore failures' or skip-to-pass conversion is permitted.
"""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
V = ROOT / 'verification/vs84'


def text_file(path):
    raw = path.read_bytes()
    # Windows PowerShell Start-Process redirects instrumentation as BOM UTF-16.
    return raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')


def read(name):
    return json.loads(text_file(V / name))


def totals(files):
    result = dict(tests=0, failures=0, errors=0, skipped=0, suites=0)
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == 'testsuite' else root.findall('testsuite')
        for suite in suites:
            result['suites'] += 1
            for key in ('tests', 'failures', 'errors', 'skipped'):
                result[key] += int(suite.get(key, '0'))
    return result


def no_errors(value, count):
    assert value['tests'] == count, value
    assert not any(value[k] for k in ('failures', 'errors', 'skipped')), value


def main():
    interop = read('interop/evidence-final/results.json')
    rows = interop['outcome']
    failed = [r for r in rows if r['status'] == 'FAIL']
    assert len(rows) == 24 and sum(r['status'] == 'PASS' for r in rows) == 23
    assert len(failed) == 1 and failed[0]['name'].startswith('UPSTREAM FAIL: unchanged Studio-only ')
    assert '"actual":"edit A nestedemphasisspan."' in failed[0]['error']
    assert '"expected":"edit A nested emphasis span."' in failed[0]['error']
    assert not interop['errors'] and all(p['closed'] for p in interop['cleanup']['portChecks'])
    for key in ('remoteStopped', 'relayClosed', 'viteClosed'):
        assert interop['cleanup'][key]
    assert read('interop/evidence-final/sources.json')['unchanged']
    fullstack = read('fullstack/atomic/fullstack-results.json')
    assert fullstack['failure'] is None and len(fullstack['rows']) == 19
    assert all(r['status'] == 'PASS' for r in fullstack['rows'])
    audit = read('fullstack/atomic/independent-audit.json')
    assert audit['status'] == 'PASS' and audit['cleanNativeOpens'] == 10
    assert audit['atomicJournalReread'] and audit['actualHandoffAndLateCallback'] and audit['unsafeRecovered'] == 10
    native = totals((V / 'native/recovery-rerun/junit').glob('TEST-*.xml'))
    no_errors(native, 375)
    assert native['suites'] == 28
    vertical = totals([V / 'fullstack/atomic/junit.xml'])
    no_errors(vertical, 1)
    build = read('device/build-summary.json')
    no_errors(build['native'], 362)
    assert build['native']['suites'] == 25 and build['webTests'] == 445
    assert build['lint'] == {'Error': 0, 'Warning': 10, 'Fatal': 0}
    assert build['buildConfigWritesEnabled'] is False and build['gateReportExists'] is False
    fingerprint = read('audit/gate-fingerprint.json')
    assert fingerprint['inputs'] == 110
    assert build['sourceSha256'] == fingerprint['sourceSha256'] == audit['androidGateFingerprint']
    apk = V / 'device/app-debug-verification.apk'
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == build['apkSha256']
    assert apk.stat().st_size == build['apkBytes']
    for config in ('device-en', 'device-ru-dark-large'):
        instrumentation = text_file(V / 'device' / config / 'instrumentation.txt')
        assert sum(line.startswith('PASS ') or line.startswith('INSTRUMENTATION_RESULT: stream=PASS ')
                   for line in instrumentation.splitlines()) == 16
        assert 'failures=0' in instrumentation and 'INSTRUMENTATION_CODE: -1' in instrumentation
        target = text_file(V / 'device' / config / 'instrumentation-target.txt')
        assert build['apkSha256'] in target.lower()
    remote = totals([V / 'audit/remote-junit.xml'])
    no_errors(remote, 205)
    before = read('audit/preacceptance-checks.json')
    assert before['historicalEvidenceFiles'] == 138 and not before['historicalEvidenceChanged']
    assert before['sourceFiles'] == 207 and not before['sourceChanged']
    assert before['writeGateReportExists'] is False
    assert len(before['testPorts']) == 29 and all(p['closed'] for p in before['testPorts'])
    inventory = text_file(V / 'device/final-inventory.txt')
    assert 'OwnedListenerCount=0' in inventory and 'OwnedEmulatorProcessCount=0' in inventory
    result = {
        'task': 'VS-84', 'sourceSha256': fingerprint['sourceSha256'],
        'androidSupportedOperations': 'PASS_WITH_EXPLICIT_FAIL_CLOSED',
        'unchangedStudioOnlyNoLoss': 'FAIL_UPSTREAM', 'interopRunnerExpectedExit': 1,
        'interop': {'PASS': 23, 'FAIL_UPSTREAM': 1, 'SKIP': 0},
        'native': native, 'fullstack': vertical, 'fullstackScenarioRows': 19,
        'completeBuildWebTests': 445, 'completeBuildNative': build['native'],
        'deviceGroupsPerConfiguration': 16, 'deviceConfigurations': 2, 'remote': remote,
        'originalEvidenceChanged': [], 'productionSourceChanged': [],
        'writeGateReportCreatedByThisScript': False,
        'limitations': [
            'Device dirty lifecycle uses controlled transport, not real network ACK.',
            'Device close/offline transitions are not full EditorScreen Back/background/profile-switch actions.',
            'Storage object reconstruction is not OS process kill/power-loss restoration.',
            'One API35 WebView/IME provider; no TalkBack, OEM/API matrix or physical device proof.',
            'Remote v1 has no durable Y.Doc, update ACK or schema negotiation.',
            'Android cannot prevent independent destructive Studio-only writes or restore previously lost text.',
        ],
    }
    (V / 'audit/integrated-evidence-checks.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
