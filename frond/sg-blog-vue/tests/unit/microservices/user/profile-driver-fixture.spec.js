// Isolated UI fixture: the API is a synthetic test double, not end-to-end evidence.
jest.mock('@/api/user', () => ({
  getUserInfo: jest.fn(() => Promise.resolve({ id: '42', nickName: 'IT User', sex: '2', avatar: null })),
  savaUserInfo: jest.fn(() => Promise.resolve())
}))
jest.mock('@/store', () => ({ state: { baseURL: '/api/blog', errorImg: '' } }))
jest.mock('@/components/header.vue', () => ({ name: 'FixtureHeader', render: h => h('div') }))

const { createLocalVue, mount } = require('@vue/test-utils')
const Element = require('element-ui')
const Profile = require('@/pages/UserInfo.vue').default
const api = require('@/api/user')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const driver = fs.readFileSync(path.resolve(__dirname, '../../../../../../scripts/microservices/user/browser-journey.cjs'), 'utf8')
const helpers = vm.runInNewContext(driver.slice(driver.indexOf('const visibleDialog ='), driver.indexOf('function editRow(')) + ';({setInput,click})')
const run = expression => Function('document', 'HTMLInputElement', 'Event', 'return ' + expression)(document, HTMLInputElement, Event)
const tick = () => new Promise(resolve => setTimeout(resolve, 0))

test('real profile form emits a synthetic save and Element UI 1 renders its success message', async () => {
  const localVue = createLocalVue()
  localVue.use(Element)
  localStorage.setItem('userInfo', JSON.stringify({ id: '42', nickName: 'IT User' }))
  const wrapper = mount(Profile, {
    localVue,
    attachTo: document.body,
    mocks: { $store: { state: { errorImg: '' } }, $router: { push: jest.fn() } },
    stubs: { 'sg-nav': true }
  })
  document.body.appendChild(wrapper.element)
  try {
    await tick()
    const input = wrapper.find('.account-form input[placeholder]')
    expect(input.element.value).toBe('IT User')
    expect(run(helpers.setInput('.account-form input[placeholder]', 'IT Blog Updated'))).toBe(true)
    await localVue.nextTick()
    expect(run(helpers.click('.account-actions .el-button--primary'))).toBe(true)
    await tick()
    expect(api.savaUserInfo).toHaveBeenCalledWith(expect.objectContaining({ nickName: 'IT Blog Updated', sex: '2' }))
    expect(document.querySelector('.el-message--success')).toBeNull()
    const message = [...document.querySelectorAll('.el-message')].find(el => el.textContent.trim() === '\u8d44\u6599\u5df2\u4fdd\u5b58')
    expect(message).toBeDefined()
    expect(message.querySelector('.el-message__img')).not.toBeNull()
  } finally {
    if (wrapper.element.parentNode) wrapper.element.parentNode.removeChild(wrapper.element)
    wrapper.destroy()
    localStorage.clear()
    Element.Message.closeAll()
  }
})
