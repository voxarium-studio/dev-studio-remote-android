"""Read-only independent validation of the exact EDITOR.md report and evidence.

Never creates, repairs or reaccepts a report. Computes current owning source bytes.
"""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    report_path = ROOT / 'verification/editor-write-gate.json'
    assert report_path.stat().st_size <= 16384
    report = json.loads(report_path.read_text('utf8'))
    gates = ['schemaParity', 'roomInterop', 'noWriteOnOpen', 'scribeAckOrdering', 'recoveryConflict']
    assert set(report) == set(gates + ['version', 'sourceSha256', 'evidence', 'evidenceSha256'])
    assert type(report['version']) is int and report['version'] == 1
    assert all(report[k] is True for k in gates)
    files = set()
    for rel in ['app/src/main', 'app/src/test', 'editor-web/src', 'editor-web/tests', 'editor-web/scripts']:
        files.update(p for p in (ROOT / rel).rglob('*') if p.is_file())
    web = ROOT / 'editor-web'
    files.update(p for p in web.iterdir() if p.is_file() and
                 (p.name in ('package.json', 'package-lock.json', 'vite.config.ts') or 'config' in p.name and p.suffix == '.json'))
    files.add(ROOT / 'app/build.gradle.kts')
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda p: p.relative_to(ROOT).as_posix()):
        digest.update(path.relative_to(ROOT).as_posix().encode('utf8'))
        digest.update(b'\0'); digest.update(path.read_bytes()); digest.update(b'\0')
    assert report['sourceSha256'] == digest.hexdigest()
    name = report['evidence']
    assert re.fullmatch(r'verification/[A-Za-z0-9/._-]+', name)
    assert all(p not in ('', '.', '..') for p in name.split('/'))
    evidence = (ROOT / name).resolve()
    assert evidence.is_relative_to((ROOT / 'verification').resolve())
    assert sha(evidence) == report['evidenceSha256']
    text = evidence.read_text('utf8')
    entries = re.findall(r'^\| \[`([^`]+)`\]\([^)]*\) \| `([0-9a-f]{64})` \|$', text, re.M)
    assert len(entries) == 32
    for rel, expected in entries:
        path = (evidence.parent / rel).resolve()
        assert path.is_relative_to(evidence.parent) and sha(path) == expected, rel
    assert 'FAIL_UPSTREAM' in text and 'edit A nestedemphasisspan.' in text
    assert 'expected `edit A nested emphasis span.`' in text
    result = {'task': 'VS-84', 'sourceSha256': digest.hexdigest(), 'sourceInputs': len(files),
              'reportSha256': sha(report_path), 'evidenceSha256': sha(evidence),
              'exactEvidenceHashesVerified': len(entries), 'fiveGates': True,
              'upstreamFailurePreserved': True, 'reportSchemaUnchanged': True}
    (HERE / 'report-validation.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
