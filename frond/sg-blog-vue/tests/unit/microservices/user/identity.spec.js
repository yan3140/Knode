jest.mock('element-ui', () => ({
  Notification: { error: jest.fn() },
  MessageBox: { confirm: jest.fn(() => Promise.resolve()) },
  Message: jest.fn()
}))
jest.mock('@/router', () => ({ currentRoute: { name: 'Home', fullPath: '/UserInfo' }, replace: jest.fn() }))
jest.mock('@/store', () => ({ state: { baseURL: '/api/blog', themeObj: {}, keywords: '', errorImg: '' } }))
jest.mock('@/utils/auth', () => ({ getToken: jest.fn(() => 'existing-token'), setToken: jest.fn(), removeToken: jest.fn() }))
jest.mock('@/api/user', () => ({ userLogin: jest.fn(), userRegister: jest.fn(), logout: jest.fn(), getUserInfo: jest.fn(), savaUserInfo: jest.fn() }))
jest.mock('@/api/social', () => ({ getUnreadCount: jest.fn(() => Promise.resolve({ unreadCount: 0 })) }))
jest.mock('@/api/category', () => ({ getCategoryList: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/utils/plug.js', () => ({ Typeit: jest.fn() }))

const { shallowMount } = require('@vue/test-utils')
const service = require('@/utils/request').default
const auth = require('@/utils/auth')
const api = require('@/api/user')
const ui = require('element-ui')
const router = require('@/router')
const Login = require('@/pages/Login.vue').default
const Header = require('@/components/header.vue').default
const Profile = require('@/pages/UserInfo.vue').default
const wait = () => new Promise(resolve => setTimeout(resolve, 0))
const longId = '9223372036854775807'

beforeEach(() => {
  jest.clearAllMocks()
  localStorage.clear()
  service.defaults.adapter = config => Promise.resolve({ data: { code: 200, data: { id: longId } }, status: 200, statusText: 'OK', headers: {}, config })
})

test('token header, API base and decimal identity survive the response interceptor', async () => {
  let seen
  service.defaults.adapter = config => {
    seen = config
    return Promise.resolve({ data: { code: 200, data: { id: longId } }, status: 200, headers: {}, config })
  }
  expect(await service({ url: '/user/userInfo', method: 'get' })).toEqual({ id: longId })
  expect(seen.baseURL).toBe('/api/blog')
  expect(seen.headers.token).toBe('existing-token')
})

test.each([403, 429, 503])('HTTP %s preserves the session', async status => {
  service.defaults.adapter = () => Promise.reject({ message: 'Request failed with status code ' + status, response: { status, data: { msg: 'temporarily unavailable' } } })
  await expect(service({ url: '/user/userInfo' })).rejects.toBeTruthy()
  expect(auth.removeToken).not.toHaveBeenCalled()
  expect(ui.Message).toHaveBeenCalled()
})

test.each([403, 429, 503])('business code %s preserves the session', async code => {
  service.defaults.adapter = config => Promise.resolve({ data: { code, msg: 'not allowed' }, status: 200, headers: {}, config })
  await expect(service({ url: '/user/userInfo' })).rejects.toMatchObject({ code })
  expect(auth.removeToken).not.toHaveBeenCalled()
})

test('network failure preserves the session and HTTP 401 invalidates it', async () => {
  service.defaults.adapter = () => Promise.reject(new Error('Network Error'))
  await expect(service({ url: '/user/userInfo' })).rejects.toThrow()
  expect(auth.removeToken).not.toHaveBeenCalled()
  service.defaults.adapter = () => Promise.reject({ message: 'unauthorized', response: { status: 401, data: { msg: 'expired' } } })
  await expect(service({ url: '/user/userInfo' })).rejects.toThrow()
  expect(auth.removeToken).toHaveBeenCalledTimes(1)
  await wait()
  expect(router.replace).toHaveBeenCalled()
})

test('business 401 invalidates the blog session', async () => {
  service.defaults.adapter = config => Promise.resolve({ data: { code: 401, msg: 'expired' }, status: 200, headers: {}, config })
  await expect(service({ url: '/user/userInfo' })).rejects.toMatchObject({ code: 401 })
  expect(auth.removeToken).toHaveBeenCalledTimes(1)
  await wait()
  expect(router.replace).toHaveBeenCalled()
})

test('login stores the exact user ID and does not mislabel 429 as a bad password', async () => {
  const mocks = { $route: { query: { redirect: '/UserInfo' } }, $router: { replace: jest.fn() } }
  const wrapper = shallowMount(Login, { mocks })
  wrapper.setData({ username: 'alice', password: 'secret' })
  api.userLogin.mockResolvedValueOnce({ token: 'new-token', userInfo: { id: longId, nickName: 'Alice' } })
  wrapper.vm.gotoHome()
  await wait()
  expect(auth.setToken).toHaveBeenCalledWith('new-token')
  expect(JSON.parse(localStorage.getItem('userInfo')).id).toBe(longId)
  expect(mocks.$router.replace).toHaveBeenCalledWith('/UserInfo')
  api.userLogin.mockRejectedValueOnce({ code: 429 })
  wrapper.vm.gotoHome()
  await wait()
  expect(wrapper.vm.loginErr).toBe(false)
  api.userLogin.mockRejectedValueOnce({ code: 505 })
  wrapper.vm.gotoHome()
  await wait()
  expect(wrapper.vm.loginErr).toBe(true)
  wrapper.destroy()
})

test('logout failure retains session while confirmed success clears it', async () => {
  localStorage.setItem('userInfo', JSON.stringify({ id: longId, nickName: 'Alice' }))
  const mocks = {
    $route: { path: '/UserInfo', name: 'UserInfo', fullPath: '/UserInfo', query: {} },
    $router: { push: jest.fn() },
    $store: { state: { themeObj: {}, keywords: '', errorImg: '' } },
    $confirm: jest.fn(() => Promise.resolve()),
    $message: jest.fn()
  }
  const wrapper = shallowMount(Header, { propsData: { compact: true }, mocks })
  api.logout.mockRejectedValueOnce(new Error('503'))
  wrapper.vm.userlogout()
  await wait()
  expect(auth.removeToken).not.toHaveBeenCalled()
  expect(JSON.parse(localStorage.getItem('userInfo')).id).toBe(longId)
  expect(mocks.$message).not.toHaveBeenCalled()
  const consoleError = jest.spyOn(console, 'error').mockImplementation(() => {})
  api.logout.mockResolvedValueOnce()
  wrapper.vm.userlogout()
  await wait()
  expect(auth.removeToken).toHaveBeenCalledTimes(1)
  expect(localStorage.getItem('userInfo')).toBeNull()
  expect(mocks.$message).toHaveBeenCalledWith(expect.objectContaining({ type: 'success' }))
  consoleError.mockRestore()
  wrapper.destroy()
})

test('profile loads and saves a decimal-string identity without precision loss', async () => {
  localStorage.setItem('userInfo', JSON.stringify({ id: longId, nickName: 'Before' }))
  api.getUserInfo.mockResolvedValueOnce({ id: longId, nickName: 'Before', avatar: null, sex: '2' })
  api.savaUserInfo.mockResolvedValueOnce()
  const mocks = { $route: { path: '/UserInfo', name: 'UserInfo', fullPath: '/UserInfo', query: {} },
    $router: { push: jest.fn() }, $store: { state: { themeObj: {}, keywords: '', errorImg: '' } },
    $message: { error: jest.fn(), success: jest.fn() } }
  const wrapper = shallowMount(Profile, { mocks, stubs: { 'sg-nav': true } })
  await wait()
  expect(api.getUserInfo).toHaveBeenCalledWith(longId)
  wrapper.vm.userInfoObj.nickName = 'After'
  wrapper.vm.saveInfoFun()
  await wait()
  expect(api.savaUserInfo.mock.calls[0][0].id).toBe(longId)
  expect(JSON.parse(localStorage.getItem('userInfo')).id).toBe(longId)
  wrapper.destroy()
})
