#!/usr/bin/env bash
#
# Ask real nvim what a key sequence does, for settling questions the
# conformance corpus leaves open or gets wrong.
#
#   tools/vim-oracle.sh '<value>' <line> <col> '<keys>'
#
# Coordinates are zero based and the document is spelled the way the corpus
# spells it: lines joined by \n, no trailing newline. nvim is a developer tool
# here, not a build dependency -- nothing in bb build or bb test needs it.
#
#   $ tools/vim-oracle.sh $'alpha bravo' 0 0 'dw'
#   doc: bravo
#   cur: 0,0
# oracle.sh '<value>' <line> <col> '<keys>'
# Runs a key sequence in real nvim and prints the result the way CodeMirror's
# getValue() would: lines joined by \n, no trailing newline.
set -eu
value=$1; line=$2; col=$3; keys=$4
d=$(mktemp -d)
# A file ending in a newline is read as exactly those lines, which is what
# CodeMirror's value means: "\n\n" is three empty lines, not two.
printf "%s\n" "$value" > "$d/in"
nvim --headless -u NONE -i NONE -n "$d/in" \
  -c "call cursor($((line+1)), $((col+1)))" \
  -c "execute 'normal! ' . \"$keys\"" \
  -c "call writefile(getline(1,'\$'), '$d/out')" \
  -c "call writefile([line('.')-1 . ',' . (col('.')-1)], '$d/pos')" \
  -c 'qa!' >/dev/null 2>&1
# strip the trailing newline writefile adds, then escape for display
doc=$(cat "$d/out"; printf X); doc=${doc%$'\n'X}
printf 'doc: %q\n' "$doc"
printf 'cur: %s\n' "$(cat "$d/pos")"
rm -rf "$d"
