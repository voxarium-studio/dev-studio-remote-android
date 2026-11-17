"""Disposable REAL Remote; no Studio imports, no fake authenticator/hub/store."""
import asyncio
import json
import socket
import sys
from pathlib import Path

import uvicorn
from voxarium_studio_remote.app import create_app
from voxarium_studio_remote.auth import hash_token, new_token
from voxarium_studio_remote.store import Store

data = Path(sys.argv[1])
data.mkdir(parents=True, exist_ok=True)
store = Store(data / 'remote.db')
tokens = {}
for name in ['android', 'studio', 'observer']:
    token = new_token()
    store.add_user(name, hash_token(token))
    tokens[name] = token
sock = socket.socket()
sock.bind(('127.0.0.1', 0))
print('HARNESS:' + json.dumps({'port': sock.getsockname()[1], 'tokens': tokens}), flush=True)
server = uvicorn.Server(uvicorn.Config(create_app(store=store), host='127.0.0.1', log_level='warning', ws_max_size=32 * 1024 * 1024))
try:
    asyncio.run(server.serve(sockets=[sock]))
finally:
    sock.close()
    store.close()
