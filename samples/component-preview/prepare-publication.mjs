#!/usr/bin/env node
// Prepare public fixture bytes using the real publisher, ContractBinder and RC renderer.
import * as fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { validatePublication } from '../../scripts/install-component-preview.mjs';

const repo = fileURLToPath(new URL('../../', import.meta.url));
function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: repo, stdio: ['ignore', 'inherit', 'inherit'] });
    child.once('error', reject);
    child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`livery failed (${signal ?? code})`)));
  });
}

export async function preparePublication(cli, output, revision = 'fixture-1') {
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(revision)) throw new Error('Revision must be a lowercase publication identifier');
  const destination = path.resolve(output);
  const exists = await fs.lstat(destination).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
  if (exists) throw new Error('Output already exists; reuse the prepared bundle or choose a new output and revision');
  await fs.mkdir(path.dirname(destination), { recursive: true });
  const work = await fs.mkdtemp(path.join(path.dirname(destination), '.prepare-preview-'));
  const bundle = path.join(work, 'bundle');
  try {
    await fs.mkdir(bundle);
    const descriptor = JSON.parse(await fs.readFile(path.join(repo, 'samples/component-preview/preview.json'), 'utf8'));
    descriptor.revision = revision;
    const dataFile = path.join(work, 'example-data.json');
    await fs.writeFile(dataFile, JSON.stringify(descriptor.data));
    await fs.writeFile(path.join(bundle, 'preview.json'), `${JSON.stringify(descriptor, null, 2)}\n`);
    const source = path.join(repo, 'livery-capture/build/samples/two-way');
    await run(cli, ['build', source, path.join(bundle, 'catalog'), '--inspect']);
    await run(cli, ['play', path.join(source, 'designs/quantity-stepper/in-app.rc'),
      '--contract', path.join(source, 'contracts/quantity.json'), '--data', dataFile,
      '--catalog', source, '--surface', 'compose', '--size', '360x80', '--density', '2',
      '--out', path.join(bundle, 'thumbnail.png')]);
    await validatePublication(bundle);
    await fs.rename(bundle, destination);
    return destination;
  } finally { await fs.rm(work, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [cli, output, revision, ...rest] = process.argv.slice(2);
  if (!cli || !output || rest.length) {
    console.error('Usage: node samples/component-preview/prepare-publication.mjs <livery-cli> <bundle-dir> [revision]');
    process.exitCode = 2;
  } else {
    try { console.log(`Prepared ${await preparePublication(cli, output, revision)}`); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
  }
}
