(ns lg-pd-color.smoke-test
  "Smoke tests for the lg-pd-color clj port — clojure.test analogue of the
  Python `tests/test_smoke.py`, plus node-behaviour tests the original could not
  run offline (the native task handlers are injectable here, so the result /
  error envelope verifies under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
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
