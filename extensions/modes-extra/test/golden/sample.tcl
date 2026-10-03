# A comment.
set count 0X10

proc greet {name} {
    set s "Hello, $name"
    if {$name eq "a"} {
        puts $s
    } else {
        return 'none'
    }
}

greet world
