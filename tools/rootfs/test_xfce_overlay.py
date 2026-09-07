#!/usr/bin/env python3
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

if len(sys.argv) > 1:
    p = Path(sys.argv[1])
else:
    p = Path(
        "tools/rootfs/overlay/alpaquita/home/flux/.config/xfce4/"
        "xfconf/xfce-perchannel-xml/xfce4-panel.xml"
    )

root = ET.parse(p).getroot()

assert root.tag == "channel", f"expected root tag channel, got {root.tag}"
assert root.attrib.get("name") == "xfce4-panel", f"expected channel name xfce4-panel, got {root.attrib.get('name')}"

panels = root.find("./property[@name='panels']")
assert panels is not None, "missing panels property"

panel1 = panels.find("./property[@name='panel-1']")
assert panel1 is not None, "panel-1 must be nested under panels"

assert root.find("./property[@name='panel-1']") is None, \
    "panel-1 must not be a top-level channel property"

plugins = root.find("./property[@name='plugins']")
assert plugins is not None, "missing plugins property"

plugin_ids = panel1.find("./property[@name='plugin-ids']")
assert plugin_ids is not None, "missing plugin-ids in panel-1"
assert len(plugin_ids.findall("value")) >= 5, "expected >= 5 plugin ids"

names = {
    p.attrib["name"]: p.attrib.get("value")
    for p in plugins.findall("property")
}

assert names.get("plugin-1") == "applicationsmenu", f"unexpected plugin-1: {names.get('plugin-1')}"
assert names.get("plugin-2") == "tasklist", f"unexpected plugin-2: {names.get('plugin-2')}"
assert names.get("plugin-3") == "separator", f"unexpected plugin-3: {names.get('plugin-3')}"
assert names.get("plugin-4") == "systray", f"unexpected plugin-4: {names.get('plugin-4')}"
assert names.get("plugin-5") == "clock", f"unexpected plugin-5: {names.get('plugin-5')}"

print("XFCE overlay panel contract: PASS")
