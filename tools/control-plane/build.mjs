import { readFileSync, writeFileSync } from 'node:fs';
const root = new URL('./', import.meta.url);
const source = readFileSync(new URL('src/worker.js', root), 'utf8');
const html = readFileSync(new URL('dashboard.html', root), 'utf8');
const output = source.replace("const DASHBOARD = '__DASHBOARD_HTML__';", `const DASHBOARD = ${JSON.stringify(html)};`);
writeFileSync(new URL('worker.js', root), output);
console.log('Built single-module worker.js');
