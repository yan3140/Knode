jest.mock('element-ui', () => ({
  Notification: { error: jest.fn() },
  MessageBox: { confirm: jest.fn(() => Promise.resolve()) },
  Message: Object.assign(jest.fn(), { error: jest.fn() })
}))
jest.mock('@riophae/vue-treeselect', () => ({ name: 'Treeselect' }))
jest.mock('@riophae/vue-treeselect/dist/vue-treeselect.css', () => ({}))
jest.mock('nprogress', () => ({ configure: jest.fn(), start: jest.fn(), done: jest.fn() }))
jest.mock('nprogress/nprogress.css', () => ({}))
jest.mock('@/router', () => ({ beforeEach: jest.fn(), afterEach: jest.fn(), addRoutes: jest.fn(), replace: jest.fn(), currentRoute: { path: '/system', fullPath: '/system?tab=1' }, constantRoutes: [] }))
jest.mock('@/store', () => ({ getters: { roles: [] }, dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('@/assets/images/profile.jpg', () => 'profile.jpg')
jest.mock('@/utils/auth', () => ({ getToken: jest.fn(() => 'admin-token'), setToken: jest.fn(), removeToken: jest.fn() }))
jest.mock('@/api/login', () => ({ login: jest.fn(), logout: jest.fn(), getInfo: jest.fn() }))
jest.mock('@/api/menu', () => ({ getRouters: jest.fn(), treeselect: jest.fn(), roleMenuTreeselect: jest.fn(), listMenu: jest.fn(), getMenu: jest.fn(), delMenu: jest.fn(), addMenu: jest.fn(), updateMenu: jest.fn() }))
jest.mock('@/api/system/user', () => ({ listUser: jest.fn(), getUser: jest.fn(), delUser: jest.fn(), addUser: jest.fn(), updateUser: jest.fn(), changeUserStatus: jest.fn() }))
jest.mock('@/api/system/role', () => ({ listAllRole: jest.fn(), listRole: jest.fn(), getRole: jest.fn(), delRole: jest.fn(), addRole: jest.fn(), updateRole: jest.fn(), changeRoleStatus: jest.fn() }))
jest.mock('@/layout/index', () => ({ name: 'Layout' }))
jest.mock('@/components/ParentView', () => ({ name: 'ParentView' }))
jest.mock('@/layout/components/InnerLink', () => ({ name: 'InnerLink' }))
jest.mock('@/components/Breadcrumb', () => ({ name: 'Breadcrumb' }))
jest.mock('@/components/Hamburger', () => ({ name: 'Hamburger' }))

const { shallowMount } = require('@vue/test-utils')
const service = require('@/utils/request').default
const auth = require('@/utils/auth')
const store = require('@/store')
const router = require('@/router')
const ui = require('element-ui')
const loginApi = require('@/api/login')
const menuApi = require('@/api/menu')
const userApi = require('@/api/system/user')
const roleApi = require('@/api/system/role')
const userModule = require('@/store/modules/user').default
const permissionModule = require('@/store/modules/permission').default
const UserView = require('@/views/system/user/index.vue').default
const RoleView = require('@/views/system/role/index.vue').default
const MenuView = require('@/views/system/menu/index.vue').default
const Navbar = require('@/layout/components/Navbar.vue').default
const wait = () => new Promise(resolve => setTimeout(resolve, 0))
const large = '9007199254740993'
const maximum = '9223372036854775807'

beforeEach(() => {
  jest.clearAllMocks()
  store.getters.roles = []
  store.dispatch.mockResolvedValue()
  userApi.listUser.mockResolvedValue({ rows: [], total: '0' })
  roleApi.listRole.mockResolvedValue({ rows: [], total: '0' })
  menuApi.listMenu.mockResolvedValue([])
  service.defaults.adapter = config => Promise.resolve({ data: { code: 200, data: { id: maximum } }, status: 200, headers: {}, config })
})

test('request interceptor keeps token header and decimal string identity', async () => {
  let seen
  service.defaults.adapter = config => {
    seen = config
    return Promise.resolve({ data: { code: 200, data: { id: maximum } }, status: 200, headers: {}, config })
  }
  expect(await service({ url: '/auth/me', method: 'get' })).toEqual({ id: maximum })
  expect(seen.headers.token).toBe('admin-token')
})

test.each([403, 429, 503])('HTTP %s preserves the session', async status => {
  service.defaults.adapter = () => Promise.reject({ message: 'Request failed with status code ' + status, response: { status, data: { msg: 'temporary failure' } } })
  await expect(service({ url: '/auth/me' })).rejects.toBeTruthy()
  expect(store.dispatch).not.toHaveBeenCalledWith('FedLogOut')
  expect(auth.removeToken).not.toHaveBeenCalled()
  expect(ui.Message).toHaveBeenCalled()
})

test.each([403, 429, 503])('business code %s preserves the session', async code => {
  service.defaults.adapter = config => Promise.resolve({ data: { code, msg: 'failure' }, status: 200, headers: {}, config })
  await expect(service({ url: '/auth/me' })).rejects.toBeTruthy()
  expect(store.dispatch).not.toHaveBeenCalledWith('FedLogOut')
  expect(auth.removeToken).not.toHaveBeenCalled()
})

test('network errors preserve session and HTTP 401 dispatches local logout', async () => {
  service.defaults.adapter = () => Promise.reject(new Error('Network Error'))
  await expect(service({ url: '/auth/me' })).rejects.toThrow()
  expect(store.dispatch).not.toHaveBeenCalledWith('FedLogOut')
  const log = jest.spyOn(console, 'error').mockImplementation(() => {})
  service.defaults.adapter = () => Promise.reject({ message: 'unauthorized', response: { status: 401, data: { msg: 'expired' } } })
  await expect(service({ url: '/auth/me' })).rejects.toThrow()
  await wait()
  expect(store.dispatch).toHaveBeenCalledWith('FedLogOut')
  expect(router.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/system?tab=1' } })
  log.mockRestore()
})

test('business 401 dispatches local logout after confirmation', async () => {
  service.defaults.adapter = config => Promise.resolve({ data: { code: 401, msg: 'expired' }, status: 200, headers: {}, config })
  await expect(service({ url: '/auth/me' })).rejects.toBeTruthy()
  await wait()
  expect(store.dispatch).toHaveBeenCalledWith('FedLogOut')
  expect(router.replace).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/system?tab=1' } })
})

test('admin login and me use nickName, roles and a local avatar fallback', async () => {
  const commit = jest.fn()
  loginApi.login.mockResolvedValueOnce({ token: 'new-token' })
  await userModule.actions.Login({ commit }, { userName: ' alice ', password: 'test' })
  expect(loginApi.login).toHaveBeenCalledWith('alice', 'test', undefined, undefined)
  expect(auth.setToken).toHaveBeenCalledWith('new-token')
  loginApi.getInfo.mockResolvedValueOnce({ user: { id: maximum, nickName: 'Admin', avatar: null }, roles: ['editor'], permissions: ['system:user:list'] })
  await userModule.actions.GetInfo({ commit }, {})
  expect(commit).toHaveBeenCalledWith('SET_NAME', 'Admin')
  const avatar = commit.mock.calls.find(x => x[0] === 'SET_AVATAR')[1]
  expect(avatar).toBeTruthy()
  expect(String(avatar)).not.toMatch(/dev-api|null|undefined/)
  expect(commit).toHaveBeenCalledWith('SET_ROLES', ['editor'])
})

test('failed logout leaves credentials intact and successful logout clears them', async () => {
  const commit = jest.fn()
  loginApi.logout.mockRejectedValueOnce(new Error('503'))
  await expect(userModule.actions.LogOut({ commit, state: { token: 'x' } })).rejects.toThrow()
  expect(auth.removeToken).not.toHaveBeenCalled()
  expect(commit).not.toHaveBeenCalled()
  loginApi.logout.mockResolvedValueOnce()
  await userModule.actions.LogOut({ commit, state: { token: 'x' } })
  expect(auth.removeToken).toHaveBeenCalledTimes(1)
  expect(commit).toHaveBeenCalledWith('SET_TOKEN', '')
})

test('string root menus build routes and route errors reject', async () => {
  menuApi.getRouters.mockResolvedValueOnce({ menus: [
    { id: maximum, parentId: '0', menuName: 'System', path: 'system', menuType: 'M', visible: '0',
      children: [{ id: large, parentId: maximum, menuName: 'Users', path: 'users', component: 'system/user/index', menuType: 'C', visible: '0' }] }
  ] })
  const commit = jest.fn()
  const routes = await permissionModule.actions.GenerateRoutes({ commit })
  expect(routes[0].path).toBe('/system')
  expect(routes[0].component.name).toBe('Layout')
  expect(routes[0].children[0].path).toBe('users')
  menuApi.getRouters.mockRejectedValueOnce(new Error('503'))
  await expect(permissionModule.actions.GenerateRoutes({ commit })).rejects.toThrow('503')
})

test.each([403, 429, 503, 'network'])('route guard preserves session on %s', async reason => {
  jest.isolateModules(() => { require('@/permission') })
  const handler = router.beforeEach.mock.calls[0][0]
  store.dispatch.mockRejectedValueOnce(new Error(String(reason)))
  const next = jest.fn()
  handler({ path: '/system', fullPath: '/system', meta: {} }, {}, next)
  await wait()
  expect(next).toHaveBeenCalledWith(false)
  expect(store.dispatch).not.toHaveBeenCalledWith('LogOut')
  expect(store.dispatch).not.toHaveBeenCalledWith('FedLogOut')
})

test('user, role and menu form writes retain large decimal IDs and string root', async () => {
  userApi.updateUser.mockResolvedValue()
  roleApi.updateRole.mockResolvedValue()
  menuApi.addMenu.mockResolvedValue()
  menuApi.updateMenu.mockResolvedValue()
  const mocks = { resetForm: jest.fn(), handleTree: x => x, $modal: { msgSuccess: jest.fn(), confirm: jest.fn() } }
  const user = shallowMount(UserView, { mocks })
  await wait()
  user.vm.form = { id: maximum, roleIds: [large] }
  user.vm.$refs.form = { validate: callback => callback(true) }
  user.vm.submitForm()
  await wait()
  expect(userApi.updateUser.mock.calls[0][0]).toMatchObject({ id: maximum, roleIds: [large] })

  const role = shallowMount(RoleView, { mocks })
  await wait()
  role.vm.form = { id: large }
  role.vm.$refs.form = { validate: callback => callback(true) }
  role.vm.$refs.menu = { getCheckedKeys: () => [maximum], getHalfCheckedKeys: () => [large] }
  role.vm.submitForm()
  await wait()
  expect(roleApi.updateRole.mock.calls[0][0]).toMatchObject({ id: large, menuIds: [large, maximum] })

  const menu = shallowMount(MenuView, { mocks, methods: { getIcons: () => [] }, stubs: { Treeselect: true } })
  await wait()
  menu.vm.reset()
  expect(menu.vm.form.parentId).toBe('0')
  menu.vm.$refs.form = { validate: callback => callback(true) }
  menu.vm.form.parentId = maximum
  menu.vm.submitForm()
  await wait()
  expect(menuApi.addMenu.mock.calls[0][0].parentId).toBe(maximum)
  menu.vm.form = { id: large, parentId: '0' }
  menu.vm.submitForm()
  await wait()
  expect(menuApi.updateMenu.mock.calls[0][0]).toMatchObject({ id: large, parentId: '0' })
  user.destroy();role.destroy();menu.destroy()
})


test('role checked keys remain decimal strings when editing', async () => {
  menuApi.roleMenuTreeselect.mockResolvedValueOnce({ menus: [{ id: maximum, label: 'Root' }], checkedKeys: [maximum, large] })
  roleApi.getRole.mockResolvedValueOnce({ id: large, roleName: 'Editor' })
  const mocks = { resetForm: jest.fn(), $modal: { msgSuccess: jest.fn() } }
  const wrapper = shallowMount(RoleView, { mocks })
  await wait()
  const tree = { setCheckedKeys: jest.fn(), setChecked: jest.fn() }
  wrapper.vm.$refs.menu = tree
  wrapper.vm.handleUpdate({ id: large })
  await wait();await wait()
  expect(menuApi.roleMenuTreeselect).toHaveBeenCalledWith(large)
  expect(roleApi.getRole).toHaveBeenCalledWith(large)
  expect(tree.setChecked).toHaveBeenCalledWith(maximum, true, false)
  expect(tree.setChecked).toHaveBeenCalledWith(large, true, false)
  wrapper.destroy()
})

test('Navbar keeps the current route when logout fails and navigates only after success', async () => {
  const dispatch = jest.fn().mockRejectedValueOnce(new Error('503')).mockResolvedValueOnce()
  const replace = jest.fn()
  const wrapper = shallowMount(Navbar, { mocks: {
    $store: { getters: { sidebar: { opened: true }, name: 'Admin', avatar: '' }, dispatch },
    $router: { replace }, $confirm: jest.fn(() => Promise.resolve())
  } })
  wrapper.vm.logout()
  await wait()
  expect(replace).not.toHaveBeenCalled()
  wrapper.vm.logout()
  await wait()
  expect(replace).toHaveBeenCalledWith('/login')
  wrapper.destroy()
})
