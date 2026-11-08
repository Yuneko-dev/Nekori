import { readFile } from 'node:fs/promises';
import path from 'node:path';

// ponytail: hoisted-aware package resolve. Upgrade if: Bun emits per-package node_modules again.
export function resolvePackageFile(toolDir, repositoryDir, relativePath) {
  const local = path.resolve(toolDir, 'node_modules', relativePath);
  const root = path.resolve(repositoryDir, 'node_modules', relativePath);
  return { local, root };
}
export async function readPackageFile(toolDir, repositoryDir, relativePath) {
  for (const candidate of [
    resolvePackageFile(toolDir, repositoryDir, relativePath).local,
    resolvePackageFile(toolDir, repositoryDir, relativePath).root,
  ]) {
    try {
      return await readFile(candidate, 'utf8');
    } catch {}
  }
  throw new Error(`Cannot read package file: ${relativePath}`);
}
