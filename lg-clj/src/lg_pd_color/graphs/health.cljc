(ns lg-pd-color.graphs.health
  "pd-color `health` graph — liveness ping.

  Faithful clj port of the `_make_health_graph()` factory in
  `lg/lg_pd_color/server.py` (ADR-2606280030).

  Topology: START → ping → END. The `ping` node returns
  {:result {:status \"ok\" :service \"lg-pd-color\"}}, matching the Python
  node which returns {\"result\": {\"status\": \"ok\", \"service\": \"lg-pd-color\"}}."
  (:require [langgraph.graph :as g]))

(defn node-ping [_state]
  {:result {:status "ok" :service "lg-pd-color"}})

(defn build
  "Compile the health StateGraph (START → ping → END)."
  []
  (-> (g/state-graph)
      (g/add-node :ping node-ping)
      (g/set-entry-point :ping)
      (g/set-finish-point :ping)
      (g/compile-graph)))

(def GRAPH (build))
