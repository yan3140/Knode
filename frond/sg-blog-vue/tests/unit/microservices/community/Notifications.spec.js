jest.mock('@/api/user', () => ({ logout: jest.fn() }))
jest.mock('@/api/category', () => ({ getCategoryList: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/utils/plug.js', () => ({ Typeit: jest.fn() }))
jest.mock('@/api/comment', () => ({
  sendComment: jest.fn(() => Promise.resolve({}))
}))
jest.mock('@/api/social', () => ({
  getNotifications: jest.fn(),
  getUnreadCount: jest.fn(),
  markAllNotificationsRead: jest.fn(() => Promise.resolve({})),
  markNotificationRead: jest.fn(() => Promise.resolve({})),
  deleteNotification: jest.fn(() => Promise.resolve({}))
}))

const { shallowMount } = require('@vue/test-utils')
const Header = require('@/components/header.vue').default
const Notifications = require('@/pages/Notifications.vue').default
const commentApi = require('@/api/comment')
const socialApi = require('@/api/social')

const LARGE_NOTIF_ID = '9007199254740993'
const LARGE_ARTICLE_ID = '9007199254740995'
const LARGE_COMMENT_ID = '9007199254740997'
const LARGE_ACTOR_ID = '9007199254740999'
const READ_FANOUT_NEG_ID = '-9007199254741001'

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve))
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  jest.clearAllMocks()
  localStorage.clear()
})

describe('COMMUNITY-05A header and Notifications adaptations', () => {
  test('header converts decimal string unreadCount "0" so zero badge is hidden and shows "99+" above 99', async () => {
    localStorage.setItem('userInfo', JSON.stringify({ id: '100', avatar: null }))
    socialApi.getUnreadCount.mockResolvedValueOnce({ unreadCount: '0' })

    const wrapper = shallowMount(Header, {
      propsData: { compact: true },
      mocks: {
        $route: { path: '/Home', name: 'Home', query: {}, fullPath: '/Home' },
        $router: { push: jest.fn() },
        $store: { state: { themeObj: {}, keywords: '', errorImg: '' } }
      },
      stubs: ['el-row', 'el-col', 'el-menu', 'el-menu-item', 'el-submenu', 'el-input', 'el-button']
    })
    await flushPromises()
    expect(wrapper.vm.unreadCount).toBe(0)
    expect(wrapper.find('.notification-badge').exists()).toBe(false)

    socialApi.getUnreadCount.mockResolvedValueOnce({ unreadCount: '125' })
    wrapper.vm.loadUnreadCount()
    await flushPromises()
    expect(wrapper.vm.unreadCount).toBe(125)
    expect(wrapper.vm.unreadBadge).toBe('99+')
    expect(wrapper.find('.notification-badge').exists()).toBe(true)
    wrapper.destroy()
  })

  test('Notifications preserves 64-bit string IDs, fixes rootCommentId "-1" inline reply, and deletes negative read-fanout IDs', async () => {
    socialApi.getNotifications.mockResolvedValue({
      rows: [
        {
          id: LARGE_NOTIF_ID,
          type: 'COMMENT',
          actorId: LARGE_ACTOR_ID,
          actorNickName: 'Alice',
          actorAvatar: 'javascript:alert(1)',
          actorInfoStatus: 'AVAILABLE',
          articleId: LARGE_ARTICLE_ID,
          articleTitle: 'Spring Cloud Guide',
          articleVisibilityStatus: 'PUBLIC',
          commentId: LARGE_COMMENT_ID,
          rootCommentId: '-1',
          content: 'Great post!',
          isRead: 0,
          createTime: '2026-11-24T12:00:00Z'
        },
        {
          id: READ_FANOUT_NEG_ID,
          type: 'ARTICLE_PUBLISHED',
          actorId: LARGE_ACTOR_ID,
          actorNickName: 'BigAuthor',
          actorAvatar: 'https://example.org/a.png',
          actorInfoStatus: 'AVAILABLE',
          articleId: LARGE_ARTICLE_ID,
          articleTitle: 'Big Author Post',
          articleVisibilityStatus: 'PUBLIC',
          commentId: null,
          rootCommentId: null,
          content: null,
          isRead: 1,
          createTime: '2026-11-24T11:00:00Z'
        }
      ],
      total: '2'
    })
    socialApi.getUnreadCount.mockResolvedValue({ unreadCount: '1' })

    const push = jest.fn()
    const wrapper = shallowMount(Notifications, {
      mocks: {
        $router: { push },
        $store: { state: { errorImg: '' } },
        $message: { success: jest.fn(), warning: jest.fn(), error: jest.fn() },
        $confirm: jest.fn(() => Promise.resolve())
      },
      stubs: ['sg-nav', 'el-button', 'el-input', 'el-pagination']
    })
    await flushPromises()

    expect(wrapper.vm.unreadCount).toBe(1)
    expect(wrapper.vm.total).toBe(2)
    // Unsafe javascript: avatar falls back to safe defaultAvatar
    expect(wrapper.findAll('.notification-avatar img').at(0).attributes('src')).toBe('static/img/tou.jpg')

    // Inline reply with rootCommentId === "-1" MUST pass commentId (NOT "-1") as rootId!
    wrapper.vm.toggleReply(wrapper.vm.notifications[0])
    wrapper.vm.replyContent = 'Thanks Alice!'
    wrapper.vm.sendInlineReply(wrapper.vm.notifications[0])
    await flushPromises()
    expect(commentApi.sendComment).toHaveBeenCalledWith(
      '0',
      LARGE_ARTICLE_ID,
      LARGE_COMMENT_ID,
      LARGE_COMMENT_ID,
      LARGE_ACTOR_ID,
      'Thanks Alice!'
    )

    // Clicking unread 64-bit notification marks read with exact string ID and navigates with exact 64-bit aid/commentId
    wrapper.vm.openNotification(wrapper.vm.notifications[0])
    await flushPromises()
    expect(socialApi.markNotificationRead).toHaveBeenCalledWith(LARGE_NOTIF_ID)
    expect(push).toHaveBeenCalledWith({
      path: '/DetailArticle',
      query: { aid: LARGE_ARTICLE_ID, commentId: LARGE_COMMENT_ID }
    })

    // Deleting negative read-fanout notification sends exact negative string ID
    wrapper.vm.removeNotification(wrapper.vm.notifications[1])
    await flushPromises()
    expect(socialApi.deleteNotification).toHaveBeenCalledWith(READ_FANOUT_NEG_ID)
    expect(wrapper.vm.notifications.length).toBe(1)
    wrapper.destroy()
  })

  test('withdrawn UNAVAILABLE article hides title and blocks dead navigation while still allowing markRead', async () => {
    socialApi.getNotifications.mockResolvedValue({
      rows: [
        {
          id: LARGE_NOTIF_ID,
          type: 'COMMENT',
          actorId: LARGE_ACTOR_ID,
          actorNickName: null,
          actorInfoStatus: 'MISSING',
          articleId: LARGE_ARTICLE_ID,
          articleTitle: null,
          articleVisibilityStatus: 'UNAVAILABLE',
          commentId: LARGE_COMMENT_ID,
          rootCommentId: '-1',
          content: 'Old comment on withdrawn post',
          isRead: 0,
          createTime: '2026-11-24T12:00:00Z'
        }
      ],
      total: '1'
    })
    socialApi.getUnreadCount.mockResolvedValue({ unreadCount: '1' })

    const push = jest.fn()
    const wrapper = shallowMount(Notifications, {
      mocks: {
        $router: { push },
        $store: { state: { errorImg: '' } },
        $message: { success: jest.fn(), warning: jest.fn(), error: jest.fn() }
      },
      stubs: ['sg-nav', 'el-button', 'el-input', 'el-pagination']
    })
    await flushPromises()

    expect(wrapper.text()).toContain('关联文章已不可见')
    expect(wrapper.text()).toContain('已注销用户')
    expect(wrapper.find('.notification-actions').exists()).toBe(false)

    // Clicking marks read, does NOT push dead /DetailArticle
    wrapper.vm.openNotification(wrapper.vm.notifications[0])
    await flushPromises()
    expect(socialApi.markNotificationRead).toHaveBeenCalledWith(LARGE_NOTIF_ID)
    expect(push).not.toHaveBeenCalled()
    wrapper.destroy()
  })

  test('does not fake empty inbox on 503 failure and ignores stale out-of-order page responses', async () => {
    socialApi.getNotifications.mockRejectedValueOnce(new Error('503'))
    socialApi.getUnreadCount.mockRejectedValueOnce(new Error('503'))

    const wrapper = shallowMount(Notifications, {
      mocks: {
        $router: { push: jest.fn() },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['sg-nav', 'el-button', 'el-input', 'el-pagination']
    })
    await flushPromises()

    expect(wrapper.text()).toContain('通知列表暂不可用')
    expect(wrapper.text()).not.toContain('暂时没有新通知')

    // Now test out-of-order page 2 vs page 3 race
    const page2 = deferred()
    const page3 = deferred()
    socialApi.getUnreadCount.mockResolvedValue({ unreadCount: '0' })
    socialApi.getNotifications
      .mockImplementationOnce(() => page2.promise)
      .mockImplementationOnce(() => page3.promise)

    wrapper.vm.changePage(2)
    // Duplicate click on page 2 while in flight does not fire another request
    wrapper.vm.changePage(2)
    expect(socialApi.getNotifications).toHaveBeenCalledTimes(2)

    // Switch to page 3 before page 2 resolves
    wrapper.vm.changePage(3)
    page3.resolve({ rows: [{ id: '303', type: 'FOLLOWED', actorId: '9', isRead: 1 }], total: '30' })
    await flushPromises()
    expect(wrapper.vm.pageNum).toBe(3)
    expect(wrapper.vm.notifications.map(n => n.id)).toEqual(['303'])

    // Late page 2 resolves -> ignored!
    page2.resolve({ rows: [{ id: '202', type: 'FOLLOWED', actorId: '8', isRead: 1 }], total: '30' })
    await flushPromises()
    expect(wrapper.vm.pageNum).toBe(3)
    expect(wrapper.vm.notifications.map(n => n.id)).toEqual(['303'])
    wrapper.destroy()
  })
})
