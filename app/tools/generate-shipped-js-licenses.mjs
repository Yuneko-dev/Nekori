import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const rootDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);
const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}

const metroSourcemap = requiredArg("--metro-sourcemap");
const licenseTemplate = requiredArg("--license-template");
const outputFile = requiredArg("--output");
const temporaryVideoInputs = path.join(
  path.dirname(outputFile),
  "video-license-inputs.json",
);
console.log(`Generating shipped JS licenses to ${outputFile}`);
const jsRuntimeDir = path.join(rootDir, "js-runtime");
const videoBundleDir = path.join(rootDir, "app", "tools", "videojs-bundle");

await mkdir(path.dirname(outputFile), { recursive: true });
await collectVideoInputs();

const packageDirectories = new Set([
  ...packageDirectoriesFromSources(
    JSON.parse(await readFile(metroSourcemap, "utf8")).sources,
  ),
  ...packageDirectoriesFromVideoInputs(
    JSON.parse(await readFile(temporaryVideoInputs, "utf8")),
  ),
]);

const packageRecords = new Map();
for (const library of await Promise.all(
  [...packageDirectories].map(readPackage),
)) {
  packageRecords.set(library.uniqueId, library);
}

const libraries = [...packageRecords.values(), ...customLibrary()].sort(
  (first, second) => first.name.localeCompare(second.name),
);
const licenseIds = new Set(libraries.flatMap((library) => library.licenses));
const nativeLicenses = JSON.parse(
  await readFile(licenseTemplate, "utf8"),
).licenses;
const licenses = Object.fromEntries(
  [...licenseIds].sort().map((id) => {
    const spdxId = id.slice("js:".length);
    const template = nativeLicenses[spdxId] ?? {
      name: spdxId,
      url: `https://spdx.org/licenses/${spdxId}.html`,
      spdxId,
    };
    return [
      id,
      {
        ...template,
        name: `JS · ${template.name}`,
        internalHash: id,
        hash: id,
      },
    ];
  }),
);

validateOutput(libraries, licenses);
await writeFile(outputFile, JSON.stringify({ libraries, licenses }));
await rm(temporaryVideoInputs, { force: true });

function requiredArg(name) {
  const value = args.get(name);
  if (!value) throw new Error(`${name} is required`);
  return path.resolve(value);
}

function collectVideoInputs() {
  const result = spawnSync(
    process.execPath,
    ["build.mjs", "--check", "--license-inputs", temporaryVideoInputs],
    { cwd: videoBundleDir, stdio: "inherit" },
  );
  if (result.status !== 0)
    throw new Error("Could not inspect Video.js bundle inputs");
}

function packageDirectoriesFromSources(sources) {
  return sources
    .map((source) => packageDirectoryFromPath(source, jsRuntimeDir))
    .filter(Boolean);
}

function packageDirectoriesFromVideoInputs(inputs) {
  return inputs
    .filter((item) => item.bytes > 0)
    .map((item) => packageDirectoryFromPath(item.input, videoBundleDir))
    .filter(Boolean);
}

function packageDirectoryFromPath(filePath, baseDirectory) {
  const segments = path
    .resolve(baseDirectory, filePath)
    .replaceAll("\\", "/")
    .split("/");
  const nodeModulesIndex = segments.lastIndexOf("node_modules");
  if (nodeModulesIndex < 0 || !segments[nodeModulesIndex + 1]) return null;
  const packageEnd = segments[nodeModulesIndex + 1].startsWith("@")
    ? nodeModulesIndex + 3
    : nodeModulesIndex + 2;
  return segments.slice(0, packageEnd).join(path.sep);
}

async function readPackage(directory) {
  let manifest = {};
  try {
    const parsed = JSON.parse(
      await readFile(path.join(directory, "package.json"), "utf8"),
    );
    manifest = parsed && typeof parsed === "object" ? parsed : {};
  } catch (error) {
    console.warn(
      `Could not read ${packageName(directory)} metadata: ${error instanceof Error ? error.message : error}`,
    );
  }

  const manifestName = nonEmptyString(manifest.name);
  const name = manifestName ?? packageName(directory);
  const version = nonEmptyString(manifest.version) ?? "";
  const license =
    nonEmptyString(manifest.license) ?? nonEmptyString(manifest.license?.type);
  const missing = [
    !manifestName && "name",
    !version && "version",
    !license && "license",
  ].filter(Boolean);
  if (missing.length)
    console.warn(`${name} is missing package metadata: ${missing.join(", ")}`);

  const website =
    nonEmptyString(manifest.homepage) ?? repositoryUrl(manifest.repository);
  const scm = repositoryMetadata(manifest.repository);
  return {
    uniqueId: `npm:${name}${version ? `@${version}` : ""}`,
    artifactVersion: version,
    name,
    description: nonEmptyString(manifest.description) ?? "",
    ...(website ? { website } : {}),
    developers: developers(manifest),
    ...(scm ? { scm } : {}),
    licenses: license
      ? license
          .replace(/[()]/g, "")
          .split(/\s+(?:AND|OR)\s+/)
          .filter(Boolean)
          .map((id) => `js:${id}`)
      : [],
    funding: [],
  };
}

function developers(manifest) {
  return [
    manifest.author,
    ...(Array.isArray(manifest.contributors) ? manifest.contributors : []),
  ]
    .map((developer) => {
      if (!developer) return null;
      if (typeof developer === "object") {
        const name = nonEmptyString(developer.name);
        const url = nonEmptyString(developer.url);
        return name || url
          ? {
              ...(name ? { name } : {}),
              ...(url ? { organisationUrl: url } : {}),
            }
          : null;
      }
      const value = nonEmptyString(developer);
      if (!value) return null;
      const name = value.replace(/\s*[<(].*$/, "").trim();
      const url = value.match(/\((https?:\/\/[^)]+)\)/)?.[1];
      return name ? { name, ...(url ? { organisationUrl: url } : {}) } : null;
    })
    .filter(Boolean);
}

function repositoryMetadata(repository) {
  const url = repositoryUrl(repository);
  if (!url) return null;
  const type =
    nonEmptyString(typeof repository === "object" ? repository?.type : null) ??
    "git";
  return { url, ...(type ? { connection: `scm:${type}:${url}` } : {}) };
}

function repositoryUrl(repository) {
  const url = nonEmptyString(
    typeof repository === "string" ? repository : repository?.url,
  );
  return url
    ?.replace(/^git\+/, "")
    .replace(/^git@github\.com:/, "https://github.com/")
    .replace(/^git:\/\/github\.com\//, "https://github.com/")
    .replace(/\.git$/, "");
}

function packageName(directory) {
  const name = path.basename(directory);
  const parent = path.basename(path.dirname(directory));
  return parent.startsWith("@") ? `${parent}/${name}` : name;
}

function nonEmptyString(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function customLibrary() {
  return [
    {
      uniqueId: "js:node-html-markdown@2.0.0",
      artifactVersion: "2.0.0",
      name: "node-html-markdown",
      description:
        "Fast HTML to markdown converter for NodeJS or the browser. Adapted to work within the Nekori application.",
      website: "https://www.npmjs.com/package/node-html-markdown",
      developers: [{ name: "crosstype" }],
      scm: {
        connection: "scm:git:https://github.com/crosstype/node-html-markdown",
        url: "https://github.com/crosstype/node-html-markdown",
      },
      licenses: ["js:MIT"],
      funding: [],
    },
    {
      uniqueId: "npm:hls.js@1.6.15",
      artifactVersion: "1.6.15",
      name: "hls.js",
      description:
        "JavaScript HLS client using MediaSourceExtension. Adapted to work within the Nekori application.",
      website: "https://github.com/video-dev/hls.js",
      developers: [{ name: "video-dev" }],
      scm: {
        url: "https://github.com/video-dev/hls.js",
        connection: "scm:git:https://github.com/video-dev/hls.js",
      },
      licenses: ["js:Apache-2.0"],
      funding: [],
    },
  ];
}

function validateOutput(libraries, licenses) {
  const libraryFields = [
    "uniqueId",
    "artifactVersion",
    "name",
    "description",
    "developers",
    "licenses",
    "funding",
  ];
  for (const library of libraries) {
    for (const field of libraryFields) {
      if (!(field in library))
        throw new Error(`${library.uniqueId ?? "Library"} is missing ${field}`);
    }
    for (const license of library.licenses) {
      if (!licenses[license])
        throw new Error(`${library.uniqueId} references missing ${license}`);
    }
  }
  for (const [id, license] of Object.entries(licenses)) {
    for (const field of ["name", "url", "hash"]) {
      if (!(field in license)) throw new Error(`${id} is missing ${field}`);
    }
  }
}
