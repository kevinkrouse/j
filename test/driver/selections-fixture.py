#!/usr/bin/env python3
"""Create a fresh selections drive fixture; refuses an existing destination.

python3 test/driver/selections-fixture.py /tmp/j-selections-simple
bb drive /tmp/j-selections-simple/selections.txt /tmp/j-selections-simple/repo/a.txt \
  --home /tmp/j-selections-simple/home --out /tmp/j-selections-simple/shots
bb drive test/driver/selections-saved.txt /tmp/j-selections-simple/repo/a.txt \
  --home /tmp/j-selections-simple/home --out /tmp/j-selections-simple/saved-shots

Pass --vim to create a home with editMode=vim. No command in the drive runs a shell.
"""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
parser.add_argument("--vim", action="store_true")
args = parser.parse_args()
root = args.directory.absolute()
root.mkdir()  # An existing fixture may contain work; never replace it.
(root / ".j-project").mkdir()
env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1",
           GIT_CONFIG_COUNT="0", GIT_CONFIG_PARAMETERS="",
           GIT_AUTHOR_NAME="Driver", GIT_AUTHOR_EMAIL="driver@example.com",
           GIT_COMMITTER_NAME="Driver", GIT_COMMITTER_EMAIL="driver@example.com")

def git(repo, *args):
    subprocess.run(["git", "-C", str(repo), *args], env=env, check=True, capture_output=True)

def own(repo):
    # j's own git commands read the repository's config: its identity, and
    # its own hooks directory over any system core.hooksPath.
    git(repo, "config", "user.name", "Driver")
    git(repo, "config", "user.email", "driver@example.com")
    git(repo, "config", "core.hooksPath", ".git/hooks")

repo = root / "repo"
repo.mkdir()
git(repo, "init", "-q", "-b", "main")
own(repo)
base = "".join(f"line {n}\n" for n in range(1, 61))
(repo / "a.txt").write_text(base)
(repo / "b.txt").write_text("b\n")
(repo / "notes.txt").write_text("alpha beta gamma\nsecond note\n")
git(repo, "add", ".")
git(repo, "commit", "-qm", "Base")
(repo / "a.txt").write_text(base.replace("line 3\n", "line three\n").replace("line 50\n", "line fifty\n"))
(repo / "b.txt").write_text("b staged\n")
git(repo, "add", "b.txt")
(repo / "c.txt").write_text("new file\n")
other = root / "other"
other.mkdir()
git(other, "init", "-q", "-b", "main")
own(other)
(other / "d.txt").write_text("d\n")
git(other, "add", ".")
git(other, "commit", "-qm", "Other base")
(other / "d.txt").write_text("d changed\n")
prefs = root / "home/.config/j/prefs"
prefs.parent.mkdir(parents=True)
prefs.write_text("blinkCaret=false\nshowSidebar=true\neditMode=" + ("vim" if args.vim else "simple") + "\n")
mode = "vim" if args.vim else "simple"
script = []
# A line starting "vim: " or "simple: " is that mode's alone.
for line in Path(__file__).with_name("selections.txt").read_text().splitlines():
    if line.startswith(("vim: ", "simple: ")):
        if not line.startswith(mode + ": "):
            continue
        line = line.split(": ", 1)[1]
    script.append(line)
text = "\n".join(script) + "\n"
(root / "selections.txt").write_text(text.replace("@FIXTURE@", str(root)).replace("@INPUT@", "VimInputHandler" if args.vim else "simple"))
print(root / "selections.txt")
