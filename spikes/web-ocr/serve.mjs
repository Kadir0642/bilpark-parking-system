// Yalnızca bu klasörü yerelde sunan küçük statik sunucu (127.0.0.1). Kamera için localhost güvenli bağlam sayılır.
// Kullanım: node serve.mjs [port]   ->   http://localhost:8765/index.html
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json',
  '.wasm': 'application/wasm', '.onnx': 'application/octet-stream', '.txt': 'text/plain; charset=utf-8',
  '.jpg': 'image/jpeg', '.png': 'image/png', '.gz': 'application/gzip', '.css': 'text/css',
};

export function startServer(port = 8765) {
  const server = createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      const file = normalize(join(ROOT, path === '/' ? '/index.html' : path));
      if (!file.startsWith(ROOT)) throw new Error('dışarı çıkış yok');
      if (!(await stat(file)).isFile()) throw new Error('dosya değil');
      res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(await readFile(file));
    } catch {
      res.writeHead(404);
      res.end('yok');
    }
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === normalize(process.argv[1])) {
  const port = Number(process.argv[2] || 8765);
  await startServer(port);
  console.log(`http://localhost:${port}/index.html  (durdurmak için Ctrl+C)`);
}
