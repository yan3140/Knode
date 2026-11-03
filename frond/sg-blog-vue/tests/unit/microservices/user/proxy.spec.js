/** @jest-environment node */
const http = require('http')
const proxyMiddleware = require('http-proxy-middleware')

function listen(server) {
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
}
function close(server) { return new Promise(resolve => server.close(resolve)) }
function request(port, path) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path, method: 'POST', headers: { token: 'fixture-token', connection: 'close' } }, res => {
      let body = ''
      res.on('data', chunk => { body += chunk })
      res.on('end', () => resolve(JSON.parse(body)))
    })
    req.on('error', reject)
    req.end('{}')
  })
}
test('development proxy forwards the exact identity path and token to the selected loopback Gateway', async () => {
  const gateway = http.createServer((req, res) => {
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({ path: req.url, token: req.headers.token }))
  })
  const gatewayPort = await listen(gateway)
  const old = process.env.KNODE_GATEWAY_TARGET
  process.env.KNODE_GATEWAY_TARGET = 'http://127.0.0.1:' + gatewayPort
  jest.resetModules()
  const config = require(process.cwd() + '/config/index.js')
  const middleware = proxyMiddleware('/api', config.dev.proxyTable['/api'])
  const frontend = http.createServer((req, res) => middleware(req, res, () => { res.statusCode = 404; res.end() }))
  try {
    const frontendPort = await listen(frontend)
    expect(await request(frontendPort, '/api/blog/auth/login')).toEqual({ path: '/api/blog/auth/login', token: 'fixture-token' })
  } finally {
    await close(frontend)
    await close(gateway)
    if (old === undefined) delete process.env.KNODE_GATEWAY_TARGET
    else process.env.KNODE_GATEWAY_TARGET = old
  }
})
