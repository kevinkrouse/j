# A comment.
import os

MASK = 0XFF


class Greeter(object):
    """Docstring."""

    def greet(self, name, n=0x1f):
        s = 'Hello, %s' % name
        if n > 0:
            print(s)
        else:
            return None
        for i in range(n):
            pass


def main():
    Greeter().greet("world")
