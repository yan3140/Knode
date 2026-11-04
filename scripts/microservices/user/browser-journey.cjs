/* Opt-in isolated CDP journey. Never print headers, bodies, cookies or tokens. */
'use strict'
const assert = require('assert')
const [debugPort, blogPort, adminPort] = process.argv.slice(2)
assert(process.env.KNODE_IT_BROWSER_CHILD === 'true', 'explicit isolated launcher required')
for (const p of [debugPort, blogPort, adminPort]) assert(/^[1-9][0-9]{0,4}$/.test(p) && Number(p) >= 1024 && Number(p) <= 65535, 'invalid port')
assert(new Set([debugPort, blogPort, adminPort]).size === 3, 'ports must differ')
assert(typeof WebSocket === 'function', 'Node.js 22 or later is required')
const host = '127.0.0.1'
const blog = 'http://' + host + ':' + blogPort
const admin = 'http://' + host + ':' + adminPort
const events = [], otherFailureStatuses = {}
const responseDetails = new Map(), requestMethods = new Map(), pending = new Map()
const checks = { me: false, checkedKeys: false, userRelation: false, roleRelation: false, rootParent: false }
let next = 0, socket, stage = 'connect'
function send(method, params = {}) {
  const id = ++next
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
function event(message) {
  if (message.id) {
    const callback = pending.get(message.id)
    if (callback) {
      pending.delete(message.id)
      if (message.error) callback.reject(new Error('CDP operation failed'))
      else callback.resolve(message.result || {})
    }
    return
  }
  const p = message.params || {}
  if (message.method === 'Network.requestWillBeSent') {
    const request = p.request
    let url
    try { url = new URL(request.url) } catch (_) { return }
    if (url.hostname !== host || ![blogPort, adminPort].includes(url.port)) return
    const path = url.pathname
    if (!(path.startsWith('/api/blog/') || path.startsWith('/dev-api/'))) return
    requestMethods.set(p.requestId, request.method)
    if (request.method === 'PUT' && ['/dev-api/api/admin/system/user','/dev-api/api/admin/system/role','/dev-api/api/admin/system/menu'].includes(path)) {
      try {
        const data = JSON.parse(request.postData)
        if (path.endsWith('/user')) checks.userRelation = data.id === '9007199254740993' && Array.isArray(data.roleIds) && data.roleIds.includes('9007199254740995')
        if (path.endsWith('/role')) checks.roleRelation = data.id === '9007199254740995' && Array.isArray(data.menuIds) && data.menuIds.includes('9007199254740999') && data.menuIds.includes('9007199254741001')
        if (path.endsWith('/menu')) checks.rootParent = data.id === '9007199254740997' && data.parentId === '0'
      } catch (_) { /* A malformed request fails the corresponding check. */ }
    }
  } else if (message.method === 'Network.responseReceived') {
    let url
    try { url = new URL(p.response.url) } catch (_) { return }
    if (url.hostname !== host || ![blogPort, adminPort].includes(url.port)) return
    const path = url.pathname
    if (!(path.startsWith('/api/blog/') || path.startsWith('/dev-api/'))) return
    const method = requestMethods.get(p.requestId) || '?'
    const status = p.response.status
    const safe = /^\/(api\/blog\/(auth\/(login|logout)|user\/userInfo)|dev-api\/api\/admin\/(auth\/(login|me|routers|logout)|system\/(user|role|menu)(\/(list|listAllRole|treeselect|roleMenuTreeselect\/9007199254740995|900719925474099[357]))?))$/.test(path)
    if (safe) events.push({ method, path, status })
    else if (status >= 400 && status <= 599) otherFailureStatuses[status] = (otherFailureStatuses[status] || 0) + 1
    if (path === '/dev-api/api/admin/auth/me' || path === '/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995') responseDetails.set(p.requestId, path)
  } else if (message.method === 'Network.loadingFinished' && responseDetails.has(p.requestId)) {
    const path = responseDetails.get(p.requestId)
    responseDetails.delete(p.requestId)
    send('Network.getResponseBody', { requestId: p.requestId }).then(result => {
      const raw = result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body
      const body = JSON.parse(raw).data
      if (path === '/dev-api/api/admin/auth/me') checks.me = !!body && body.user.nickName === 'IT Administrator' && !body.user.avatar
      else checks.checkedKeys = !!body && Array.isArray(body.checkedKeys) && body.checkedKeys.includes('9007199254740999')
    }).catch(() => {})
  }
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails) throw new Error('page operation failed')
  return result.result.value
}
async function until(expression, label, seconds = 60) {
  const end = Date.now() + seconds * 1000
  while (Date.now() < end) {
    try { if (await evaluate(expression)) return } catch (_) {}
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(label)
}
async function perform(expression, label) { await until('Boolean(' + expression + ')', label) }
async function navigate(url) { await send('Page.navigate', { url }) }
function setInput(selector, value) {
  return "(() => { const e=document.querySelector(" + JSON.stringify(selector) + "); if(!e) return false; Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + JSON.stringify(value) + "); e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return true })()"
}
function click(selector) {
  return "(() => {const e=document.querySelector(" + JSON.stringify(selector) + ");if(!e)return false;e.click();return true})()"
}
function editRow(text) {
  return "(() => {const r=[...document.querySelectorAll('.el-table__body .el-table__row')].find(x=>x.textContent.includes(" + JSON.stringify(text) + "));const b=r&&r.querySelector('.el-icon-edit');if(!b)return false;b.closest('button').click();return true})()"
}
function has(path, method, minimum = 1) { return events.filter(x => x.path === path && x.method === method && x.status === 200).length >= minimum }
async function main() {
  let page
  for (let i = 0; i < 80 && !page; i++) {
    try {
      const list = await (await fetch('http://' + host + ':' + debugPort + '/json/list')).json()
      page = list.find(item => item.type === 'page' && item.webSocketDebuggerUrl)
    } catch (_) {}
    if (!page) await new Promise(resolve => setTimeout(resolve, 250))
  }
  assert(page, 'no isolated page')
  socket = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('CDP connection failed')) })
  socket.onmessage = message => event(JSON.parse(message.data))
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  stage = 'blog-login'
  await navigate(blog + '/#/Login?login=1&redirect=%2FUserInfo')
  await perform(setInput('.login-form input[type=text]', 'it-user'), stage)
  await perform(setInput('.login-form input[type=password]', 'synthetic-test-password'), stage)
  await perform(click('.login-form .submit-button'), stage)
  await until("document.querySelector('.account-form input[placeholder]') && document.querySelector('.account-form input[placeholder]').value === 'IT User'", 'blog profile read')
  stage = 'blog-update'
  await perform(setInput('.account-form input[placeholder]', 'IT Blog Updated'), stage)
  await perform(click('.account-actions .el-button--primary'), stage)
  await until("document.querySelector('.el-message--success') !== null", stage)
  await send('Page.reload')
  await until("document.querySelector('.account-form input[placeholder]') && document.querySelector('.account-form input[placeholder]').value === 'IT Blog Updated'", 'blog profile reread')
  stage = 'blog-logout'
  await perform("(() => {const a=[...document.querySelectorAll('.haslogin-info a')].find(x=>x.textContent.includes('\u9000\u51fa\u767b\u5f55'));if(!a)return false;a.click();return true})()",stage)
  await perform(click('.el-message-box__btns .el-button--primary'), stage)
  await until("!document.cookie.includes('user-Token=') && !localStorage.getItem('userInfo')", stage)
  stage = 'admin-login'
  await navigate(admin + '/#/login')
  await perform(setInput('.login-form input[name=userName]', 'it-admin'), stage)
  await perform(setInput('.login-form input[name=password]', 'synthetic-test-password'), stage)
  await perform(click('.login-button'), stage)
  await until("document.querySelector('.navbar .user-copy strong') && document.querySelector('.navbar .user-copy strong').textContent.trim()==='IT Administrator'", 'admin identity')
  await until("document.querySelector('.navbar .user-avatar') && document.querySelector('.navbar .user-avatar').complete && document.querySelector('.navbar .user-avatar').naturalWidth>0", 'admin default avatar')
  await until("document.body.textContent.includes('IT System')", 'dynamic menu')
  stage = 'admin-logout'
  await perform(click('.avatar-wrapper'), stage)
  await perform("(() => {const e=[...document.querySelectorAll('.user-dropdown .el-dropdown-menu__item')].find(x=>x.textContent.includes('\\u9000\\u51fa\\u767b\\u5f55'));if(!e)return false;e.click();return true})()", stage)
  await perform(click('.el-message-box__btns .el-button--primary'), stage)
  await until("location.hash.startsWith('#/login') && !document.cookie.includes('Admin-Token=') && !!document.querySelector('.login-form')", stage)
  stage = 'admin-relogin'
  await perform(setInput('.login-form input[name=userName]', 'it-admin'), stage)
  await perform(setInput('.login-form input[name=password]', 'synthetic-test-password'), stage)
  await perform(click('.login-button'), stage)
  await until("document.querySelector('.navbar .user-copy strong') && document.querySelector('.navbar .user-copy strong').textContent.trim()==='IT Administrator'", stage)
  await until("document.body.textContent.includes('IT System')", stage)
  stage = 'admin-user-edit'
  await navigate(admin + '/#/system/user')
  await perform(editRow('9007199254740993'), stage)
  await until("document.querySelector('.el-dialog input[placeholder]') !== null", stage)
  await until("document.querySelector('.el-dialog').textContent.includes('IT target role')", 'user role selected')
  await perform(setInput('.el-dialog input[placeholder]', 'IT Target Updated'), stage)
  await perform(click('.el-dialog .dialog-footer .el-button--primary'), stage)
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  stage = 'admin-role-edit'
  await navigate(admin + '/#/system/role')
  await perform(editRow('9007199254740995'), stage)
  await until("document.querySelector('.el-dialog .el-tree') !== null", stage)
  await perform("(() => {const x=[...document.querySelectorAll('.el-dialog .el-checkbox')].find(e=>e.textContent.includes('\u5c55\u5f00'));if(!x)return false;x.click();return true})()",stage)
  await until("(() => {const n=[...document.querySelectorAll('.el-tree-node__content')].find(x=>x.textContent.includes('IT First Permission'));return !!n && !!n.querySelector('.el-checkbox.is-checked')})()", 'role checkedKeys shown')
  await perform("(() => {const n=[...document.querySelectorAll('.el-tree-node__content')].find(x=>x.textContent.includes('IT Second Permission'));const b=n&&n.querySelector('.el-checkbox');if(!b)return false;if(!b.classList.contains('is-checked'))b.click();return true})()",stage)
  await perform(setInput('.el-dialog input[placeholder]', 'IT Target Role Updated'), stage)
  await perform(click('.el-dialog .dialog-footer .el-button--primary'), stage)
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  stage = 'admin-menu-edit'
  await navigate(admin + '/#/system/menu')
  await perform(editRow('IT System'), stage)
  await until("document.querySelector('.el-dialog input[placeholder=\"\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0\"]') !== null", stage)
  await perform(setInput('.el-dialog input[placeholder="\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0"]', 'IT System Updated'), stage)
  await perform(click('.el-dialog .dialog-footer .el-button--primary'), stage)
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  await new Promise(resolve => setTimeout(resolve, 1000))
  stage = 'network-assertions'
  for (const [path, method, count] of [
    ['/api/blog/auth/login','POST',1], ['/api/blog/user/userInfo','GET',2],
    ['/api/blog/user/userInfo','PUT',1], ['/api/blog/auth/logout','POST',1],
    ['/dev-api/api/admin/auth/login','POST',2], ['/dev-api/api/admin/auth/logout','POST',1], ['/dev-api/api/admin/auth/me','GET',2],
    ['/dev-api/api/admin/auth/routers','GET',2], ['/dev-api/api/admin/system/user/9007199254740993','GET',1],
    ['/dev-api/api/admin/system/user','PUT',1], ['/dev-api/api/admin/system/role/9007199254740995','GET',1],
    ['/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995','GET',1],
    ['/dev-api/api/admin/system/role','PUT',1], ['/dev-api/api/admin/system/menu/9007199254740997','GET',1],
    ['/dev-api/api/admin/system/menu','PUT',1]
  ]) assert(has(path,method,count), 'missing identity request')
  assert(Object.values(checks).every(Boolean), 'identity response or request mismatch')
  process.stdout.write(JSON.stringify({ version: 1, checks, events, otherFailureStatuses }))
  socket.close()
}
main().catch(() => {
  process.stdout.write(JSON.stringify({ failed: true, stage }))
  if (socket) socket.close()
  process.exitCode = 1
})
