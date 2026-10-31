'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const root = path.resolve(__dirname, '../../..');
const source = fs.readFileSync(path.join(root, 'target/upstream/Sentinel-d75a6687b82711f8611ef91f6a56e993d24f57c5/sentinel-dashboard/src/main/webapp/resources/dist/js/app.js'), 'utf8');
const moduleSource = fs.readFileSync(path.join(root, 'overlay/webapp/resources/app/scripts/app.js'), 'utf8');
assert(source.startsWith(moduleSource), 'served bundle must include the maintained interceptor module');
assert(!source.slice(moduleSource.length).includes('angular.module(\"sentinelDashboardApp\",['), 'must not reinitialize the module');
const factories = {};
const chain = {factory: (name, value) => { factories[name] = value; return chain; }, config: () => chain, filter: () => chain, service: () => chain};
vm.runInNewContext(source, {angular: {module: () => chain}});
assert(factories.NacosVersionInterceptor);
const alerts = [];
const interceptor = factories.NacosVersionInterceptor[factories.NacosVersionInterceptor.length - 1]({reject: value => ({rejected: value})}, {alert: msg => alerts.push(msg)}, () => Promise.resolve(), {});
const cases = [
  ['/v1/flow/rules', '/v1/flow/rule', '/v1/flow/save.json', '/v1/flow/delete.json', 'PUT', 'DELETE'],
  ['/degrade/rules.json', '/degrade/rule', '/degrade/rule/42', '/degrade/rule/42', 'PUT', 'DELETE'],
  ['/gateway/flow/list.json', '/gateway/flow/new.json', '/gateway/flow/save.json', '/gateway/flow/delete.json', 'POST', 'POST'],
  ['/gateway/api/list.json', '/gateway/api/new.json', '/gateway/api/save.json', '/gateway/api/delete.json', 'POST', 'POST']
];
for (const [list, create, update, del, updateMethod, deleteMethod] of cases) {
  const version = '0123456789abcdef0123456789abcdef';
  interceptor.response({config: {url: 'http://localhost' + list, method: 'GET'}, data: {code: 0, data: [{id: 42}]},
    headers: name => name === 'X-Nacos-Version' ? version : name === 'X-Nacos-Application' ? 'demo' : null});
  const added = interceptor.request({url: create, method: 'POST', data: {app: 'demo'}});
  assert.equal(added.params.app, 'demo');
  assert.equal(added.headers['X-Nacos-Version'], version);
  const changed = interceptor.request({url: update, method: updateMethod, params: {id: 42}});
  assert.equal(changed.params.app, 'demo');
  assert.equal(changed.headers['X-Nacos-Version'], version);
  const deleted = interceptor.request({url: del, method: deleteMethod, params: {id: 42}});
  assert.equal(deleted.params.app, 'demo');
  assert.equal(deleted.headers['X-Nacos-Version'], version);
  const unknown = interceptor.request({url: update, method: updateMethod, data: {app: 'demo', id: 99}});
  assert.equal(unknown.headers['X-Nacos-Version'], undefined);
}
interceptor.response({config: {url: '/v1/flow/rules', method: 'GET'}, data: {code: 503, msg: 'unavailable'}, headers: () => null});
assert.equal(alerts.length, 1);
const failure = {config: {url: '/gateway/api/new.json'}, status: 503};
assert.strictEqual(interceptor.responseError(failure).rejected, failure);
assert.equal(alerts.length, 2);
console.log('Native Angular interceptor: four rule types, read/create/update/delete, unknown id, and failures passed');

(async function testReadAfterPublish() {
  const next = 'ffffffffffffffffffffffffffffffff';
  const old = '00000000000000000000000000000000';
  const notices = [];
  const win = {alert: msg => notices.push(msg), document: {getElementsByTagName: () => [{href: 'http://local/'}]}};
  const response = (config, version) => ({config, data: {code: 0, data: []},
    headers: name => name === 'X-Nacos-Version' ? version : name === 'X-Nacos-Application' ? 'demo' : null});
  let attempts = 0, fresh = true, delayed;
  const injector = {get: () => config => {
    attempts++;
    return Promise.resolve(delayed.response(response(config, fresh ? next : old)));
  }};
  delayed = factories.NacosVersionInterceptor[factories.NacosVersionInterceptor.length - 1](
    {reject: value => Promise.reject(value)}, win, () => Promise.resolve(), injector);
  delayed.response({config: {url: '/v1/flow/rule', method: 'POST'}, data: {code: 0},
    headers: name => name === 'X-Nacos-Version' ? next : name === 'X-Nacos-Application' ? 'demo' : null});
  const loaded = await delayed.response(response({url: 'http://local//v1/flow/rules', method: 'GET'}, old));
  assert.equal(loaded.headers('X-Nacos-Version'), next);
  assert.equal(attempts, 1);
  fresh = false; attempts = 0;
  delayed.response({config: {url: '/v1/flow/rule', method: 'POST'}, data: {code: 0},
    headers: name => name === 'X-Nacos-Version' ? next : name === 'X-Nacos-Application' ? 'demo' : null});
  await assert.rejects(() => delayed.response(response({url: 'http://local//v1/flow/rules', method: 'GET'}, old)));
  assert.equal(attempts, 10);
  assert.equal(notices.length, 1);
  console.log('Read-after-publish: bounded retry and unresolved propagation warning passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
