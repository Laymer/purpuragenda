#!/usr/bin/env python3
"""Preserve failure evidence while removing credentials before artifact collection."""
import pathlib,re,sys
roots=[pathlib.Path(x) for x in sys.argv[1:]] or [pathlib.Path('acceptance-tests/target'),pathlib.Path('artifacts')]
patterns=[(re.compile(r'((?:token|contactToken)=)[A-Za-z0-9_-]{20,}'),r'\1[REDACTED]'),
          (re.compile(r'(fill\(")[^"]*("\))'),r'\1[REDACTED]\2'),
          (re.compile(r'((?:Authorization|Cookie|Set-Cookie):)[^\r\n]+',re.I),r'\1 [REDACTED]')]
for root in roots:
    files=[root] if root.is_file() else root.rglob('*') if root.exists() else []
    for path in files:
        if not path.is_file() or path.suffix.lower() not in {'.json','.xml','.txt','.log','.html'}:continue
        try:source=path.read_text()
        except UnicodeError:continue
        value=source
        for pattern,replacement in patterns:value=pattern.sub(replacement,value)
        if source!=value:path.write_text(value)
