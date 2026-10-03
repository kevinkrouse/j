; A comment.
section .text
global _start

_start:
    mov eax, 0x1F
    mov ebx, 0
    int 0x80        ; exit
