#!/usr/bin/env python3
"""Checks res/values-<qualifier>/strings.xml against res/values/strings.xml (plan 20).
usage: check.py <qualifier> [...]   e.g. check.py de ru pt-rBR
Fails on: malformed XML, missing/extra keys, placeholder (%N$s, %%) mismatch, unescaped apostrophe/quote/ampersand."""
import os, re, sys
import xml.etree.ElementTree as ET
RES = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'res')
def load(path):
    out = {}
    for el in ET.parse(path).getroot().iter('string'):
        out[el.get('name')] = ''.join(el.itertext())
    return out
def raw_ok(path):
    bad = []
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(path, encoding='utf-8').read(), re.S):
        t = m.group(2)
        if re.search(r"(?<!\\)'", t): bad.append((m.group(1), 'unescaped apostrophe'))
        if re.search(r'(?<!\\)"', t): bad.append((m.group(1), 'unescaped quote'))
    return bad
def ph(t): return sorted(re.findall(r'%(?:\d+\$)?[sdf]|%%', t))
rc = 0
base = load(os.path.join(RES, 'values', 'strings.xml'))
for q in sys.argv[1:]:
    p = os.path.join(RES, 'values-' + q, 'strings.xml')
    try: tr = load(p)
    except Exception as e: print(q, 'XML ERROR', e); rc = 1; continue
    miss = [k for k in base if k not in tr]; extra = [k for k in tr if k not in base]
    badph = [k for k in base if k in tr and ph(base[k]) != ph(tr[k])]
    esc = raw_ok(p)
    print(q, 'strings', len(tr), '/', len(base), 'missing', len(miss), 'extra', len(extra), 'placeholder-mismatch', len(badph), 'escape', len(esc))
    for k in miss[:5]: print('  missing', k)
    for k in badph[:8]: print('  ph', k, base[k][:50], '=>', tr[k][:50])
    for k, why in esc[:5]: print('  esc', k, why)
    if miss or extra or badph or esc: rc = 1
sys.exit(rc)
