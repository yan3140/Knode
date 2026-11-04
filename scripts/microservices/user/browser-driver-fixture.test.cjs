// Isolated DOM and CDP-event fixtures; these are not User/Gateway integration evidence.
'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { JSDOM } = require('../../../frond/sg-blog-vue/node_modules/jsdom')
const source = fs.readFileSync(path.join(__dirname, 'browser-journey.cjs'), 'utf8')
const part = (start, end) => {
  const a = source.indexOf(start), b = source.indexOf(end, a)
  assert(a >= 0 && b > a)
  return source.slice(a, b)
}

test('admin helpers target the visible dialog and the correct logout confirmation', () => {
  const helpers = vm.runInNewContext(part('const visibleDialog =', 'function has(') +
    '\n;({dialogQuery,setInput,click,confirmLogout,editRow})')
  const dom = new JSDOM('<!doctype html><body>' +
    '<div class="el-dialog__wrapper" style="display:none"><input placeholder="\u8bf7\u8f93\u5165\u7528\u6237\u6635\u79f0" value="hidden"><div class="dialog-footer"><button class="el-button--primary"></button></div></div>' +
    '<div class="el-dialog__wrapper"><input placeholder="\u9009\u62e9\u4e0a\u7ea7\u83dc\u5355" value="unrelated">' +
    '<input placeholder="\u8bf7\u8f93\u5165\u7528\u6237\u6635\u79f0" value="visible">' +
    '<input placeholder="\u8bf7\u8f93\u5165\u89d2\u8272\u540d\u79f0" value="role">' +
    '<input placeholder="\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0" value="menu"><div class="dialog-footer"><button class="el-button--primary"></button></div></div>' +
    '<div class="el-message-box__wrapper" style="display:none"><div class="el-message-box__btns"><button class="el-button--primary">\u786e\u5b9a</button></div></div>' +
    '<div class="el-message-box__wrapper"><div class="el-message-box__btns"><button class="el-button--primary">\u786e\u5b9a\u9000\u51fa</button></div></div>' +
    '<table class="el-table__body"><tbody><tr class="el-table__row"><td>9007199254740993</td><td><button><i class="el-icon-edit"></i></button></td></tr></tbody></table>' +
    '</body>', { runScripts: 'outside-only' })
  const { document } = dom.window
  const inputs = document.querySelectorAll('.el-dialog__wrapper input')
  assert.equal(dom.window.eval(helpers.setInput('input[placeholder="\u8bf7\u8f93\u5165\u7528\u6237\u6635\u79f0"]', 'updated', true)), true)
  assert.equal(dom.window.eval(helpers.setInput('input[placeholder="\u8bf7\u8f93\u5165\u89d2\u8272\u540d\u79f0"]', 'role-updated', true)), true)
  assert.equal(dom.window.eval(helpers.setInput('input[placeholder="\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0"]', 'menu-updated', true)), true)
  assert.equal(inputs[0].value, 'hidden')
  assert.equal(inputs[1].value, 'unrelated')
  assert.equal(inputs[2].value, 'updated')
  assert.equal(inputs[3].value, 'role-updated')
  assert.equal(inputs[4].value, 'menu-updated')
  let clicked = 0
  document.querySelectorAll('.dialog-footer button')[1].addEventListener('click', () => clicked++)
  assert.equal(dom.window.eval(helpers.click('.dialog-footer .el-button--primary', true)), true)
  assert.equal(clicked, 1)
  const confirm = document.querySelectorAll('.el-message-box__wrapper button')[1]
  confirm.addEventListener('click', () => clicked++)
  assert.equal(dom.window.eval(helpers.confirmLogout('\u786e\u5b9a')), false)
  assert.equal(dom.window.eval(helpers.confirmLogout('\u786e\u5b9a\u9000\u51fa')), true)
  assert.equal(clicked, 2)
  document.querySelector('.el-table__row button').addEventListener('click', () => clicked++)
  assert.equal(dom.window.eval(helpers.editRow('9007199254740993')), true)
  assert.equal(clicked, 3)
  dom.window.close()
})

test('profile response keeps only the bounded request, status and numeric business code', async () => {
  const context = { URL, Set, Map, Number, Buffer, setTimeout, clearTimeout, blogPort: '12345', adminPort: '12346' }
  const api = vm.runInNewContext(part("const host = '127.0.0.1'", 'async function evaluate(') +
    '\n;({event, setSocket: s => { socket = s }, state: () => ({requests,events,profilePutCode,profilePutComplete})})', context)
  api.setSocket({ send: data => {
    const request = JSON.parse(data)
    queueMicrotask(() => api.event({ id: request.id, result: { body: JSON.stringify({ code: 515, msg: 'do-not-save', data: { token: 'do-not-save' } }) } }))
  } })
  const url = 'http://127.0.0.1:12345/api/blog/user/userInfo?ignored=secret'
  api.event({ method: 'Network.requestWillBeSent', params: { requestId: '1', request: { url, method: 'PUT', headers: { token: 'do-not-save' } } } })
  api.event({ method: 'Network.responseReceived', params: { requestId: '1', response: { url, status: 200 } } })
  api.event({ method: 'Network.loadingFinished', params: { requestId: '1' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  const state = api.state()
  assert.equal(state.profilePutComplete, true)
  assert.equal(state.profilePutCode, 515)
  const summary = JSON.stringify(state)
  assert(!summary.includes('do-not-save'))
  assert(!summary.includes('ignored=secret'))
  assert.equal(state.requests[0].path, '/api/blog/user/userInfo')
  assert.equal(state.events[0].status, 200)
})
