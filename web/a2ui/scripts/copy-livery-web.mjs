// Copies livery-web's Wasm module (`./gradlew :livery-web:webDist`) into dist/livery-web/.
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const from = join(root, '../../livery-web/build/web-dist');
if (!existsSync(join(from, 'livery-livery-web.mjs'))) {
  console.error(`no ${from}: run ./gradlew :livery-web:webDist first`);
  process.exit(1);
}
mkdirSync(join(root, 'dist/livery-web'), { recursive: true });
cpSync(from, join(root, 'dist/livery-web'), { recursive: true });
