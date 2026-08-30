(ns lg-pd-color.smoke-test
  "Smoke tests for the lg-pd-color clj port — clojure.test analogue of the
  Python `tests/test_smoke.py`, plus node-behaviour tests the original could not
  run offline (the native task handlers are injectable here, so the result /
  error envelope verifies under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-pd-color.server :as server]
            [lg-pd-color.graphs.health :as health]
            [lg-pd-color.graphs.task :as task]))

(def expected-graphs
  #{"health"
    "videoSegmentShots" "videoRestoreFrames" "videoColorizeFrames"
    "videoEnhanceQuality" "videoEncodePackage" "videoMuxLocalizedPackages"
    "audioExtractTimedText" "audioGenerateDubbedAudio"
    "localizationTranslateSubtitles"})

(def expected-nsid-map
  {"com.etzhayyim.apps.pdColor.health"                        "health"
   "com.etzhayyim.apps.pdColor.videoSegmentShots"             "videoSegmentShots"
   "com.etzhayyim.apps.pdColor.videoRestoreFrames"            "videoRestoreFrames"
   "com.etzhayyim.apps.pdColor.videoColorizeFrames"           "videoColorizeFrames"
   "com.etzhayyim.apps.pdColor.videoEnhanceQuality"           "videoEnhanceQuality"
   "com.etzhayyim.apps.pdColor.videoEncodePackage"            "videoEncodePackage"
   "com.etzhayyim.apps.pdColor.videoMuxLocalizedPackages"     "videoMuxLocalizedPackages"
   "com.etzhayyim.apps.pdColor.audioExtractTimedText"         "audioExtractTimedText"
   "com.etzhayyim.apps.pdColor.audioGenerateDubbedAudio"      "audioGenerateDubbedAudio"
   "com.etzhayyim.apps.pdColor.localizationTranslateSubtitles" "localizationTranslateSubtitles"})

;; ── server registry parity (mirrors test_smoke.py) ──────────────────────────

(deftest graphs-match-expected-set
  (is (= expected-graphs (set (keys server/GRAPHS)))))

(deftest nsid-map-completeness
  (is (= expected-nsid-map server/NSID-MAP)))

(deftest nsid-map-references-known-graphs
  (doseq [[nsid gname] server/NSID-MAP]
    (is (contains? server/GRAPHS gname) (str nsid " → " gname " not in GRAPHS"))))

(deftest all-graphs-invocable
  (doseq [[nm graph] server/GRAPHS]
    (is (some? graph) (str "GRAPHS[" nm "] nil"))))

;; ── dispatch surface (/ok, /health, /runs, /xrpc) ───────────────────────────

(deftest ok-endpoint-lists-graphs
  (let [r (server/ok)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= expected-graphs (set (get-in r [:body :graphs]))))))

(deftest health-endpoint
  (let [r (server/health)]
    (is (= 200 (:status r)))
    (is (= "ok" (get-in r [:body :status])))
    (is (= "lg-pd-color" (get-in r [:body :service])))))

(deftest unknown-assistant-404
  (is (= 404 (:status (server/dispatch-run {:assistant_id "nope" :input {}})))))

(deftest unknown-nsid-xrpc-501
  ;; server.py raises HTTPException 501 for an unmapped NSID (faithful parity).
  (is (= 501 (:status (server/dispatch-xrpc "com.etzhayyim.apps.pdColor.unknownMethod" {})))))

;; ── health graph end-to-end ─────────────────────────────────────────────────

(deftest health-graph-invokes
  (let [out (g/invoke health/GRAPH {:input {}})]
    (is (= {:status "ok" :service "lg-pd-color"} (:result out)))))

;; ── task graph topology + result/error envelope via injected handlers ────────

(deftest task-graph-happy-path-stubbed
  (binding [task/*handlers*
            (assoc task/default-handlers
                   "videoColorizeFrames"
                   (fn [kwargs] {:colorized true :echo kwargs}))]
    (let [r (server/dispatch-run {:assistant_id "videoColorizeFrames"
                                  :input {:jobId "j1"}})]
      (is (= 200 (:status r)))
      (is (= {:colorized true :echo {:jobId "j1"}} (get-in r [:body :output]))))))

(deftest task-graph-error-envelope
  (binding [task/*handlers*
            (assoc task/default-handlers
                   "videoRestoreFrames"
                   (fn [_] (throw (ex-info "ffmpeg boom" {}))))]
    (let [r (server/dispatch-run {:assistant_id "videoRestoreFrames" :input {}})]
      (is (= 500 (:status r)))
      (is (re-find #"ffmpeg boom" (get-in r [:body :error]))))))

(deftest task-default-handler-is-boundary
  (testing "unconfigured native handler fails loud (injectable seam)"
    (let [r (server/dispatch-run {:assistant_id "videoSegmentShots" :input {}})]
      (is (= 500 (:status r)))
      (is (re-find #"native worker handler not configured" (get-in r [:body :error]))))))

(deftest xrpc-dispatch-stubbed
  (binding [task/*handlers*
            (assoc task/default-handlers
                   "audioExtractTimedText"
                   (fn [_] {:vtt "WEBVTT"}))]
    (let [r (server/dispatch-xrpc "com.etzhayyim.apps.pdColor.audioExtractTimedText" {})]
      (is (= 200 (:status r)))
      (is (= {:vtt "WEBVTT"} (get-in r [:body :output]))))))

;; ── envelope + dispatch defaults ────────────────────────────────────────────
;;
;; Everything above this line was in the suite when it could only be run by
;; `bb`. What follows pins the parts of the envelope that the docstrings claim
;; and nothing read: the 300-char clip, the 120-char clip on a caller-supplied
;; NSID, the documented `assistant_id` default, the empty-map input, and the
;; loud boundary for ALL NINE task graphs rather than the one that happened to
;; be sampled.

(deftest error-message-is-clipped-to-300-chars
  (testing "the node docstring says `clipped to 300 chars`; an unbounded handler message otherwise lands verbatim in an HTTP body"
    (let [long-msg (apply str (repeat 500 "x"))]
      (binding [task/*handlers*
                (assoc task/default-handlers
                       "videoEncodePackage"
                       (fn [_] (throw (ex-info long-msg {}))))]
        (let [r (server/dispatch-run {:assistant_id "videoEncodePackage" :input {}})]
          (is (= 500 (:status r)))
          (is (= 300 (count (get-in r [:body :error])))))))))

(deftest a-thrown-value-without-a-message-still-yields-an-error-string
  (testing "an empty envelope reads as `the handler succeeded and returned nothing`"
    (binding [task/*handlers*
              (assoc task/default-handlers
                     "videoEnhanceQuality"
                     (fn [_] (throw (ex-info nil {}))))]
      (let [r (server/dispatch-run {:assistant_id "videoEnhanceQuality" :input {}})]
        (is (= 500 (:status r)))
        (is (seq (get-in r [:body :error])))))))

(deftest every-task-graph-fails-loud-when-its-handler-is-unbound
  (testing "a deploy that forgets to inject one handler must not report success for that one graph"
    (doseq [nm task/task-names]
      (let [r (server/dispatch-run {:assistant_id nm :input {}})]
        (is (= 500 (:status r)) (str nm " did not fail loud"))
        (is (re-find #"native worker handler not configured" (get-in r [:body :error]))
            (str nm " failed for some other reason"))
        (is (str/includes? (get-in r [:body :error]) nm)
            (str nm " does not name itself in its own boundary error"))))))

(deftest runs-without-an-assistant-id-is-health
  (testing "server.py's documented default; a client that omits the field gets liveness, not a 404"
    (let [r (server/dispatch-run {})]
      (is (= 200 (:status r)))
      (is (= {:status "ok" :service "lg-pd-color"} (get-in r [:body :output]))))))

(deftest a-missing-input-reaches-the-handler-as-an-empty-map
  (testing "not nil — a handler doing (:k kwargs) on nil is a different failure than on {}"
    (let [seen (atom :never-called)]
      (binding [task/*handlers*
                (assoc task/default-handlers
                       "videoColorizeFrames"
                       (fn [kwargs] (reset! seen kwargs) {:ok true}))]
        (let [r (server/dispatch-run {:assistant_id "videoColorizeFrames"})]
          (is (= 200 (:status r)))
          (is (= {} @seen)))))))

(deftest an-unmapped-nsid-is-not-echoed-back-unbounded
  (testing "the NSID is caller-supplied and lands in an error body; server.cljc clips it to 120"
    (let [r (server/dispatch-xrpc (apply str (repeat 400 "n")) {})]
      (is (= 501 (:status r)))
      (is (> 200 (count (get-in r [:body :error])))
          "the whole caller-supplied NSID came back in the error"))))

(deftest a-mapped-nsid-whose-graph-is-missing-is-404-not-501
  (testing "501 means `this server does not implement that method`; 404 means the registry is inconsistent — collapsing them hides a broken deploy"
    (with-redefs [server/GRAPHS (dissoc server/GRAPHS "health")]
      (is (= 404 (:status (server/dispatch-xrpc "com.etzhayyim.apps.pdColor.health" {})))))))
