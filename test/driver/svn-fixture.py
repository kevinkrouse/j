#!/usr/bin/env python3
"""Create a fresh svn drive fixture; refuses an existing destination.

python3 test/driver/svn-fixture.py /tmp/j-svn-simple
bb drive /tmp/j-svn-simple/svn.txt /tmp/j-svn-simple/svn/a.txt \
  --home /tmp/j-svn-simple/home --out /tmp/j-svn-simple/shots

Pass --vim to create a home with editMode=vim. No command in the drive runs a
shell. The project has an svn working copy, with a conflict in a.txt, beside a
Git repository; svn here reads the fixture's own config, and j's the home's.
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
config = root / "svn-config"
env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1",
           GIT_CONFIG_COUNT="0", GIT_CONFIG_PARAMETERS="",
           GIT_AUTHOR_NAME="Driver", GIT_AUTHOR_EMAIL="driver@example.com",
           GIT_COMMITTER_NAME="Driver", GIT_COMMITTER_EMAIL="driver@example.com")


def run(*command, cwd=None):
    subprocess.run(command, cwd=cwd, env=env, check=True, capture_output=True)


def svn(wc, *args):
    run("svn", "--non-interactive", "--config-dir", str(config), *args, cwd=wc)


def commit(wc, message):
    svn(wc, "commit", "-q", "--username", "Driver", "-m", message)
    svn(wc, "update", "-q")


repository = root / "svn-repository"
run("svnadmin", "create", str(repository))
url = repository.as_uri()
wc = root / "svn"
svn(root, "checkout", "-q", url, str(wc))
for name, text in [("a.txt", "one\nbase\nthree\n"), ("b.txt", "b\n"), ("c.txt", "c\n"),
                   ("d.txt", "d one\nd two\n"), ("e.txt", "e\n")]:
    (wc / name).write_text(text)
svn(wc, "add", "-q", "a.txt", "b.txt", "c.txt", "d.txt", "e.txt")
commit(wc, "Svn base")
(wc / "d.txt").write_text("d one\nd two changed\n")
commit(wc, "Change d")
# Theirs from another working copy, then ours here: a conflict on update.
other = root / "svn-other"
svn(root, "checkout", "-q", url, str(other))
(other / "a.txt").write_text("one\ntheirs\nthree\n")
commit(other, "Theirs")
(wc / "a.txt").write_text("one\nours\nthree\n")
subprocess.run(["svn", "--non-interactive", "--config-dir", str(config), "update", "-q"],
               cwd=wc, env=env, capture_output=True)
(wc / "b.txt").write_text("b changed\n")
(wc / "c.txt").write_text("c changed\n")
(wc / "e.txt").write_text("e changed\n")

git = root / "git"
git.mkdir()
run("git", "-C", str(git), "init", "-q", "-b", "main")
for key, value in [("user.name", "Driver"), ("user.email", "driver@example.com"),
                   ("core.hooksPath", ".git/hooks")]:
    run("git", "-C", str(git), "config", key, value)
(git / "g.txt").write_text("g\n")
run("git", "-C", str(git), "add", ".")
run("git", "-C", str(git), "commit", "-qm", "Git base")
(git / "g.txt").write_text("g changed\n")

prefs = root / "home/.config/j/prefs"
prefs.parent.mkdir(parents=True)
prefs.write_text("blinkCaret=false\nshowSidebar=true\neditMode=" + ("vim" if args.vim else "simple") + "\n")
mode = "vim" if args.vim else "simple"
script = []
# A line starting "vim: " or "simple: " is that mode's alone.
for line in Path(__file__).with_name("svn.txt").read_text().splitlines():
    if line.startswith(("vim: ", "simple: ")):
        if not line.startswith(mode + ": "):
            continue
        line = line.split(": ", 1)[1]
    script.append(line)
text = "\n".join(script) + "\n"
(root / "svn.txt").write_text(text.replace("@FIXTURE@", str(root)).replace("@INPUT@", "VimInputHandler" if args.vim else "simple"))
print(root / "svn.txt")
