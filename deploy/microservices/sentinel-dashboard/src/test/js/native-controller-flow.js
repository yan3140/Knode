'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const project = path.resolve(__dirname, '../../..');
const upstream = path.join(project, 'target/upstream/Sentinel-d75a6687b82711f8611ef91f6a56e993d24f57c5/sentinel-dashboard/src/main/webapp/resources/app');
const factories = {}, services = {}, controllers = {};
const chain = {
  factory: (name, spec) => { factories[name] = spec; return chain; },
  service: (name, spec) => { services[name] = spec; return chain; },
  controller: (name, spec) => { controllers[name] = spec; return chain; },
  config: () => chain,
  filter: () => chain
};
const alerts = [];
const angular = {module: () => chain, copy: x => JSON.parse(JSON.stringify(x))};
const context = {angular, alert: msg => alerts.push(msg), console};
vm.runInNewContext(fs.readFileSync(path.join(project, 'target/upstream/Sentinel-d75a6687b82711f8611ef91f6a56e993d24f57c5/sentinel-dashboard/src/main/webapp/resources/dist/js/app.js'), 'utf8'), context);
function load(name) { vm.runInNewContext(fs.readFileSync(path.join(upstream, name), 'utf8'), context); }
[
 'scripts/controllers/flow_v1.js', 'scripts/controllers/degrade.js',
 'scripts/controllers/gateway/flow.js', 'scripts/controllers/gateway/api.js'
].forEach(load);
const cases = [
 {service: 'FlowServiceV1', controller: 'FlowControllerV1', list: 'getMachineRules', add: 'addNewRule',
  save: 'saveRule', edit: 'editRule', remove: 'deleteRule', current: 'currentRule', rows: 'rules',
  row: {id: 42, app: 'demo', ip: '127.0.0.1', port: 8719, resource: 'r', limitApp: 'default', grade: 1, count: 4, strategy: 0, controlBehavior: 0},
  fill: x => {x.resource = 'r'; x.count = 4;}},
 {service: 'DegradeService', controller: 'DegradeCtl', list: 'getMachineRules', add: 'addNewRule',
  save: 'saveRule', edit: 'editRule', remove: 'deleteRule', current: 'currentRule', rows: 'rules',
  row: {id: 42, app: 'demo', ip: '127.0.0.1', port: 8719, resource: 'r', limitApp: 'default', grade: 1, count: 0.5, timeWindow: 10, minRequestAmount: 5, statIntervalMs: 1000},
  fill: x => {x.resource = 'r'; x.count = 0.5; x.timeWindow = 10; x.slowRatioThreshold = 0.5;}},
 {service: 'GatewayFlowService', controller: 'GatewayFlowCtl', list: 'getMachineRules', add: 'addNewRule',
  save: 'saveRule', edit: 'editRule', remove: 'deleteRule', current: 'currentRule', rows: 'rules',
  row: {id: 42, app: 'demo', ip: '127.0.0.1', port: 8719, resource: 'route', resourceMode: 0, grade: 1, count: 4, interval: 1, intervalUnit: 0, controlBehavior: 0},
  fill: x => {x.resource = 'route'; x.count = 4;}},
 {service: 'GatewayApiService', controller: 'GatewayApiCtl', list: 'getApis', add: 'addNewApi',
  save: 'saveApi', edit: 'editApi', remove: 'deleteApi', current: 'currentApi', rows: 'apis',
  row: {id: 42, app: 'demo', ip: '127.0.0.1', port: 8719, apiName: 'group', predicateItems: [{pattern: '/a/**', matchStrategy: 1}]},
  fill: x => {x.apiName = 'new-group'; x.predicateItems[0].pattern = '/a/**';}}
];
for (const item of cases) {
 const version = '0123456789abcdef0123456789abcdef';
 const interceptor = factories.NacosVersionInterceptor[factories.NacosVersionInterceptor.length - 1]({reject: x => x}, {alert: msg => alerts.push(msg)}, () => Promise.resolve(), {});
 let reject = false, requests = [], dialogs = [];
 function http(config) {
  config = interceptor.request(config); requests.push(config);
  const isRead = config.method === 'GET';
  if (!isRead) {
   assert.strictEqual(config.params.app, 'demo', item.service);
   assert.strictEqual(config.headers['X-Nacos-Version'], version, item.service);
  }
  const payload = isRead ? {code: 0, data: [JSON.parse(JSON.stringify(item.row))]} :
    reject ? {code: 409, msg: 'version changed'} : {code: 0, data: {}};
  interceptor.response({config, data: payload, headers: name =>
   name === 'X-Nacos-Version' ? version : name === 'X-Nacos-Application' ? 'demo' : null});
  return {success: callback => { callback(payload); return this; }};
 }
 function instantiate(spec, deps) { return spec[spec.length - 1](...spec.slice(0, -1).map(name => deps[name])); }
 const service = {};
 services[item.service][services[item.service].length-1].call(service, http);
 const apiService = {};
 services.GatewayApiService[services.GatewayApiService.length-1].call(apiService, http);
 const scope = {$watch: () => {}};
 const machine = {getAppMachines: () => ({success: cb => cb({code: 0, data: [{healthy: true, ip: '127.0.0.1', port: 8719}]})})};
 const dialog = {open: () => { const d = {closed: false, close() {this.closed = true;}}; dialogs.push(d); return d; }};
 instantiate(controllers[item.controller], {$scope: scope, $stateParams: {app: 'demo'},
  [item.service]: service, GatewayApiService: item.service === 'GatewayApiService' ? service : apiService,
  ngDialog: dialog, MachineService: machine});
 scope[item.list]();
 assert(scope[item.rows].length, item.service + ' did not load');
 scope[item.add](); item.fill(scope[item.current]); reject = true;
 const failed = dialogs[dialogs.length-1], alertCount = alerts.length;
 scope[item.save]();
 assert(!failed.closed, item.service + ' closed dialog after failure');
 assert(alerts.length > alertCount, item.service + ' hid publish failure');
 reject = false; scope[item.save](); assert(failed.closed, item.service + ' create');
 scope[item.edit](scope[item.rows][0]); scope[item.save](); assert(dialogs[dialogs.length-1].closed, item.service + ' edit');
 scope[item.remove](scope[item.rows][0]); scope.confirm(); assert(dialogs[dialogs.length-1].closed, item.service + ' delete');
 assert(requests.filter(x => x.method !== 'GET').length >= 4, item.service);
}
console.log('Official controllers and services: four page CRUD paths and rejection UI passed');
