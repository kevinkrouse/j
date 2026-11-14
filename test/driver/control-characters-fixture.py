#!/usr/bin/env python3
"""Create a fresh control-character display drive fixture; refuses an existing destination.

python3 test/driver/control-characters-fixture.py /tmp/j-ctrl-light --theme Dayfox
bb drive /tmp/j-ctrl-light/control-characters.txt /tmp/j-ctrl-light/controls.py \
  --home /tmp/j-ctrl-light/home --out /tmp/j-ctrl-light/shots

python3 test/driver/control-characters-fixture.py /tmp/j-ctrl-dark --theme Nightfox
bb drive /tmp/j-ctrl-dark/control-characters.txt /tmp/j-ctrl-dark/controls.py \
  --home /tmp/j-ctrl-dark/home --out /tmp/j-ctrl-dark/shots

The fixture is UTF-16LE with a BOM: a UTF-8 file holding a NUL is detected
as Binary mode, but the BOM makes Utilities.getFileType treat it as text and
SystemBuffer.load keeps the control code units. Controls sit at the start,
middle and end of labeled lines; the last lines carry the ordinary
control-picture glyphs (U+2400..) as printable text for comparison. No
drive step types a control or embeds one in a command; the printable labels
are the mouse targets.
"""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
parser.add_argument("--theme", default="Dayfox",
                   help="theme name for the seeded home (light or dark)")
args = parser.parse_args()
root = args.directory.absolute()
root.mkdir()  # An existing fixture may contain work; never replace it.

# Raw control code units, by name.
CTRL = {
    "NUL": "\x00",
    "BEL": "\x07",
    "BS":  "\x08",
    "ESC": "\x1b",
    "DEL": "\x7f",
}

# Labeled lines: each printable label names the control(s) around it, so a
# screenshot can be read without typing anything. Controls go at the start,
# middle and end of lines.
lines = [
    "controls fixture",
    "start ESC: " + CTRL["ESC"] + " tail",
    "middle BEL: a" + CTRL["BEL"] + "b",
    "end BS: text" + CTRL["BS"],
    "nul NUL: x" + CTRL["NUL"] + "y",
    "del DEL: q" + CTRL["DEL"] + "r",
    "run ESC BEL DEL: " + CTRL["ESC"] + CTRL["BEL"] + CTRL["DEL"] + " end",
    "in string: s = \"tab\\t esc" + CTRL["ESC"] + "nul" + CTRL["NUL"] + "\"",
    "adjacent: " + CTRL["ESC"] + CTRL["ESC"] + CTRL["ESC"] + " done",
    # Starts with a control so a `click line` (offset 0) parks the block
    # caret on it, to picture the inverted picture.
    CTRL["ESC"] + "caret here",
    # Ordinary control-picture glyphs: printable, not controls. Compare
    # their rendering with the raw controls above.
    "pictures: \u241b \u2407 \u2400 \u2421 printable",
    "end of fixture",
]
text = "\n".join(lines) + "\n"

# UTF-16LE with a BOM so the NUL survives the file-type sniff.
fixture = root / "controls.py"
fixture.write_bytes(b"\xff\xfe" + text.encode("utf-16-le"))

# A scratch home with the theme and a steady caret.
prefs = root / "home/.config/j/prefs"
prefs.parent.mkdir(parents=True)
prefs.write_text(f"theme={args.theme}\nblinkCaret=false\nshowSidebar=false\n")

# The drive script: the fixture opens as the file argument. Screenshot the
# whole fixture, then click a printable label to park the caret beside a
# control and picture the block caret's inverted picture. The printable
# labels are the mouse targets; no control is typed and no command runs.
script = f"""# Control-character display drive ({args.theme}).
shot open
click line caret here
shot caret-on-control
click line pictures
shot caret-on-picture
"""
(root / "control-characters.txt").write_text(script)
print(root / "control-characters.txt")
