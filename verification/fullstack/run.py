"""VS-78 vertical gate: production Kotlin coordinator -> typed browser seam -> real TLS Remote.
Run with backend/.venv Python; mutable build/browser/Remote artifacts stay in --temp.
"""
import argparse, asyncio, datetime, hashlib, ipaddress, json, os, pathlib, secrets, shutil, socket, subprocess, sys, threading, time
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

def main():
    android_root = pathlib.Path(__file__).resolve().parents[2]
    studio_root = android_root.parent / 'VoxariumStudio'
    p=argparse.ArgumentParser(); p.add_argument('--studio',default=str(studio_root)); p.add_argument('--android',default=str(android_root)); p.add_argument('--temp',default=str(studio_root / '.voxarium_studio/temp/TASK-78/fullstack')); p.add_argument('--tests',action='append'); p.add_argument('--output',required=True); a=p.parse_args()
    root=pathlib.Path(a.temp).resolve(); root.mkdir(parents=True,exist_ok=True)
    run=root/('run-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')); run.mkdir()
    android=pathlib.Path(a.android); studio=pathlib.Path(a.studio)
    gate_files=set()
    for parent in (android/'app/src/main', android/'app/src/test'):
        gate_files.update(p for p in parent.rglob('*') if p.is_file())
    web=android/'editor-web'
    for parent in (web/'src',web/'tests',web/'scripts'):
        gate_files.update(p for p in parent.rglob('*') if p.is_file())
    gate_files.update(p for p in web.iterdir() if p.is_file() and (p.name in ('package.json','package-lock.json','vite.config.ts') or 'config' in p.name and p.suffix=='.json'))
    gate_files.add(android/'app/build.gradle.kts')
    gate_digest=hashlib.sha256()
    for f in sorted(gate_files,key=lambda p:p.relative_to(android).as_posix()):
        gate_digest.update(f.relative_to(android).as_posix().encode());gate_digest.update(b'\0');gate_digest.update(f.read_bytes());gate_digest.update(b'\0')
    source_files=set(gate_files)
    for parent in (studio/'frontend/src/lib/collab',studio/'frontend/src/lib/editor',studio/'frontend/src/lib/md',studio/'frontend/src/session',studio/'remote/voxarium_studio_remote'):
        source_files.update(p for p in parent.rglob('*') if p.is_file() and '__pycache__' not in str(p))
    source_files.add(studio/'frontend/package-lock.json')
    harness=pathlib.Path(__file__).parent
    source_files.update(p for p in harness.rglob('*') if p.is_file() and 'evidence' not in p.relative_to(harness).parts and p.suffix in ('.py','.mjs','.ts','.kt','.html','.gradle','.ps1','.txt'))
    manifest={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(source_files)}
    (run/'sources.json').write_text(json.dumps({'androidGateFingerprint':gate_digest.hexdigest(),'manifest':manifest},indent=2))
    sys.path.insert(0,str(pathlib.Path(a.studio)/'remote'))
    import uvicorn
    from voxarium_studio_remote.app import create_app
    from voxarium_studio_remote.store import Store
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'VS78 disposable localhost')])
    now=datetime.datetime.now(datetime.timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]),critical=False).sign(key,hashes.SHA256()))
    (run/'cert.pem').write_bytes(cert.public_bytes(serialization.Encoding.PEM)); (run/'key.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    store=Store(run/'remote.db'); tokens={n:'vxs_'+secrets.token_urlsafe(32) for n in ('alice','bob','revoked')}
    for n,t in tokens.items(): store.add_user(n,hashlib.sha256(t.encode()).hexdigest())
    app=create_app(store=store)
    class Probe:
        async def __call__(self,scope,receive,send):
            # No alternate implementation of wire/store/room. Only test fault injection and audit.
            async def recv():
                msg=await receive()
                if msg['type']=='websocket.receive' and 'text' in msg:
                    f=json.loads(msg['text']); audit={'time':time.monotonic(),'direction':'in','frame':f}
                    with (run/'wire.jsonl').open('a') as out: out.write(json.dumps(audit)+'\n')
                return msg
            async def output(msg):
                msg=dict(msg)
                if msg['type']=='websocket.send' and 'text' in msg:
                    f=json.loads(msg['text'])
                    if f.get('t')=='ack' and (run/'hold-ack').exists():
                        (run/'ack-held').write_text('held')
                        deadline=time.monotonic()+15
                        while (run/'hold-ack').exists() and time.monotonic()<deadline: await asyncio.sleep(.02)
                    with (run/'wire.jsonl').open('a') as out: out.write(json.dumps({'time':time.monotonic(),'direction':'out','frame':f})+'\n')
                await send(msg)
            await app(scope,recv,output)
    sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    config={'url':f'https://localhost:{port}','cert':str(run/'cert.pem'),'run':str(run),**tokens}
    (run/'config.json').write_text(json.dumps(config));env=os.environ.copy()
    env.update(VS78_NATIVE_TEMP=str(root),VS78_NATIVE_SOURCE=str(pathlib.Path(__file__).parent.resolve()),VS78_NATIVE_CONFIG=str(run/'config.json'),PYTHONDONTWRITEBYTECODE='1',TEMP=str(root),TMP=str(root))
    env['STUDIO_ROOT']=a.studio;env['ANDROID_ROOT']=a.android
    # Vite otherwise exits immediately on stdin EOF, bypassing asynchronous evidence/cleanup.
    env['CI']='true'
    env['GRADLE_USER_HOME']=str(root/'gradle-home');env['GRADLE_RO_DEP_CACHE']=str(pathlib.Path.home()/'.gradle/caches')
    (root/'jvm').mkdir(exist_ok=True)
    # Existing dependencies are read-only inputs; copy Robolectric jars so no writes hit ~/.m2.
    src=pathlib.Path.home()/'.m2/repository/org/robolectric'; dst=root/'m2/repository/org/robolectric'
    if src.exists() and not dst.exists(): shutil.copytree(src,dst)
    server=uvicorn.Server(uvicorn.Config(Probe(),host='127.0.0.1',port=port,ssl_certfile=str(run/'cert.pem'),ssl_keyfile=str(run/'key.pem'),log_level='warning',timeout_graceful_shutdown=2,ws_max_size=32*1024*1024))
    thread=threading.Thread(target=server.run,kwargs={'sockets':[sock]},daemon=True)
    cmd=[str(pathlib.Path.home()/'.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle.bat'), '--no-daemon','--console=plain','--project-cache-dir',str(root/'project-cache'),'-I',str(pathlib.Path(__file__).parent/'acceptance.init.gradle'),'-Pkotlin.compiler.execution.strategy=in-process','-Pkotlin.project.persistent.dir='+str(root/'kotlin'),'-PeditorBuildTemp='+str(root/'editor'),':app:testDebugUnitTest','-x',':app:generateEditorAssets']
    for test in a.tests or ['com.voxarium.remote.acceptance.FullstackCoordinatorTest']: cmd += ['--tests',test]
    thread.start()
    browser=None
    try:
        deadline=time.monotonic()+15
        while not server.started and thread.is_alive() and time.monotonic()<deadline: time.sleep(.05)
        if not server.started: raise RuntimeError('Remote did not start')
        with (run/'browser.log').open('w') as blog:
            browser=subprocess.Popen(['node',str(pathlib.Path(__file__).parent/'browser.mjs'),str(run/'config.json')],cwd=root,env=env,stdin=subprocess.PIPE,stdout=blog,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+120
            while not (run/'browser-service.json').exists() and browser.poll() is None and time.monotonic()<deadline: time.sleep(.1)
            if not (run/'browser-service.json').exists(): raise RuntimeError('Browser did not start; see browser.log')
        (run/'command.json').write_text(json.dumps(cmd,indent=2))
        print('Real Remote TLS ready, disposable run:',run,flush=True)
        with (run/'gradle.log').open('w') as log: result=subprocess.run(cmd,cwd=a.android,env=env,stdout=log,stderr=subprocess.STDOUT)
        print('Gradle exit:',result.returncode,'log:',run/'gradle.log',flush=True)
        return result.returncode
    finally:
        try:
            if browser is not None and browser.poll() is None:
                browser.communicate(input=b'stop\n',timeout=30)
        except subprocess.TimeoutExpired:
            subprocess.run(['taskkill','/PID',str(browser.pid),'/T','/F'],capture_output=True)
        server.should_exit=True;thread.join(timeout=15)
        if thread.is_alive(): server.force_exit=True;thread.join(timeout=3)
        sock.close();store.close();(run/'cleanup.json').write_text(json.dumps({'threadStopped':not thread.is_alive(),'port':port,'browserExit':browser.poll() if browser is not None else None}))
        print('Remote stopped:',not thread.is_alive(),flush=True)
        changed=[p for p,digest in manifest.items() if hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()!=digest]
        source_result=json.loads((run/'sources.json').read_text()); source_result.update(unchanged=not changed,changed=changed)
        (run/'sources.json').write_text(json.dumps(source_result,indent=2))
        evidence=pathlib.Path(a.output).resolve(); evidence.mkdir(parents=True,exist_ok=True)
        # Never export temporary credentials, certificate private keys, database or document storage.
        for name in ('sources.json','browser.log','recovery.json','gradle.log','command.json','fullstack-results.json','native-events.json','native-sends.json','wire.jsonl','browser-wire.json','browser-errors.json','browser-cleanup.json','browser-service.json','cleanup.json','browser.png'):
            if (run/name).exists(): shutil.copyfile(run/name,evidence/name)
        xml=root/'build/app/test-results/testDebugUnitTest/TEST-com.voxarium.remote.acceptance.FullstackCoordinatorTest.xml'
        if xml.exists(): shutil.copyfile(xml,evidence/'junit.xml')
        (evidence/'environment.json').write_text(json.dumps({'date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'python':sys.version,'pythonRemoteSource':str(studio/'remote'),'run':str(run),'androidGateFingerprint':gate_digest.hexdigest()},indent=2))
        if changed: raise RuntimeError('Read-only source changed during verification: '+str(changed))
        if browser is not None:
            if browser.returncode != 0 or not (run/'browser-cleanup.json').exists(): raise RuntimeError('Browser shutdown/evidence incomplete')
            if json.loads((run/'browser-errors.json').read_text()): raise RuntimeError('Browser errors; see browser-errors.json')
            if not all(c['closed'] for c in json.loads((run/'browser-cleanup.json').read_text())): raise RuntimeError('Browser test listener remains open')
if __name__=='__main__': raise SystemExit(main())

