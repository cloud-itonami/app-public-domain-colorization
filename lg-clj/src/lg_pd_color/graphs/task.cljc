(ns lg-pd-color.graphs.task
  "pd-color single-node `task` graphs — faithful clj port of the
  `_make_single_node_graph(handler, name)` factory in
  `lg/lg_pd_color/server.py` (ADR-2606280030).

  Topology (every task graph): START → execute → END.

  The Python factory closes over an opaque async handler imported from the
  external worker module `kotodama.zeebe_worker_main`
  (`task_pd_color_video_segment_shots`, `…_video_colorize_frames`, …). Those
  handlers are NATIVE side-effecting jobs (ffmpeg / video restoration / dubbing
  / packaging) that live outside this repo. Per the actor-swap pattern they are
  ported as an INJECTABLE boundary: the `execute` node resolves its handler from
  the dynamic registry `*handlers*` at invoke time, so the graph topology +
  result/error envelope verify offline while the real native worker (or a kotoba
  job seam) is bound at deploy.

  Result envelope (matches the Python node exactly):
    success → {:result <handler-return>}
    failure → {:error  <message clipped to 300 chars>}

  DEVIATION (noted): langgraph-clj has no RetryPolicy; the Python factory adds
  none either, so this is exact parity. The handlers themselves are not
  reimplemented (they are external/native); defaults raise a clear boundary
  error so an unconfigured deploy fails loud rather than silently no-op'ing."
  (:require [langgraph.graph :as g]))

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn boundary-handler
  "Default handler for task `name`: the native worker is not bound. Raises a
  clear boundary error (the actor-swap seam — inject a real fn via `*handlers*`)."
  [name]
  (fn [_kwargs]
    (throw (ex-info (str "native worker handler not configured: " name
                         " (inject via lg-pd-color.graphs.task/*handlers*)")
                    {:handler name}))))

(def task-names
  "The nine task graph names (camelCase, parity with server.py GRAPHS)."
  ["videoSegmentShots"
   "videoRestoreFrames"
   "videoColorizeFrames"
   "videoEnhanceQuality"
   "videoEncodePackage"
   "videoMuxLocalizedPackages"
   "audioExtractTimedText"
   "audioGenerateDubbedAudio"
   "localizationTranslateSubtitles"])

(def default-handlers
  "name → handler fn. Defaults are boundary stubs (raise until injected)."
  (into {} (map (fn [n] [n (boundary-handler n)]) task-names)))

(def ^:dynamic *handlers*
  "Injectable registry of native task handlers. Rebind in tests / deploy."
  default-handlers)

(defn make-node
  "Build the `execute` node for task `name`. Resolves the handler from
  `*handlers*` at invoke time and wraps it in the {:result}/{:error} envelope."
  [name]
  (fn [state]
    (let [handler (get *handlers* name)
          kwargs  (or (:input state) {})]
      (try
        {:result (handler kwargs)}
        (catch Exception e
          {:error (clip (.getMessage e) 300)})))))

(defn build
  "Compile a single-node task StateGraph for `name` (START → execute → END)."
  [name]
  (-> (g/state-graph)
      (g/add-node :execute (make-node name))
      (g/set-entry-point :execute)
      (g/set-finish-point :execute)
      (g/compile-graph)))
