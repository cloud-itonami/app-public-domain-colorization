-- Readable copy of view_pd_color_process_event_log.
-- Canonical DDL: 30-graph/graph-schema/migrations/20260429161000_pd_color_process_mining_views.ts
--
-- Process-mining event log for public-domain colorization.
-- Output columns follow the common CSV shape used by PM4Py/Apromore:
-- case_id, activity, timestamp, resource, lifecycle, work_id, artifact_id, detail

WITH events AS (
  SELECT
    run_vertex_id AS case_id,
    activity,
    event_at AS timestamp,
    actor_id AS resource,
    lifecycle,
    work_id,
    vertex_id AS artifact_id,
    COALESCE(artifact_cid, status) AS detail
  FROM vertex_pd_color_process_event

  UNION ALL
  SELECT
    vertex_id AS case_id,
    '01 Candidate persisted' AS activity,
    created_at AS timestamp,
    actor_id AS resource,
    'complete' AS lifecycle,
    work_id,
    vertex_id AS artifact_id,
    status AS detail
  FROM vertex_pd_color_run
  WHERE vertex_id LIKE 'pdcolor:run:%'

  UNION ALL
  SELECT
    replace(vertex_id, 'pdcolor:rights:', '') AS case_id,
    '02 Rights approved' AS activity,
    reviewed_at AS timestamp,
    reviewer_did AS resource,
    'complete' AS lifecycle,
    work_id,
    vertex_id AS artifact_id,
    rights_classification AS detail
  FROM vertex_pd_color_rights_review
  WHERE rights_approved = true

  UNION ALL
  SELECT
    run_vertex_id AS case_id,
    '03 Derivatives ready' AS activity,
    created_at AS timestamp,
    actor_id AS resource,
    'complete' AS lifecycle,
    work_id,
    vertex_id AS artifact_id,
    status AS detail
  FROM vertex_pd_color_derivative_asset

  UNION ALL
  SELECT
    d.run_vertex_id AS case_id,
    '04 Localization ready: ' || l.lang AS activity,
    l.created_at AS timestamp,
    l.actor_id AS resource,
    'complete' AS lifecycle,
    l.work_id,
    l.vertex_id AS artifact_id,
    l.manifest_cid AS detail
  FROM vertex_pd_color_localization_asset l
  JOIN vertex_pd_color_derivative_asset d ON d.vertex_id = l.derivative_vertex_id

  UNION ALL
  SELECT
    d.run_vertex_id AS case_id,
    '05 Published' AS activity,
    p.published_at AS timestamp,
    p.actor_id AS resource,
    'complete' AS lifecycle,
    p.work_id,
    p.vertex_id AS artifact_id,
    p.publication_cid AS detail
  FROM vertex_pd_color_publication p
  JOIN vertex_pd_color_derivative_asset d ON d.vertex_id = p.derivative_vertex_id
)
SELECT
  case_id,
  activity,
  timestamp,
  resource,
  lifecycle,
  work_id,
  artifact_id,
  detail
FROM events
WHERE timestamp IS NOT NULL AND timestamp <> ''
ORDER BY case_id, timestamp, activity;
