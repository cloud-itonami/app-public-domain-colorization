const catalogUrl = new URL("../publications.json", import.meta.url);

const $ = (id) => document.getElementById(id);

function ipfsUrl(cid) {
  return `https://ipfs.etzhayyim.com/ipfs/${cid}`;
}

function setText(id, value) {
  $(id).textContent = value ?? "";
}

function renderLanguages(languages) {
  $("languages").replaceChildren(
    ...languages.map((lang) => {
      const item = document.createElement("span");
      item.textContent = lang;
      return item;
    }),
  );
}

function renderAssets(publication) {
  const entries = [
    ["Source video", publication.source.sourceIpfsCid],
    ["Publication manifest", publication.assets.publicationManifestCid],
    ["Localized package", publication.assets.localizedPackageManifestCid],
    ["Subtitle manifest", publication.assets.subtitleManifestCid],
    ["Dubbed audio manifest", publication.assets.dubbedAudioManifestCid],
    ["Rights evidence", publication.assets.rightsEvidenceCid],
  ];

  $("asset-grid").replaceChildren(
    ...entries.map(([label, cid]) => {
      const card = document.createElement("article");
      card.className = "asset-card";

      const title = document.createElement("h3");
      title.textContent = label;

      const code = document.createElement("code");
      code.textContent = cid;

      const link = document.createElement("a");
      link.href = ipfsUrl(cid);
      link.target = "_blank";
      link.rel = "noreferrer";
      link.textContent = "Open IPFS asset";

      card.append(title, code, link);
      return card;
    }),
  );
}

async function main() {
  const catalog = await fetch(catalogUrl).then((res) => {
    if (!res.ok) throw new Error(`catalog fetch failed: ${res.status}`);
    return res.json();
  });
  const publication = catalog.publications[0];

  $("source-video").src = publication.source.sourceIpfsUrl;
  $("archive-link").href = publication.source.archiveUrl;
  $("record-link").href = publication.publication.recordUrl;

  setText("catalog-status", `${catalog.publications.length} publication`);
  setText("work-title", publication.title);
  setText(
    "work-summary",
    `${publication.year} ${publication.workKind}. Source pinned to IPFS, localized for ${publication.targetLanguages.length} languages.`,
  );
  setText("record-status", publication.status);
  setText("published-at", publication.publishedAt);
  setText("publication-cid", publication.publication.cid);
  setText("publication-uri", publication.publication.uri);
  setText("run-id", publication.runVertexId);
  setText("rights-summary", `${publication.rights.classification} in ${publication.publishJurisdiction}`);

  renderLanguages(publication.targetLanguages);
  renderAssets(publication);
  $("record-json").textContent = JSON.stringify(publication, null, 2);
}

main().catch((error) => {
  setText("catalog-status", "Catalog error");
  $("record-json").textContent = error instanceof Error ? error.stack : String(error);
});
