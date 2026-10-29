import Vue from 'vue'
import Router from 'vue-router'
import { getToken } from '@/utils/auth'
Vue.use(Router)

const router = new Router({
	scrollBehavior(to, from, savePosition) { // 在点击浏览器的“前进/后退”，或者切换导航的时候触发。
		if (to.name === 'Login') {
			return { x: 0, y: 0 }
		}
		if (to.name === 'KnowledgeChat') {
			return { x: 0, y: 0 }
		}
		if (savePosition) {
			return savePosition;
		} else {
            var top;
            if (window.innerWidth >= 700) {
                 top = 676
            } else {
                 top = 267
            }
			return {
				x: 0,
				y: top
			}
		}
	},
	routes: [{
			path: '/',
			component: resolve => require(['../pages/Home.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'Home'
		}, //首页
		{
			path: '/Home',
			redirect: '/'
		}, //首页
		{
			path: '/Share',
			component: resolve => require(['../pages/Share.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'Share'
		}, //分类
		{
			path: '/DetailArticle',
			component: resolve => require(['../pages/DetailArticle.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'DetailArticle'
		}, //分享详情
		{
			path: '/Reward',
			component: resolve => require(['../pages/Reward.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'Reward'
		}, //赞赏
		{
			path: '/FriendsLink',
			component: resolve => require(['../pages/FriendsLink.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'FriendsLink'
		}, //友链
		{
			path: '/Search',
			component: resolve => require(['../pages/Search.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'Search'
		}, //文章搜索


		{
			path: '/Login',
			component: resolve => require(['../pages/Login.vue'], resolve),
			meta: {
				auth: false
			},
			name: 'Login'
		}, //注册登录
		{
			path: '/UserInfo',
			component: resolve => require(['../pages/UserInfo.vue'], resolve),
			meta: {
				auth: true
			},
			name: 'UserInfo'
		}, //用户个人中心
		{
			path: '/Notifications',
			component: resolve => require(['../pages/Notifications.vue'], resolve),
			meta: {
				auth: true
			},
			name: 'Notifications'
		}, //通知中心
		{
			path: '/KnowledgeChat',
			component: resolve => require(['../pages/KnowledgeChat.vue'], resolve),
			meta: {
				auth: true
			},
			name: 'KnowledgeChat'
		}, //知识库问答
		{
			path: '/UserProfile',
			component: resolve => require(['../pages/UserProfile.vue'], resolve),
			meta: { auth: false },
			name: 'UserProfile'
		},
		{
			path: '/WriteArticle',
			component: resolve => require(['../pages/WriteArticle.vue'], resolve),
			meta: { auth: true },
			name: 'WriteArticle'
		},

	]
})

router.beforeEach((to, from, next) => {
	if (to.meta.auth && !getToken()) {
		next({
			name: 'Login',
			query: { login: '1', redirect: to.fullPath }
		})
		return
	}
	if (to.name === 'Login' && getToken()) {
		next('/')
		return
	}
	next()
})

export default router
