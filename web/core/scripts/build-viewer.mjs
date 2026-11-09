import { build } from 'esbuild';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(root, 'viewer-dist');
const wasm = join(root, 'dist/livery-web');
if (!existsSync(join(wasm, 'livery-livery-web-core.wasm'))) throw new Error('Build the neutral Wasm module and npm package before bundling the viewer');
const version = process.env.LIVERY_VIEWER_VERSION ?? 'development';
if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(version)) throw new Error('Invalid viewer version');
rmSync(output, { recursive: true, force: true }); mkdirSync(join(output, 'livery-web'), { recursive: true });
await build({ entryPoints: [join(root, 'dist/viewer.js')], bundle: true, format: 'esm', target: 'es2022', legalComments: 'linked', outfile: join(output, 'viewer.js') });
// The Kotlin bridge imports js-joda. Bundle that dependency too, so the host needs
// no import map, npm resolver or third-party network request at runtime.
await build({ entryPoints: [join(wasm, 'livery-livery-web-core.mjs')], bundle: true, format: 'esm', target: 'es2022', platform: 'browser', external: ['node:*'], legalComments: 'linked', outfile: join(output, 'livery-web/livery-livery-web-core.mjs') });
cpSync(join(wasm, 'livery-livery-web-core.wasm'), join(output, 'livery-web/livery-livery-web-core.wasm'));
cpSync(join(root, 'viewer.css'), join(output, 'viewer.css'));
cpSync(join(root, 'vendor/PROVENANCE.md'), join(output, 'PROVENANCE.md'));
cpSync(join(root, '../../LICENSE'), join(output, 'LICENSE'));
cpSync(join(root, 'HOSTING.md'), join(output, 'HOSTING.md'));
const files = {};
function inventory(dir, prefix = '') {
  for (const name of readdirSync(dir).sort()) {
    const relative = prefix + name, path = join(dir, name);
    if (statSync(path).isDirectory()) inventory(path, relative + '/');
    else { const bytes = readFileSync(path); files[relative] = { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }; }
  }
}
inventory(output);
writeFileSync(join(output, 'bundle.json'), JSON.stringify({ schemaVersion: 1, version, entry: 'viewer.js', stylesheet: 'viewer.css', files }, null, 2) + '\n');
if (process.argv.includes('--zip')) {
  const distributions = join(root, 'build'); mkdirSync(distributions, { recursive: true });
  const archive = join(distributions, `livery-preview-viewer-${version}.zip`);
  rmSync(archive, { force: true });
  execFileSync('zip', ['-q', '-r', '-X', archive, '.'], { cwd: output });
  console.log(archive);
}
console.log(`${Object.keys(files).length} viewer assets -> ${output}`);
