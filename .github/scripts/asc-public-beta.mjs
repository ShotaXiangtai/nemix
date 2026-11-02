// Creates (or reuses) an external TestFlight group with a public link, adds the latest
// processed build, and submits it for Beta App Review. Prints the public link.
// Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, GROUP_NAME
import crypto from "node:crypto";
import fs from "node:fs";

const API = "https://api.appstoreconnect.apple.com";
const { ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, GROUP_NAME } = process.env;
const privateKey = fs.readFileSync(ASC_KEY_PATH);

function fail(msg) {
  console.log(`::error::${msg}`);
  process.exit(1);
}

function token() {
  const b64u = (x) => Buffer.from(x).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const head = b64u(JSON.stringify({ alg: "ES256", kid: ASC_KEY_ID, typ: "JWT" }));
  const body = b64u(JSON.stringify({ iss: ASC_ISSUER_ID, iat: now, exp: now + 900, aud: "appstoreconnect-v1" }));
  const sig = crypto.sign("sha256", Buffer.from(`${head}.${body}`), { key: privateKey, dsaEncoding: "ieee-p1363" });
  return `${head}.${body}.${b64u(sig)}`;
}

async function api(method, path, payload, { allowFail = false } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${token()}`, "Content-Type": "application/json" },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : {};
  if (!res.ok) {
    for (const e of json.errors || []) console.log(`   ${e.code} - ${e.title} - ${e.detail}`);
    if (allowFail) return { error: res.status, json };
    fail(`${method} ${path} failed with HTTP ${res.status}`);
  }
  return json;
}

const apps = await api("GET", `/v1/apps?filter[bundleId]=${encodeURIComponent(BUNDLE_ID)}`);
const appId = apps.data?.[0]?.id ?? fail(`no app for ${BUNDLE_ID}`);
console.log(`app ${BUNDLE_ID} = ${appId}`);

// Latest processed build
const builds = await api("GET", `/v1/builds?filter[app]=${appId}&filter[processingState]=VALID&sort=-uploadedDate&limit=1&include=preReleaseVersion`);
const build = builds.data?.[0] ?? fail("no processed (VALID) build found");
const version = builds.included?.find((i) => i.type === "preReleaseVersions")?.attributes?.version;
console.log(`latest build: ${version} (${build.attributes.version}) id=${build.id}`);

// External group (create or reuse)
const groups = await api("GET", `/v1/apps/${appId}/betaGroups?limit=50`);
let group = (groups.data || []).find((g) => !g.attributes.isInternalGroup && g.attributes.name === GROUP_NAME);
if (!group) {
  const created = await api("POST", "/v1/betaGroups", {
    data: {
      type: "betaGroups",
      attributes: {
        name: GROUP_NAME,
        isInternalGroup: false,
        publicLinkEnabled: true,
        publicLinkLimitEnabled: false,
        feedbackEnabled: true,
      },
      relationships: { app: { data: { type: "apps", id: appId } } },
    },
  });
  group = created.data;
  console.log(`created external group "${GROUP_NAME}" (${group.id})`);
} else {
  console.log(`using external group "${GROUP_NAME}" (${group.id})`);
  if (!group.attributes.publicLinkEnabled) {
    const updated = await api("PATCH", `/v1/betaGroups/${group.id}`, {
      data: { type: "betaGroups", id: group.id, attributes: { publicLinkEnabled: true, publicLinkLimitEnabled: false } },
    });
    group = updated.data;
  }
}

// Add the build to the group (ignored if already added)
await api("POST", `/v1/betaGroups/${group.id}/relationships/builds`, {
  data: [{ type: "builds", id: build.id }],
}, { allowFail: true });
console.log("build added to the group");

// Submit for Beta App Review (ignored if already submitted / approved)
const submit = await api("POST", "/v1/betaAppReviewSubmissions", {
  data: { type: "betaAppReviewSubmissions", relationships: { build: { data: { type: "builds", id: build.id } } } },
}, { allowFail: true });
if (submit.error) {
  console.log("Beta App Review submission not created (see messages above; it may already be submitted).");
} else {
  console.log(`submitted for Beta App Review: state=${submit.data?.attributes?.betaReviewState}`);
}

const refreshed = await api("GET", `/v1/betaGroups/${group.id}`);
console.log(`PUBLIC LINK: ${refreshed.data?.attributes?.publicLink ?? "(not available yet)"}`);
console.log("The link starts working after Apple approves the Beta App Review.");
