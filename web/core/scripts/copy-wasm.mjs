import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const from = fileURLToPath(new URL('../../../livery-web-core/build/web-dist/', import.meta.url));
const to = fileURLToPath(new URL('../dist/livery-web/', import.meta.url));
if (!existsSync(from + '/livery-livery-web-core.mjs')) throw new Error('Run ./gradlew :livery-web-core:webDist first');
mkdirSync(to, { recursive: true });
cpSync(from, to, { recursive: true });
