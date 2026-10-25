#!/usr/bin/env python3
"""Optional isolated Ubuntu ABI libraries for this Kali checkout; never changes system libraries.
The supported CI/development host remains Ubuntu24.04 with Playwright install-deps.
"""
import hashlib,io,json,lzma,pathlib,subprocess,urllib.request
root=pathlib.Path(__file__).resolve().parents[2];target=root/'.cache/browser-libs';target.mkdir(parents=True,exist_ok=True)
needed={'libicu74','libxml2','libjpeg-turbo8','libgtk-4-1','libgraphene-1.0-0'}
found={}
for suite in ['noble','noble-updates']:
    for section in ['main','universe']:
        url=f'https://archive.ubuntu.com/ubuntu/dists/{suite}/{section}/binary-amd64/Packages.xz'
        with urllib.request.urlopen(url,timeout=120) as response:data=lzma.decompress(response.read()).decode()
        for stanza in data.split('\n\n'):
            name=stanza.split('\n',1)[0].removeprefix('Package: ')
            if name not in needed:continue
            fields=dict(line.split(': ',1) for line in stanza.splitlines() if ': ' in line and not line.startswith(' '))
            previous=found.get(name)
            if previous and subprocess.run(['dpkg','--compare-versions',fields['Version'],'le',previous['version']]).returncode==0:continue
            found[name]={'version':fields['Version'],'url':'https://archive.ubuntu.com/ubuntu/'+fields['Filename'],'sha256':fields['SHA256']}
if needed-found.keys():raise SystemExit('Missing upstream packages: '+str(needed-found.keys()))
for name,item in found.items():
    with urllib.request.urlopen(item['url'],timeout=120) as response:data=response.read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('Library checksum mismatch')
    archive=target/(name+'.deb');archive.write_bytes(data);subprocess.run(['dpkg-deb','-x',str(archive),str(target/'root')],check=True)
(root/'ops/optional-kali-browser-libs.lock.json').write_text(json.dumps(found,indent=2)+'\n')
print('Verified and extracted optional browser libraries into the private checkout cache.')
