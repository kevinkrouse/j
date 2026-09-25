#!/usr/bin/env bash
#
# Ask real nvim what a key sequence does, for settling questions the
# conformance corpus leaves open or gets wrong.
#
#   tools/vim-oracle.sh '<value>' <line> <col> '<keys>' ['<more keys>'...]
#
# Coordinates are zero based and the document is spelled the way the corpus
# spells it: lines joined by \n, no trailing newline. nvim is a developer tool
# here, not a build dependency -- nothing in bb build or bb test needs it.
#
#   $ tools/vim-oracle.sh $'alpha bravo' 0 0 'dw'
#   doc: bravo
#   cur: 0,0
#
# Each extra key argument runs as its own step, with an undo boundary before
# it, and prints its own result. That is the only way to ask about undo: vim
# treats everything in one :normal as a single undo block, so a single
# argument ending in u would report the whole sequence being taken back.
#
#   $ tools/vim-oracle.sh $'a\nb' 0 0 'yyp' 'u'
#   [1] doc: $'a\na\nb'
#   [1] cur: 1,0
#   [2] doc: $'a\nb'
#   [2] cur: 0,0
set -eu
value=$1; line=$2; col=$3; shift 3
d=$(mktemp -d)
# A file ending in a newline is read as exactly those lines, which is what
# CodeMirror's value means: "\n\n" is three empty lines, not two.
printf "%s\n" "$value" > "$d/in"

# One vimscript file rather than a pile of -c options: a step needs an undo
# boundary in front of it, and "let &undolevels = &undolevels" is the way to
# force one.
script="$d/run.vim"
: > "$script"
printf 'call cursor(%d, %d)\n' "$((line+1))" "$((col+1))" >> "$script"
step=0
for keys in "$@"; do
    step=$((step+1))
    # The keys end up inside a vimscript double-quoted string. Escape what
    # that string treats specially first -- otherwise di" ends the string
    # early and the run silently does something else -- then mark up <Esc>
    # and friends, which need the backslash vimscript uses for a key name.
    escaped=$(printf '%s' "$keys" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
                                  | sed -E 's/<([A-Za-z0-9-]+)>/\\<\1>/g')
    printf 'let &undolevels = &undolevels\n' >> "$script"
    printf 'execute "normal! %s"\n' "$escaped" >> "$script"
    printf "call writefile(getline(1,'\$'), '%s/out%d')\n" "$d" "$step" >> "$script"
    printf "call writefile([line('.')-1 . ',' . (col('.')-1)], '%s/pos%d')\n" \
        "$d" "$step" >> "$script"
done
printf 'qa!\n' >> "$script"
nvim --headless -u NONE -i NONE -n "$d/in" -S "$script" >/dev/null 2>&1

many=$([ "$step" -gt 1 ] && echo yes || echo no)
for i in $(seq 1 "$step"); do
    # strip the trailing newline writefile adds, then escape for display
    doc=$(cat "$d/out$i"; printf X); doc=${doc%$'\n'X}
    prefix=""
    [ "$many" = yes ] && prefix="[$i] "
    printf '%sdoc: %q\n' "$prefix" "$doc"
    printf '%scur: %s\n' "$prefix" "$(cat "$d/pos$i")"
done
rm -rf "$d"
