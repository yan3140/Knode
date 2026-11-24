jest.mock('@/components/header.vue', () => ({
  name: 'sg-nav',
  render(h) { return h('div') }
}))
jest.mock('@/api/article.js', () => ({
  getArticle: jest.fn(),
  updateViewCount: jest.fn(() => Promise.resolve()),
  articleList: jest.fn(),
  publishArticle: jest.fn()
}))
jest.mock('@/api/category.js', () => ({ getCategoryList: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/api/tag.js', () => ({ getAllTags: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/api/link.js', () => ({ getAllLink: jest.fn(() => Promise.resolve([])) }))
jest.mock('@/api/comment.js', () => ({
  sendComment: jest.fn(),
  getArticleComment: jest.fn(),
  getLinkComment: jest.fn()
}))
jest.mock('@/api/social', () => ({
  getUserProfile: jest.fn(),
  getUserFollowing: jest.fn(),
  followUser: jest.fn(),
  getFollowStatus: jest.fn(),
  unfollowUser: jest.fn()
}))
jest.mock('@/utils/auth.js', () => ({ getToken: jest.fn(() => 'blog-token') }))
jest.mock('mavon-editor', () => {
  const md = {
    options: { html: true },
    set(opts) { Object.assign(this.options, opts) },
    render(src) {
      // Simulate markdown-it rendering unsafe HTML/links to prove sanitizer + html:false defense in depth
      if (src.includes('javascript:')) {
        return '<p><a href="javascript:alert(1)" onclick="alert(2)">bad</a><a href="https://example.org/ok">good</a></p><script>alert(3)</script><img src="data:text/html,evil" onerror="alert(4)">'
      }
      return '<p>' + src + '</p>'
    }
  }
  return {
    mavonEditor: {
      name: 'mavon-editor',
      render(h) { return h('div') },
      getMarkdownIt: () => md
    }
  }
})

const { shallowMount } = require('@vue/test-utils')
const ArticleDetail = require('@/components/articleDetail.vue').default
const ArticleList = require('@/components/articlelist.vue').default
const UserProfile = require('@/pages/UserProfile.vue').default
const WriteArticle = require('@/pages/WriteArticle.vue').default
const Message = require('@/components/message.vue').default
const Friends = require('@/components/friends.vue').default

const articleApi = require('@/api/article.js')
const commentApi = require('@/api/comment.js')
const linkApi = require('@/api/link.js')
const socialApi = require('@/api/social')

const LARGE_ID = '9007199254740993'
const LARGE_ID_2 = '9007199254740995'

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve))
}

beforeEach(() => {
  jest.clearAllMocks()
  localStorage.clear()
})

describe('G8 blog content adaptations', () => {
  test('articleDetail keeps 64-bit string ID, sanitizes unsafe HTML/URLs, and degrades author info', async () => {
    articleApi.getArticle.mockResolvedValue({
      id: LARGE_ID,
      title: 'Safe MD',
      content: '[bad](javascript:alert(1))',
      categoryId: LARGE_ID_2,
      categoryName: 'Java',
      authorId: LARGE_ID_2,
      authorName: null,
      authorInfoStatus: 'UNAVAILABLE',
      viewCount: '42',
      createTime: '2026-11-20 08:00:00'
    })
    const wrapper = shallowMount(ArticleDetail, {
      mocks: {
        $route: { query: { aid: LARGE_ID } },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-row', 'el-col', 'el-button']
    })
    await flushPromises()
    expect(articleApi.getArticle).toHaveBeenCalledWith(LARGE_ID)
    expect(articleApi.updateViewCount).toHaveBeenCalledWith(LARGE_ID)
    expect(wrapper.find('.detail-author').text()).toContain('作者信息暂不可用')
    const html = wrapper.find('.article-content').html()
    expect(html).not.toContain('<script')
    expect(html).not.toContain('javascript:')
    expect(html).not.toContain('onclick')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('data:text/html')
    expect(html).toContain('https://example.org/ok')
    expect(html).toContain('noopener noreferrer')
    wrapper.destroy()
  })

  test('articlelist preserves 64-bit classId and uses categoryId in links', async () => {
    articleApi.articleList.mockResolvedValue({
      rows: [{
        id: LARGE_ID,
        title: 'List Item',
        summary: 'S',
        categoryId: LARGE_ID_2,
        categoryName: 'Cloud',
        authorId: LARGE_ID,
        authorName: null,
        authorInfoStatus: 'UNAVAILABLE',
        viewCount: '5',
        createTime: '2026-11-20 08:00:00'
      }],
      total: '1'
    })
    const wrapper = shallowMount(ArticleList, {
      mocks: {
        $route: { query: { classId: LARGE_ID_2 } },
        $store: { state: { keywords: '' } }
      },
      stubs: ['el-row', 'el-col']
    })
    await flushPromises()
    expect(articleApi.articleList).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10, categoryId: LARGE_ID_2 })
    expect(wrapper.find('.ui.label a').attributes('href')).toBe('#/Share?classId=' + LARGE_ID_2)
    expect(wrapper.find('.article-author').text()).toContain('作者信息暂不可用')
    wrapper.destroy()
  })

  test('UserProfile preserves 64-bit IDs and never fakes zero counts or unfollowed state when community is unavailable', async () => {
    localStorage.setItem('userInfo', JSON.stringify({ id: LARGE_ID_2 }))
    socialApi.getUserProfile.mockResolvedValue({
      id: LARGE_ID,
      nickName: 'Creator',
      avatar: null,
      createTime: null,
      relationStatus: 'UNAVAILABLE',
      followingCount: null,
      followerCount: null,
      viewerFollowing: null,
      viewerFollowStatus: 'UNAVAILABLE',
      articles: { rows: [], total: '0' }
    })
    socialApi.getUserFollowing.mockRejectedValue(new Error('503'))
    const wrapper = shallowMount(UserProfile, {
      mocks: {
        $route: { query: { userId: LARGE_ID }, fullPath: '/UserProfile?userId=' + LARGE_ID },
        $router: { push: jest.fn(), replace: jest.fn() },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['sg-nav', 'el-row', 'el-col', 'el-button', 'el-pagination']
    })
    await flushPromises()
    expect(socialApi.getUserProfile).toHaveBeenCalledWith(LARGE_ID, { pageNum: 1, pageSize: 8 })
    expect(wrapper.vm.profileId).toBe(LARGE_ID)
    expect(wrapper.vm.isSelf).toBe(false)
    expect(wrapper.vm.followerCountDisplay).toBe('暂不可用')
    expect(wrapper.vm.followingCountDisplay).toBe('暂不可用')
    expect(wrapper.vm.followState).toBe('unavailable')
    expect(wrapper.text()).toContain('关注状态暂不可用')
    expect(wrapper.text()).toContain('关注列表暂不可用')
    expect(socialApi.getFollowStatus).not.toHaveBeenCalled()
    wrapper.destroy()
  })

  test('WriteArticle keeps 64-bit categoryId and tags as strings and prevents duplicate submit', async () => {
    let resolvePublish
    articleApi.publishArticle.mockImplementation(() => new Promise(r => { resolvePublish = r }))
    localStorage.setItem('userInfo', JSON.stringify({ id: LARGE_ID }))
    const push = jest.fn()
    const wrapper = shallowMount(WriteArticle, {
      mocks: {
        $router: { push, back: jest.fn() },
        $message: { success: jest.fn(), error: jest.fn() }
      },
      stubs: {
        'sg-nav': true,
        'el-form': {
          template: '<form><slot /></form>',
          methods: { validate(cb) { cb(true) }, clearValidate() {} }
        },
        'el-form-item': true,
        'el-input': true,
        'el-row': true,
        'el-col': true,
        'el-select': true,
        'el-option': true,
        'el-radio-group': true,
        'el-radio': true,
        'el-button': true,
        'mavon-editor': true
      }
    })
    wrapper.vm.form.title = 'Long ID Post'
    wrapper.vm.form.categoryId = LARGE_ID
    wrapper.vm.form.tags = [LARGE_ID_2]
    wrapper.vm.form.content = 'Hello body'
    wrapper.vm.submitAs('0')
    wrapper.vm.submitAs('0')
    expect(articleApi.publishArticle).toHaveBeenCalledTimes(1)
    const sent = articleApi.publishArticle.mock.calls[0][0]
    expect(sent.categoryId).toBe(LARGE_ID)
    expect(sent.tags).toEqual([LARGE_ID_2])
    resolvePublish()
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ path: '/UserProfile', query: { userId: LARGE_ID } })
    wrapper.destroy()
  })

  test('message component keeps 64-bit aid, omits articleId on FriendsLink, and falls back to pageSize=1 on HTTP 413', async () => {
    commentApi.getArticleComment
      .mockRejectedValueOnce({ response: { status: 413, data: { code: 515 } } })
      .mockResolvedValueOnce({ rows: [{ id: LARGE_ID_2, createBy: LARGE_ID, username: null, userInfoStatus: 'UNAVAILABLE', createTime: 'now', content: 'hi', children: [] }], total: '2' })
    const articleWrapper = shallowMount(Message, {
      mocks: {
        $route: { name: 'DetailArticle', query: { aid: LARGE_ID } },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-input', 'el-row', 'el-col']
    })
    await flushPromises()
    expect(commentApi.getArticleComment).toHaveBeenNthCalledWith(1, { articleId: LARGE_ID, pageNum: 1, pageSize: 10 })
    expect(commentApi.getArticleComment).toHaveBeenNthCalledWith(2, { articleId: LARGE_ID, pageNum: 1, pageSize: 1 })
    expect(articleWrapper.find('.i-name').text()).toContain('用户昵称暂不可用')
    articleWrapper.destroy()

    commentApi.getLinkComment.mockResolvedValue({ rows: [], total: '0' })
    commentApi.sendComment.mockResolvedValue({})
    const linkWrapper = shallowMount(Message, {
      mocks: {
        $route: { name: 'FriendsLink', query: {} },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-input', 'el-row', 'el-col']
    })
    await flushPromises()
    expect(commentApi.getLinkComment).toHaveBeenCalledWith({ pageNum: 1, pageSize: 10 })
    linkWrapper.vm.textarea = 'friend hello'
    linkWrapper.vm.sendMsg()
    expect(commentApi.sendComment).toHaveBeenCalledWith(1, null, -1, -1, -1, 'friend hello')
    linkWrapper.destroy()
  })

  test('friends component rejects unsafe javascript URLs and adds rel=noopener noreferrer', async () => {
    linkApi.getAllLink.mockResolvedValue([
      { name: 'Safe', address: 'https://example.org', logo: 'https://example.org/a.png', description: 'ok' },
      { name: 'Evil', address: 'javascript:alert(1)', logo: 'javascript:alert(2)', description: 'bad' }
    ])
    const wrapper = shallowMount(Friends, {
      mocks: { $store: { state: { errorImg: '' } } },
      stubs: ['el-row', 'el-col', 'el-button']
    })
    await flushPromises()
    const links = wrapper.findAll('a')
    expect(links.length).toBe(1)
    expect(links.at(0).attributes('href')).toBe('https://example.org')
    expect(links.at(0).attributes('rel')).toBe('noopener noreferrer')
    expect(wrapper.html()).not.toContain('javascript:')
    wrapper.destroy()
  })

  function deferred() {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
  }

  test('articleDetail ignores slow stale response after switching to another article', async () => {
    const reqA = deferred()
    const reqB = deferred()
    articleApi.getArticle
      .mockImplementationOnce(() => reqA.promise)
      .mockImplementationOnce(() => reqB.promise)

    const route = { query: { aid: LARGE_ID } }
    const wrapper = shallowMount(ArticleDetail, {
      mocks: {
        $route: route,
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-row', 'el-col', 'el-button']
    })

    // Switch to article B before A resolves
    route.query = { aid: LARGE_ID_2 }
    wrapper.vm.routeChange()
    expect(wrapper.vm.detailObj.title).toBeUndefined()

    // B resolves first
    reqB.resolve({ id: LARGE_ID_2, title: 'Article B', content: 'Body B', categoryId: '2', categoryName: 'Java', viewCount: '1', createTime: '2026-11-23 10:00:00' })
    await flushPromises()
    expect(wrapper.vm.detailObj.title).toBe('Article B')

    // Slow response for A resolves later -> must NOT overwrite B
    reqA.resolve({ id: LARGE_ID, title: 'Article A', content: 'Body A', categoryId: '2', categoryName: 'Java', viewCount: '9', createTime: '2026-11-23 09:00:00' })
    await flushPromises()
    expect(wrapper.vm.detailObj.id).toBe(LARGE_ID_2)
    expect(wrapper.vm.detailObj.title).toBe('Article B')
    expect(wrapper.find('.article-content').text()).toContain('Body B')
    wrapper.destroy()
  })

  test('UserProfile resets state and ignores slow profile and followStatus responses after switching user', async () => {
    const Vue = require('vue')
    localStorage.setItem('userInfo', JSON.stringify({ id: '100' }))
    const profA = deferred()
    const profB = deferred()
    const followA = deferred()

    socialApi.getUserProfile.mockReset()
    socialApi.getUserProfile
      .mockImplementationOnce(() => profA.promise)
      .mockImplementationOnce(() => profB.promise)
    socialApi.getUserFollowing.mockReset()
    socialApi.getUserFollowing.mockResolvedValue({ rows: [], total: '0' })
    socialApi.getFollowStatus.mockReset()
    socialApi.getFollowStatus.mockImplementationOnce(() => followA.promise)

    const route = Vue.observable({ query: { userId: LARGE_ID }, fullPath: '/UserProfile?userId=' + LARGE_ID })
    const wrapper = shallowMount(UserProfile, {
      mocks: {
        $route: route,
        $router: { push: jest.fn(), replace: jest.fn() },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['sg-nav', 'el-row', 'el-col', 'el-button', 'el-pagination']
    })

    // Resolve profile A without viewerFollowStatus so it triggers slow getFollowStatus(A)
    profA.resolve({ id: LARGE_ID, nickName: 'UserA', relationStatus: 'AVAILABLE', followingCount: '1', followerCount: '2', articles: { rows: [], total: '0' } })
    await flushPromises()
    expect(socialApi.getFollowStatus).toHaveBeenCalledWith(LARGE_ID)

    // Switch to user B via reactive $route watcher while getFollowStatus(A) is still in flight
    route.query = { userId: LARGE_ID_2 }
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.profile.id).toBeUndefined()
    expect(wrapper.vm.followState).toBe('unknown')

    // Profile B resolves with viewerFollowing: false
    profB.resolve({ id: LARGE_ID_2, nickName: 'UserB', relationStatus: 'AVAILABLE', followingCount: '5', followerCount: '6', viewerFollowStatus: 'AVAILABLE', viewerFollowing: false, articles: { rows: [], total: '0' } })
    await flushPromises()
    expect(wrapper.vm.profile.nickName).toBe('UserB')
    expect(wrapper.vm.followState).toBe('not_following')

    // Stale getFollowStatus(A) resolves as true -> must NOT overwrite User B's followState!
    followA.resolve({ following: true })
    await flushPromises()
    expect(wrapper.vm.profile.nickName).toBe('UserB')
    expect(wrapper.vm.followState).toBe('not_following')
    wrapper.destroy()
  })

  test('message pagination retries failed page 2 without duplicating page 1 and prevents concurrent double clicks', async () => {
    const page2FirstAttempt = deferred()
    commentApi.getArticleComment
      .mockResolvedValueOnce({
        rows: [{ id: '101', createBy: LARGE_ID, username: 'U1', userInfoStatus: 'AVAILABLE', createTime: 't1', content: 'p1', children: [] }],
        total: '2'
      })
      .mockImplementationOnce(() => page2FirstAttempt.promise)
      .mockResolvedValueOnce({
        rows: [{ id: '102', createBy: LARGE_ID, username: 'U2', userInfoStatus: 'AVAILABLE', createTime: 't2', content: 'p2', children: [] }],
        total: '2'
      })

    const wrapper = shallowMount(Message, {
      mocks: {
        $route: { name: 'DetailArticle', query: { aid: LARGE_ID } },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-input', 'el-row', 'el-col']
    })
    await flushPromises()
    expect(wrapper.vm.commentList.map(c => c.id)).toEqual(['101'])
    expect(wrapper.vm.hasMore).toBe(true)

    // Double-click addMoreFun while page 2 is in flight -> only ONE request for page 2
    wrapper.vm.addMoreFun()
    wrapper.vm.addMoreFun()
    expect(commentApi.getArticleComment).toHaveBeenCalledTimes(2)
    expect(commentApi.getArticleComment).toHaveBeenNthCalledWith(2, { articleId: LARGE_ID, pageNum: 2, pageSize: 10 })

    // Page 2 fails -> retry must request page 2 (NOT page 1!)
    page2FirstAttempt.reject(new Error('network error'))
    await flushPromises()
    expect(wrapper.vm.commentError).toBe('network error')
    expect(wrapper.vm.commentList.map(c => c.id)).toEqual(['101'])

    wrapper.vm.retryCommentPage()
    await flushPromises()
    expect(commentApi.getArticleComment).toHaveBeenCalledTimes(3)
    expect(commentApi.getArticleComment).toHaveBeenNthCalledWith(3, { articleId: LARGE_ID, pageNum: 2, pageSize: 10 })
    expect(wrapper.vm.commentList.map(c => c.id)).toEqual(['101', '102'])
    expect(wrapper.vm.hasMore).toBe(false)
    wrapper.destroy()
  })

  test('articlelist prevents concurrent load-more double clicks and retries failed page without advancing or duplicating', async () => {
    const page2Fail = deferred()
    articleApi.articleList
      .mockResolvedValueOnce({
        rows: [{ id: LARGE_ID, title: 'P1', summary: 's1', categoryId: '2', categoryName: 'Java', viewCount: '1', createTime: '2026-11-23 08:00:00' }],
        total: '2'
      })
      .mockImplementationOnce(() => page2Fail.promise)
      .mockResolvedValueOnce({
        rows: [{ id: LARGE_ID_2, title: 'P2', summary: 's2', categoryId: '2', categoryName: 'Java', viewCount: '2', createTime: '2026-11-23 09:00:00' }],
        total: '2'
      })

    const wrapper = shallowMount(ArticleList, {
      mocks: {
        $route: { query: {} },
        $store: { state: { keywords: '' } }
      },
      stubs: ['el-row', 'el-col']
    })
    await flushPromises()
    expect(wrapper.vm.articleList.map(a => a.id)).toEqual([LARGE_ID])
    expect(wrapper.vm.nextPageNum).toBe(2)

    // Rapid double click -> single in-flight request for page 2
    wrapper.vm.addMoreFun()
    wrapper.vm.addMoreFun()
    expect(articleApi.articleList).toHaveBeenCalledTimes(2)
    expect(articleApi.articleList).toHaveBeenNthCalledWith(2, { pageNum: 2, pageSize: 10, categoryId: '0' })

    // Fail page 2 -> nextPageNum stays 2, retryList fetches page 2 and succeeds cleanly
    page2Fail.reject(new Error('timeout'))
    await flushPromises()
    expect(wrapper.vm.loadError).toBe('timeout')
    expect(wrapper.vm.nextPageNum).toBe(2)

    wrapper.vm.retryList()
    await flushPromises()
    expect(articleApi.articleList).toHaveBeenCalledTimes(3)
    expect(articleApi.articleList).toHaveBeenNthCalledWith(3, { pageNum: 2, pageSize: 10, categoryId: '0' })
    expect(wrapper.vm.articleList.map(a => a.id)).toEqual([LARGE_ID, LARGE_ID_2])
    expect(wrapper.vm.nextPageNum).toBe(3)
    expect(wrapper.vm.hasMore).toBe(false)
    wrapper.destroy()
  })

  test('UserProfile clears followPending cleanly when paginating articles while follow write is in flight', async () => {
    localStorage.setItem('userInfo', JSON.stringify({ id: '100' }))
    const followWrite1 = deferred()
    const followWrite2 = deferred()
    const page2Profile = deferred()

    socialApi.getUserProfile.mockReset()
    socialApi.getUserProfile
      .mockResolvedValueOnce({
        id: LARGE_ID,
        nickName: 'Author',
        relationStatus: 'AVAILABLE',
        followingCount: '1',
        followerCount: '2',
        viewerFollowStatus: 'AVAILABLE',
        viewerFollowing: false,
        articles: { rows: [{ id: '1', title: 'P1' }], total: '20' }
      })
      .mockImplementationOnce(() => page2Profile.promise)
      .mockResolvedValueOnce({
        id: LARGE_ID,
        nickName: 'Author',
        relationStatus: 'AVAILABLE',
        followingCount: '1',
        followerCount: '3',
        viewerFollowStatus: 'AVAILABLE',
        viewerFollowing: true,
        articles: { rows: [{ id: '2', title: 'P2' }], total: '20' }
      })
      .mockResolvedValueOnce({
        id: LARGE_ID,
        nickName: 'Author',
        relationStatus: 'AVAILABLE',
        followingCount: '1',
        followerCount: '3',
        viewerFollowStatus: 'AVAILABLE',
        viewerFollowing: true,
        articles: { rows: [{ id: '3', title: 'P3' }], total: '20' }
      })
    socialApi.getUserFollowing.mockReset()
    socialApi.getUserFollowing.mockResolvedValue({ rows: [], total: '0' })
    socialApi.followUser.mockReset()
    socialApi.followUser.mockImplementationOnce(() => followWrite1.promise)
    socialApi.unfollowUser.mockReset()
    socialApi.unfollowUser.mockImplementationOnce(() => followWrite2.promise)

    const wrapper = shallowMount(UserProfile, {
      mocks: {
        $route: { query: { userId: LARGE_ID }, fullPath: '/UserProfile?userId=' + LARGE_ID },
        $router: { push: jest.fn(), replace: jest.fn() },
        $store: { state: { errorImg: '' } }
      },
      stubs: ['sg-nav', 'el-row', 'el-col', 'el-button', 'el-pagination']
    })
    await flushPromises()
    expect(wrapper.vm.followState).toBe('not_following')
    expect(wrapper.vm.followPending).toBe(false)

    // Click follow -> write is in flight
    wrapper.vm.toggleFollow()
    expect(wrapper.vm.followPending).toBe(true)

    // Paginate to page 2 BEFORE follow write finishes; page 2 profile resolves first
    wrapper.vm.changeArticlePage(2)
    page2Profile.resolve({
      id: LARGE_ID,
      nickName: 'Author',
      relationStatus: 'AVAILABLE',
      followingCount: '1',
      followerCount: '2',
      viewerFollowStatus: 'AVAILABLE',
      viewerFollowing: false,
      articles: { rows: [{ id: '2', title: 'P2' }], total: '20' }
    })
    await flushPromises()
    expect(wrapper.vm.articles[0].id).toBe('2')
    expect(wrapper.vm.followPending).toBe(true)

    // Now follow write finishes -> followPending MUST clear and followState updates to 'following'
    followWrite1.resolve()
    await flushPromises()
    expect(wrapper.vm.followPending).toBe(false)
    expect(wrapper.vm.followState).toBe('following')

    // Also verify rejected write after concurrent pagination clears followPending cleanly
    wrapper.vm.toggleFollow()
    expect(wrapper.vm.followPending).toBe(true)
    wrapper.vm.changeArticlePage(3)
    await flushPromises()
    followWrite2.reject(new Error('network fail'))
    await flushPromises()
    expect(wrapper.vm.followPending).toBe(false)
    expect(wrapper.vm.followState).toBe('following')
    wrapper.destroy()
  })

  test('message preserves new article draft when previous article comment submit resolves late', async () => {
    const Vue = require('vue')
    const submitA = deferred()
    commentApi.getArticleComment.mockReset()
    commentApi.getArticleComment
      .mockResolvedValueOnce({ rows: [{ id: '1', content: 'A-c1', createBy: LARGE_ID, username: 'U', userInfoStatus: 'AVAILABLE', createTime: 't', children: [] }], total: '1' })
      .mockResolvedValueOnce({ rows: [{ id: '2', content: 'B-c1', createBy: LARGE_ID, username: 'U', userInfoStatus: 'AVAILABLE', createTime: 't', children: [] }], total: '1' })
    commentApi.sendComment.mockReset()
    commentApi.sendComment.mockImplementationOnce(() => submitA.promise)

    const route = Vue.observable({ name: 'DetailArticle', query: { aid: LARGE_ID }, fullPath: '/DetailArticle?aid=' + LARGE_ID })
    const wrapper = shallowMount(Message, {
      mocks: {
        $route: route,
        $store: { state: { errorImg: '' } }
      },
      stubs: ['el-input', 'el-row', 'el-col']
    })
    await flushPromises()

    // Submit comment on Article A
    wrapper.vm.textarea = 'Comment for A'
    wrapper.vm.sendMsg()
    expect(wrapper.vm.sending).toBe(true)
    expect(commentApi.sendComment).toHaveBeenCalledWith(0, LARGE_ID, -1, -1, -1, 'Comment for A')

    // Switch to Article B BEFORE Article A's submit completes and begin drafting on B
    route.query = { aid: LARGE_ID_2 }
    route.fullPath = '/DetailArticle?aid=' + LARGE_ID_2
    wrapper.vm.routeChange()
    await flushPromises()
    expect(wrapper.vm.sending).toBe(false)
    expect(wrapper.vm.textarea).toBe('')
    wrapper.vm.textarea = 'Draft for B'
    wrapper.vm.rootId = '2'

    // Article A's stale submit resolves -> MUST NOT clear Article B's draft, reply target, or reload B
    submitA.resolve({})
    await flushPromises()
    expect(wrapper.vm.textarea).toBe('Draft for B')
    expect(wrapper.vm.rootId).toBe('2')
    expect(wrapper.vm.sending).toBe(false)
    expect(commentApi.getArticleComment).toHaveBeenCalledTimes(2)
    wrapper.destroy()
  })
})
