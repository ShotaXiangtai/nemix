// Uploads an IPA to App Store Connect (TestFlight) with the Build Upload REST API.
// The older upload service used by altool/xcodebuild rejects some team API keys
// ("Cannot verify client's JWT headers"), while the REST API accepts them.
//
// Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, APP_VERSION, BUILD_NUMBER
// Usage: node asc-upload.mjs path/to/app.ipa
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const API = "https://api.appstoreconnect.apple.com";
const { ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, APP_VERSION, BUILD_NUMBER } = process.env;
const ipaPath = process.argv[2];
for (const [k, v] of Object.entries({ ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH, BUNDLE_ID, APP_VERSION, BUILD_NUMBER, ipaPath })) {
  if (!v) fail(`missing ${k}`);
}
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

async function api(method, urlPath, payload) {
  const res = await fetch(API + urlPath, {
    method,
    headers: { Authorization: `Bearer ${token()}`, "Content-Type": "application/json" },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : {};
  if (!res.ok) {
    for (const e of json.errors || []) console.log(`   ${e.code} - ${e.title} - ${e.detail}`);
    fail(`${method} ${urlPath} failed with HTTP ${res.status}`);
  }
  return json;
}

const apps = await api("GET", `/v1/apps?filter[bundleId]=${encodeURIComponent(BUNDLE_ID)}`);
const appId = apps.data?.[0]?.id;
if (!appId) fail(`no App Store Connect app with bundle id ${BUNDLE_ID}`);
console.log(`[1/5] app ${BUNDLE_ID} (${appId}), version ${APP_VERSION} build ${BUILD_NUMBER}`);

const upload = await api("POST", "/v1/buildUploads", {
  data: {
    type: "buildUploads",
    attributes: { cfBundleShortVersionString: APP_VERSION, cfBundleVersion: String(BUILD_NUMBER), platform: "IOS" },
    relationships: { app: { data: { type: "apps", id: appId } } },
  },
});
const uploadId = upload.data.id;
console.log(`[2/5] build upload created (${uploadId})`);

const size = fs.statSync(ipaPath).size;
const file = await api("POST", "/v1/buildUploadFiles", {
  data: {
    type: "buildUploadFiles",
    attributes: { assetType: "ASSET", fileName: path.basename(ipaPath), fileSize: size, uti: "com.apple.ipa" },
    relationships: { buildUpload: { data: { type: "buildUploads", id: uploadId } } },
  },
});
const fileId = file.data.id;
const ops = file.data.attributes.uploadOperations || [];
console.log(`[3/5] file reserved (${size} bytes, ${ops.length} part(s))`);

const fd = fs.openSync(ipaPath, "r");
for (const [i, op] of ops.entries()) {
  const buf = Buffer.alloc(op.length);
  fs.readSync(fd, buf, 0, op.length, op.offset);
  const headers = {};
  for (const h of op.requestHeaders || []) headers[h.name] = h.value; // only Apple-provided headers, no API token
  const res = await fetch(op.url, { method: op.method || "PUT", headers, body: buf });
  if (!res.ok) fail(`part ${i + 1}/${ops.length} failed with HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
}
fs.closeSync(fd);
console.log(`[4/5] uploaded ${ops.length} part(s)`);

await api("PATCH", `/v1/buildUploadFiles/${fileId}`, {
  data: { type: "buildUploadFiles", id: fileId, attributes: { uploaded: true } },
});
console.log("[5/5] upload committed; App Store Connect is processing the build");

// Processing usually takes 5-30 minutes. Report progress for a while, but do not fail on timeout.
for (let i = 0; i < 20; i++) {
  await new Promise((r) => setTimeout(r, 30_000));
  const builds = await api("GET", `/v1/builds?filter[app]=${appId}&filter[version]=${encodeURIComponent(BUILD_NUMBER)}&limit=1`);
  const state = builds.data?.[0]?.attributes?.processingState;
  console.log(`   processing state: ${state ?? "not visible yet"}`);
  if (state === "VALID") {
    console.log("Build is ready in TestFlight.");
    process.exit(0);
  }
  if (state === "FAILED" || state === "INVALID") fail(`build processing ended with state ${state} (check the email from App Store Connect)`);
}
console.log("Still processing; it will appear in TestFlight when done.");
