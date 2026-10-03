# A comment.
CC = gcc
CFLAGS = -Wall -O2

all: demo

demo: demo.o util.o
	$(CC) $(CFLAGS) -o $@ $^

%.o: %.c
	$(CC) -c $< -o $@

.PHONY: clean
clean:
	rm -f *.o demo
