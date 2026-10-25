#!/usr/bin/env python3
"""Evaluate verified backup snapshot time, not upload completion time."""
import datetime,json,pathlib,sys
if len(sys.argv)!=2:raise SystemExit('usage: backup-health.py VERIFIED_MANIFEST.json')
manifest=json.loads(pathlib.Path(sys.argv[1]).read_text())
snapshot=datetime.datetime.fromisoformat(manifest['snapshotNotBefore'].replace('Z','+00:00'))
age=(datetime.datetime.now(datetime.timezone.utc)-snapshot).total_seconds()
state='CRITICAL' if age>45*60 else 'WARNING' if age>30*60 else 'OK'
print(json.dumps({'state':state,'snapshotAgeSeconds':round(age)}))
sys.exit(2 if state=='CRITICAL' else 1 if state=='WARNING' else 0)
