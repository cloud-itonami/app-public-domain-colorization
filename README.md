# etzhayyim-project-public-domain-colorization

`pd-color.etzhayyim.com` is a publication pipeline for restored and colorized public-domain audiovisual works. The project does not decide public-domain status by title age alone; it records a provenance bundle, delegates rights screening to `copyright.etzhayyim.com`, and only publishes when a human reviewer signs the jurisdiction-specific public-domain conclusion.

## Scope

| Area | Decision |
|---|---|
| Works | Film, animation, newsreel, short film, silent film, trailers, and related stills where source material can be legally ingested. |
| Rights model | Per-jurisdiction public-domain evidence, not a global boolean. A work can be publishable in one territory and blocked in another. |
| Derivative output | Colorized/restored derivative assets are released with a machine-readable notice that separates source public-domain evidence from new restoration/colorization contribution rights. |
| Localization | Subtitles and optional dubbed audio are generated per target language after QC, using i18n translation memory and a separate voice policy. |
| Compute | Reuse `generic.comfyui.call` from the animeka pipeline for frame restoration, colorization, interpolation, and final encode. |
| Orchestration | LangServer BPMN-contract process `public_domain_colorization_pipeline`, dispatched through the existing BPMN dispatcher. |

## Actor Boundary

| Actor | Responsibility |
|---|---|
| `did:web:pd-color.etzhayyim.com` | Pipeline coordinator and publication actor. |
| `did:web:copyright.etzhayyim.com` | Rights evidence inspection, orphan-work signals, registration checks, license/public-domain classification. |
| `did:web:media-anime.etzhayyim.com` | Optional catalog enrichment for anime titles, studio/person links, and media metadata. |
| `did:web:ipfs.etzhayyim.com` | Primary movie source ingest and pinning through `ipfs.etzhayyim.com`. |
| `did:web:storage.etzhayyim.com` | Blob and derivative asset storage for generated manifests and non-source artifacts. |

This project should not become a generic copyright registry. It consumes copyright conclusions and stores colorization production records.

## Public-Domain Gate

The gate requires all of the following:

1. `copyright.etzhayyim.com` returns `classification = "public-domain"` or `classification = "license-permits-colorization"` for the requested `publishJurisdiction`.
2. The evidence bundle includes publication date, country of origin, author/studio where known, source archive URL or accession ID, and rule basis.
3. Music, subtitles, intertitles, dub audio, posters, title cards, and restored source scans are screened separately when present.
4. Human reviewer sets `rightsApproved = true`. Automated age heuristics cannot publish.

Useful policy anchors verified 2026-04-28:

- Japan: the Agency for Cultural Affairs describes the general copyright term as life plus 70 years, and expired works as public domain.
- United States: the U.S. Copyright Office describes anonymous, pseudonymous, and work-made-for-hire terms as 95 years from publication or 120 years from creation, whichever expires first; expired works enter the public domain.
- U.S. restoration/renewal rules can change the result for older films, so the worker must use `copyright.etzhayyim.com` evidence instead of only checking a year.

## Data Model

| Table / vertex | Purpose |
|---|---|
| `vertex_pd_color_work` | Canonical audiovisual work candidate. |
| `vertex_pd_color_source_asset` | Ingested source reels/files/stills, checksums, archive IDs, acquisition notes. |
| `vertex_pd_color_rights_review` | Public-domain or license decision with evidence CIDs and reviewer DID. |
| `vertex_pd_color_run` | LangServer process run and status. |
| `vertex_pd_color_shot` | Scene/shot segmentation and frame ranges. |
| `vertex_pd_color_derivative_asset` | Restored/colorized/proxy/master assets and parameters. |
| `vertex_pd_color_localization_asset` | Subtitle, dubbed audio, and localized package manifests per target language. |
| `vertex_pd_color_publication` | Published package, territory, notice, and takedown state. |

Core edges:

```text
(:PdColorWork)-[:HAS_SOURCE]->(:PdColorSourceAsset)
(:PdColorWork)-[:HAS_RIGHTS_REVIEW]->(:PdColorRightsReview)
(:PdColorWork)-[:HAS_RUN]->(:PdColorRun)
(:PdColorRun)-[:PRODUCED]->(:PdColorDerivativeAsset)
(:PdColorDerivativeAsset)-[:LOCALIZED_AS]->(:PdColorLocalizationAsset)
(:PdColorDerivativeAsset)-[:PUBLISHED_AS]->(:PdColorPublication)
(:PdColorWork)-[:SAME_AS]->(:CopyrightWork)
(:PdColorWork)-[:MEDIA_METADATA]->(:AnimeTitle|:FilmTitle)
```

## BPMN Process

Process: `public_domain_colorization_pipeline`

NSID: `com.etzhayyim.apps.publicDomainColorization.colorizeWork`

Canonical BPMN: `00-contracts/bpmn/com/etzhayyim/public-domain-colorization/colorizeWork.bpmn`

Project mirror: `bpmn/colorize-public-domain-work.bpmn`

Lexicon: `00-contracts/lexicons/com/etzhayyim/apps/publicDomainColorization/colorizeWork.json`

Migration: `30-graph/graph-schema/migrations/20260429090000_public_domain_colorization.ts`

| Step | LangServer task type | Output |
|---|---|---|
| Ingest movie to IPFS | `pdColor.ipfs.ingestMovie` | `sourceIpfsCid`, gateway URL, source SHA-256, byte size. |
| Fetch source metadata | `generic.xrpc.invoke` | `sourceRecord`, archive metadata, source blob CID. |
| Inspect rights | `generic.xrpc.invoke` | `rightsClassification`, evidence CID, blocked reasons. |
| Persist candidate | `generic.db.insert` | `runVertexId`. |
| Human rights approval | LangServer user task | `rightsApproved`. |
| Segment shots | `pdColor.video.segmentShots` | shot map JSON and keyframes. |
| Restore frames | `pdColor.video.restoreFrames` | restored frame sequence CID using the selected quality profile. |
| Colorize frames | `pdColor.video.colorizeFrames` | colorized frame sequence CID. |
| Enhance quality | `pdColor.video.enhanceQuality` | enhanced colorized sequence CID, target resolution, grain policy. |
| QC | LangServer user task | `qcApproved`, correction notes. |
| Encode package | `pdColor.video.encodePackage` | master video CID, poster CID, captions CID. |
| Extract timed text | `pdColor.audio.extractTimedText` | transcript/intertitle timed text CID. |
| Translate subtitles | `pdColor.localization.translateSubtitles` | i18n translated subtitle manifest CID. |
| Generate dubbed audio | `pdColor.audio.generateDubbedAudio` | per-language dubbed audio manifest CID. |
| Mux localized packages | `pdColor.video.muxLocalizedPackages` | localized package manifest CID. |
| Persist derivatives | `generic.db.insert` | derivative asset rows. |
| Publish | `generic.pds.dispatch` | public record. |
| Audit | `generic.audit.emit` | immutable event trail. |

## LangServer Worker Design

MVP uses existing generic workers. A dedicated worker should be added only after the data model is stable, because most work is policy orchestration plus ComfyUI calls.

Dedicated task types, if promoted:

| Task type | Handler | Responsibility |
|---|---|---|
| `pdColor.rights.aggregateEvidence` | `task_pd_color_rights_aggregate_evidence` | Call copyright, media catalog, archive metadata, and produce a normalized evidence bundle. |
| `pdColor.video.segmentShots` | `task_pd_color_video_segment_shots` | Extract technical metadata, detect cuts, produce shot rows and frame ranges. |
| `pdColor.video.restoreFrames` | `task_pd_color_video_restore_frames` | Stabilize, degrain, repair scratches, and prepare restored frames. |
| `pdColor.video.colorizeFrames` | `task_pd_color_video_colorize_frames` | Apply shot-aware colorization with palette references and temporal consistency. |
| `pdColor.video.enhanceQuality` | `task_pd_color_video_enhance_quality` | Upscale or enhance the colorized sequence with explicit resolution and grain-preservation controls. |
| `pdColor.video.encodePackage` | `task_pd_color_video_encode_package` | Encode the publication master, poster, and publication manifest. |
| `pdColor.audio.extractTimedText` | `task_pd_color_audio_extract_timed_text` | Transcribe narration/intertitles into timestamped text. |
| `pdColor.localization.translateSubtitles` | `task_pd_color_localization_translate_subtitles` | Call the i18n translation contract and produce a subtitle manifest. |
| `pdColor.audio.generateDubbedAudio` | `task_pd_color_audio_generate_dubbed_audio` | Generate per-language dubbed audio under the selected voice policy. |
| `pdColor.video.muxLocalizedPackages` | `task_pd_color_video_mux_localized_packages` | Mux master video, subtitles, dubbed audio, and notices into localized package manifests. |
| `pdColor.video.planColorization` | `task_pd_color_video_plan_colorization` | Future promoted task for stable prompts and palette references per shot. |
| `pdColor.localization.planVoice` | `task_pd_color_localization_plan_voice` | Future promoted task for target languages, voice policy, subtitle style, and dubbing constraints. |
| `pdColor.publication.composeNotice` | `task_pd_color_publication_compose_notice` | Generate public-domain evidence notice, derivative rights notice, and source attribution. |

Worker runtime:

- Python `LangServer`, deployed with the shared `mitama-udf-pool` worker fleet.
- Long GPU operations use `generic.comfyui.call` with a 600s timeout; the dedicated worker should enqueue batches and return CIDs, not stream frames through LangServer variables.
- Store large artifacts in blob storage and pass only CIDs, hashes, durations, dimensions, and manifest references through BPMN.
- Movie source files are first added to `ipfs.etzhayyim.com`; downstream tasks should read `sourceIpfsCid`/`sourceIpfsUrl`, not arbitrary source URLs.
- Fail closed on rights uncertainty. `classification in ["unknown", "in-copyright", "blocked"]` routes to `End_Blocked`.
- Never publish directly from a ComfyUI task; publication is after human QC and a separate rights-approved variable.
- Subtitle translation uses `com.etzhayyim.apps.i18n.translateBatch` with `contentKind = "timed-text"` so translation memory, glossary approval, and RTL handling stay in the i18n actor.
- Voice translation is opt-in per run. The default `voicePolicy = "narration-neutral"` avoids cloning a living performer; archival voice matching must be separately approved and recorded in the rights evidence bundle.

## Localization Contract

| Field | Meaning |
|---|---|
| `sourceLanguage` | Original spoken/intertitle language; if absent, the timed-text extraction task returns `detectedLanguage`. |
| `targetLanguages` | ISO language tags for subtitle and dubbed audio outputs. MVP default should be `["ja", "en", "es", "fr", "zh-Hans", "ko"]`. |
| `glossaryCid` | Optional terminology bundle for names, title cards, historical terms, and studio/person names. |
| `voicePolicy` | `none`, `narration-neutral`, `character-neutral`, or `approved-archival-match`. |
| `voiceLipSync` | Boolean. When true, localized audio generation may request lip-sync timing; still stores original audio. |

Artifacts:

- `timedTextCid`: original transcript/intertitle SRT/WebVTT-style JSON with timecodes.
- `subtitleManifestCid`: per-language subtitle CIDs, writing direction, glossary hits, and quality score.
- `dubbedAudioManifestCid`: per-language audio CIDs and voice policy metadata.
- `localizedPackageManifestCid`: final muxed variants that reference master video, subtitles, audio tracks, and notices.

## Input Contract

```json
{
  "workId": "pdcolor:work:example",
  "sourceUrl": "https://archive.example/item/example",
  "sourceIpfsCid": "",
  "sourceFilename": "example-film.mp4",
  "sourceContentType": "video/mp4",
  "maxSourceBytes": 0,
  "sourceBlobCid": "bafy...",
  "title": "Example Film",
  "workKind": "film",
  "publishJurisdiction": "JP",
  "sourceLanguage": "en",
  "targetLanguages": ["ja", "en", "es", "fr", "zh-Hans", "ko"],
  "voicePolicy": "narration-neutral",
  "voiceLipSync": false,
  "requestedLicense": "pd-mark",
  "callerDid": "did:web:...",
  "dryRun": false
}
```

## Output Contract

```json
{
  "runVertexId": "pdcolor:run:...",
  "workVertexId": "pdcolor:work:...",
  "publicationCid": "bafy...",
  "masterVideoCid": "bafy...",
  "sourceIpfsCid": "bafy...",
  "sourceIpfsUrl": "https://ipfs.etzhayyim.com/ipfs/bafy...",
  "subtitleManifestCid": "bafy...",
  "dubbedAudioManifestCid": "bafy...",
  "localizedPackageManifestCid": "bafy...",
  "rightsEvidenceCid": "bafy...",
  "status": "published"
}
```

## Rollout

1. Draft mode: deploy BPMN but set `dryRun = true`; persist run, rights review, and QC records only.
2. Private preview: allow publication to private/unlisted records after rights reviewer approval.
3. Public canary: publish 5-10 low-risk works with complete evidence bundles and takedown path.
4. General queue: allow batch ingestion, still with per-work rights and QC gates.

## Demo Ingest

Demo queue: `demo/public-domain-demo-works.json`

Prepared dry-run variables:

- `demo/runs/gertie-the-dinosaur-1914.variables.json`
- `demo/runs/steamboat-willie-1928.variables.json`

The demo script downloads the archive source, verifies byte size and SHA-256, and writes LangServer start variables:

```sh
node 60-apps/etzhayyim-project-public-domain-colorization/scripts/pdcolor-demo-ingest.mjs --work gertie-the-dinosaur-1914
node 60-apps/etzhayyim-project-public-domain-colorization/scripts/pdcolor-demo-ingest.mjs --work steamboat-willie-1928
```

To add the verified file to `ipfs.etzhayyim.com`, run the same script with `IPFS_HMAC` and `--ipfs-add`:

```sh
IPFS_HMAC=... node 60-apps/etzhayyim-project-public-domain-colorization/scripts/pdcolor-demo-ingest.mjs --work gertie-the-dinosaur-1914 --ipfs-add
```

After the `etzhayyim-ipfs-proxy` Worker with `/etzhayyim/v1/demo/ingest-public-domain` is deployed, the script can ask the Worker to fetch an allowlisted demo source and add it without exposing `IPFS_HMAC` to the caller:

```sh
node 60-apps/etzhayyim-project-public-domain-colorization/scripts/pdcolor-demo-ingest.mjs --work gertie-the-dinosaur-1914 --worker-ingest
```

Local verification on 2026-04-29:

- `Gertie the Dinosaur`: 39,234,070 bytes, SHA-256 `8c032769f14f7e545c8ab4aa1bb1b5d950b84268237ca7ecce19b6e8c8ac0237`
- `Steamboat Willie`: 33,335,230 bytes, SHA-256 `6f7c8c9309edf3357a27d27a8bb13feed44853a814b682c43ead24ca9446aa43`
- `Gertie the Dinosaur` IPFS CID: `bafybeifeqsv57dnlfvhjhwm6aah6emxljva2tkbshd7x6tfubw7ow4opue`
- `Steamboat Willie` IPFS CID: `bafybeifanztr2e2wnhpnonwbezm3p5gvxveo4uumrr2xbdsgu55uix2mga`
- The files were added through a local `kubectl -n ipfs port-forward svc/kubo 5001:5001` session to avoid exposing `IPFS_HMAC` outside Cloudflare.

## Published Demo Viewer

The static viewer reads `demo/publications.json` and opens the first published canary record. It links the source movie on `ipfs.etzhayyim.com`, publication package manifests, localized subtitle/audio manifests, and the live PDS record.

```sh
cd 60-apps/etzhayyim-project-public-domain-colorization/demo
python3 -m http.server 8765
open http://127.0.0.1:8765/public/
```

Current canary publication:

- Work: `Gertie the Dinosaur`
- PDS record CID: `3mkmf36owkk2m`
- AT URI: `at://did:web:pd-color.etzhayyim.com/com.etzhayyim.apps.publicDomainColorization.publication/3mkmf36owkk2m`
- Publication package CID: `bafkreibf2d5q7t4thvtro2qjomq4ejytqlv2rh3z5vg5otrpwngma4fice`
- Localized package CID: `bafkreifoyld7dyj7urialgcq357w5n6yhnrdveosspeukflcw6ablv2t6e`
- Subtitle manifest CID: `bafkreigoyykv7ckdivx463qtjw4nldd43rrwkwa5iad5deatzxh7xffvle`
- Dubbed audio manifest CID: `bafkreih6vpbhmtesxw6cibm74flkmzz6q54g6drbxwmpjy2765moekqp3y`

## References

- Japan Agency for Cultural Affairs: https://www.bunka.go.jp/seisaku/chosakuken/taisetsu/point
- U.S. Copyright Office lifecycle: https://www.copyright.gov/history/copyright-exhibit/lifecycle/
- U.S. Copyright Office circulars and investigation guidance: https://copyright.gov/circs/
