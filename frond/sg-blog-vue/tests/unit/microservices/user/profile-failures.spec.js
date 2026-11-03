jest.mock('element-ui', () => ({
  Notification: { error: jest.fn() },
  MessageBox: { confirm: jest.fn(() => Promise.resolve()) },
  Message: jest.fn()
}))
jest.mock('@/router', () => ({ currentRoute: { name: 'UserInfo', fullPath: '/UserInfo' }, replace: jest.fn() }))
jest.mock('@/store', () => ({ state: { baseURL: '/api/blog', themeObj: {}, keywords: '', errorImg: '' } }))
jest.mock('@/api/social', () => ({ getUnreadCount: jest.fn() }))
jest.mock('@/api/category', () => ({ getCategoryList: jest.fn() }))
jest.mock('@/utils/plug.js', () => ({ Typeit: jest.fn() }))

const { shallowMount } = require('@vue/test-utils')
const Profile = require('@/pages/UserInfo.vue').default
const service = require('@/utils/request').default
const auth = require('@/utils/auth')
const router = require('@/router')
const ui = require('element-ui')
const longId = '9223372036854775807'
const tick = () => new Promise(resolve => setTimeout(resolve, 0))
const success = config => Promise.resolve({
  data: { code: 200, data: { id: longId, nickName: 'Before', avatar: null, sex: '2' } },
  status: 200, headers: {}, config
})
const failure = (status, config) => {
  if (status === 'network') return Promise.reject(new Error('Network Error'))
  if (status === 'business401' || status === 'business503') {
    return Promise.resolve({ data: { code: status === 'business401' ? 401 : 503, msg: 'fixture failure' }, status: 200, headers: {}, config })
  }
  return Promise.reject({ message: 'Request failed with status code ' + status, response: { status, data: { msg: 'fixture failure' } } })
}

async function mountProfile () {
  localStorage.setItem('userInfo', JSON.stringify({ id: longId, nickName: 'Before' }))
  auth.setToken('fixture-token')
  service.defaults.adapter = success
  const message = { error: jest.fn(), success: jest.fn() }
  const wrapper = shallowMount(Profile, {
    mocks: { $route: { path: '/UserInfo' }, $router: { push: jest.fn() }, $message: message,
      $store: { state: { themeObj: {}, keywords: '', errorImg: '' } } },
    stubs: { 'sg-nav': true }
  })
  await tick()
  return { wrapper, message }
}

beforeEach(() => {
  jest.clearAllMocks()
  localStorage.clear()
  auth.removeToken()
})

afterEach(() => {
  localStorage.clear()
  auth.removeToken()
})

test('successful load and save update the form and session only after confirmation', async () => {
  const { wrapper, message } = await mountProfile()
  expect(wrapper.vm.userInfoObj).toMatchObject({ id: longId, nickName: 'Before' })
  wrapper.vm.userInfoObj.nickName = 'Edited'
  let request
  service.defaults.adapter = config => {
    request = config
    return Promise.resolve({ data: { code: 200 }, status: 200, headers: {}, config })
  }
  const result = wrapper.vm.saveInfoFun()
  expect(wrapper.vm.saving).toBe(true)
  expect(await result).toBe(true)
  expect(wrapper.vm.saving).toBe(false)
  expect(JSON.parse(request.data)).toMatchObject({ id: longId, nickName: 'Edited' })
  expect(JSON.parse(localStorage.getItem('userInfo')).nickName).toBe('Edited')
  expect(message.success).toHaveBeenCalledTimes(1)
  wrapper.destroy()
})

test.each([401, 403, 429, 503, 'network', 'business401', 'business503'])('profile load failure %s preserves the draft and reports failure', async status => {
  const { wrapper, message } = await mountProfile()
  wrapper.vm.userInfoObj.nickName = 'Unsent draft'
  service.defaults.adapter = config => failure(status, config)
  expect(await wrapper.vm.loadUser()).toBe(false)
  await tick()
  expect(wrapper.vm.userInfoObj.nickName).toBe('Unsent draft')
  expect(message.success).not.toHaveBeenCalled()
  expect(message.error).not.toHaveBeenCalled()
  if (status === 401 || status === 'business401') {
    expect(auth.getToken()).toBeUndefined()
    expect(localStorage.getItem('userInfo')).toBeNull()
    expect(ui.MessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledTimes(1)
  } else {
    expect(auth.getToken()).toBe('fixture-token')
    expect(JSON.parse(localStorage.getItem('userInfo')).nickName).toBe('Before')
    if (status === 'business503') expect(ui.Notification.error).toHaveBeenCalledTimes(1)
    else expect(ui.Message).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  }
  wrapper.destroy()
})

test.each([401, 403, 429, 503, 'network', 'business401', 'business503'])('profile save failure %s keeps the draft and clears saving', async status => {
  const { wrapper, message } = await mountProfile()
  wrapper.vm.userInfoObj.nickName = 'Unsent draft'
  service.defaults.adapter = config => failure(status, config)
  const result = wrapper.vm.saveInfoFun()
  expect(wrapper.vm.saving).toBe(true)
  expect(await result).toBe(false)
  await tick()
  expect(wrapper.vm.saving).toBe(false)
  expect(wrapper.vm.userInfoObj.nickName).toBe('Unsent draft')
  expect(message.success).not.toHaveBeenCalled()
  expect(message.error).not.toHaveBeenCalled()
  if (status === 401 || status === 'business401') {
    expect(auth.getToken()).toBeUndefined()
    expect(localStorage.getItem('userInfo')).toBeNull()
    expect(ui.MessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledTimes(1)
  } else {
    expect(auth.getToken()).toBe('fixture-token')
    expect(JSON.parse(localStorage.getItem('userInfo')).nickName).toBe('Before')
    if (status === 'business503') expect(ui.Notification.error).toHaveBeenCalledTimes(1)
    else expect(ui.Message).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  }
  wrapper.destroy()
})
