import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const packageDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const repositoryDir = path.resolve(packageDir, '../..');

function resolveWorkspaceModule(relativePath) {
  const local = path.join(packageDir, 'node_modules', relativePath);
  const root = path.join(repositoryDir, 'node_modules', relativePath);
  return { local, root };
}

test('packed package installs and typechecks from a TypeScript consumer', async () => {
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), 'nekori-reader-consumer-'));
  try {
    const packDirectory = path.join(temporaryRoot, 'pack');
    const consumerDirectory = path.join(temporaryRoot, 'consumer');
    await mkdir(packDirectory);
    await mkdir(consumerDirectory);
    const readerPackage = runNpm(['pack', '--json', '--pack-destination', packDirectory]);
    const vanjsDir = resolveWorkspaceModule('vanjs-core').local;
    const vanjsDirRoot = resolveWorkspaceModule('vanjs-core').root;
    let vanjsPackage;
    try {
      vanjsPackage = runNpm([
        'pack', '--json', '--pack-destination', packDirectory, vanjsDir,
      ]);
    } catch {
      vanjsPackage = runNpm([
        'pack', '--json', '--pack-destination', packDirectory, vanjsDirRoot,
      ]);
    }
    const readerTarball = path.join(packDirectory, JSON.parse(readerPackage.stdout)[0].filename);
    const vanjsTarball = path.join(packDirectory, JSON.parse(vanjsPackage.stdout)[0].filename);
    await writeFile(path.join(consumerDirectory, 'package.json'), JSON.stringify({
      name: 'reader-runtime-consumer-check', version: '1.0.0', private: true, type: 'module',
    }));
    runNpm([
      'install', '--offline', '--ignore-scripts', '--no-audit', '--no-fund',
      '--package-lock=false', readerTarball, vanjsTarball,
    ], consumerDirectory);
    await writeFile(path.join(consumerDirectory, 'consumer.ts'), `
import {
  createReader,
  createRuntimeHost,
  parseReaderConfig,
  READER_PROTOCOL_VERSION,
  type CreateReaderOptions,
  type CreateRuntimeHostOptions,
  type NativeReaderTransport,
  type ReaderCommandName,
  type ReaderConfig,
  type ReaderEventName,
  type ReaderHost,
  type ReaderRequestName,
} from '@nekori/reader';

declare const root: HTMLElement;
declare const config: ReaderConfig;
const transport: NativeReaderTransport = { postMessage: (_message) => {}, onmessage: null };
const options: CreateRuntimeHostOptions = { root, config, transport };
const host: ReaderHost = createRuntimeHost(options);
const readerOptions: CreateReaderOptions = { root, config, host };
const reader = createReader(readerOptions);
const protocolVersion: number = READER_PROTOCOL_VERSION;
const command: ReaderCommandName = 'layout.move';
const event: ReaderEventName = 'reader.error';
const request: ReaderRequestName = 'media.requestProtectedPlayback';
const parsed: ReaderConfig = parseReaderConfig(config);
const browserApi = window.nekori;
void reader.ready;
void protocolVersion;
void command;
void event;
void request;
void parsed;
void browserApi;
`);
    await writeFile(path.join(consumerDirectory, 'tsconfig.json'), JSON.stringify({
      compilerOptions: {
        strict: true, noEmit: true, target: 'ES2022', module: 'ESNext', moduleResolution: 'Bundler',
        lib: ['ES2022', 'DOM', 'DOM.Iterable'], skipLibCheck: true,
      },
      files: ['consumer.ts'],
    }));
    const typescriptLocal = resolveWorkspaceModule('typescript/bin/tsc').local;
    const typescriptRoot = resolveWorkspaceModule('typescript/bin/tsc').root;
    let tscPath = typescriptLocal;
    try {
      const { statSync } = await import('node:fs');
      statSync(tscPath);
    } catch {
      tscPath = typescriptRoot;
    }
    const typecheck = spawnSync(process.execPath, [
      tscPath, '-p', 'tsconfig.json',
    ], { cwd: consumerDirectory, encoding: 'utf8' });
    assert.equal(typecheck.status, 0, `${typecheck.stdout}\n${typecheck.stderr}`);

    const runtime = spawnSync(process.execPath, ['--input-type=module', '-e', `
      import * as reader from '@nekori/reader';
      for (const name of ['createReader', 'createRuntimeHost', 'parseReaderConfig', 'READER_PROTOCOL_VERSION']) {
        if (!(name in reader)) throw new Error('Missing packed export: ' + name);
      }
    `], { cwd: consumerDirectory, encoding: 'utf8' });
    assert.equal(runtime.status, 0, `${runtime.stdout}\n${runtime.stderr}`);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

function runNpm(args, cwd = packageDir) {
  const result = spawnSync('npm', args, { cwd, encoding: 'utf8' });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  return result;
}
