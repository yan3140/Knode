import { constantRoutes } from '@/router'
import { getRouters } from '@/api/menu'
import Layout from '@/layout/index'
import ParentView from '@/components/ParentView'
import InnerLink from '@/layout/components/InnerLink'

const permission = {
  state: {
    routes: [],
    addRoutes: [],
    defaultRoutes: [],
    topbarRouters: [],
    sidebarRouters: []
  },
  mutations: {
    SET_ROUTES: (state, routes) => {
      state.addRoutes = routes
      state.routes = constantRoutes.concat(routes)
    },
    SET_DEFAULT_ROUTES: (state, routes) => {
      state.defaultRoutes = constantRoutes.concat(routes)
    },
    SET_TOPBAR_ROUTES: (state, routes) => {
      // 顶部导航菜单默认添加统计报表栏指向首页
      const index = [{
        path: 'index',
        meta: { title: '统计报表', icon: 'dashboard' }
      }]
      state.topbarRouters = routes.concat(index)
    },
    SET_SIDEBAR_ROUTERS: (state, routes) => {
      state.sidebarRouters = routes
    }
  },
  actions: {
    // 生成路由
    GenerateRoutes({ commit }) {
      return new Promise((resolve, reject) => {
        // 向后端请求路由数据   直接获取routers 暂时注释
        // getRouters().then(res => {
        //   const sdata = JSON.parse(JSON.stringify(res.routers))
        //   const rdata = JSON.parse(JSON.stringify(res.routers))
        //   const sidebarRoutes = filterAsyncRouter(sdata)
        //   const rewriteRoutes = filterAsyncRouter(rdata, false, true)
        //   rewriteRoutes.push({ path: '*', redirect: '/404', hidden: true })
        //   commit('SET_ROUTES', rewriteRoutes)
        //   commit('SET_SIDEBAR_ROUTERS', constantRoutes.concat(sidebarRoutes))
        //   commit('SET_DEFAULT_ROUTES', sidebarRoutes)
        //   commit('SET_TOPBAR_ROUTES', sidebarRoutes)
        //   resolve(rewriteRoutes)
        // })

        getRouters().then(res => {
          // 获取menus 自己组装routers
          const routers = buildRouterTree(res.menus)
          const sdata = JSON.parse(JSON.stringify(routers))
          const rdata = JSON.parse(JSON.stringify(routers))
          const sidebarRoutes = filterAsyncRouter(sdata)
          const rewriteRoutes = filterAsyncRouter(rdata, false, true)
          // 本项目未提供 /404 页面。动态菜单失配时回到工作台，避免通配路由
          // 重定向到不存在的页面而递归匹配。
          rewriteRoutes.push({ path: '*', redirect: '/dashboard', hidden: true })
          commit('SET_ROUTES', rewriteRoutes)
          commit('SET_SIDEBAR_ROUTERS', constantRoutes.concat(sidebarRoutes))
          commit('SET_DEFAULT_ROUTES', sidebarRoutes)
          commit('SET_TOPBAR_ROUTES', sidebarRoutes)
          resolve(rewriteRoutes)
        }).catch(reject)
      })
    }
  }
}
function buildRouterTree(menus) {
  // 遍历menus
  var routers = []
  for (var i = 0; i < menus.length; i++) {
    var menu = menus[i]
    var router = {}
    router.hidden = menu.visible === '1'
    router.name = getRouterName(menu)
    router.path = getRouterPath(menu)
    router.component = getComponent(menu)
    router.meta = getComponentMeta(menu)

    var cMenus = menu.children
    if (cMenus && cMenus.length > 0 && menu.menuType === 'M') {
      router.alwaysShow = true
      router.children = buildRouterTree(cMenus)
    }

    routers.push(router)
  }
  return routers
}

function getComponentMeta(menu) {
  var meta = {}
  meta.title = menu.menuName
  meta.icon = menu.icon
  meta.link = menu.link
  return meta
}

function getComponent(menu) {
  if (menu.component) {
    return menu.component
  }
  if (!menu.component && String(menu.parentId) !== '0') {
    return 'InnerLink'
  }
  return 'Layout'
}

function getRouterPath(menu) {
  if (String(menu.parentId) === '0') {
    return '/' + menu.path
  }
  return menu.path
}
function getRouterName(menu) {
  return firstToUpper(menu.path)
}
// 字符串首字母转大写，后面字母小写
/**
 * 方法一：js字符串切割
 * @param {*} str
 */
function firstToUpper(str) {
  return str.trim().toLowerCase().replace(str[0], str[0].toUpperCase())
}
// 遍历后台传来的路由字符串，转换为组件对象
function filterAsyncRouter(asyncRouterMap, lastRouter = false, type = false) {
  return asyncRouterMap.filter(route => {
    if (type && route.children) {
      route.children = filterChildren(route.children)
    }
    if (route.component) {
      // Layout ParentView 组件特殊处理
      if (route.component === 'Layout') {
        route.component = Layout
      } else if (route.component === 'ParentView') {
        route.component = ParentView
      } else if (route.component === 'InnerLink') {
        route.component = InnerLink
      } else {
        route.component = loadView(route.component)
      }
    }
    if (route.children != null && route.children && route.children.length) {
      route.children = filterAsyncRouter(route.children, route, type)
    } else {
      delete route['children']
      delete route['redirect']
    }
    return true
  })
}

function filterChildren(childrenMap, lastRouter = false) {
  var children = []
  childrenMap.forEach((el, index) => {
    if (el.children && el.children.length) {
      if (el.component === 'ParentView' && !lastRouter) {
        el.children.forEach(c => {
          c.path = el.path + '/' + c.path
          if (c.children && c.children.length) {
            children = children.concat(filterChildren(c.children, c))
            return
          }
          children.push(c)
        })
        return
      }
    }
    if (lastRouter) {
      el.path = lastRouter.path + '/' + el.path
    }

    children = children.concat(el)
  })
  return children
}

export const loadView = (view) => {
  if (process.env.NODE_ENV === 'development') {
    return (resolve) => require([`@/views/${view}`], resolve)
  }
  return () => import(`@/views/${view}`)
}

export default permission
