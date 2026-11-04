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
const events = [], requests = [], otherFailureStatuses = {}
const safePaths = new Set([
  '/api/blog/auth/login', '/api/blog/auth/logout', '/api/blog/user/userInfo',
  '/dev-api/api/admin/auth/login', '/dev-api/api/admin/auth/logout', '/dev-api/api/admin/auth/me', '/dev-api/api/admin/auth/routers',
  '/dev-api/api/admin/system/user', '/dev-api/api/admin/system/user/list', '/dev-api/api/admin/system/user/9007199254740993',
  '/dev-api/api/admin/system/role', '/dev-api/api/admin/system/role/list', '/dev-api/api/admin/system/role/listAllRole',
  '/dev-api/api/admin/system/role/9007199254740995', '/dev-api/api/admin/system/menu', '/dev-api/api/admin/system/menu/list',
  '/dev-api/api/admin/system/menu/treeselect', '/dev-api/api/admin/system/menu/9007199254740997',
  '/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995'
])
const allowedMethods = new Set(['GET', 'POST', 'PUT', 'DELETE'])
const responseDetails = new Map(), requestMethods = new Map(), pending = new Map()
let profilePutCode = null, profilePutComplete = false
function remember(list, fact) { if (list.length === 120) list.shift(); list.push(fact) }
const checks = { me: false, checkedKeys: false, userRelation: false, roleRelation: false, rootParent: false }
let next = 0, socket, stage = 'connect', step = 'connect-debugger'
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
    if (safePaths.has(path) && allowedMethods.has(request.method)) remember(requests, { method: request.method, path })
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
    const safe = safePaths.has(path) && allowedMethods.has(method) && Number.isInteger(status) && status >= 100 && status <= 599
    if (safe) remember(events, { method, path, status })
    else if (Number.isInteger(status) && status >= 400 && status <= 599) otherFailureStatuses[status] = (otherFailureStatuses[status] || 0) + 1
    if (path === '/dev-api/api/admin/auth/me' || path === '/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995' ||
        (path === '/api/blog/user/userInfo' && method === 'PUT')) responseDetails.set(p.requestId, { path, method })
  } else if (message.method === 'Network.loadingFinished' && responseDetails.has(p.requestId)) {
    const { path, method } = responseDetails.get(p.requestId)
    responseDetails.delete(p.requestId)
    send('Network.getResponseBody', { requestId: p.requestId }).then(result => {
      const raw = result.base64Encoded ? Buffer.from(result.body, 'base64').toString('utf8') : result.body
      const parsed = JSON.parse(raw)
      if (path === '/api/blog/user/userInfo' && method === 'PUT') {
        if (Number.isInteger(parsed.code) && parsed.code >= 0 && parsed.code <= 9999) profilePutCode = parsed.code
      } else if (path === '/dev-api/api/admin/auth/me') {
        const body = parsed.data
        checks.me = !!body && !!body.user && body.user.nickName === 'IT Administrator' && !body.user.avatar
      } else {
        const body = parsed.data
        checks.checkedKeys = !!body && Array.isArray(body.checkedKeys) && body.checkedKeys.includes('9007199254740999')
      }
    }).catch(() => {}).finally(() => {
      if (path === '/api/blog/user/userInfo' && method === 'PUT') profilePutComplete = true
    })
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
async function awaitProfilePut() {
  const end = Date.now() + 60000
  while (Date.now() < end) {
    const response = events.find(x => x.method === 'PUT' && x.path === '/api/blog/user/userInfo')
    if (response && response.status !== 200) throw new Error('profile update HTTP failure')
    if (response && profilePutComplete) {
      if (profilePutCode !== 200) throw new Error('profile update business failure')
      return
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('profile update response unavailable')
}
async function navigate(url) { await send('Page.navigate', { url }) }
const visibleDialog = "([...document.querySelectorAll('.el-dialog__wrapper')].find(x=>getComputedStyle(x).display!=='none'))"
function dialogQuery(selector) { return '(' + visibleDialog + '?.querySelector(' + JSON.stringify(selector) + '))' }
function setInput(selector, value, inDialog = false) {
  const expression = inDialog ? dialogQuery(selector) : 'document.querySelector(' + JSON.stringify(selector) + ')'
  return "(() => { const e=" + expression + "; if(!e) return false; Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + JSON.stringify(value) + "); e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return true })()"
}
function click(selector, inDialog = false) {
  const expression = inDialog ? dialogQuery(selector) : 'document.querySelector(' + JSON.stringify(selector) + ')'
  return "(() => {const e=" + expression + ";if(!e)return false;e.click();return true})()"
}
function confirmLogout(label) {
  return "(() => {const box=[...document.querySelectorAll('.el-message-box__wrapper')].find(x=>getComputedStyle(x).display!=='none');const b=box&&box.querySelector('.el-message-box__btns .el-button--primary');if(!b || b.textContent.trim()!==" + JSON.stringify(label) + ")return false;b.click();return true})()"
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
  step = 'connect-page'
  socket = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('CDP connection failed')) })
  socket.onmessage = message => event(JSON.parse(message.data))
  step = 'connect-enable'
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable')
  stage = 'blog-login'
  step = 'blog-login-navigate'
  await navigate(blog + '/#/Login?login=1&redirect=%2FUserInfo')
  step = 'blog-login-username'
  await perform(setInput('.login-form input[type=text]', 'it-user'), stage)
  step = 'blog-login-password'
  await perform(setInput('.login-form input[type=password]', 'synthetic-test-password'), stage)
  step = 'blog-login-submit'
  await perform(click('.login-form .submit-button'), stage)
  step = 'blog-login-profile'
  await until("document.querySelector('.account-form input[placeholder]') && document.querySelector('.account-form input[placeholder]').value === 'IT User'", 'blog profile read')
  stage = 'blog-update'
  step = 'blog-update-input'
  await perform(setInput('.account-form input[placeholder]', 'IT Blog Updated'), stage)
  step = 'blog-update-click'
  await perform(click('.account-actions .el-button--primary'), stage)
  step = 'blog-update-put-response'
  await awaitProfilePut()
  step = 'blog-update-success-message'
  await until("[...document.querySelectorAll('.el-message')].some(e=>getComputedStyle(e).display!=='none' && e.textContent.trim()==='\\u8d44\\u6599\\u5df2\\u4fdd\\u5b58' && !!e.querySelector('.el-message__img'))", stage)
  step = 'blog-update-reload'
  await send('Page.reload')
  step = 'blog-update-reread'
  await until("document.querySelector('.account-form input[placeholder]') && document.querySelector('.account-form input[placeholder]').value === 'IT Blog Updated'", 'blog profile reread')
  stage = 'blog-logout'
  step = 'blog-logout-open'
  await perform("(() => {const a=[...document.querySelectorAll('.haslogin-info a')].find(x=>x.textContent.includes('\u9000\u51fa\u767b\u5f55'));if(!a)return false;a.click();return true})()",stage)
  step = 'blog-logout-confirm'
  await perform(confirmLogout('\u786e\u5b9a'), stage)
  step = 'blog-logout-clear'
  await until("!document.cookie.includes('user-Token=') && !localStorage.getItem('userInfo')", stage)
  stage = 'admin-login'
  step = 'admin-login-navigate'
  await navigate(admin + '/#/login')
  step = 'admin-login-username'
  await perform(setInput('.login-form input[name=userName]', 'it-admin'), stage)
  step = 'admin-login-password'
  await perform(setInput('.login-form input[name=password]', 'synthetic-test-password'), stage)
  step = 'admin-login-submit'
  await perform(click('.login-button'), stage)
  step = 'admin-login-identity'
  await until("document.querySelector('.navbar .user-copy strong') && document.querySelector('.navbar .user-copy strong').textContent.trim()==='IT Administrator'", 'admin identity')
  step = 'admin-login-avatar'
  await until("document.querySelector('.navbar .user-avatar') && document.querySelector('.navbar .user-avatar').complete && document.querySelector('.navbar .user-avatar').naturalWidth>0", 'admin default avatar')
  step = 'admin-login-menus'
  await until("document.body.textContent.includes('IT System')", 'dynamic menu')
  stage = 'admin-logout'
  step = 'admin-logout-open'
  await perform(click('.avatar-wrapper'), stage)
  step = 'admin-logout-choice'
  await perform("(() => {const e=[...document.querySelectorAll('.user-dropdown .el-dropdown-menu__item')].find(x=>x.textContent.includes('\\u9000\\u51fa\\u767b\\u5f55'));if(!e)return false;e.click();return true})()", stage)
  step = 'admin-logout-confirm'
  await perform(confirmLogout('\u786e\u5b9a\u9000\u51fa'), stage)
  step = 'admin-logout-clear'
  await until("location.hash.startsWith('#/login') && !document.cookie.includes('Admin-Token=') && !!document.querySelector('.login-form')", stage)
  stage = 'admin-relogin'
  step = 'admin-relogin-username'
  await perform(setInput('.login-form input[name=userName]', 'it-admin'), stage)
  step = 'admin-relogin-password'
  await perform(setInput('.login-form input[name=password]', 'synthetic-test-password'), stage)
  step = 'admin-relogin-submit'
  await perform(click('.login-button'), stage)
  step = 'admin-relogin-identity'
  await until("document.querySelector('.navbar .user-copy strong') && document.querySelector('.navbar .user-copy strong').textContent.trim()==='IT Administrator'", stage)
  step = 'admin-relogin-menus'
  await until("document.body.textContent.includes('IT System')", stage)
  stage = 'admin-user-edit'
  step = 'admin-user-edit-navigate'
  await navigate(admin + '/#/system/user')
  step = 'admin-user-edit-row'
  await perform(editRow('9007199254740993'), stage)
  step = 'admin-user-edit-form'
  await until('!!' + dialogQuery('input[placeholder="\u8bf7\u8f93\u5165\u7528\u6237\u6635\u79f0"]'), stage)
  step = 'admin-user-edit-role'
  await until(visibleDialog + "?.textContent.includes('IT target role')", 'user role selected')
  step = 'admin-user-edit-nickname'
  await perform(setInput('input[placeholder="\u8bf7\u8f93\u5165\u7528\u6237\u6635\u79f0"]', 'IT Target Updated', true), stage)
  step = 'admin-user-edit-save'
  await perform(click('.dialog-footer .el-button--primary', true), stage)
  step = 'admin-user-edit-closed'
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  stage = 'admin-role-edit'
  step = 'admin-role-edit-navigate'
  await navigate(admin + '/#/system/role')
  step = 'admin-role-edit-row'
  await perform(editRow('9007199254740995'), stage)
  step = 'admin-role-edit-tree'
  await until('!!' + dialogQuery('.el-tree'), stage)
  step = 'admin-role-edit-expand'
  await perform("(() => {const d=" + visibleDialog + ";const x=d&&[...d.querySelectorAll('.el-checkbox')].find(e=>e.textContent.includes('\u5c55\u5f00'));if(!x)return false;x.click();return true})()",stage)
  step = 'admin-role-edit-checked'
  await until("(() => {const d=" + visibleDialog + ";const n=d&&[...d.querySelectorAll('.el-tree-node__content')].find(x=>x.textContent.includes('IT First Permission'));return !!n && !!n.querySelector('.el-checkbox.is-checked')})()", 'role checkedKeys shown')
  step = 'admin-role-edit-select'
  await perform("(() => {const d=" + visibleDialog + ";const n=d&&[...d.querySelectorAll('.el-tree-node__content')].find(x=>x.textContent.includes('IT Second Permission'));const b=n&&n.querySelector('.el-checkbox');if(!b)return false;if(!b.classList.contains('is-checked'))b.click();return true})()",stage)
  step = 'admin-role-edit-name'
  await perform(setInput('input[placeholder="\u8bf7\u8f93\u5165\u89d2\u8272\u540d\u79f0"]', 'IT Target Role Updated', true), stage)
  step = 'admin-role-edit-save'
  await perform(click('.dialog-footer .el-button--primary', true), stage)
  step = 'admin-role-edit-closed'
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  stage = 'admin-menu-edit'
  step = 'admin-menu-edit-navigate'
  await navigate(admin + '/#/system/menu')
  step = 'admin-menu-edit-row'
  await perform(editRow('IT System'), stage)
  step = 'admin-menu-edit-form'
  await until('!!' + dialogQuery('input[placeholder="\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0"]'), stage)
  step = 'admin-menu-edit-name'
  await perform(setInput('input[placeholder="\u8bf7\u8f93\u5165\u83dc\u5355\u540d\u79f0"]', 'IT System Updated', true), stage)
  step = 'admin-menu-edit-save'
  await perform(click('.dialog-footer .el-button--primary', true), stage)
  step = 'admin-menu-edit-closed'
  await until("![...document.querySelectorAll('.el-dialog__wrapper')].some(x=>getComputedStyle(x).display!=='none')", stage)
  step = 'admin-menu-edit-settle'
  await new Promise(resolve => setTimeout(resolve, 1000))
  stage = 'network-assertions'
  step = 'network-assertions-check'
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
main().catch(async () => {
  const deadline = Date.now() + 1000
  while (events.some(x => x.method === 'PUT' && x.path === '/api/blog/user/userInfo') && !profilePutComplete && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  process.stdout.write(JSON.stringify({ failed: true, stage, step, requests, events, profilePutCode, checks }))
  if (socket) socket.close()
  process.exitCode = 1
})
