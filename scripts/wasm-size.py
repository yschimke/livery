#!/usr/bin/env python3
"""Report Wasm sections and named function body bytes (before cross-module optimization)."""
import collections
import pathlib
import sys

class Reader:
    def __init__(self, data): self.data, self.pos = data, 0
    def byte(self):
        b = self.data[self.pos]; self.pos += 1; return b
    def uint(self):
        value = shift = 0
        while True:
            b = self.byte(); value |= (b & 127) << shift
            if b < 128: return value
            shift += 7
    def take(self, n):
        b = self.data[self.pos:self.pos+n]; self.pos += n; return b
    def text(self): return self.take(self.uint()).decode('utf8')
    def valtype(self):
        if self.byte() in (0x63, 0x64): self.uint()
    def limits(self):
        flags = self.uint(); self.uint()
        if flags & 1: self.uint()

for path in sys.argv[1:]:
    data = pathlib.Path(path).read_bytes()
    assert data[:8] == b'\0asm\x01\0\0\0'
    r = Reader(data[8:]); names = {}; bodies = []; imported = 0; sections = collections.Counter()
    while r.pos < len(r.data):
        kind = r.byte(); size = r.uint(); s = Reader(r.take(size))
        label = str(kind)
        if kind == 0:
            label = s.text()
            if label == 'name':
                while s.pos < len(s.data):
                    sub = s.byte(); subreader = Reader(s.take(s.uint()))
                    if sub == 1:
                        for _ in range(subreader.uint()):
                            index = subreader.uint(); names[index] = subreader.text()
        elif kind == 2:
            for _ in range(s.uint()):
                s.text(); s.text(); desc = s.byte()
                if desc == 0: imported += 1; s.uint()
                elif desc == 1: s.valtype(); s.limits()
                elif desc == 2: s.limits()
                elif desc == 3: s.valtype(); s.byte()
                elif desc == 4: s.uint(); s.uint()
                else: raise ValueError(f'unsupported import kind {desc}')
        elif kind == 10:
            for _ in range(s.uint()):
                n = s.uint(); bodies.append(n); s.take(n)
        sections[label] += size
    groups = collections.Counter()
    for i, size in enumerate(bodies, imported):
        name = names.get(i, '')
        if name.startswith('io.ktor.'): group = 'Ktor'
        elif name.startswith('kotlinx.serialization.'): group = 'kotlinx-serialization'
        elif 'remotecompose' in name.lower() or 'rcplayer' in name.lower() or 'rc.player' in name.lower(): group = 'RC protocol/player'
        elif name.startswith('ee.schimke.livery.'): group = 'Livery (including generated serializers)'
        elif name.startswith('kotlin.'): group = 'Kotlin runtime/stdlib'
        else: group = 'other/unnamed'
        groups[group] += size
    print(f'{path}: {len(data):,} bytes')
    print('Section payloads:', dict(sections))
    for group, size in groups.most_common(): print(f'  {group}: {size:,} code-body bytes')
