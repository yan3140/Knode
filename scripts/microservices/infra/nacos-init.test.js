'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../..');
const cli = path.join(__dirname, 'nacos-init.js');
const id = () => crypto.randomBytes(6).toString('hex');
const ids = 'knode-gateway.gw-flow.json,knode-user.flow.json';
const dir = ns => path.join(root, 'deploy/microservices/nacos/target/initialization', ns);
function argumentsFor(port, run) {
  return ['--server=http://127.0.0.1:' + port, '--namespace=verify-' + run,
    '--group=VERIFY_' + run.toUpperCase(), '--data-ids=' + ids];
}
function invoke(args, secret = 'fixture-token', exclusive = 'confirm') {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {env: {...process.env, NACOS_INIT_ACCESS_TOKEN: secret, NACOS_INIT_EXCLUSIVE_INSTANCE: exclusive}});
    let stdout = '', stderr = '';
    child.stdout.on('data', x => { stdout += x; }); child.stderr.on('data', x => { stderr += x; });
    child.on('error', reject);
    child.on('exit', code => { if (secret) { assert(!stdout.includes(secret)); assert(!stderr.includes(secret)); } resolve({code, stdout, stderr}); });
  });
}
function listen(server) {
  return new Promise((resolve, reject) => {
    const port = 20000 + crypto.randomInt(20000);
    server.once('error', reject); server.listen(port, '127.0.0.1', () => resolve(port));
  });
}
test('plan and invalid requests perform no writes or network calls', async () => {
  const run=id(), ns='verify-'+run;
  const plan=await invoke(argumentsFor(24567,run),'');
  assert.equal(plan.code,0); assert.equal(JSON.parse(plan.stdout).result,'plan');
  assert.equal(fs.existsSync(dir(ns)),false);
  const invalid=await invoke(argumentsFor(8848,run).concat('--apply'));
  assert.equal(invalid.code,1); assert.equal(fs.existsSync(dir(ns)),false);
  const noToken=await invoke(argumentsFor(24567,run).concat('--apply'),'');
  assert.equal(noToken.code,1); assert.equal(fs.existsSync(dir(ns)),false);
  const noConfirmation=await invoke(argumentsFor(24567,run).concat('--apply'),'fixture-token','');
  assert.equal(noConfirmation.code,1); assert.equal(fs.existsSync(dir(ns)),false);
  for (const replacement of ['--server=http://192.0.2.1:23456','--server=http://127.0.0.1:18089','--server=http://127.0.0.1:64535',
      '--namespace=public','--group=DEFAULT_GROUP','--data-ids=knode-gateway.yaml,unknown.json']) {
    const invalidArgs=argumentsFor(24567,run);
    const key=replacement.split('=')[0];invalidArgs[invalidArgs.findIndex(x=>x.startsWith(key+'='))]=replacement;
    assert.equal((await invoke(invalidArgs.concat('--apply'))).code,1);
    assert.equal(fs.existsSync(dir(ns)),false);
  }
});
test('manifest precedes network, conditional create, no-op and drift refusal', async () => {
  const run=id(), ns='verify-'+run, group='VERIFY_'+run.toUpperCase();
  let created=false, owner='', port=0, writes=0, manifestSeen=false;
  const configs=new Map(); const token='fixture-token';
  const server=http.createServer((req,res) => {
    const manifest=path.join(dir(ns),'manifest.json');
    manifestSeen ||= fs.existsSync(manifest) && !fs.readFileSync(manifest,'utf8').includes(token);
    const url=new URL(req.url,'http://127.0.0.1');
    const respond=(code,data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({code,data}));};
    const body=[];req.on('data',x=>body.push(x));req.on('end',()=>{
      const params=req.method==='GET'?url.searchParams:new URLSearchParams(Buffer.concat(body).toString());
      if(url.pathname.endsWith('/core/state')) return respond(0,{version:'3.0.3',server_port:String(port),startup_mode:'standalone',auth_enabled:'true'});
      if(req.headers.authorization!=='Bearer '+token) return respond(403,null,403);
      if(url.pathname.endsWith('/namespace/check')) return respond(0,created?1:0);
      if(url.pathname.endsWith('/core/namespace')) {
        if(req.method==='GET') return respond(0,{namespace:ns,namespaceDesc:owner});
        created=true;owner=params.get('namespaceDesc');return respond(0,true);
      }
      if(url.pathname.endsWith('/cs/config')) {
        assert.equal(params.get('namespaceId'),ns);assert.equal(params.get('groupName'),group);
        const key=params.get('dataId');
        if(req.method==='GET') return configs.has(key)?respond(0,{content:configs.get(key)}):respond(404,null,404);
        assert.equal(req.headers.casmd5,'knode-no-existing-md5-v1');
        if(configs.has(key)) return respond(409,null,409);
        configs.set(key,params.get('content'));writes++;return respond(0,true);
      }
      respond(404,null,404);
    });
  });
  port=await listen(server);
  try {
    const args=argumentsFor(port,run);
    const lock=path.join(root,'deploy/microservices/nacos/target/initialization','server-'+port+'.lock');
    fs.mkdirSync(path.dirname(lock),{recursive:true});
    fs.closeSync(fs.openSync(lock,'wx'));
    try {
      const blocked=await invoke(args.concat('--apply'));
      assert.equal(blocked.code,1);assert.equal(manifestSeen,false);assert.equal(writes,0);
      assert.equal(fs.existsSync(dir(ns)),false);
    } finally { fs.unlinkSync(lock); }
    const applied=await invoke(args.concat('--apply'));
    assert.equal(applied.code,0,applied.stderr);assert.equal(JSON.parse(applied.stdout).result,'created');
    assert.equal(manifestSeen,true);assert.equal(writes,2);
    const repeat=await invoke(args.concat('--apply'));
    assert.equal(repeat.code,0,repeat.stderr);assert.equal(JSON.parse(repeat.stdout).result,'no-op');assert.equal(writes,2);
    owner='unexpected-owner';
    const changedOwner=await invoke(args.concat('--apply'));
    assert.equal(changedOwner.code,1);assert.equal(writes,2);
    owner=JSON.parse(fs.readFileSync(path.join(dir(ns),'manifest.json'),'utf8')).owner;
    configs.set('knode-user.flow.json','different');
    const drift=await invoke(args.concat('--apply'));
    assert.equal(drift.code,1);assert.equal(writes,2);assert.equal(configs.get('knode-user.flow.json'),'different');
  } finally {await new Promise(resolve=>server.close(resolve));}
});

test('an existing namespace without a completed ownership manifest cannot be written', async () => {
  const run=id(), ns='verify-'+run;
  let port=0, writes=0;
  const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://127.0.0.1');
    const respond=(code,data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({code,data}));};
    if(req.method!=='GET') { writes++;return respond(0,true); }
    if(url.pathname.endsWith('/core/state')) return respond(0,{version:'3.0.3',server_port:String(port),startup_mode:'standalone',auth_enabled:'true'});
    if(!req.headers.authorization) return respond(403,null,403);
    if(url.pathname.endsWith('/namespace/check')) return respond(0,1);
    respond(404,null,404);
  });
  port=await listen(server);
  try {
    const result=await invoke(argumentsFor(port,run).concat('--apply'));
    assert.equal(result.code,1);assert.match(result.stderr,/existing namespace/);assert.equal(writes,0);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dir(ns),'manifest.json'),'utf8')).state,'planned');
  } finally {await new Promise(resolve=>server.close(resolve));}
});
