jest.mock('element-ui', () => ({
  Notification: { error: jest.fn() },
  MessageBox: { confirm: jest.fn(() => Promise.resolve()) },
  Message: jest.fn()
}))
jest.mock('@/router', () => ({ currentRoute: { path: '/', fullPath: '/' }, replace: jest.fn() }))
jest.mock('@/store', () => ({ dispatch: jest.fn(() => Promise.resolve()) }))
jest.mock('@/utils/auth', () => ({ getToken: jest.fn(() => 'admin-token') }))

const service = require('@/utils/request').default
const { uploadImg } = require('@/api/content/upload')
const articleApi = require('@/api/content/article')
const tagApi = require('@/api/content/tag')
const linkApi = require('@/api/content/link')

const LARGE_ID = '9007199254740993'
const MAX_ID = '9223372036854775807'

test('uploadImg sends FormData img without manual boundary-less Content-Type header', async () => {
  const captured = []
  service.defaults.adapter = config => {
    captured.push({ url: config.url, method: config.method, data: config.data, headers: Object.assign({}, config.headers) })
    return Promise.resolve({ data: { code: 200, msg: '上传成功', data: 'https://cdn.example.invalid/a.png' }, status: 200, headers: {}, config })
  }
  const fakeFile = new Blob(['png-bytes'], { type: 'image/png' })
  const url = await uploadImg(fakeFile)
  expect(url).toBe('https://cdn.example.invalid/a.png')
  expect(captured).toHaveLength(1)
  expect(captured[0].url).toBe('/upload')
  expect(captured[0].method).toBe('post')
  expect(captured[0].data instanceof FormData).toBe(true)
  expect(captured[0].headers['Content-Type']).toBeUndefined()
  expect(captured[0].headers['content-type']).toBeUndefined()
  expect(captured[0].headers.token).toBe('admin-token')
})

test('admin content API wrappers preserve 64-bit int64 decimal string IDs', async () => {
  const calls = []
  service.defaults.adapter = config => {
    calls.push({ url: config.url, method: config.method, body: config.data ? JSON.parse(config.data) : null, token: config.headers.token })
    return Promise.resolve({ data: { code: 200, data: {} }, status: 200, headers: {}, config })
  }
  await articleApi.getArticle(MAX_ID)
  await articleApi.updateArticle({ id: MAX_ID, categoryId: LARGE_ID, tags: [LARGE_ID, MAX_ID] })
  await articleApi.updateArticleStatus(MAX_ID, '1')
  await articleApi.delArticle(LARGE_ID + ',' + MAX_ID)
  await tagApi.getTag(LARGE_ID)
  await tagApi.updateTag({ id: LARGE_ID, name: 'Java' })
  await linkApi.changeLinkStatus(MAX_ID, '0')

  expect(calls[0].url).toBe('/content/article/' + MAX_ID)
  expect(calls[1].body).toEqual({ id: MAX_ID, categoryId: LARGE_ID, tags: [LARGE_ID, MAX_ID] })
  expect(calls[2].url).toBe('/content/article/' + MAX_ID + '/status')
  expect(calls[3].url).toBe('/content/article/' + LARGE_ID + ',' + MAX_ID)
  expect(calls[4].url).toBe('/content/tag/' + LARGE_ID)
  expect(calls[5].body.id).toBe(LARGE_ID)
  expect(calls[6].body).toEqual({ id: MAX_ID, status: '0' })
  calls.forEach(c => expect(c.token).toBe('admin-token'))
})

describe('admin write article route-switch isolation (G8 P1)', () => {
  const { shallowMount } = require('@vue/test-utils')
  const WriteArticleView = require('@/views/content/article/write/index.vue').default

  function deferred() {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
  }

  function flushPromises() {
    return new Promise(resolve => setImmediate(resolve))
  }

  function mountWrite(initialId) {
    const mocks = {
      $route: { path: '/write', query: initialId ? { id: initialId } : {} },
      $router: { push: jest.fn() },
      $modal: { msgSuccess: jest.fn() },
      $message: { error: jest.fn(), success: jest.fn() }
    }
    const wrapper = shallowMount(WriteArticleView, {
      mocks,
      stubs: [
        'el-form', 'el-row', 'el-col', 'el-form-item', 'el-input',
        'el-select', 'el-option', 'el-radio-group', 'el-radio',
        'el-upload', 'el-button', 'mavon-editor'
      ]
    })
    return { wrapper, mocks }
  }

  test('switching /write?id=A to /write?id=B resets form, ignores slow A response, blocks submit until B loads, and only updates B', async () => {
    const reqA = deferred()
    const reqB = deferred()
    const updatedPayloads = []

    service.defaults.adapter = config => {
      if (config.url === '/content/category/listAllCategory' || config.url === '/content/tag/listAllTag') {
        return Promise.resolve({ data: { code: 200, data: [] }, status: 200, headers: {}, config })
      }
      if (config.url === '/content/article/' + LARGE_ID && config.method === 'get') {
        return reqA.promise.then(data => ({ data: { code: 200, data }, status: 200, headers: {}, config }))
      }
      if (config.url === '/content/article/' + MAX_ID && config.method === 'get') {
        return reqB.promise.then(data => ({ data: { code: 200, data }, status: 200, headers: {}, config }))
      }
      if (config.url === '/content/article' && config.method === 'put') {
        updatedPayloads.push(JSON.parse(config.data))
        return Promise.resolve({ data: { code: 200, data: null }, status: 200, headers: {}, config })
      }
      return Promise.resolve({ data: { code: 200, data: {} }, status: 200, headers: {}, config })
    }

    const { wrapper, mocks } = mountWrite(LARGE_ID)
    // While A is loading, submit must be blocked
    wrapper.vm.handleSubmit()
    expect(updatedPayloads).toHaveLength(0)

    // Switch route from A to B BEFORE A resolves (reused component via AppMain)
    wrapper.vm.$route.query = { id: MAX_ID }
    wrapper.vm.$options.watch.$route.call(wrapper.vm, { path: '/write', query: { id: MAX_ID } })
    expect(wrapper.vm.aId).toBe(MAX_ID)
    expect(wrapper.vm.form.title).toBe('')
    expect(wrapper.vm.form.content).toBe('')
    expect(wrapper.vm.canSubmitEdit).toBe(false)

    // Slow response for A arrives AFTER switch to B -> must be ignored!
    reqA.resolve({ id: LARGE_ID, title: 'Article A', content: 'Body A', categoryId: '2', tags: ['3'] })
    await flushPromises()
    expect(wrapper.vm.form.title).toBe('')
    expect(wrapper.vm.form.content).toBe('')
    expect(wrapper.vm.loadedArticleId).toBe('')
    wrapper.vm.handleSubmit()
    expect(updatedPayloads).toHaveLength(0)

    // B resolves -> now form has B and updateArticle sends B's own data with id=B
    reqB.resolve({ id: MAX_ID, title: 'Article B', content: 'Body B', categoryId: '5', tags: ['7'] })
    await flushPromises()
    expect(wrapper.vm.form.title).toBe('Article B')
    expect(wrapper.vm.form.content).toBe('Body B')
    expect(wrapper.vm.loadedArticleId).toBe(MAX_ID)
    expect(wrapper.vm.canSubmitEdit).toBe(true)

    wrapper.vm.handleSubmit()
    await flushPromises()
    expect(updatedPayloads).toHaveLength(1)
    expect(updatedPayloads[0].id).toBe(MAX_ID)
    expect(updatedPayloads[0].title).toBe('Article B')
    expect(updatedPayloads[0].content).toBe('Body B')
    expect(mocks.$router.push).toHaveBeenCalledWith({ path: '/content/article' })
    wrapper.destroy()
  })

  test('switching from edit to failed load blocks update and switching to create resets to addArticle', async () => {
    const addedPayloads = []
    const updatedPayloads = []
    let failB = true

    service.defaults.adapter = config => {
      if (config.url === '/content/category/listAllCategory' || config.url === '/content/tag/listAllTag') {
        return Promise.resolve({ data: { code: 200, data: [] }, status: 200, headers: {}, config })
      }
      if (config.url === '/content/article/' + LARGE_ID && config.method === 'get') {
        return Promise.resolve({
          data: { code: 200, data: { id: LARGE_ID, title: 'Loaded A', content: 'Content A', categoryId: '2', tags: [] } },
          status: 200,
          headers: {},
          config
        })
      }
      if (config.url === '/content/article/' + MAX_ID && config.method === 'get') {
        if (failB) return Promise.reject(new Error('load failed'))
        return Promise.resolve({
          data: { code: 200, data: { id: MAX_ID, title: 'Recovered B', content: 'Content B', categoryId: '2', tags: [] } },
          status: 200,
          headers: {},
          config
        })
      }
      if (config.url === '/content/article' && config.method === 'put') {
        updatedPayloads.push(JSON.parse(config.data))
        return Promise.resolve({ data: { code: 200, data: null }, status: 200, headers: {}, config })
      }
      if (config.url === '/content/article' && config.method === 'post') {
        addedPayloads.push(JSON.parse(config.data))
        return Promise.resolve({ data: { code: 200, data: null }, status: 200, headers: {}, config })
      }
      return Promise.resolve({ data: { code: 200, data: {} }, status: 200, headers: {}, config })
    }

    const { wrapper } = mountWrite(LARGE_ID)
    await flushPromises()
    expect(wrapper.vm.form.title).toBe('Loaded A')

    // Switch to B where GET fails -> A is cleared and update is strictly forbidden
    wrapper.vm.$route.query = { id: MAX_ID }
    wrapper.vm.$options.watch.$route.call(wrapper.vm, { path: '/write', query: { id: MAX_ID } })
    await flushPromises()
    expect(wrapper.vm.form.title).toBe('')
    expect(wrapper.vm.loadError).toBeTruthy()
    expect(wrapper.vm.canSubmitEdit).toBe(false)
    wrapper.vm.handleSubmit()
    expect(updatedPayloads).toHaveLength(0)

    // Retry loading B -> succeeds
    failB = false
    await wrapper.vm.loadArticleForCurrentId()
    await flushPromises()
    expect(wrapper.vm.form.title).toBe('Recovered B')
    expect(wrapper.vm.canSubmitEdit).toBe(true)

    // Switch to /write (create mode) -> clears B and calls addArticle without id
    wrapper.vm.$route.query = {}
    wrapper.vm.$options.watch.$route.call(wrapper.vm, { path: '/write', query: {} })
    await flushPromises()
    expect(wrapper.vm.aId).toBe('')
    expect(wrapper.vm.form.title).toBe('')
    wrapper.vm.form.title = 'Brand New'
    wrapper.vm.form.content = 'Fresh Body'
    wrapper.vm.handleSubmit()
    await flushPromises()
    expect(updatedPayloads).toHaveLength(0)
    expect(addedPayloads).toHaveLength(1)
    expect(addedPayloads[0].id).toBeUndefined()
    expect(addedPayloads[0].title).toBe('Brand New')
    wrapper.destroy()
  })

  test('handleUpload notifies Element-UI onError on failure so retry is not blocked by limit=1', async () => {
    service.defaults.adapter = config => {
      if (config.url === '/upload') return Promise.reject(new Error('Upload unavailable'))
      return Promise.resolve({ data: { code: 200, data: [] }, status: 200, headers: {}, config })
    }
    const { wrapper, mocks } = mountWrite('')
    await flushPromises()
    const onError = jest.fn()
    const onSuccess = jest.fn()
    const handle = wrapper.vm.handleUpload({ file: new Blob(['x'], { type: 'image/png' }), onError, onSuccess })
    expect(handle && typeof handle.abort === 'function').toBe(true)
    expect(handle.then).toBeUndefined()
    await flushPromises()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onSuccess).not.toHaveBeenCalled()
    expect(wrapper.vm.fileList).toEqual([])
    expect(mocks.$message.error).toHaveBeenCalledWith('Upload unavailable')
    wrapper.destroy()
  })

  test('handleUpload returns abortable handle and ignores stale response/error after user removes before completion', async () => {
    const upload1 = deferred()
    const upload2 = deferred()
    let calls = 0
    service.defaults.adapter = config => {
      if (config.url === '/upload') {
        calls++
        const d = calls === 1 ? upload1 : upload2
        return d.promise.then(url => ({ data: { code: 200, data: url }, status: 200, headers: {}, config }))
      }
      return Promise.resolve({ data: { code: 200, data: [] }, status: 200, headers: {}, config })
    }
    const { wrapper, mocks } = mountWrite('')
    await flushPromises()

    // Upload 1 starts, user clicks remove before completion -> Element UI invokes handle.abort() and fileRemove()
    const onSuccess1 = jest.fn()
    const onError1 = jest.fn()
    const file1 = Object.assign(new Blob(['png1'], { type: 'image/png' }), { name: 'first.png' })
    const handle1 = wrapper.vm.handleUpload({ file: file1, onSuccess: onSuccess1, onError: onError1 })
    expect(typeof handle1.abort).toBe('function')
    expect(() => handle1.abort()).not.toThrow()
    wrapper.vm.fileRemove()

    // Stale upload 1 resolves AFTER abort -> must be completely ignored!
    upload1.resolve('https://cdn.example.invalid/stale-1.png')
    await flushPromises()
    expect(wrapper.vm.form.thumbnail).toBe('')
    expect(wrapper.vm.fileList).toEqual([])
    expect(onSuccess1).not.toHaveBeenCalled()
    expect(onError1).not.toHaveBeenCalled()

    // Upload 2 starts and is aborted before rejecting -> late rejection must also be ignored with no error toast!
    const onSuccess2 = jest.fn()
    const onError2 = jest.fn()
    const handle2 = wrapper.vm.handleUpload({ file: file1, onSuccess: onSuccess2, onError: onError2 })
    handle2.abort()
    upload2.reject(new Error('late network error'))
    await flushPromises()
    expect(onSuccess2).not.toHaveBeenCalled()
    expect(onError2).not.toHaveBeenCalled()
    expect(mocks.$message.error).not.toHaveBeenCalled()
    wrapper.destroy()
  })
})
