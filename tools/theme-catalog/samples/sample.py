#!/usr/bin/env python3
"""Count the words in a file, most common first."""

import sys
from collections import Counter

MIN_LENGTH = 3


class WordCounter:
    def __init__(self, path):
        self.path = path
        self.counts = Counter()

    def count(self):
        with open(self.path, encoding="utf-8") as f:
            for line in f:
                for word in line.lower().split():
                    if len(word) >= MIN_LENGTH:  # skip short
                        self.counts[word] += 1
        return self.counts.most_common(10)


if __name__ == "__main__":
    for word, n in WordCounter(sys.argv[1]).count():
        print(f"{word:>12} {n}")
