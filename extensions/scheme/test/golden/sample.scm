;; A comment.
(define (greet name)
  (let ((s (string-append "Hello, " name)))
    (if (string? s)
        (display s)
        #f)))

(define mask #xFF)
