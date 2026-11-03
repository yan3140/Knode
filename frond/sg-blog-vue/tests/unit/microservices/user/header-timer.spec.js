jest.mock('@/api/user', () => ({ logout: jest.fn() }))
jest.mock('@/api/social', () => ({ getUnreadCount: jest.fn(() => Promise.resolve({ unreadCount: 0 })) }))
jest.mock('@/api/category', () => ({ getCategoryList: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/utils/plug.js', () => ({ Typeit: jest.fn() }))

const { shallowMount } = require('@vue/test-utils')
const Header = require('@/components/header.vue').default
const { Typeit } = require('@/utils/plug.js')

function mountHeader (compact = false) {
  return shallowMount(Header, {
    propsData: { compact },
    mocks: {
      $route: { path: '/UserInfo', name: 'UserInfo', query: {} },
      $router: { push: jest.fn() },
      $store: { state: { themeObj: { user_start: 'fixture' }, keywords: '', errorImg: '' } }
    }
  })
}

beforeEach(() => {
  jest.useFakeTimers()
  jest.clearAllMocks()
  localStorage.clear()
})

afterEach(() => {
  jest.clearAllTimers()
  jest.useRealTimers()
  localStorage.clear()
})

test('starts the animation once with its own element after 500ms', () => {
  const wrapper = mountHeader()
  const ownTarget = wrapper.element.querySelector('#luke')
  jest.advanceTimersByTime(499)
  expect(Typeit).not.toHaveBeenCalled()
  jest.advanceTimersByTime(1)
  expect(Typeit).toHaveBeenCalledTimes(1)
  expect(Typeit).toHaveBeenCalledWith('fixture', ownTarget)
  wrapper.destroy()
})

test('destroying the header cancels the delayed animation', () => {
  const wrapper = mountHeader()
  wrapper.destroy()
  jest.advanceTimersByTime(500)
  expect(Typeit).not.toHaveBeenCalled()
})

test('does not use another page element when its own target disappears', () => {
  const wrapper = mountHeader()
  wrapper.element.querySelector('#luke').remove()
  const foreign = document.createElement('span')
  foreign.id = 'luke'
  document.body.appendChild(foreign)
  try {
    jest.advanceTimersByTime(500)
    expect(Typeit).not.toHaveBeenCalled()
  } finally {
    foreign.remove()
    wrapper.destroy()
  }
})

test('compact headers do not start the animation', () => {
  const wrapper = mountHeader(true)
  jest.advanceTimersByTime(500)
  expect(Typeit).not.toHaveBeenCalled()
  wrapper.destroy()
})
