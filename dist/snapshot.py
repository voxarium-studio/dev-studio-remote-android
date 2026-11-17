"""VS-79: immutable source snapshot and existing acceptance validation. No report creation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

EXCLUDED = {"build", "builds", ".gradle", ".kotlin", "node_modules", ".tools", "verification", ".idea", ".git", "dist", ".vite", ".cache"}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def gate_files(root):
    files = set()
    for rel in ("app/src/main", "app/src/test", "editor-web/src", "editor-web/tests", "editor-web/scripts"):
        files.update(p for p in (root / rel).rglob("*") if p.is_file())
    files.update(root / "editor-web" / n for n in ("package.json", "package-lock.json", "vite.config.ts"))
    files.update((root / "editor-web").glob("*config*.json"))
    files.add(root / "app/build.gradle.kts")
    return sorted(files, key=lambda p: p.relative_to(root).as_posix())


def validate(root):
    report_file = root / "verification/editor-write-gate.json"
    assert report_file.is_file(), "BLOCKER: missing acceptance report"
    assert report_file.stat().st_size <= 16384
    report = json.loads(report_file.read_text("utf-8"))
    checks = ("schemaParity", "roomInterop", "noWriteOnOpen", "scribeAckOrdering", "recoveryConflict")
    assert set(report) == set(checks) | {"version", "sourceSha256", "evidence", "evidenceSha256"}
    assert type(report["version"]) is int and report["version"] == 1
    assert all(report[k] is True for k in checks), "BLOCKER: incomplete acceptance"
    digest = hashlib.sha256()
    files = gate_files(root)
    for p in files:
        digest.update(p.relative_to(root).as_posix().encode() + b"\0" + p.read_bytes() + b"\0")
    assert digest.hexdigest() == report["sourceSha256"], "BLOCKER: stale source fingerprint"
    name = report["evidence"]
    assert re.fullmatch(r"verification/[A-Za-z0-9/._-]+", name)
    assert all(s not in ("", ".", "..") for s in name.split("/"))
    evidence = (root / name).resolve()
    assert evidence.is_relative_to((root / "verification").resolve())
    assert sha(evidence) == report["evidenceSha256"], "BLOCKER: changed evidence"
    entries = re.findall(r"^\| \[`([^`]+)`\]\([^)]*\) \| `([0-9a-f]{64})` \|$", evidence.read_text("utf-8"), re.M)
    assert len(entries) == 32, "BLOCKER: unexpected accepted evidence manifest"
    for rel, expected in entries:
        p = (evidence.parent / rel).resolve()
        assert p.is_relative_to(evidence.parent) and sha(p) == expected, f"BLOCKER: evidence drift {rel}"
    return {"sourceSha256": digest.hexdigest(), "sourceInputs": len(files), "reportSha256": sha(report_file),
            "evidenceSha256": sha(evidence), "criticalEvidenceFiles": len(entries), "fiveGates": True}


def selected(root):
    for base, dirs, names in os.walk(root):
        dirs[:] = sorted(n for n in dirs if n not in EXCLUDED)
        for name in sorted(names):
            p = Path(base) / name
            if p.relative_to(root).as_posix() not in ("README.md", "RELEASE.md"):
                yield p


parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=("copy", "verify"))
parser.add_argument("--source", required=True, type=Path)
parser.add_argument("--work", required=True, type=Path)
args = parser.parse_args()
source, work = args.source.resolve(), args.work.resolve()
allowed = (Path(__file__).resolve().parents[1] / 'VoxariumStudio' / '.voxarium_studio/temp/TASK-79').resolve()
assert work.is_relative_to(allowed) and work != allowed, "Work must be inside TASK-79"
manifest_path = work / "source-manifest.json"
copy = work / "source"
if args.mode == "copy":
    assert not copy.exists(), "Use a fresh work directory"
    result = validate(source)
    files = list(selected(source))
    report = json.loads((source / "verification/editor-write-gate.json").read_text("utf-8"))
    files += [source / "verification/editor-write-gate.json", source / report["evidence"]]
    manifest = {p.relative_to(source).as_posix(): sha(p) for p in files}
    for p in files:
        target = copy / p.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, target)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
else:
    result = validate(source)
    manifest = json.loads(manifest_path.read_text("utf-8"))
for root in (source, copy):
    for rel, expected in manifest.items():
        assert sha(root / rel) == expected, f"BLOCKER: changed source/copy {root / rel}"
    assert all(p.relative_to(root).as_posix() in manifest for p in gate_files(root)), "New gate input"
result.update({"capturedFiles": len(manifest), "sourceAndCopyUnchanged": True})
(work / ("gate-before.json" if args.mode == "copy" else "gate-after.json")).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
