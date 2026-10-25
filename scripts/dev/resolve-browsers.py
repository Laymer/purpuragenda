#!/usr/bin/env python3
"""Resolve vendor stable Linux installers; pin the resulting version and SHA256."""
import gzip,json,pathlib,re,urllib.request
root=pathlib.Path(__file__).resolve().parents[2]
vendors=[('chrome','https://dl.google.com/linux/chrome/deb/','google-chrome-stable','opt/google/chrome/chrome'),('msedge','https://packages.microsoft.com/repos/edge/','microsoft-edge-stable','opt/microsoft/msedge/msedge')]
lock={'schemaVersion':1,'platform':'linux-amd64','installers':{}}
for name,base,package,executable in vendors:
    with urllib.request.urlopen(base+'dists/stable/main/binary-amd64/Packages.gz',timeout=60) as response:data=gzip.decompress(response.read()).decode()
    choices=[]
    for stanza in data.split('\n\n'):
        fields=dict(line.split(': ',1) for line in stanza.splitlines() if ': ' in line and not line.startswith(' '))
        if fields.get('Package')==package and fields.get('Architecture')=='amd64':choices.append(fields)
    item=max(choices,key=lambda d:tuple(int(x) for x in re.findall(r'\d+',d['Version'])))
    lock['installers'][name]={'version':item['Version'],'url':base+item['Filename'].removeprefix('./'),'sha256':item['SHA256'],'executable':executable}
(root/'ops/browser-installers.lock.json').write_text(json.dumps(lock,indent=2)+'\n')
print('Resolved vendor installer versions and checksums; review ops/browser-installers.lock.json')
