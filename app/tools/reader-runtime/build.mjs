import { cp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { spawnSync } from "node:child_process";

const packageDir = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.join(packageDir, "dist");
const sourceDir = path.join(packageDir, "src");
const androidAssetsDir = readOption("--android-assets");
const metafilePath = readOption("--metafile");
const repositoryDir = path.resolve(packageDir, "../../..");
const inlineCss = {
  name: "inline-css",
  setup(builder) {
    builder.onResolve({ filter: /\.css\?inline$/ }, (args) => ({
      path: path.resolve(args.resolveDir, args.path.replace(/\?inline$/, "")),
      namespace: "inline-css",
    }));
    builder.onLoad({ filter: /.*/, namespace: "inline-css" }, async (args) => ({
      contents: await readFile(args.path, "utf8"),
      loader: "text",
    }));
  },
};

for (const required of [
  "entries/reader.ts",
  "entries/download.ts",
  "entries/skeleton.ts",
  "index.ts",
  "styles/reader.css",
  "styles/skeleton.css",
]) {
  const input = path.join(sourceDir, required);
  try {
    if ((await stat(input)).size === 0) throw new Error("is empty");
  } catch (error) {
    throw new Error(`Reader build input ${input} is missing or empty`, {
      cause: error,
    });
  }
}

assertInsideRepository(distDir);
await rm(distDir, { recursive: true, force: true });
const assetsDir = path.join(distDir, "assets");
await mkdir(assetsDir, { recursive: true });
const metafileInputs = {};

for (const [entry, outfile] of [
  ["reader", "reader.js"],
  ["download", "download.js"],
  ["skeleton", "skeleton.js"],
]) {
  const result = await build({
    absWorkingDir: packageDir,
    entryPoints: [path.join(sourceDir, "entries", `${entry}.ts`)],
    outfile: path.join(assetsDir, outfile),
    bundle: true,
    format: "iife",
    platform: "browser",
    target: "chrome138",
    minify: true,
    legalComments: "none",
    metafile: true,
    plugins: [inlineCss],
  });
  Object.assign(metafileInputs, result.metafile.inputs);
}

await build({
  absWorkingDir: packageDir,
  entryPoints: [path.join(sourceDir, "index.ts")],
  outfile: path.join(distDir, "index.js"),
  bundle: true,
  format: "esm",
  platform: "browser",
  target: "chrome138",
  packages: "external",
  sourcemap: false,
  legalComments: "none",
  plugins: [inlineCss],
});

runTypeScriptDeclarations();
await mkdir(path.join(distDir, "styles"), { recursive: true });
await build({
  absWorkingDir: packageDir,
  entryPoints: [path.join(sourceDir, "styles", "reader.css")],
  outfile: path.join(distDir, "styles", "reader.css"),
  bundle: true,
  platform: "browser",
  target: "chrome138",
  minify: true,
});

await cp(path.join(sourceDir, "styles", "skeleton.css"), path.join(distDir, "styles", "skeleton.css"));

if (metafilePath) {
  const metadataFile = path.resolve(packageDir, metafilePath);
  await mkdir(path.dirname(metadataFile), { recursive: true });
  await writeFile(metadataFile, JSON.stringify({ inputs: metafileInputs }));
}

if (androidAssetsDir) {
  const output = path.resolve(packageDir, androidAssetsDir);
  const novelReaderDir = path.join(output, "novel-reader");
  assertInsideRepository(novelReaderDir);
  await rm(novelReaderDir, { recursive: true, force: true });
  await mkdir(novelReaderDir, { recursive: true });
  await Promise.all([
    cp(path.join(assetsDir, "reader.js"), path.join(novelReaderDir, "reader.js")),
    cp(path.join(assetsDir, "download.js"), path.join(novelReaderDir, "download.js")),
    cp(path.join(assetsDir, "skeleton.js"), path.join(novelReaderDir, "skeleton.js")),
    cp(path.join(distDir, "styles", "skeleton.css"), path.join(novelReaderDir, "skeleton.css")),
    cp(path.join(distDir, "styles", "reader.css"), path.join(novelReaderDir, "reader.css")),
  ]);
}

function assertInsideRepository(output) {
  const relative = path.relative(repositoryDir, path.resolve(output));
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Build output must stay below the repository: ${output}`);
  }
}

function readOption(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) return null;
  const value = process.argv[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`${name} requires a path`);
  return value;
}

function runTypeScriptDeclarations() {
  const result = spawnSync(
    process.execPath,
    ["node_modules/typescript/bin/tsc", "-p", "tsconfig.types.json"],
    { cwd: packageDir, stdio: "inherit" },
  );
  if (result.status !== 0) throw new Error("Could not emit reader TypeScript declarations");
}
