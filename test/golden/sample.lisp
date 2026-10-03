;;; A comment.
(defpackage :demo (:use :cl))

(defun greet (name &optional (n #x1F))
  "Docstring."
  (let ((s (format nil "Hello, ~A" name)))
    (when (> n 0)
      (print s))
    #| block
       comment |#
    s))

(defmacro twice (form)
  `(progn ,form ,form))
