jest.mock('element-ui', () => ({
  Notification: { error: jest.fn() }, MessageBox: { confirm: jest.fn(() => Promise.resolve()) }, Message: jest.fn()
}))
jest.mock('@/router', () => ({ currentRoute: { path: '/', fullPath: '/' }, replace: jest.fn() }))
jest.mock('@/store', () => ({ dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('@/utils/auth', () => ({ getToken: jest.fn(() => 'admin-token') }))

const service = require('@/utils/request').default
const users = require('@/api/system/user')
const roles = require('@/api/system/role')
const menus = require('@/api/menu')
const large = '9007199254740993'
const maximum = '9223372036854775807'

test('actual identity API wrappers serialize positive int64 IDs as decimal strings', async () => {
  const calls = []
  service.defaults.adapter = config => {
    calls.push({ url: config.url, method: config.method, body: config.data ? JSON.parse(config.data) : null, token: config.headers.token })
    return Promise.resolve({ data: { code: 200, data: {} }, status: 200, headers: {}, config })
  }
  await users.getUser(maximum)
  await users.updateUser({ id: maximum, roleIds: [large] })
  await users.resetUserPwd(maximum, 'test-only')
  await users.changeUserStatus(large, '1')
  await roles.updateRole({ id: large, menuIds: [large, maximum] })
  await roles.changeRoleStatus(maximum, '0')
  await menus.roleMenuTreeselect(maximum)
  await menus.addMenu({ parentId: '0' })
  await menus.updateMenu({ id: maximum, parentId: large })
  expect(calls[0].url).toBe('/system/user/' + maximum)
  expect(calls[1].body).toEqual({ id: maximum, roleIds: [large] })
  expect(calls[2].body.userId).toBe(maximum)
  expect(calls[3].body.userId).toBe(large)
  expect(calls[4].body).toEqual({ id: large, menuIds: [large, maximum] })
  expect(calls[5].body.roleId).toBe(maximum)
  expect(calls[6].url).toBe('/system/menu/roleMenuTreeselect/' + maximum)
  expect(calls[7].body.parentId).toBe('0')
  expect(calls[8].body).toEqual({ id: maximum, parentId: large })
  calls.forEach(call => expect(call.token).toBe('admin-token'))
})
