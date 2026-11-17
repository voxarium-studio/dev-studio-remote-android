"""Read-only source snapshot; never includes credentials, caches or generated output."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(android, studio):
    result = {}
    patterns = {
        "android": (android, ["app/src/**/*", "editor-web/src/**/*", "editor-web/tests/**/*",
                              "editor-web/scripts/**/*", "editor-web/fixtures/**/*",
                              "editor-web/upstream/**/*", "gradle/**/*", "*.kts",
                              "gradle.properties", "gradlew*", "app/*.kts",
                              "editor-web/*.json", "editor-web/*.ts", "editor-web/*.mjs"]),
        "studio": (studio, ["frontend/src/lib/editor/**/*", "frontend/src/lib/collab/**/*",
                            "frontend/src/lib/md/**/*", "frontend/src/components/docs/**/*",
                            "frontend/package*.json", "remote/voxarium_studio_remote/**/*",
                            "remote/pyproject.toml"]),
    }
    for name, (root, globs) in patterns.items():
        for pattern in globs:
            for path in root.glob(pattern):
                if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                    result[f"{name}/{path.relative_to(root).as_posix()}"] = digest(path)
    return dict(sorted(result.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--studio", type=Path, default=(Path(__file__).resolve().parents[2] / 'VoxariumStudio'))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    data = snapshot(args.android, args.studio)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.compare:
        before = json.loads(args.compare.read_text(encoding="utf-8"))
        changed = [key for key in sorted(before.keys() | data.keys()) if before.get(key) != data.get(key)]
        print(json.dumps({"files": len(data), "changed": changed}, indent=2))
        raise SystemExit(bool(changed))
    print(f"Recorded {len(data)} source hashes")


if __name__ == "__main__":
    main()
