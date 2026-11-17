"""Isolated real Remote + TLS, production code imported read-only. Always stopped in finally.
Run with backend/.venv Python; all mutable artifacts stay in --temp.
"""
import argparse, asyncio, datetime, hashlib, ipaddress, json, os, pathlib, secrets, shutil, socket, subprocess, sys, threading, time
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

def main():
    if os.name=='nt': asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    android_root = pathlib.Path(__file__).resolve().parents[2]
    studio_root = android_root.parent / 'VoxariumStudio'
    p=argparse.ArgumentParser(); p.add_argument('--studio',default=str(studio_root)); p.add_argument('--android',default=str(android_root)); p.add_argument('--temp',default=str(studio_root / '.voxarium_studio/temp/TASK-78/native')); p.add_argument('--tests',action='append'); p.add_argument('--output',required=True); a=p.parse_args()
    root=pathlib.Path(a.temp).resolve(); root.mkdir(parents=True,exist_ok=True)
    run=root/('run-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')); run.mkdir()
    android=pathlib.Path(a.android); studio=pathlib.Path(a.studio)
    inputs=set()
    for folder in (android/'app/src/main',android/'app/src/test',android/'editor-web/src',android/'editor-web/tests',studio/'remote/voxarium_studio_remote',pathlib.Path(__file__).parent/'src'):
        inputs.update(f for f in folder.rglob('*') if f.is_file() and '__pycache__' not in str(f))
    inputs.update([android/'app/build.gradle.kts',pathlib.Path(__file__).resolve(),pathlib.Path(__file__).parent/'acceptance.init.gradle'])
    manifest={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(inputs)}
    (run/'sources.json').write_text(json.dumps({'manifest':manifest},indent=2))
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
            scope=dict(scope); prefix=''
            for x in ('/future-me','/future-hello'):
                if scope.get('path','').startswith(x): prefix=x; scope['path']=scope['path'][len(x):]; scope['raw_path']=scope['path'].encode()
            if (run/'revoke').exists() and store.is_active(next(u.id for u in store.list_users() if u.name=='revoked')): store.revoke_user('revoked')
            async def recv():
                msg=await receive()
                if msg['type']=='websocket.receive' and 'text' in msg:
                    f=json.loads(msg['text']); audit={'direction':'in','t':f.get('t'),'id':f.get('id'),'path':f.get('path'),'base_rev':f.get('base_rev')}
                    with (run/'wire.jsonl').open('a') as out: out.write(json.dumps(audit)+'\n')
                return msg
            async def output(msg):
                msg=dict(msg)
                if msg['type']=='websocket.send' and 'text' in msg:
                    f=json.loads(msg['text'])
                    if prefix=='/future-hello' and f.get('t')=='hello': f['protocol']=999;msg['text']=json.dumps(f)
                    if f.get('t')=='ack' and (run/'hold-ack').exists():
                        (run/'ack-held').write_text('held')
                        deadline=time.monotonic()+15
                        while (run/'hold-ack').exists() and time.monotonic()<deadline: await asyncio.sleep(.02)
                    with (run/'wire.jsonl').open('a') as out: out.write(json.dumps({'direction':'out','t':f.get('t'),'rev':f.get('rev'),'deleted':f.get('deleted')})+'\n')
                if prefix=='/future-me' and msg['type']=='http.response.body':
                    f=json.loads(msg['body']); f['protocol']=999;msg['body']=json.dumps(f).encode()
                if prefix=='/future-me' and msg['type']=='http.response.start': msg['headers']=[(k,v) for k,v in msg['headers'] if k!=b'content-length']
                await send(msg)
            await app(scope,recv,output)
    sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    config={'url':f'https://localhost:{port}','cert':str(run/'cert.pem'),'run':str(run),**tokens}
    (run/'config.json').write_text(json.dumps(config));env=os.environ.copy()
    env.update(VS78_NATIVE_TEMP=str(root),VS78_NATIVE_SOURCE=str(pathlib.Path(__file__).parent.resolve()),VS78_NATIVE_CONFIG=str(run/'config.json'),PYTHONDONTWRITEBYTECODE='1',TEMP=str(root),TMP=str(root))
    env['GRADLE_USER_HOME']=str(root/'gradle-home');env['GRADLE_RO_DEP_CACHE']=str(pathlib.Path.home()/'.gradle/caches')
    (root/'jvm').mkdir(exist_ok=True); (root/'home').mkdir(exist_ok=True)
    # Existing dependencies are read-only inputs; copy Robolectric jars so no writes hit ~/.m2.
    src=pathlib.Path.home()/'.m2/repository/org/robolectric'; dst=root/'m2/repository/org/robolectric'
    if src.exists() and not dst.exists(): shutil.copytree(src,dst)
    server=uvicorn.Server(uvicorn.Config(Probe(),host='127.0.0.1',port=port,ssl_certfile=str(run/'cert.pem'),ssl_keyfile=str(run/'key.pem'),log_level='warning',timeout_graceful_shutdown=2,ws_max_size=32*1024*1024))
    thread=threading.Thread(target=server.run,kwargs={'sockets':[sock]},daemon=True)
    cmd=[str(pathlib.Path.home()/'.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle.bat'), '--no-daemon','--console=plain','--project-cache-dir',str(root/'project-cache'),'-I',str(pathlib.Path(__file__).parent/'acceptance.init.gradle'),'-Pkotlin.compiler.execution.strategy=in-process','-Pkotlin.project.persistent.dir='+str(root/'kotlin'),'-PeditorBuildTemp='+str(root/'editor'),':app:testDebugUnitTest','-x',':app:generateEditorAssets']
    for test in a.tests or []: cmd += ['--tests',test]
    thread.start()
    try:
        deadline=time.monotonic()+15
        while not server.started and thread.is_alive() and time.monotonic()<deadline: time.sleep(.05)
        if not server.started: raise RuntimeError('Remote did not start')
        (run/'command.json').write_text(json.dumps(cmd,indent=2))
        print('Real Remote TLS ready, disposable run:',run,flush=True)
        with (run/'gradle.log').open('w') as log: result=subprocess.run(cmd,cwd=a.android,env=env,stdout=log,stderr=subprocess.STDOUT)
        reports=root/'build/app/test-results/testDebugUnitTest'
        if reports.exists(): shutil.copytree(reports,run/'junit',ignore=shutil.ignore_patterns('binary'))
        print('Gradle exit:',result.returncode,'log:',run/'gradle.log',flush=True)
        return result.returncode
    finally:
        server.should_exit=True;thread.join(timeout=15)
        if thread.is_alive(): server.force_exit=True;thread.join(timeout=3)
        sock.close();store.close()
        with socket.socket() as probe: closed=probe.connect_ex(('127.0.0.1',port))!=0
        (run/'cleanup.json').write_text(json.dumps({'threadStopped':not thread.is_alive(),'port':port,'portClosed':closed}))
        changed=[p for p,digest in manifest.items() if hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()!=digest]
        (run/'sources.json').write_text(json.dumps({'manifest':manifest,'unchanged':not changed,'changed':changed},indent=2))
        evidence=pathlib.Path(a.output).resolve(); evidence.mkdir(parents=True,exist_ok=True)
        for name in ('sources.json','gradle.log','command.json','wire.jsonl','cleanup.json'):
            if (run/name).exists(): shutil.copyfile(run/name,evidence/name)
        if (run/'junit').exists(): shutil.copytree(run/'junit',evidence/'junit',dirs_exist_ok=True)
        (evidence/'environment.json').write_text(json.dumps({'date':datetime.datetime.now(datetime.timezone.utc).isoformat(),'python':sys.version,'run':str(run)},indent=2))
        print('Remote stopped:',not thread.is_alive(),'port closed:',closed,flush=True)
        if changed: raise RuntimeError('Read-only source changed during native verification: '+str(changed))
        if thread.is_alive() or not closed: raise RuntimeError('Test Remote cleanup failed')
if __name__=='__main__': raise SystemExit(main())

