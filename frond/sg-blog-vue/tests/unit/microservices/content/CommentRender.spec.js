jest.mock('@/api/comment.js', () => ({
  sendComment: jest.fn(),
  getArticleComment: jest.fn(),
  getLinkComment: jest.fn()
}))
jest.mock('@/utils/auth.js', () => ({ getToken: jest.fn() }))

const { shallowMount } = require('@vue/test-utils')
const Message = require('@/components/message.vue').default

describe('comment rendering', () => {
  it('renders root and reply text literally and only uses known emoji assets', () => {
    const rootText = '<img src=x onerror=alert(1)> [\u5fae\u7b11] [\u672a\u77e5]'
    const replyText = '<script>alert(1)</script> [\u54c8\u54c8] & text'
    const wrapper = shallowMount(Message, {
      mocks: { $route: { query: {} }, $store: { state: { errorImg: '' } } },
      stubs: ['el-input', 'el-row', 'el-col'],
      methods: { routeChange: jest.fn() },
      data () {
        return {
          commentList: [{
            id: '1', createBy: '42', username: 'root', createTime: 'now', content: rootText,
            children: [{ id: '2', createBy: '77', username: 'reply', toCommentUserName: 'root', createTime: 'now', content: replyText }]
          }]
        }
      }
    })
    const root = wrapper.find('#comment-1 > article section p')
    const reply = wrapper.find('#comment-2 > article section p')
    expect(root.text()).toContain('<img src=x onerror=alert(1)>')
    expect(root.text()).toContain('[\u672a\u77e5]')
    expect(root.findAll('img').length).toBe(1)
    expect(root.find('img').attributes('src')).toBe('static/img/emot/image/weixiao.gif')
    expect(root.find('[onerror]').exists()).toBe(false)
    expect(reply.text()).toContain('<script>alert(1)</script>')
    expect(reply.text()).toContain('& text')
    expect(reply.find('script').exists()).toBe(false)
    expect(reply.findAll('img').length).toBe(1)
    expect(reply.find('img').attributes('src')).toBe('static/img/emot/image/haha.gif')
    wrapper.destroy()
  })
})
