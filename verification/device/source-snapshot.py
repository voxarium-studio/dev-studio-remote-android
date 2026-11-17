"""Disposable full-source snapshot, gate fingerprint and immutable-file audit."""
import argparse, hashlib, json, pathlib, shutil

EXCLUDED = {"build", "builds", ".gradle", ".kotlin", "node_modules", ".tools", "verification", ".idea", ".git", "dist", ".vite", ".cache"}
EXPECTED = "2a0438f6d82c56f3e0d4d7ad53a90a64d7cd166006b24b3350b9651311ed7307"

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def files(root):
    return sorted((p for p in root.rglob('*') if p.is_file() and not EXCLUDED.intersection(p.relative_to(root).parts[:-1])), key=lambda p:p.relative_to(root).as_posix())
def gate(root):
    fs=set()
    for folder in ('app/src/main','app/src/test','editor-web/src','editor-web/tests','editor-web/scripts'):
        fs.update(p for p in (root/folder).rglob('*') if p.is_file())
    fs.update(root/'editor-web'/s for s in ('package.json','package-lock.json','vite.config.ts'))
    fs.update((root/'editor-web').glob('*config*.json'))
    fs.add(root/'app/build.gradle.kts')
    h=hashlib.sha256()
    for p in sorted(fs,key=lambda p:p.relative_to(root).as_posix()):
        h.update(p.relative_to(root).as_posix().encode()+b'\0'+p.read_bytes()+b'\0')
    return h.hexdigest()

parser=argparse.ArgumentParser()
parser.add_argument('mode',choices=['copy','verify'])
parser.add_argument('--root',required=True,type=pathlib.Path)
parser.add_argument('--manifest',required=True,type=pathlib.Path)
parser.add_argument('--destination',type=pathlib.Path)
parser.add_argument('--allow-nongate-doc-drift',action='store_true')
args=parser.parse_args()
root=args.root.resolve()
changed=[]
if args.mode=='copy':
    dst=args.destination.resolve()
    expected_temp=(pathlib.Path(__file__).resolve().parents[2] / 'VoxariumStudio' / '.voxarium_studio/temp/TASK-78/device').resolve()
    if not dst.is_relative_to(expected_temp) or dst.exists(): raise SystemExit('Destination must be new and inside device task temp')
    selected=files(root)
    manifest={p.relative_to(root).as_posix():sha(p) for p in selected}
    args.manifest.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    for p in selected:
        q=dst/p.relative_to(root); q.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(p,q)
    assert all(sha(dst/n)==value for n,value in manifest.items())
else:
    manifest=json.loads(args.manifest.read_text(encoding='utf-8'))
    changed=[n for n,value in manifest.items() if not (root/n).is_file() or sha(root/n)!=value]
    if changed and not (args.allow_nongate_doc_drift and set(changed)<={'VERIFICATION.md'}):
        raise SystemExit('Changed source files: '+repr(changed))
digest=gate(root)
print(json.dumps({'root':str(root),'sourceFiles':len(manifest),'sourceSha256':digest,'expectedSourceSha256':EXPECTED,'allManifestFilesUnchanged':not changed,'concurrentNonGateDocumentChanges':changed},indent=2))
assert digest==EXPECTED, 'Source fingerprint differs from requested snapshot'
