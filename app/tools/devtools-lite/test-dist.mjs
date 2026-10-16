import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
fs.mkdirSync(path.join(root, 'app/build'), {recursive: true});
const temporary = fs.mkdtempSync(path.join(root, 'app/build/devtools-dist-test-'));
const source = path.join(temporary, 'source');
const remote = path.join(temporary, 'remote.git');
const output = path.join(temporary, 'assets');
const cacheRoot = path.join(temporary, 'cache');
const lock = path.join(temporary, 'dist.lock.json');
fs.mkdirSync(source);
const git = (...args) => execFileSync('git', ['-C', source, ...args],
  {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
git('init', '--bare', remote);
git('init', '-b', 'chii');
git('config', 'user.name', 'Dist test');
git('config', 'user.email', 'dist@example.invalid');
git('remote', 'add', 'origin', remote);
fs.writeFileSync(path.join(source, 'source-only.txt'), 'This must never be fetched');
git('add', '.');
git('commit', '-m', 'Source fixture');
const sourceCommit = git('rev-parse', 'HEAD');
git('push', 'origin', 'chii');
git('checkout', '--orphan', 'dist');
git('rm', '-f', '--', 'source-only.txt');
const files = {
  'front_end/chii_app.html': '<!doctype html>',
  'front_end/entrypoints/chii_app/chii_app.js': '// distribution test fixture\n',
  'front_end/core/i18n/locales/en-US.json': '{}',
  'vendor/chobitsu.js': '// distribution test fixture\n',
  'vendor/chobitsu-LICENSE': 'Test license',
  LICENSE: 'Test license',
  'THIRD-PARTY-NOTICES.txt': 'Test notices',
  'front_end/obsolete.js': '// old asset\n',
};
const commitDist = () => {
  const manifest = {sourceRevision: sourceCommit, sourceDirty: false, chobitsuVersion: 'test', files: {}};
  for (const [file, contents] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(source, file)), {recursive: true});
    fs.writeFileSync(path.join(source, file), contents);
    manifest.files[file] = createHash('sha256').update(contents).digest('hex');
  }
  fs.writeFileSync(path.join(source, 'manifest.json'), JSON.stringify(manifest));
  git('add', '--all');
  git('commit', '-m', 'Dist fixture');
  git('push', 'origin', 'dist');
  return git('rev-parse', 'HEAD');
};
const first = commitDist();
delete files['front_end/obsolete.js'];
fs.unlinkSync(path.join(source, 'front_end/obsolete.js'));
files['front_end/current.js'] = '// new asset\n';
const second = commitDist();
const pin = commit => fs.writeFileSync(lock, JSON.stringify({repository: pathToFileURL(remote).href, commit}));
const prepare = () => execFileSync(process.execPath,
  [path.join(root, 'app/tools/devtools-lite/prepare-assets.mjs'), lock, output, cacheRoot],
  {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
pin(null);
assert.throws(prepare, /DevTools dist is not pinned/);
assert.ok(!fs.existsSync(cacheRoot), 'Missing pin must fail before accessing Git');
pin(first);
prepare();
const cache = path.join(cacheRoot, fs.readdirSync(cacheRoot)[0]);
const cachedGit = (...args) => execFileSync('git', ['-C', cache, ...args],
  {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
assert.equal(cachedGit('rev-parse', 'HEAD'), first, 'An old pin must not resolve to the new dist tip');
assert.equal(cachedGit('branch', '-r'), 'origin/dist');
assert.equal(cachedGit('tag'), '');
assert.throws(() => cachedGit('cat-file', '-e', sourceCommit), 'The chii commit must not be downloaded');
assert.ok(!fs.existsSync(path.join(cache, 'source-only.txt')));
pin(sourceCommit);
assert.throws(prepare, /not in dist history/);
pin(second);
prepare();
assert.equal(cachedGit('rev-parse', 'HEAD'), second);
assert.ok(!fs.existsSync(path.join(output, 'devtools-lite/front_end/obsolete.js')));
assert.ok(fs.existsSync(path.join(output, 'devtools-lite/front_end/current.js')));
execFileSync(process.execPath, [path.join(root, 'app/tools/devtools-lite/check-assets.mjs'),
  path.join(output, 'devtools-lite')], {stdio: 'inherit'});
fs.renameSync(remote, remote + '.offline');
prepare();
fs.renameSync(remote + '.offline', remote);
// A corrupt tracked asset must be rejected before replacing a working output.
fs.writeFileSync(path.join(source, 'vendor/chobitsu.js'), 'corrupt');
git('add', '.');
git('commit', '-m', 'Corrupt fixture');
git('push', 'origin', 'dist');
pin(git('rev-parse', 'HEAD'));
assert.throws(prepare, /vendor\/chobitsu.js/);
assert.equal(fs.readFileSync(path.join(output, 'devtools-lite/vendor/chobitsu.js'), 'utf8'), files['vendor/chobitsu.js']);
// Keep a valid fixture lock for running the real Gradle preparation task locally.
pin(second);
fs.writeFileSync(path.join(root, 'app/build/last-devtools-test-lock.txt'), lock);
console.log('PASS: missing pin, exact old/new pins, dist-only objects, wrong-branch rejection, offline cache, stale removal and hash integrity');
console.log('Local Gradle verification lock: ' + lock);
