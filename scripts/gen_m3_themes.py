# Run from app/src/main/res:  python3 ../../../../scripts/gen_m3_themes.py
"""Generate Material 3 twins ("M3." + name) of every app theme that descends from Base.V19."""
import glob, os, re
import xml.etree.ElementTree as ET

ROOT = 'Base.V19'
M3_PARENT = 'Theme.Material3.DayNight.NoActionBar'
files = [f for f in glob.glob('values*/styles*.xml') if not f.endswith('styles_m3.xml')]
defs = {}        # name -> list of (file, element)
for f in files:
    for st in ET.parse(f).getroot().iter('style'):
        defs.setdefault(st.get('name'), []).append((f, st))

def parents(name):
    out = set()
    for f, st in defs.get(name, []):
        p = st.get('parent')
        if p is None and '.' in name:
            p = name.rsplit('.', 1)[0]
        if p:
            out.add(p.replace('@style/', ''))
    return out

memo = {}
def descends(name, seen=()):
    if name == ROOT:
        return True
    if name in memo:
        return memo[name]
    if name in seen:
        return False
    r = any(descends(p, seen + (name,)) for p in parents(name))
    memo[name] = r
    return r

themed = {n for n in defs if descends(n)}
by_dir = {}
for n in sorted(themed):
    for f, st in defs[n]:
        by_dir.setdefault(os.path.dirname(f), []).append((n, st))

for d, styles in by_dir.items():
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- PipeVR: Material 3 versions of the app themes (generated; edit the originals',
             '     and re-run the generator). Picked by ThemeHelper when Material 3 is on. -->',
             '<resources>']
    for n, st in styles:
        p = st.get('parent')
        if p is None and '.' in n:
            p = n.rsplit('.', 1)[0]
        p = (p or '').replace('@style/', '')
        if n == ROOT:
            newp = M3_PARENT
        elif p in themed:
            newp = 'M3.' + p
        else:
            newp = p
        lines.append(f'    <style name="M3.{n}" parent="{newp}">')
        if n == ROOT:
            # Material 3 dialogs (rounded, M3 buttons) for every AlertDialog in the app
            lines.append('        <item name="alertDialogTheme">@style/PipeVR.M3.AlertDialog</item>')
            lines.append('        <item name="materialAlertDialogTheme">@style/ThemeOverlay.Material3.MaterialAlertDialog</item>')
            lines.append('        <item name="preferenceTheme">@style/PipeVR.M3.PreferenceTheme</item>')
        for it in st.findall('item'):
            txt = (it.text or '').strip()
            lines.append(f'        <item name="{it.get("name")}">{txt}</item>')
        lines.append('    </style>')
    lines.append('</resources>')
    open(os.path.join(d, 'styles_m3.xml'), 'w').write('\n'.join(lines) + '\n')
    print(d, len(styles), 'styles')
