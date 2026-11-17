"""VS-84 read-only acceptance audit. Output only under verification/vs84/audit.

Run after all test runners have stopped. Does not stop arbitrary processes/devices.
No server, package installation, credential reads or global configuration changes.
"""
import datetime
import hashlib
import importlib.util
import json
import socket
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
VS84 = HERE.parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port_closed(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(('127.0.0.1', port)) != 0


def main():
    historical = json.loads((VS84 / 'historical/original-evidence-manifest.json').read_text('utf8'))
    history_changes = [p for p, digest in historical.items()
                       if not (ROOT / 'verification' / p).is_file()
                       or sha(ROOT / 'verification' / p) != digest]
    spec = importlib.util.spec_from_file_location('source_integrity', ROOT / 'verification/audit/source_integrity.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    before = json.loads((HERE / 'source-before.json').read_text('utf8'))
    after = module.snapshot(ROOT, (Path(__file__).resolve().parents[3] / 'VoxariumStudio'))
    (HERE / 'source-after.json').write_text(json.dumps(after, indent=2) + '\n', encoding='utf8')
    source_changes = [p for p in sorted(before.keys() | after.keys()) if before.get(p) != after.get(p)]
    ports = set()
    cleanup_files = []
    for p in VS84.rglob('*.json'):
        if p.parent == HERE or 'historical' in p.parts:
            continue
        if p.name not in ('results.json', 'cleanup.json', 'browser-cleanup.json', 'environment.json'):
            continue
        try:
            value = json.loads(p.read_text('utf-8-sig'))
        except (ValueError, OSError):
            continue
        def visit(v):
            if isinstance(v, dict):
                for key, item in v.items():
                    if key == 'port' and isinstance(item, int):
                        ports.add(item)
                    elif key == 'ports' and isinstance(item, dict):
                        ports.update(n for n in item.values() if isinstance(n, int))
                    else:
                        visit(item)
            elif isinstance(v, list):
                for item in v:
                    visit(item)
        visit(value)
        cleanup_files.append(p.relative_to(ROOT).as_posix())
    checks = [{'port': n, 'closed': port_closed(n)} for n in sorted(ports)]
    # These are forbidden Studio ports, not owned test listeners.
    assert not ports.intersection({8765, 5173}), 'A harness used a forbidden Studio port'
    report = {
        'task': 'VS-84', 'date': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'historicalEvidenceFiles': len(historical), 'historicalEvidenceChanged': history_changes,
        'sourceFiles': len(after), 'sourceChanged': source_changes,
        'cleanupInputs': cleanup_files, 'testPorts': checks,
        'writeGateReportExists': (ROOT / 'verification/editor-write-gate.json').exists(),
        'scope': 'Read-only source/evidence and retained test-port audit; AVD inventory is checked separately.',
    }
    (HERE / 'final-checks.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
    print(json.dumps(report, indent=2))
    if history_changes or source_changes or not ports or any(not p['closed'] for p in checks):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
