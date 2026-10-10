;;; sample.lisp -- a tiny inventory

(defpackage :inventory
  (:use :common-lisp)
  (:export #:add-item #:total-value))

(in-package :inventory)

(defparameter *items* (make-hash-table :test 'equal)
  "Item name to (quantity . unit-price).")

(defstruct item
  (name "" :type string)
  (quantity 0 :type integer)
  (price 0.0 :type float))

(defun add-item (name quantity price)
  "Add QUANTITY of NAME at PRICE each."
  (setf (gethash name *items*)
        (make-item :name name :quantity quantity
                   :price price)))

(defun total-value ()
  (loop for item being the hash-values of *items*
        sum (* (item-quantity item) (item-price item))))

(add-item "widget" 12 2.5)
(format t "~&Total: ~,2F~%" (total-value))
