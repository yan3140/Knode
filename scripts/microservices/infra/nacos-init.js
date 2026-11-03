'use strict';

// This tool is deliberately limited to fresh loopback Nacos 3.0.3 test namespaces.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '../../..');
const files = Object.freeze({
  'knode-gateway.yaml': 'deploy/microservices/nacos/knode-gateway.yaml',
  'knode-gateway.gw-flow.json': 'deploy/microservices/nacos/knode-gateway.gw-flow.json',
  'knode-gateway.gw-api-group.json': 'deploy/microservices/nacos/knode-gateway.gw-api-group.json',
  'knode-user.relay.json': 'deploy/microservices/nacos/knode-user.relay.json',
  'knode-user.flow.json': 'deploy/microservices/nacos/service.flow.json',
  'knode-user.degrade.json': 'deploy/microservices/nacos/service.degrade.json'
});
const ABSENT_MD5 = 'knode-no-existing-md5-v1'; // Intentionally not a possible 32-hex Nacos content MD5.
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
class SafeError extends Error {}
function fail(message) { throw new SafeError(message); }
// Only fixed non-secret Gateway routes, empty rule sets and the bounded User field may be published.
function validateTemplate(id, content) {
  if (id === 'knode-gateway.yaml' &&
      sha(Buffer.from(content.replace(/\r\n/g, '\n'), 'utf8')) !==
        '133e51601b49c19a14f5f6c1eeed600ba5078015503fefa8f71fbe59e1ab597b')
    fail('Gateway template differs from approved non-secret routes');
  if (id === 'knode-user.relay.json' &&
      !/^[ \t\r\n]*\{[ \t\r\n]*"sessionEventRelayBatchSize"[ \t\r\n]*:[ \t\r\n]*(?:[1-9]|[1-9][0-9]|100)[ \t\r\n]*\}[ \t\r\n]*(?![^])/.test(content))
    fail('invalid User relay template');
  if ((id === 'knode-user.flow.json' || id === 'knode-user.degrade.json' ||
       id === 'knode-gateway.gw-flow.json' || id === 'knode-gateway.gw-api-group.json') &&
      !/^[ \t\r\n]*\[[ \t\r\n]*\][ \t\r\n]*(?![^])/.test(content))
    fail('rule initialization requires an empty array');
}
function parseArgs(args) {
  const options = {};
  for (const arg of args) {
    if (arg === '--apply') { if (options.apply) fail('duplicate apply'); options.apply = true; continue; }
    const match = /^--(server|namespace|group|data-ids)=(.+)$/.exec(arg);
    if (!match || Object.hasOwn(options, match[1])) fail('invalid or duplicate argument');
    options[match[1]] = match[2];
  }
  for (const key of ['server', 'namespace', 'group', 'data-ids']) if (!options[key]) fail('missing ' + key);
  const address = new URL(options.server);
  if (address.protocol !== 'http:' || address.hostname !== '127.0.0.1' || !address.port ||
      address.username || address.password || address.pathname !== '/' || address.search || address.hash ||
      Number(address.port) < 1024 || Number(address.port) > 64534 ||
      [3306, 6379, 8080, 8848, 9848, 9849, 18089, 18090, 18091].includes(Number(address.port))) fail('server must be an explicit non-default loopback port');
  const m = /^verify-([a-f0-9]{12})$/.exec(options.namespace);
  if (!m || options.group !== 'VERIFY_' + m[1].toUpperCase()) fail('invalid verify namespace or group');
  const ids = options['data-ids'].split(',');
  if (ids.length < 2 || ids.length > Object.keys(files).length || new Set(ids).size !== ids.length ||
      ids.some(id => !Object.hasOwn(files, id)) || !ids.some(id => id.startsWith('knode-gateway.')) ||
      !ids.some(id => id.startsWith('knode-user.'))) fail('explicit Gateway and User Data IDs required');
  const entries = ids.sort().map(id => {
    const filename = path.join(root, files[id]);
    let current = root;
    for (const part of path.relative(root, filename).split(path.sep)) {
      current = path.join(current, part);
      if (fs.lstatSync(current).isSymbolicLink()) fail('unsafe template path');
    }
    if (!fs.statSync(filename).isFile()) fail('invalid template');
    const content = fs.readFileSync(filename);
    if (content.length === 0 || content.length > 32768) fail('invalid template size');
    const text = content.toString('utf8');
    validateTemplate(id, text);
    return { id, template: files[id], sha256: sha(content), content: text };
  });
  return { server: address.origin, namespace: options.namespace, group: options.group, entries, apply: !!options.apply };
}
function publicPlan(c) {
  return { schemaVersion: 1, server: c.server, namespace: c.namespace, group: c.group,
    entries: c.entries.map(({id, template, sha256}) => ({id, template, sha256})) };
}
function syncWrite(file, value, exclusive = false) {
  const tmp = exclusive ? file : file + '.next';
  const fd = fs.openSync(tmp, 'wx', 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
  if (!exclusive) fs.renameSync(tmp, file);
}
function ensureSafeDir(p) {
  const relative = path.relative(root, p);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) fail('unsafe manifest directory');
  let current = root;
  for (const part of relative.split(path.sep)) {
    current = path.join(current, part);
    try { fs.mkdirSync(current); } catch (error) { if (error.code !== 'EEXIST') throw error; }
    const info = fs.lstatSync(current);
    if (info.isSymbolicLink() || !info.isDirectory()) fail('unsafe manifest directory');
  }
}
function request(c, method, target, parameters, token, extra = {}) {
  return new Promise((resolve, reject) => {
    const encoded = new URLSearchParams(parameters || {}).toString();
    const uri = new URL(target + (method === 'GET' && encoded ? '?' + encoded : ''), c.server);
    const headers = {...extra};
    if (token) headers.authorization = 'Bearer ' + token;
    if (method !== 'GET') { headers['content-type'] = 'application/x-www-form-urlencoded'; headers['content-length'] = Buffer.byteLength(encoded); }
    const req = http.request(uri, {method, headers, timeout: 5000}, res => {
      let bytes = 0; const parts = [];
      res.on('data', chunk => { bytes += chunk.length; if (bytes > 131072) { req.destroy(); return; } parts.push(chunk); });
      res.on('end', () => {
        let json;
        try { json = JSON.parse(Buffer.concat(parts).toString('utf8')); }
        catch { return reject(new SafeError('invalid Nacos response')); }
        resolve({status: res.statusCode, json});
      });
      res.on('error', () => reject(new SafeError('Nacos response failed')));
    });
    req.on('timeout', () => req.destroy());
    req.on('error', () => reject(new SafeError('Nacos request failed')));
    req.end(method === 'GET' ? undefined : encoded);
  });
}
function ok(result) {
  if (result.status !== 200 || result.json.code !== 0) fail('Nacos rejected request');
  return result.json.data;
}
async function getConfig(c, item, token) {
  const r = await request(c, 'GET', '/nacos/v3/admin/cs/config',
    {namespaceId: c.namespace, groupName: c.group, dataId: item.id}, token);
  if (r.status === 404 && r.json.code !== 0) return null;
  const data = ok(r);
  if (!data || typeof data.content !== 'string') fail('invalid config response');
  return data.content;
}
async function execute(c, token) {
  if (!token || token.length > 16384 || /[\r\n]/.test(token)) fail('missing or invalid NACOS_INIT_ACCESS_TOKEN');
  if (process.env.NACOS_INIT_EXCLUSIVE_INSTANCE !== 'confirm')
    fail('explicit exclusive isolated instance confirmation required');
  const base = path.join(root, 'deploy/microservices/nacos/target/initialization');
  ensureSafeDir(base);
  const serverLock = path.join(base, 'server-' + new URL(c.server).port + '.lock');
  let serverFd;
  try { serverFd = fs.openSync(serverLock, 'wx', 0o600); }
  catch (error) { if (error.code === 'EEXIST') fail('another initializer or stale server lock exists'); throw error; }
  let fd, lock;
  try {
    const dir = path.join(base, c.namespace); ensureSafeDir(dir);
    lock = path.join(dir, 'lock');
    fd = fs.openSync(lock, 'wx', 0o600);
    const manifestPath = path.join(dir, 'manifest.json');
    const plan = publicPlan(c);
    let old;
    let owner = 'knode-local-init-' + crypto.randomBytes(16).toString('hex');
    if (fs.existsSync(manifestPath)) {
      if (fs.lstatSync(manifestPath).isSymbolicLink()) fail('unsafe manifest');
      old = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
      if (old.state !== 'complete' || !/^knode-local-init-[a-f0-9]{32}$/.test(old.owner) ||
          JSON.stringify(old.plan) !== JSON.stringify(plan)) fail('existing run ownership or plan cannot be confirmed');
      owner = old.owner;
    } else {
      syncWrite(manifestPath, {state: 'planned', plan, owner, processId: process.pid, createdAt: new Date().toISOString()}, true);
    }
    const state = ok(await request(c, 'GET', '/nacos/v3/admin/core/state', {}, null));
    if (!state || state.version !== '3.0.3' || String(state.startup_mode).toLowerCase() !== 'standalone' ||
        String(state.server_port) !== new URL(c.server).port || String(state.auth_enabled).toLowerCase() !== 'true')
      fail('Nacos 3.0.3 standalone with authentication required');
    const anonymous = await request(c, 'GET', '/nacos/v3/admin/core/namespace/check', {namespaceId: c.namespace}, null);
    if (![401, 403].includes(anonymous.status)) fail('Nacos admin endpoint is not authentication protected');
    const present = ok(await request(c, 'GET', '/nacos/v3/admin/core/namespace/check', {namespaceId: c.namespace}, token));
    if (present !== 0 && present !== 1) fail('unsupported Nacos namespace response; expected 3.0.3');
    if (old) {
      if (present !== 1) fail('owned namespace is missing');
      const info = ok(await request(c, 'GET', '/nacos/v3/admin/core/namespace', {namespaceId: c.namespace}, token));
      if (!info || info.namespace !== c.namespace || info.namespaceDesc !== owner) fail('namespace ownership cannot be confirmed');
      for (const item of c.entries) if (await getConfig(c, item, token) !== item.content) fail('existing config differs or is missing');
      return {result: 'no-op', manifest: manifestPath, plan};
    }
    if (present !== 0) fail('existing namespace is not owned by this run');
    const created = ok(await request(c, 'POST', '/nacos/v3/admin/core/namespace',
      {namespaceId: c.namespace, namespaceName: c.namespace, namespaceDesc: owner}, token));
    if (created !== true) fail('namespace creation was not confirmed');
    syncWrite(manifestPath, {state: 'owned-incomplete', plan, owner, processId: process.pid, createdAt: new Date().toISOString()});
    const info = ok(await request(c, 'GET', '/nacos/v3/admin/core/namespace', {namespaceId: c.namespace}, token));
    if (!info || info.namespace !== c.namespace || info.namespaceDesc !== owner) fail('new namespace ownership cannot be confirmed');
    for (const item of c.entries) if (await getConfig(c, item, token) !== null) fail('new namespace unexpectedly contains a Data ID');
    for (const item of c.entries) {
      // The sentinel rejects an existing normal MD5, but is not a substitute for exclusive ownership:
      // Nacos 3.0.3 can report success for both concurrent creates of the same missing Data ID.
      if (ok(await request(c, 'POST', '/nacos/v3/admin/cs/config',
          {namespaceId: c.namespace, groupName: c.group, dataId: item.id, content: item.content,
           type: item.id.endsWith('.yaml') ? 'yaml' : 'json'}, token, {casMd5: ABSENT_MD5})) !== true)
        fail('conditional config creation was not confirmed');
      if (await getConfig(c, item, token) !== item.content) fail('created config verification failed');
    }
    syncWrite(manifestPath, {state: 'complete', plan, owner, processId: process.pid, createdAt: new Date().toISOString()});
    return {result: 'created', manifest: manifestPath, plan};
  } finally {
    if (fd !== undefined) { fs.closeSync(fd); fs.unlinkSync(lock); }
    fs.closeSync(serverFd); fs.unlinkSync(serverLock);
  }
}
async function main() {
  const c = parseArgs(process.argv.slice(2));
  if (!c.apply) return console.log(JSON.stringify({result: 'plan', plan: publicPlan(c)}, null, 2));
  console.log(JSON.stringify(await execute(c, process.env.NACOS_INIT_ACCESS_TOKEN), null, 2));
}
if (require.main === module) main().catch(error => { console.error('initialization refused: ' + (error instanceof SafeError ? error.message : 'I/O or protocol failure')); process.exitCode = 1; });
module.exports = {validateTemplate};
