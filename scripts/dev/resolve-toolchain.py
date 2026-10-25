#!/usr/bin/env python3
"""Resolve approved upstream artifacts to a reviewable lock; never invent checksums."""
import hashlib, json, pathlib, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'purpuragenda-build'}), timeout=90) as response:
        return response.read()
def image(repo, tag):
    name = repo if '/' in repo else 'library/' + repo
    token = json.loads(fetch('https://auth.docker.io/token?service=registry.docker.io&scope=repository:' + name + ':pull'))['token']
    request = urllib.request.Request('https://registry-1.docker.io/v2/' + name + '/manifests/' + tag,
        headers={'Authorization':'Bearer '+token, 'Accept':'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'})
    with urllib.request.urlopen(request, timeout=90) as response:
        digest = response.headers.get('Docker-Content-Digest')
        data = response.read()
        return {'image':repo+':'+tag, 'digest': digest or 'sha256:'+hashlib.sha256(data).hexdigest()}
def main():
    release = json.loads(fetch('https://api.github.com/repos/adoptium/temurin25-binaries/releases/tags/jdk-25.0.5%2B8'))
    binary = next(a for a in release['assets'] if a['name']=='OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz')
    binary_hash = fetch(binary['browser_download_url']+'.sha256.txt').decode().split()[0]
    node_file='node-v24.22.1-linux-x64.tar.xz'
    node_hash=next(line.split()[0] for line in fetch('https://nodejs.org/dist/v24.22.1/SHASUMS256.txt').decode().splitlines() if line.endswith('  '+node_file))
    maven='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip'
    lock={'schemaVersion':1,'baseline':'2026-10-24','artifacts':{
      'jdk':{'version':'25.0.5+8','url':binary['browser_download_url'],'sha256':binary_hash,'archive':'tar.gz'},
      'node':{'version':'24.22.1','url':'https://nodejs.org/dist/v24.22.1/'+node_file,'sha256':node_hash,'archive':'tar.xz'},
      'maven':{'version':'3.9.16','url':maven,'sha512':fetch(maven+'.sha512').decode().strip(),'archive':'zip'}},
      'versions':{'springBoot':'4.1.2','angular':'22.2.3','angularBuild':'22.2.4','typescript':'6.0.3','rxjs':'7.8.2','junit':'6.1.3','cucumber':'8.0.4','playwright':'1.64.0','testcontainers':'2.0.5'},'images':{},'actions':{}}
    for key,repo,tag in [('postgres','postgres','18.6-bookworm'),('ubuntu','ubuntu','24.04'),('caddy','caddy','2.11.7-alpine'),('mailpit','axllent/mailpit','v1.32.0')]:
        lock['images'][key]=image(repo,tag)
    for repo,tag in [('actions/checkout','v5'),('actions/setup-java','v5'),('actions/setup-node','v5'),('actions/upload-artifact','v4'),('github/codeql-action','v3')]:
        ref=json.loads(fetch('https://api.github.com/repos/'+repo+'/git/ref/tags/'+tag))['object']
        if ref['type']=='tag': ref=json.loads(fetch(ref['url']))['object']
        lock['actions'][repo]={'tag':tag,'sha':ref['sha']}
    target=ROOT/'ops/toolchain.lock.json';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(lock,indent=2)+'\n')
    print('Resolved upstream artifacts, image digests and action SHAs to ops/toolchain.lock.json')
if __name__=='__main__':main()
