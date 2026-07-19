# pd-color process mining

This folder documents the canonical event log for the public-domain
colorization pipeline. `pdColor.*` worker tasks append step-level events to
`vertex_pd_color_process_event`; `view_pd_color_process_event_log` unions those
live worker events with the coarser run/derivative/localization/publication
graph records.

The event log shape is:

```text
case_id,activity,timestamp,resource,lifecycle,work_id,artifact_id,detail
```

The SQL here is kept as a readable copy of the view definition. Do not commit
CSV/JSON snapshots; export them from the Python actor image when needed:

```sh
python -m kotodama.pd_color_process_mining csv > /tmp/pdcolor-event-log.csv
python -m kotodama.pd_color_process_mining summary > /tmp/pdcolor-process-summary.json
```

The CSV shape can be imported into PM4Py, Apromore, Celonis, or a simple
notebook for conformance and throughput analysis.
