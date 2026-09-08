#!/usr/bin/env nbb
;; run_tests.cljs — the same suite on BOTH runtimes.
;;
;; These namespaces are `.cljc`, but until this file existed the only runtime
;; that had ever executed them was `bb`, declared in a `bb.edn` — the script
;; host this workspace retired (ADR-2607173000). A `.cljc` that only ever runs
;; on the JVM half is portable by claim, not by observation, and this one was
;; not portable at all: `graphs/task.cljc` caught `Exception` and called
;; `.getMessage`, so on nbb the namespace could not even be read
;; ("Unable to resolve symbol: Exception"). Nothing reported that, because
;; nothing ran it.
;;
;; nbb is first (it is this workspace's script host); the JVM follows as the
;; compatibility oracle. The green marker prints only when BOTH are green —
;; a runner that printed it after one would put the defect above back.
;;
;;   nbb run_tests.cljs
;;
;; Both halves read the same two files: nbb from nbb.edn, the JVM from deps.edn.
;; `contract-test` asserts those two pin the same langgraph.
(ns run-tests
  (:require ["node:child_process" :as cp]
            [kotoba.lang.text :as str]
            [clojure.test :as t]
            [lg-pd-color.contract-test]
            [lg-pd-color.smoke-test]))

(def green-marker
  "maturity-loop's `:green-marker`. Printed only when both runtimes pass."
  "lg-pd-color: both runtimes green (nbb + JVM)")

(def suites
  "Named in BOTH the require above and the run-tests call below: requiring only
  registers the vars. A runner that requires two namespaces and runs one prints
  the same `Ran N tests` shape as one that runs both."
  '[lg-pd-color.smoke-test lg-pd-color.contract-test])

(defn- jvm-suite []
  (println "── JVM (clojure -M:test)")
  (let [r (cp/spawnSync "clojure" #js ["-M:test"]
                        #js {:encoding "utf8" :shell false
                             :maxBuffer (* 16 1024 1024)})
        out (str (.-stdout r) (.-stderr r))]
    (println (str/trim out))
    (zero? (or (.-status r) 1))))

(def jvm-green? (jvm-suite))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (let [nbb-green? (t/successful? m)]
    (if (and nbb-green? jvm-green?)
      (println (str "\n" green-marker))
      (do (println (str "\nlg-pd-color: FAILED — nbb=" (if nbb-green? "green" "red")
                        " jvm=" (if jvm-green? "green" "red")))
          (js/process.exit 1)))))

(println "\n── nbb (cljs.test)")
(apply t/run-tests suites)
