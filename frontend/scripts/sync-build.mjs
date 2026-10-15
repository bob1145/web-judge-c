import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const frontend = fileURLToPath(new URL('..', import.meta.url));
const resources = path.resolve(frontend, '../src/main/resources');
const distribution = path.join(frontend, 'dist');
const manifest = JSON.parse(await readFile(path.join(distribution, '.vite/manifest.json'), 'utf8'));
const entry = manifest['index.html'];
if (!entry?.file || !entry.css?.length) throw new Error('Missing frontend entry or stylesheet');
const shell = await readFile(path.join(distribution, 'index.html'), 'utf8');
await mkdir(path.join(resources, 'static/js/app'), { recursive: true });
await cp(distribution, path.join(resources, 'static/js/app'), { recursive: true });
for (const template of ['index.html', 'auth.html']) {
  await writeFile(path.join(resources, 'templates', template), shell);
}
console.log('React build synchronized to Spring Boot resources.');
