"""VS-79: inspect the freshly built APK and complete build results. No network/device actions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def text(path):
    b = path.read_bytes()
    return b.decode("utf-16" if b.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def dex_config(b):
    """Read encoded static values of BuildConfig directly from the packaged DEX."""
    assert b[:4] == b"dex\n"
    u32 = lambda off: struct.unpack_from("<I", b, off)[0]
    def uleb(off):
        value = shift = 0
        while True:
            v = b[off]; off += 1
            value |= (v & 127) << shift
            if not v & 128:
                return value, off
            shift += 7
    ns, so = u32(56), u32(60)
    strings = []
    for i in range(ns):
        _, off = uleb(u32(so + i * 4))
        strings.append(b[off:b.index(0, off)].decode("utf-8", "replace"))
    nt, to = u32(64), u32(68)
    types = [strings[u32(to + i * 4)] for i in range(nt)]
    nf, fo = u32(80), u32(84)
    fields = [struct.unpack_from("<HHI", b, fo + i * 8) for i in range(nf)]
    nc, co = u32(96), u32(100)
    for i in range(nc):
        off = co + i * 32
        if types[u32(off)] != "Lcom/voxarium/remote/BuildConfig;":
            continue
        cd, values = u32(off + 24), u32(off + 28)
        count, cd = uleb(cd)
        for _ in range(3):
            _, cd = uleb(cd)
        names = []; idx = 0
        for _ in range(count):
            diff, cd = uleb(cd); idx += diff
            _, cd = uleb(cd)
            names.append(strings[fields[idx][2]])
        n, values = uleb(values)
        result = {}
        for name in names[:n]:
            header = b[values]; values += 1
            kind, arg = header & 31, header >> 5
            if kind == 31:
                value = bool(arg)
            elif kind == 30:
                value = None
            else:
                value = int.from_bytes(b[values:values + arg + 1], "little", signed=kind in (0, 2, 4, 6))
                values += arg + 1
                if kind == 23:
                    value = strings[value]
                else:
                    assert kind in (0, 2, 3, 4, 6), (name, kind)
            result[name] = value
        return result
    return None


parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument("--work", type=Path, required=True)
parser.add_argument("--sdk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"), required=not os.environ.get("JAVA_HOME"))
args = parser.parse_args()
source, work = args.source.resolve(), args.work.resolve()
assert work.is_relative_to((Path(__file__).resolve().parents[1] / 'VoxariumStudio' / '.voxarium_studio/temp/TASK-79').resolve())
out = work / "audit"; out.mkdir(exist_ok=True)
app = work / "source/app/build"
apk = app / "outputs/apk/debug/app-debug.apk"
tools = args.sdk / "build-tools/36.0.0"
env = dict(os.environ, JAVA_HOME=str(args.java_home))
def command(name, command):
    result = subprocess.run(command, capture_output=True, env=env)
    data = result.stdout + result.stderr
    (out / name).write_bytes(data)
    assert result.returncode == 0, f"{name}: exit {result.returncode}"
    return data.decode("utf-8", "replace")
badging = command("aapt-badging.txt", [str(tools / "aapt.exe"), "dump", "badging", str(apk)])
assert "package: name='com.voxarium.remote' versionCode='1' versionName='0.1.0'" in badging
assert "sdkVersion:'23'" in badging and "targetSdkVersion:'36'" in badging and "application-debuggable" in badging
signature = command("apksigner.txt", ["cmd.exe", "/d", "/c", str(tools / "apksigner.bat"), "verify", "--verbose", "--print-certs", str(apk)])
assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
assert "CN=Android Debug" in signature
command("zipalign.txt", [str(tools / "zipalign.exe"), "-c", "-P", "16", "-v", "4", str(apk)])
config_file = app / "generated/source/buildConfig/debug/com/voxarium/remote/BuildConfig.java"
assert "EDITOR_WRITES_ENABLED = true;" in text(config_file)
accepted_apk = source / "verification/vs84/device/write-capable/app-debug-verification.apk"
assets = {}; forbidden = []; findings = []; configs = []; dex_same = {}
with zipfile.ZipFile(apk) as z, zipfile.ZipFile(accepted_apk) as accepted:
    assert len(z.namelist()) == len(set(z.namelist()))
    for name in z.namelist():
        data = z.read(name)
        if name.startswith("assets/"):
            assert name.startswith("assets/editor/"), name
            generated = app / "generated/editorAssets" / name[len("assets/"):]
            assert data == generated.read_bytes(), name
            assert data == accepted.read(name), f"Accepted runtime asset differs: {name}"
            assets[name] = sha(data)
        if re.search(r"(^|/)(node_modules|verification|tests?|\.env)(/|$)|\.(map|keystore|jks|pem|p12|sqlite|db)$", name, re.I):
            forbidden.append(name)
        for label, pattern in {
            "privateKey": rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
            "jwt": rb"eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}",
            "githubToken": rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})",
            "awsAccessKey": rb"AKIA[0-9A-Z]{16}",
            "bearerLiteral": rb"Bearer [A-Za-z0-9._~-]{16,}",
        }.items():
            if re.search(pattern, data):
                findings.append({"entry": name, "kind": label})
        if re.fullmatch(r"classes\d*\.dex", name):
            value = dex_config(data)
            if value is not None:
                configs.append(value)
            dex_same[name] = data == accepted.read(name)
    assert {"assets/editor/room.html", "assets/editor/editor.js", "assets/editor/editor.css", "assets/editor/NOTICE", "assets/editor/PROVENANCE.json"} <= assets.keys()
    assert not forbidden and not findings, (forbidden, findings)
    assert len(configs) == 1 and configs[0]["EDITOR_WRITES_ENABLED"] is True
    assert configs[0]["APPLICATION_ID"] == "com.voxarium.remote" and configs[0]["BUILD_TYPE"] == "debug"
    # DEBUG is initialized in <clinit> with Boolean.parseBoolean("true"), not by
    # its encoded initial false/default. Do not misreport the pre-<clinit> value.
    configs[0].pop("DEBUG", None)
    assert all(dex_same.values()), "BLOCKER: DEX differs from accepted VS-84 enabled APK"
    assert apk.read_bytes() == accepted_apk.read_bytes(), "BLOCKER: exact accepted binary identity not established; new acceptance required"
    html = z.read("assets/editor/room.html").decode("utf-8")
    assert "Content-Security-Policy" in html and "connect-src 'none'" in html
    assert not re.search(r"(?:src|href)=[\"']https?://", html)
native = {k: 0 for k in ("tests", "failures", "errors", "skipped")}
xmls = list((app / "test-results/testDebugUnitTest").glob("TEST-*.xml"))
for p in xmls:
    suite = ET.parse(p).getroot()
    for k in native:
        native[k] += int(suite.get(k, "0"))
assert len(xmls) == 25 and native == {"tests": 362, "failures": 0, "errors": 0, "skipped": 0}
lint = {k: 0 for k in ("Error", "Warning", "Fatal")}
for issue in ET.parse(app / "reports/lint-results-debug.xml").getroot().findall("issue"):
    severity = issue.get("severity")
    lint[severity] = lint.get(severity, 0) + 1
assert lint["Error"] == lint["Fatal"] == 0
log = text(work / "build.log")
for expected in ("BUILD SUCCESSFUL", "Tests  445 passed (445)", "Standalone ESM/DOM bundle smoke: pass", "Standalone APK page/bridge/theme/RU/format/flush smoke: pass"):
    assert expected in log, expected
result = {"task": "VS-79", "apkSha256": sha(apk.read_bytes()), "apkBytes": apk.stat().st_size,
          "package": "com.voxarium.remote", "versionName": "0.1.0", "versionCode": 1, "minSdk": 23, "targetSdk": 36,
          "debugApk": True, "apkBuildConfigConstantValues": configs[0], "nativeSuites": len(xmls), "native": native,
          "webTests": 445, "lint": lint, "assetSha256": assets, "assetsEqualAcceptedVS84": True,
          "dexEqualAcceptedVS84": dex_same, "byteIdenticalAcceptedVS84": apk.read_bytes() == accepted_apk.read_bytes(),
          "forbiddenEntries": forbidden, "secretPatternFindings": findings,
          "secretScanScope": "All uncompressed ZIP entries; private-key/JWT/GitHub/AWS/Bearer literals and sensitive file names. Heuristic, not a proof against arbitrary obfuscation.",
          "debugSignerCertificateSha256": re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", signature).group(1)}
(out / "artifact-audit.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
