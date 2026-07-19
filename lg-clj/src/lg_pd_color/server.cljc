(ns lg-pd-color.server
  "lg-pd-color dispatch surface — clj port of `lg/lg_pd_color/server.py`
  (ADR-2606280030).

  The Python file is a FastAPI app exposing ten stateless request/response
  graphs (1 health + 9 single-node task graphs) over:
    GET  /ok            → {:ok true :graphs [...]}
    GET  /health        → health graph result
    POST /runs          → invoke graph by `assistant_id`
    POST /xrpc/{nsid}   → XRPC shim (NSID → graph), GET also supported

  This namespace ports the ROUTING + graph registry (GRAPHS / NSID-MAP) and the
  invoke/serialize logic as plain clj functions (`dispatch-run`, `dispatch-xrpc`,
  `health`, `ok`). The graphs + dispatch are the load-bearing port; wrapping
  them in a concrete httpkit server is left to the deploy layer. The Python
  FastAPI server (`lg/`) remains the deployed runtime and COEXISTS — this clj
  twin is additive (py_removed = 0).

  Faithful to server.py:
    • 10 graphs, identical names + topology (single execute node, no retry).
    • Result envelope: success → {:output result}, error → {:error msg}.
    • /xrpc unmapped NSID → 501 (Python raises HTTPException 501); a mapped NSID
      whose graph is missing → 404 (cannot happen here — all map entries exist).
  DEVIATION (noted): langgraph-clj has no RetryPolicy (Python adds none either);
  the native task handlers are an injectable boundary (see graphs.task)."
  (:require [langgraph.graph :as g]
            [lg-pd-color.graphs.health :as health]
            [lg-pd-color.graphs.task :as task]))

(def GRAPHS
  (into {"health" health/GRAPH}
        (map (fn [n] [n (task/build n)]) task/task-names)))

(def NSID-MAP
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

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn- invoke-graph
  "Invoke `graph` with {:input input}; return {:status :body} with the
  {:output result} / {:error msg} envelope (mirrors server.py /runs)."
  [graph input]
  (let [state (g/invoke graph {:input (or input {})})]
    (if-let [err (:error state)]
      {:status 500 :body {:error err}}
      {:status 200 :body {:output (:result state)}})))

(defn ok
  "GET /ok → {:ok true :graphs [...]} (parity with the smoke test expectation)."
  []
  {:status 200 :body {:ok true :graphs (vec (keys GRAPHS))}})

(defn health
  "GET /health → the health graph result, e.g. {:status \"ok\" :service ...}."
  []
  (let [state (g/invoke (get GRAPHS "health") {:input {}})]
    {:status 200 :body (or (:result state) {:status "ok"})}))

(defn dispatch-run
  "POST /runs body → {:status :body}. body keys: :assistant_id (default
  \"health\"), :input. Unknown assistant → 404."
  [body]
  (let [aid   (or (:assistant_id body) "health")
        graph (get GRAPHS aid)]
    (if (nil? graph)
      {:status 404 :body {:error (str "graph '" aid "' not found")}}
      (invoke-graph graph (:input body)))))

(defn dispatch-xrpc
  "POST|GET /xrpc/{nsid} → {:status :body}. NSID mapped to a graph; body/query
  is the graph input. Unmapped NSID → 501 (parity with server.py)."
  ([nsid] (dispatch-xrpc nsid {}))
  ([nsid body]
   (let [gname (get NSID-MAP nsid)]
     (cond
       (nil? gname) {:status 501 :body {:error (str "NSID not mapped: " (clip nsid 120))}}
       :else        (let [graph (get GRAPHS gname)]
                      (if (nil? graph)
                        {:status 404 :body {:error (str "graph '" gname "' not found")}}
                        (invoke-graph graph (or body {}))))))))
