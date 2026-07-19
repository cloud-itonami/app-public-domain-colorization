#!/usr/bin/env node

import { createHash, createHmac, randomUUID } from "node:crypto";
import { createWriteStream, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dirname, "..");
const worksPath = path.join(projectRoot, "demo", "public-domain-demo-works.json");
const runsDir = path.join(projectRoot, "demo", "runs");
const works = JSON.parse(readFileSync(worksPath, "utf8")).works;

function argValue(name, fallback = "") {
  const flag = `--${name}`;
  const index = process.argv.indexOf(flag);
  if (index === -1) return fallback;
  return process.argv[index + 1] ?? fallback;
}

function hasFlag(name) {
  return process.argv.includes(`--${name}`);
}

function selectedWork() {
  const slug = argValue("work", "gertie-the-dinosaur-1914");
  const work = works.find((entry) => entry.slug === slug);
  if (!work) {
    throw new Error(`unknown work "${slug}". Known works: ${works.map((entry) => entry.slug).join(", ")}`);
  }
  return work;
}

async function downloadAndHash(work) {
  const res = await fetch(work.sourceUrl, { redirect: "follow" });
  if (!res.ok || !res.body) {
    throw new Error(`failed to fetch ${work.sourceUrl}: ${res.status} ${res.statusText}`);
  }

  const tmpPath = path.join(tmpdir(), `pdcolor-${work.slug}-${randomUUID()}${path.extname(work.sourceFilename)}`);
  const hash = createHash("sha256");
  let byteSize = 0;
  const maxBytes = Number(work.sourceByteSize || 0) + 1024 * 1024;
  const writer = createWriteStream(tmpPath);

  for await (const chunk of res.body) {
    byteSize += chunk.length;
    if (maxBytes > 0 && byteSize > maxBytes) {
      writer.destroy();
      rmSync(tmpPath, { force: true });
      throw new Error(`download exceeded expected demo size for ${work.slug}`);
    }
    hash.update(chunk);
    if (!writer.write(chunk)) {
      await new Promise((resolve) => writer.once("drain", resolve));
    }
  }
  writer.end();
  await new Promise((resolve, reject) => {
    writer.on("finish", resolve);
    writer.on("error", reject);
  });

  return { tmpPath, byteSize, sha256: hash.digest("hex") };
}

function buildVariables(work) {
  return {
    runVertexId: `pdcolor:run:demo:${work.slug}`,
    derivativeVertexId: `pdcolor:derivative:demo:${work.slug}`,
    workId: work.workId,
    sourceUrl: work.sourceUrl,
    sourceIpfsCid: "",
    sourceFilename: work.sourceFilename,
    sourceContentType: work.sourceContentType,
    maxSourceBytes: Math.ceil((Number(work.sourceByteSize || 0) + 1024 * 1024) / 1024 / 1024) * 1024 * 1024,
    title: work.title,
    workKind: work.workKind,
    publishJurisdiction: work.publishJurisdiction,
    sourceLanguage: work.sourceLanguage,
    targetLanguages: work.targetLanguages,
    voicePolicy: work.voicePolicy,
    voiceLipSync: work.voiceLipSync,
    requestedLicense: "pd-mark",
    callerDid: "did:web:pd-color.etzhayyim.com",
    dryRun: true,
  };
}

function multipartBody(fileBytes, filename) {
  const boundary = randomUUID().replaceAll("-", "");
  const head = Buffer.from(
    `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="${filename}"\r\n` +
      "Content-Type: application/octet-stream\r\n\r\n",
  );
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
  return { boundary, body: Buffer.concat([head, fileBytes, tail]) };
}

async function addToIpfs(filePath, filename) {
  const ipfsUrl = (process.env.IPFS_URL || "https://ipfs.etzhayyim.com").replace(/\/+$/u, "");
  const hmacKey = process.env.IPFS_HMAC || "";
  if (!hmacKey) {
    throw new Error("IPFS_HMAC is not set; cannot write to ipfs.etzhayyim.com");
  }

  const fileBytes = readFileSync(filePath);
  const { boundary, body } = multipartBody(fileBytes, filename);
  const signature = createHmac("sha256", hmacKey).update(body).digest("hex");
  const res = await fetch(`${ipfsUrl}/api/v0/add?pin=true&cid-version=1`, {
    method: "POST",
    headers: {
      "Content-Type": `multipart/form-data; boundary=${boundary}`,
      "X-etzhayyim-Ipfs-Auth": signature,
    },
    body,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`IPFS add failed: ${res.status} ${text}`);
  const last = text.trim().split(/\r?\n/u).filter(Boolean).at(-1);
  const parsed = JSON.parse(last);
  return { cid: parsed.Hash, ipfsUrl: `${ipfsUrl}/ipfs/${parsed.Hash}` };
}

async function ingestViaWorker(work) {
  const ipfsUrl = (process.env.IPFS_URL || "https://ipfs.etzhayyim.com").replace(/\/+$/u, "");
  const res = await fetch(`${ipfsUrl}/etzhayyim/v1/demo/ingest-public-domain`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sourceUrl: work.sourceUrl, filename: work.sourceFilename }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`Worker demo ingest failed: ${res.status} ${text}`);
  const parsed = JSON.parse(text);
  return { cid: parsed.cid, ipfsUrl: parsed.ipfsUrl, sourceByteSize: parsed.sourceByteSize };
}

async function main() {
  const work = selectedWork();
  mkdirSync(runsDir, { recursive: true });

  const verified = await downloadAndHash(work);
  const expectedSha = work.sourceSha256;
  if (expectedSha && verified.sha256 !== expectedSha) {
    rmSync(verified.tmpPath, { force: true });
    throw new Error(`sha256 mismatch for ${work.slug}: got ${verified.sha256}, expected ${expectedSha}`);
  }

  const variables = buildVariables(work);
  const report = {
    ok: true,
    work: work.slug,
    sourceUrl: work.sourceUrl,
    sourceByteSize: verified.byteSize,
    sourceSha256: verified.sha256,
    variablesPath: path.join("demo", "runs", `${work.slug}.variables.json`),
    ipfs: null,
  };

  if (hasFlag("ipfs-add")) {
    const ipfs = await addToIpfs(verified.tmpPath, work.sourceFilename);
    variables.sourceUrl = "";
    variables.sourceIpfsCid = ipfs.cid;
    report.ipfs = ipfs;
  }

  if (hasFlag("worker-ingest")) {
    const ipfs = await ingestViaWorker(work);
    variables.sourceUrl = "";
    variables.sourceIpfsCid = ipfs.cid;
    report.ipfs = ipfs;
  }

  writeFileSync(path.join(runsDir, `${work.slug}.variables.json`), `${JSON.stringify(variables, null, 2)}\n`);
  rmSync(verified.tmpPath, { force: true });
  console.log(JSON.stringify(report, null, 2));
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
