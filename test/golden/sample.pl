#!/usr/bin/perl
use strict;

# A comment.
my $count = 0X1f;
my @list = (1, 2, 3);

sub greet {
    my ($name) = @_;
    if ($name =~ /^a/) {
        print "Hello, $name\n";
    }
    return 'done';
}

greet("world");
