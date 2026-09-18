#!/usr/bin/env bash
#
# Run j under a virtual X server and photograph it, for checking things that
# only exist on screen -- the caret's shape, where it is, what the status bar
# says. Needs no real display, so it works over ssh and in CI.
#
#   tools/vim-screenshot.sh <out-dir> <file> [xdotool key spec]...
#
# Each key spec is passed to "xdotool key" and a frame is captured after it,
# named for its position in the sequence. Run it through nix-shell for the
# two tools it needs:
#
#   nix-shell -p xvfb xdotool --run \
#     'tools/vim-screenshot.sh /tmp/shots README.md l l j i'
#
# The editMode=vim preference is set in a throwaway home, so this never reads
# or writes your own configuration.
#
# Needs the same JDK the build used on PATH. nix-shell puts its own PATH in
# front, so export JAVA_HOME and PATH before calling it, or the system java
# will be found and j will not start.
set -eu

out=$1; shift
file=$1; shift

display=:99
home="$out/home"
mkdir -p "$out" "$home/.config/j"
# blinkCaret off, or a frame may land on the half of the blink with no caret.
printf 'editMode=vim\nblinkCaret=false\n' > "$home/.config/j/prefs"

export DISPLAY=$display
Xvfb $display -screen 0 900x500x24 >/dev/null 2>&1 &
xvfb=$!
trap 'kill $xvfb 2>/dev/null || true' EXIT
sleep 2

java -cp build/classes Main --home "$home" --no-session --no-restore "$file" \
     > "$out/j.log" 2>&1 &
j=$!
trap 'kill $j 2>/dev/null || true; kill $xvfb 2>/dev/null || true' EXIT
sleep 6

# A j that failed to start would otherwise be photographed as a black screen,
# and the frames would look like a rendering bug rather than a missing JDK.
if ! kill -0 $j 2>/dev/null; then
    echo "j did not start; $out/j.log says:" >&2
    sed 's/^/    /' "$out/j.log" >&2
    exit 1
fi

shot() {
    ffmpeg -loglevel quiet -y -f x11grab -video_size 900x500 -i $display \
           -frames:v 1 "$out/$1.png"
}

shot 00-start
n=0
for keys in "$@"; do
    xdotool key --clearmodifiers $keys
    sleep 0.5
    n=$((n + 1))
    shot "$(printf '%02d' $n)-$(printf '%s' "$keys" | tr -c 'A-Za-z0-9' '_')"
done

echo "wrote $out/*.png"
