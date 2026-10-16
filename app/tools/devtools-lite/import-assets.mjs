import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
assert.ok(process.argv[2] && process.argv[3], 'Usage: import-assets.mjs DIST OUTPUT');
const source = fs.realpathSync(process.argv[2]);
const output = path.resolve(process.argv[3]);
assert.ok(output !== source && !source.startsWith(output + path.sep), 'Output cannot contain the dist checkout');
const destination = path.join(output, 'devtools-lite');
const manifest = JSON.parse(fs.readFileSync(path.join(source, 'manifest.json'), 'utf8'));
assert.equal(manifest.sourceDirty, false, 'Only a clean source build may be imported');
assert.match(manifest.sourceRevision, /^[a-f0-9]{40}$/);
const files = Object.keys(manifest.files).filter(file => file.startsWith('front_end/') ||
  file.startsWith('vendor/') || ['LICENSE', 'THIRD-PARTY-NOTICES.txt'].includes(file));
for (const [file, hash] of Object.entries(manifest.files)) {
  assert.ok(!file.includes('\\') && !file.split('/').some(part => !part || part === '.' || part === '..'), file);
  const resolved = path.resolve(source, file);
  assert.ok(resolved.startsWith(source + path.sep));
  assert.equal(fs.realpathSync(resolved), resolved, 'Symlink in distribution: ' + file);
  assert.equal(createHash('sha256').update(fs.readFileSync(resolved)).digest('hex'), hash, file);
}
for (const file of ['front_end/chii_app.html', 'front_end/entrypoints/chii_app/chii_app.js',
  'vendor/chobitsu.js', 'vendor/chobitsu-LICENSE', 'LICENSE', 'THIRD-PARTY-NOTICES.txt']) {
  assert.ok(files.includes(file), 'Missing distribution asset: ' + file);
}
// Validate everything before replacing this generated subtree. Native host files stay in main/assets.
fs.mkdirSync(output, {recursive: true});
assert.equal(fs.realpathSync(output), output, 'Refusing a redirected output directory');
if (fs.existsSync(destination)) {
  assert.ok(!fs.lstatSync(destination).isSymbolicLink());
  fs.rmSync(destination, {recursive: true});
}
for (const file of files) {
  const target = path.join(destination, file);
  fs.mkdirSync(path.dirname(target), {recursive: true});
  fs.copyFileSync(path.join(source, file), target);
}
fs.writeFileSync(path.join(destination, 'manifest.json'), JSON.stringify({...manifest,
  files: Object.fromEntries(files.map(file => [file, manifest.files[file]]))}, null, 2) + '\n');
console.log(`Imported ${files.length} verified offline DevTools assets`);
