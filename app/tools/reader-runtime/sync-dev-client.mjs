import { cp, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const packageDir = path.dirname(fileURLToPath(import.meta.url));
const targetArgument = process.argv[2];
if (!targetArgument) throw new Error('Usage: node sync-dev-client.mjs <nekori-plugins checkout>');
const targetRoot = path.resolve(targetArgument);
const targetPackage = JSON.parse(await readFile(path.join(targetRoot, 'package.json'), 'utf8'));
if (targetPackage.name !== 'nekori-plugins') throw new Error('Target must be a nekori-plugins checkout');
const dist = path.join(packageDir, 'dist');
for (const file of ['index.js', 'index.d.ts', 'styles/reader.css', 'vendor/hls.min.js', 'vendor/videojs.min.js']) {
  if (!(await stat(path.join(dist, file))).isFile()) throw new Error(`Missing build output: ${file}`);
}
const destination = path.join(targetRoot, 'public/static/novel-reader');
await mkdir(destination, { recursive: true });
await cp(dist, destination, { recursive: true });
console.log(`Copied built reader files to ${destination}`);
