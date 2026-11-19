#!/usr/bin/env python3
"""Erzeugt app/src/main/java/de/philipdecker/player/GlyphData.kt aus Lucide (npm-Paket lucide-static).

    npm pack lucide-static && tar xzf lucide-static-*.tgz
    python3 scripts/icons.py package/icon-nodes.json > app/src/main/java/de/philipdecker/player/GlyphData.kt

Weitere Symbole: Namen unten in WANT ergänzen (siehe https://lucide.dev/icons).
"""
import json, re, sys

WANT = """phone message-circle message-square mail send at-sign video camera image images film clapperboard tv eighth-notes headphones mic radio audio-lines
play disc-3 gamepad-2 dice-5 puzzle globe compass map map-pin navigation car bus train-front tram-front plane bike ship sailboat fuel
cloud-sun sun moon cloud-rain umbrella thermometer wind snowflake calendar clock alarm-clock timer hourglass watch bell
settings sliders-horizontal wrench hammer search scan qr-code calculator file-text folder notebook-pen pen-line pencil palette brush
code terminal cpu shield lock key fingerprint-pattern eye glasses house building store landmark briefcase graduation-cap book-open library newspaper
wallet credit-card banknote euro bitcoin piggy-bank receipt chart-line trending-up chart-pie shopping-cart shopping-bag package truck gift tag ticket
coffee utensils pizza cake-slice wine beer apple carrot cooking-pot dumbbell activity heart-pulse heart pill stethoscope bed-double baby
leaf flower-2 tree-pine sprout mountain waves-horizontal dog cat bird fish paw-print user users face-slightly-smiling ghost star sparkles rocket satellite orbit earth atom
telescope zap battery-full wifi bluetooth smartphone laptop monitor printer download upload share-2 link rss archive trash recycle
sticky-note list-todo square-check clipboard languages brain lightbulb flame droplet feather anchor flag trophy medal crown gem shirt scissors
bookmark inbox hash infinity circle-dot hexagon triangle square circle diamond speaker cast""".split()


def f(x):
    return '%g' % float(x)


def conv(tag, a):
    if tag == 'path':
        return a['d']
    if tag in ('circle', 'ellipse'):
        cx, cy = float(a['cx']), float(a['cy'])
        rx = float(a.get('r', a.get('rx'))); ry = float(a.get('r', a.get('ry')))
        return f"M{f(cx-rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2*rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2*rx)} 0"
    if tag == 'rect':
        x, y, w, h = float(a.get('x', 0)), float(a.get('y', 0)), float(a['width']), float(a['height'])
        rx = float(a.get('rx', a.get('ry', 0))); ry = float(a.get('ry', rx))
        if rx <= 0:
            return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        return (f"M{f(x+rx)} {f(y)}h{f(w-2*rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h-2*ry)}"
                f"a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w-2*rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}"
                f"v{f(-(h-2*ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z")
    if tag == 'line':
        return f"M{f(a['x1'])} {f(a['y1'])}L{f(a['x2'])} {f(a['y2'])}"
    if tag in ('polyline', 'polygon'):
        p = re.split(r'[\s,]+', a['points'].strip())
        d = "M" + " L".join(f"{f(p[i])} {f(p[i+1])}" for i in range(0, len(p), 2))
        return d + ("Z" if tag == 'polygon' else "")
    raise ValueError(tag)


nodes = json.load(open(sys.argv[1]))
rows = []
for w in WANT:
    paths = ", ".join('"%s"' % conv(t, a) for t, a in nodes[w])
    rows.append('        "%s" to listOf(%s),' % (w, paths))
print('''package de.philipdecker.playerconnected

// Linien-Symbole aus Lucide (https://lucide.dev, ISC-Lizenz, siehe licenses/LUCIDE.txt), umgewandelt in Pfade (24×24, Strich 2).
// Erzeugt von scripts/icons.py – nicht von Hand bearbeiten.
object GlyphData {
    val all: List<Pair<String, List<String>>> = listOf(
%s
    )
}''' % "\n".join(rows))
