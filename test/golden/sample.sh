#!/bin/sh
# A comment.
count=0

greet() {
    name="$1"
    if [ -n "$name" ]; then
        echo "Hello, $name"
    else
        echo 'nobody'
    fi
}

for f in *.txt; do
    greet "$f"
done
