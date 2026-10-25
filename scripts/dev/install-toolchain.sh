#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
python3 - <<'PY'
import hashlib,json,pathlib,tarfile,urllib.request,zipfile
root=pathlib.Path.cwd(); lock=json.loads((root/'ops/toolchain.lock.json').read_text())
base=root/'.toolchain';base.mkdir(exist_ok=True)
for name,item in lock['artifacts'].items():
    dest=base/name
    if (dest/'.installed').exists(): continue
    archive=base/(name+'.'+item['archive'])
    with urllib.request.urlopen(item['url'],timeout=180) as response: data=response.read()
    algorithm='sha256' if 'sha256' in item else 'sha512'
    if hashlib.new(algorithm,data).hexdigest()!=item[algorithm]: raise SystemExit('Checksum mismatch: '+name)
    archive.write_bytes(data); temporary=base/(name+'-extract');temporary.mkdir(exist_ok=True)
    if item['archive']=='zip':
        with zipfile.ZipFile(archive) as z:z.extractall(temporary)
    else:
        with tarfile.open(archive) as t:t.extractall(temporary,filter='data')
    children=list(temporary.iterdir())
    if len(children)!=1:raise SystemExit('Unexpected archive layout: '+name)
    children[0].rename(dest)
    if name=='maven': (dest/'bin/mvn').chmod(0o755)
    (dest/'.installed').write_text(item['version'])
    print('Verified and installed '+name+' '+item['version'])
PY
