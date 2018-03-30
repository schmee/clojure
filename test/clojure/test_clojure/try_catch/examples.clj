(ns clojure.test-clojure.try-catch.examples)

(gen-class :name clojure.test_clojure.try_catch.examples.A1 
           :extends java.lang.Exception)

(gen-class :name clojure.test_clojure.try_catch.examples.A2 
           :extends clojure.test_clojure.try_catch.examples.A1)
