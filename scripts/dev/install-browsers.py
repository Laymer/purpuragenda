#!/usr/bin/env python3
import hashlib,json,pathlib,subprocess,urllib.request
root=pathlib.Path(__file__).resolve().parents[2]
lock=json.loads((root/'ops/browser-installers.lock.json').read_text())
for name,item in lock['installers'].items():
    dest=root/'.cache/browser-installers'/name
    if (dest/item['executable']).exists():continue
    dest.mkdir(parents=True,exist_ok=True)
    with urllib.request.urlopen(item['url'],timeout=180) as response:data=response.read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('Browser checksum mismatch: '+name)
    archive=dest/'installer.deb';archive.write_bytes(data)
    subprocess.run(['dpkg-deb','-x',str(archive),str(dest)],check=True)
    print('Verified and extracted '+name+' '+item['version'])
