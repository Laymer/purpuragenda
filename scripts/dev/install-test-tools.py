#!/usr/bin/env python3
"""Install the locked Linux x64 test utilities without changing system packages."""
import bz2,hashlib,json,pathlib,tarfile,urllib.request
root=pathlib.Path(__file__).resolve().parents[2]
lock=json.loads((root/'ops/toolchain.lock.json').read_text())
for name,item in lock.get('testTools',{}).items():
    dest=root/'.cache/test-tools'/name
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest()==item.get('binarySha256'):continue
    dest.parent.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(item['url'],timeout=120) as response:data=response.read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('Checksum mismatch: '+name)
    if item.get('archive')=='tar.gz':
        archive=dest.with_suffix('.tar.gz');archive.write_bytes(data)
        with tarfile.open(archive) as tar:
            member=next(m for m in tar.getmembers() if m.name==item['member'])
            dest.write_bytes(tar.extractfile(member).read())
    elif item.get('archive')=='bz2':dest.write_bytes(bz2.decompress(data))
    else:dest.write_bytes(data)
    dest.chmod(0o755)
    print('Installed checksum-verified '+name)
