;; A comment.
(define (greet name)
  (let ((s (string-append "Hello, " name)))
    (if (string? s)
        (display s)
        #f)))

(define mask #xFF)

#| a block
comment |#

(define msg "a
multiline string")

(display #\x) ; a line comment after a char literal
