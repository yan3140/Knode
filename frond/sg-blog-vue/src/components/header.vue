<!-- 头部公用 -->
<template>
<div :class="{ 'compact-header': compact }">
	<div class="headBack">
		<el-row class="container">
			<el-col :span="24">
				<!-- pc端导航 -->
				<div class="headBox">
					<el-menu :default-active="activeIndex" class="el-menu-demo" mode="horizontal" @select="handleSelect" :router="true">
						<el-menu-item index="/Home"><i class="fa fa-wa fa-home"></i> 首页</el-menu-item>
						<el-submenu index="/Share">
							<template slot="title"><i class="fa fa-wa fa-archive"></i> 分类</template>
							<el-menu-item v-for="(item,index) in classListObj" :key="'class1'+index" :index="'/Share?classId='+item.id">{{item.name}}</el-menu-item>
						</el-submenu>
						<el-menu-item index="/KnowledgeChat"><i class="fa fa-wa fa-comments-o"></i> 知识问答</el-menu-item>
						<el-menu-item index="/Reward"><i class="fa fa-wa fa-cny"></i> 赞赏</el-menu-item>
						<el-menu-item index="/Friendslink"><i class="fa fa-wa fa-users"></i>友链</el-menu-item>

						<div class="userInfo">
							<div class="header-search" :class="{ open: searchOpen }">
								<el-input
									v-model.trim="searchKeyword"
									size="small"
									placeholder="搜索文章"
									clearable
									@keyup.enter.native="goSearch"
									@clear="closeSearch"
								/>
								<button class="search-trigger" type="button" aria-label="搜索文章" @click="toggleSearch">
									<i class="fa fa-search"></i>
								</button>
							</div>
							<a :href="adminUrl" class="admin-entry"><i class="fa fa-fw fa-cog"></i>管理后台</a>
							<a v-show="haslogin" href="javascript:void(0);" class="notification-entry" title="通知中心" @click="openNotifications">
								<i class="fa fa-fw fa-bell-o"></i>
								<span v-if="unreadCount" class="notification-badge">{{ unreadBadge }}</span>
							</a>
							<div v-show="!haslogin" class="nologin">
								<a href="javascript:void(0);" @click="logoinFun(1)">登录&nbsp;</a>|<a href="javascript:void(0);" @click="logoinFun(0)">&nbsp;注册</a>
							</div>
							<div v-show="haslogin" class="haslogin">
								<a href="javascript:void(0);" class="header-avatar" @click="goMyProfile">
									<img v-if="userInfo.avatar" :src="userInfo.avatar" :onerror="$store.state.errorImg" alt="我的主页">
									<i v-else class="fa fa-fw fa-user-circle userImg"></i>
								</a>
								<ul class="haslogin-info">
									<li>
										<a href="javascript:void(0);" @click="goMyProfile">我的主页</a>
									</li>
									<li>
										<a href="#/UserInfo">个人设置</a>
									</li>
									<li>
										<a href="#/WriteArticle">写博客</a>
									</li>
									<li>
										<a href="javascript:void(0);" @click="userlogout">退出登录</a>
									</li>
								</ul>
							</div>
						</div>
					</el-menu>
				</div>
			</el-col>
		</el-row>
		<div class="mobile-actions">
			<button v-show="haslogin" class="mobile-agent-entry" type="button" aria-label="知识问答" @click="$router.push('/KnowledgeChat')">
				<i class="fa fa-comments-o"></i>
			</button>
			<button class="mobile-search-trigger" type="button" aria-label="搜索文章" @click="toggleSearch">
				<i class="fa fa-search"></i>
			</button>
			<button v-show="haslogin" class="mobile-notification-entry" type="button" aria-label="通知中心" @click="openNotifications">
				<i class="fa fa-bell-o"></i><span v-if="unreadCount" class="notification-badge">{{ unreadBadge }}</span>
			</button>
			<a :href="adminUrl" class="mobile-admin-entry"><i class="fa fa-fw fa-cog"></i>管理后台</a>
		</div>
		<div v-show="searchOpen" class="mobile-search-panel">
			<el-input
				v-model.trim="searchKeyword"
				placeholder="搜索文章"
				clearable
				@keyup.enter.native="goSearch"
				@clear="closeSearch"
			>
				<el-button slot="append" icon="el-icon-search" aria-label="提交搜索" @click="goSearch"></el-button>
			</el-input>
		</div>
	</div>
	<div class="headImgBox" :style="{backgroundImage:this.$store.state.themeObj.top_image?'url('+this.$store.state.themeObj.top_image+')':'url(static/img/headbg05.jpg)'}">
		<div class="scene">
			<div><span id="luke"></span></div>
		</div>
		<div class="h-information">

                    <img :src="this.$store.state.themeObj.head_portrait?this.$store.state.themeObj.head_portrait:'static/img/tou.png'" alt="">

			<h2 class="h-description">

                        {{this.$store.state.themeObj.autograph?this.$store.state.themeObj.autograph:"三更灯火五更鸡，正是男儿读书时"}}

                </h2>
		</div>
	</div>
</div>
</template>
<script>
import {logout} from '../api/user'
import {getUnreadCount} from '../api/social'
import {removeToken} from '../utils/auth'
import {getCategoryList} from '../api/category'
import {
	Typeit
} from '../utils/plug.js'
export default {
	props: {
		compact: {
			type: Boolean,
			default: false
		}
	},
	data() { //选项 / 数据
		return {
			userInfo: '', //用户信息
			haslogin: false, //是否已登录
			classListObj: '', //分类
			activeIndex: '/', //当前选择的路由模块
			state: '', //icon点击状态
			pMenu: true, //手机端菜单打开
			// path:'',//当前打开页面的路径
						input: '', //input输入内容
			searchKeyword: '',
						searchOpen: false,
			unreadCount: 0,
			headBg: 'url(static/img/headbg05.jpg)', //头部背景图
			headTou: '', //头像
			projectList: '', //项目列表
			adminUrl: process.env.ADMIN_URL || '/admin/#/login'
		}
	},
	watch: {

	},
	methods: { //事件处理器
		handleOpen(key, keyPath) { //分组菜单打开
			// console.log(key, keyPath);
		},
		handleClose(key, keyPath) { //分组菜单关闭
			// console.log(key, keyPath);
		},
			searchChangeFun(e) { //input change 事件
			// console.log(e)
			if (this.input == '') {
				this.$store.state.keywords = '';
				this.$router.push({path:'/'});
			}
			},
			toggleSearch() {
				if (this.searchOpen && this.searchKeyword) {
					this.goSearch()
					return
				}
				this.searchOpen = !this.searchOpen
			},
			closeSearch() {
				this.searchOpen = false
			},
			goSearch() {
				if (!this.searchKeyword) {
					this.searchOpen = true
					return
				}
				this.$router.push({ path: '/Search', query: { keyword: this.searchKeyword, page: 1 } })
				this.searchOpen = false
			},
			loadUnreadCount() {
				if (!this.haslogin) {
					this.unreadCount = 0
					return
				}
				getUnreadCount().then(response => {
					this.unreadCount = response.unreadCount || 0
				}).catch(() => {})
			},
			openNotifications() {
				this.$router.push({ path: '/Notifications' })
			},
			goMyProfile() {
				if (this.userInfo && this.userInfo.id) {
					this.$router.push({ path: '/UserProfile', query: { userId: this.userInfo.id } })
				}
			},
		getCategoryList(){
			getCategoryList().then((response)=>{
				this.classListObj = response
			})
		},
		handleSelect(key, keyPath) { //pc菜单选择
			//    console.log(key, keyPath);
		},
		logoinFun: function(msg) { //用户登录和注册跳转
			// console.log(msg);
			this.$router.push({
				name: 'Login',
				query: { login: String(msg), redirect: this.$route.fullPath }
			});
		},
		// 用户退出登录
		userlogout: function() {
			var that = this;
			this.$confirm('是否确认退出?', '退出提示', {
				confirmButtonText: '确定',
				cancelButtonText: '取消',
				type: 'warning'
			}).then(() => {
				// console.log(that.$route.path);

				return logout().then((response)=>{
					removeToken()
					localStorage.removeItem('userInfo');
					that.haslogin = false;
					window.location.reload();
						that.$message({
							type: 'success',
							message: '退出成功!'
						});
					if (that.$route.path == '/UserInfo') {
						that.$router.push({
							path: '/'
						});
					}
				})
			}).catch(() => {
				//
			});

		},
		routeChange: function() {
			var that = this;
			that.pMenu = true
			this.activeIndex = this.$route.path == '/' ? '/Home' : this.$route.path;
			if (localStorage.getItem('userInfo')) { //存储用户信息
				that.haslogin = true;
				that.userInfo = JSON.parse(localStorage.getItem('userInfo'));
				// console.log(that.userInfo);
			} else {
				that.haslogin = false;
			}
			this.loadUnreadCount()
			//获取分类
			this.getCategoryList()

			if ((this.$route.name == "Share" || this.$route.name == "Home") && this.$store.state.keywords) {
				this.input = this.$store.state.keywords;
			} else {
				this.input = '';
				this.$store.state.keywords = '';
			}
			if (this.$route.name === 'Search') {
				this.searchKeyword = this.$route.query.keyword || ''
			}
		}
	},
	components: { //定义组件

	},
	watch: {
		// 如果路由有变化，会再次执行该方法
		'$route': 'routeChange'
	},
	created() { //生命周期函数
		//判断当前页面是否被隐藏
		var that = this;
		var hiddenProperty = 'hidden' in document ? 'hidden' :
			'webkitHidden' in document ? 'webkitHidden' :
			'mozHidden' in document ? 'mozHidden' :
			null;
		var visibilityChangeEvent = hiddenProperty.replace(/hidden/i, 'visibilitychange');
		var onVisibilityChange = function() {
			if (!document[hiddenProperty]) { //被隐藏
				if (that.$route.path != '/DetailShare') {
					if (localStorage.getItem('userInfo')) {
						that.haslogin = true;
					} else {
						that.haslogin = false;
					}
				}
			}
		}
		document.addEventListener(visibilityChangeEvent, onVisibilityChange);
		window.addEventListener('notification-read', this.loadUnreadCount)
		// console.log();
		this.routeChange();


	},
	beforeDestroy() {
		clearTimeout(this._typingTimer)
		window.removeEventListener('notification-read', this.loadUnreadCount)
	},
	computed: {
		unreadBadge() {
			return this.unreadCount > 99 ? '99+' : this.unreadCount
		}
	},
	mounted() { //页面元素加载完成
		if (this.compact) return
		this._typingTimer = setTimeout(() => {
			this._typingTimer = null
			const target = this.$el.querySelector('#luke')
			if (target) Typeit(this.$store.state.themeObj.user_start, target)
		}, 500);
	}
}
</script>

<style>
/*********头部导航栏********/

/*头部导航栏盒子*/

.headBack {
	width: 100%;
	background: rgba(40, 42, 44, 0.6);
	/*margin-bottom:30px;*/
	box-shadow: 0 2px 4px 0 rgba(0, 0, 0, .12), 0 0 6px 0 rgba(0, 0, 0, .04);
	position: fixed;
	left: 0;
	top: 0;
	right: 0;
	z-index: 100;
}

.headBox li.is-active {
	/*background: #48456C;*/
	background: rgba(73, 69, 107, 0.7);
}

.el-menu--horizontal>.el-submenu.is-active .el-submenu__title {
	border-bottom: none!important;
}

.headBox .el-menu {
	background: transparent;
	border-bottom: none!important;
}

.headBox .el-menu-demo li.el-menu-item,
.headBox .el-menu--horizontal .el-submenu .el-submenu__title {
	height: 38px;
	line-height: 38px;
	border-bottom: none!important;

}

.headBox .el-submenu li.el-menu-item {
	height: 38px;
	line-height: 38px;
}

.headBox li .fa-wa {
	vertical-align: baseline;
}

.headBox ul li.el-menu-item,
.headBox ul li.el-menu-item.is-active,
.headBox ul li.el-menu-item:hover,
.headBox .el-submenu div.el-submenu__title,
.headBox .el-submenu__title i.el-submenu__icon-arrow {
	color: #fff;
}

.headBox .el-menu--horizontal .el-submenu>.el-menu {
	top: 38px;
	border: none;
	padding: 0;
}

.headBox>ul li.el-menu-item:hover,
.headBox>ul li.el-submenu:hover .el-submenu__title {
	background: #48456C;
	border-bottom: none;
}

.headBox>ul .el-submenu .el-menu,
.headBox>ul .el-submenu .el-menu .el-menu-item {
	background: #48456C;
}

.headBox>ul .el-submenu .el-menu .el-menu-item {
	min-width: 0;
}

.headBox>ul .el-submenu .el-menu .el-menu-item:hover {
	background: #64609E;
}

/*pc搜索框*/

.headBox .pcsearchbox {
	padding: 0;
	max-width: 170px;
	/*min-width: 30px;*/
	height: 100%;
	line-height: 38px;
	position: absolute;
	top: 0;
	right: 0;
	cursor: pointer;
}

.headBox .pcsearchbox:hover .pcsearchinput {
	opacity: 1;
	/*transform: scaleX(1);*/
	visibility: visible;
}

.headBox .pcsearchbox i.pcsearchicon {
	color: #fff;
	padding-left: 10px;
}

.headBox .pcsearchbox .pcsearchinput {
	width: 180px;
	padding: 10px 20px 10px 20px;
	background: rgba(40, 42, 44, 0.6);
	border-radius: 0 0 2px 2px;
	position: absolute;
	right: 0;
	top: 38px;
	opacity: 0;
	visibility: hidden;
	/*transform: scaleX(0);*/
	transform-origin: right;
	transition: all 0.3s ease-out;
}

.headBox .pcsearchbox .hasSearched {
	opacity: 1;
	/*transform: scaleX(1);*/
	visibility: visible;
}

.headBox .pcsearchbox .el-input {
	width: 100%;
}

.headBox .el-input__inner {
	height: 30px;
	border: none;
	background: #fff;
	/*border: 1px solid #333;*/
	border-radius: 2px;
	padding-right: 10px;
}

.headBox .userInfo {
	height: 100%;
	line-height: 38px;
	position: absolute;
	right: 30px;
	top: 0;
	color: #fff;
	display: flex;
	align-items: center;
	gap: 14px;
}

.headBox .header-search {
	position: relative;
	display: flex;
	align-items: center;
	width: 28px;
	height: 28px;
}

.headBox .header-search.open {
	width: 28px;
}

.headBox .header-search .el-input {
	position: absolute;
	right: 0;
	top: 34px;
	width: 220px;
	opacity: 0;
	visibility: hidden;
	pointer-events: none;
	transform: translateY(-4px);
	transition: opacity .2s ease, transform .2s ease, visibility .2s ease;
}

.headBox .header-search .el-input__inner {
	padding: 0 10px;
	box-shadow: 0 8px 20px rgba(0, 0, 0, .2);
}

.headBox .header-search.open .el-input {
	opacity: 1;
	visibility: visible;
	pointer-events: auto;
	transform: translateY(0);
}

.headBox .search-trigger {
	flex: 0 0 28px;
	width: 28px;
	height: 28px;
	padding: 0;
	border: 0;
	border-radius: 50%;
	color: #fff;
	background: rgba(20, 22, 24, .25);
	cursor: pointer;
}

.headBox .search-trigger:hover {
	background: rgba(255, 255, 255, .2);
}

.headBox .userInfo a {
	color: #fff;
	font-size: 13px;
	transition: all 0.2s ease-out;
}

.headBox .userInfo a:hover {
	color: #ffd6c8;
}

.headBox .userInfo .admin-entry {
	display: inline-flex;
	align-items: center;
	height: 28px;
	padding: 0 11px;
	border: 1px solid rgba(255, 255, 255, .34);
	border-radius: 15px;
	background: rgba(20, 22, 24, .2);
	font-weight: 600;
}

.headBox .notification-entry,
.mobile-notification-entry {
	position: relative;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	width: 28px;
	height: 28px;
	border: 1px solid rgba(255, 255, 255, .34);
	border-radius: 50%;
	color: #fff;
	background: rgba(20, 22, 24, .2);
}

.headBox .notification-entry:hover {
	color: #fff;
	background: rgba(255, 255, 255, .2);
}

.notification-badge {
	position: absolute;
	top: -6px;
	right: -9px;
	min-width: 15px;
	height: 15px;
	padding: 0 3px;
	border: 1px solid rgba(255,255,255,.85);
	border-radius: 9px;
	color: #fff;
	background: #e55f67;
	font-size: 10px;
	font-weight: 700;
	line-height: 15px;
	text-align: center;
}

.mobile-actions, .mobile-search-panel { display: none; }

@media screen and (max-width: 700px) {
	.mobile-actions {
		display: flex;
		align-items: center;
		gap: 7px;
		position: absolute;
		right: 14px;
		top: 5px;
		z-index: 101;
	}
	.mobile-admin-entry {
		display: inline-flex;
		align-items: center;
		height: 28px;
		padding: 0 11px;
		border: 1px solid rgba(255,255,255,.38);
		border-radius: 15px;
		color: #fff;
		background: rgba(20,22,24,.32);
		font-size: 12px;
		font-weight: 600;
	}
	.mobile-search-trigger {
		width: 28px;
		height: 28px;
		padding: 0;
		border: 1px solid rgba(255,255,255,.38);
		border-radius: 50%;
		color: #fff;
		background: rgba(20,22,24,.32);
		cursor: pointer;
	}
	.mobile-notification-entry {
		border-color: rgba(255,255,255,.38);
		cursor: pointer;
	}
	.mobile-search-panel {
		display: block;
		position: absolute;
		right: 14px;
		top: 38px;
		width: min(260px, calc(100vw - 28px));
		z-index: 101;
	}
	.mobile-search-panel .el-input__inner {
		height: 34px;
		border: 0;
		box-shadow: 0 8px 20px rgba(0,0,0,.22);
	}
	.headBox .header-search .el-input { width: 180px; }
}

.headBox .nologin {
	text-align: right;
}

.headBox .haslogin {
	text-align: right;
	position: relative;
	min-width: 80px;
	cursor: pointer;
}

.headBox .header-avatar {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	width: 28px;
	height: 28px;
	overflow: hidden;
	border: 1px solid rgba(255,255,255,.48);
	border-radius: 50%;
	color: #fff;
}

.headBox .header-avatar img { width: 100%; height: 100%; object-fit: cover; }

.headBox .haslogin:hover ul {
	visibility: visible;
	opacity: 1;
}

.headBox .haslogin ul {
	background: rgba(40, 42, 44, 0.6);
	padding: 5px 10px;
	position: absolute;
	right: 0;
	visibility: hidden;
	opacity: 0;
	transition: all 0.3s ease-out;
}

.headBox .haslogin ul li {
	border-bottom: 1px solid #48456C;
}

.headBox .haslogin ul li:last-child {
	border-bottom: 1px solid transparent;
}

/*******移动端*******/

.mobileBox {
	position: relative;
	height: 38px;
	line-height: 38px;
	color: #fff;
}

.hideMenu {
	position: relative;
	width: 100%;
	height: 100%;
	line-height: 38px;
}

.hideMenu ul.mlistmenu {
	width: 100%;
	position: absolute;
	left: 0;
	top: 100%;
	box-sizing: border-box;
	z-index: 999;
	box-shadow: 0 2px 6px 0 rgba(0, 0, 0, .12), 0 0 8px 0 rgba(0, 0, 0, .04);
	background: #48456C;
	color: #fff;
	border-right: none;
}

.hideMenu .el-submenu .el-menu {
	background: #64609E;
}

.hideMenu .el-menu-item,
.hideMenu .el-submenu__title {
	color: #fff;
}

.hideMenu>i {
	position: absolute;
	left: 10px;
	top: 12px;
	width: 30px;
	height: 30px;
	font-size: 16px;
	color: #fff;
	cursor: pointer;
}

.hideMenu .el-menu-item,
.el-submenu__title {
	height: 40px;
	line-height: 40px;
}

.mobileBox .searchBox {
	padding-left: 40px;
	width: 100%;
	box-sizing: border-box;
}

.mobileBox .searchBox .el-input__inner {
	display: block;
	border-radius: 2px;
	border: none;
	height: 25px;
}

.hideMenu ul.mlistmenu.pshow {
	display: block;
}

.hideMenu ul.mlistmenu .el-submenu__icon-arrow,
.mobileBox li.el-menu-item a {
	color: #fff;
}

.hideMenu>ul li.el-menu-item:hover,
.hideMenu>ul li.el-menu-item.is-active {
	background: #48576a;
}



/*头部背景图*/

.headImgBox {
	height: 650px;
	position: relative;
	width: 100%;
	background-size: cover;
	background-position: center 50%;
	background-repeat: no-repeat;
	margin-bottom: 90px;
}

.h-information {
	text-align: center;
	width: 70%;
	margin: auto;
	position: relative;
	top: 480px;
	padding: 40px 0;
	font-size: 16px;
	opacity: 0.98;
	background: rgba(230, 244, 249, 0.8);
	border-radius: 5px;
	z-index: 1;
	animation: b 1s ease-out;
	-webkit-animation: b 1s ease-out;
}

@-webkit-keyframes b {
	0% {
		-webkit-transform: translateY(90px);
		transform: translateY(90px);
	}
	80% {
		-webkit-transform: translateY(5px);
		transform: translateY(5px)
	}
	90% {
		-webkit-transform: translateY(-5px);
		transform: translateY(-5px)
	}
	to {
		-webkit-transform: translateY(0);
		transform: translateY(0)
	}
}

@keyframes b {
	0% {
		-webkit-transform: translateY(90px);
		transform: translateY(90px);
	}
	80% {
		-webkit-transform: translateY(5px);
		transform: translateY(5px)
	}
	90% {
		-webkit-transform: translateY(-5px);
		transform: translateY(-5px)
	}
	to {
		-webkit-transform: translateY(0);
		transform: translateY(0)
	}
}

.h-information img {
	width: 100px;
	height: 100px;
	border-radius: 100%;
	transition: all .4s ease-in-out;
	-webkit-transition: all .4s ease-in-out;
	object-fit: cover;
}

.h-information img:hover {
	transform: rotate(360deg);
	-webkit-transform: rotate(360deg);
}

.h-information h2 {
	margin-top: 20px;
	font-size: 18px;
	font-weight: 700;
	/*font-family: 'Sigmar One';*/
}
.h-information h2  a{
	background: linear-gradient(to right, #DF2050, #48456D);
	-webkit-background-clip: text;
	color: transparent;
}
.headImgBox .scene {
	width: 100%;
	/*height:300px;*/
	text-align: center;
	font-size: 100px;
	font-weight: 200;
	color: #fff;
	position: absolute;
	left: 0;
	top: 160px;
	font-family: 'Sigmar One', Arial;
	text-shadow: 0 2px 2px #47456d;

}

.headImgBox .scene span {
	transform: matrix(1, 0, 0, 1, 0, 0);
	-webkit-transform: matrix(1, 0, 0, 1, 0, 0);
	text-shadow: 1px 1px 0 #ff3f1a, -1px -1px 0 #00a7e0;
}

.saying:after {
	content: "|";
	font-family: Arial, sans-serif;
	font-size: 1em;
	/*line-height: 0;*/
	display: inline-block;
	vertical-align: baseline;
	opacity: 1;
	text-shadow: 1px 1px 0 #ff3f1a, -1px -1px 0 #00a7e0;
	animation: caret 500ms infinite;
}

@keyframes caret {
	0%,
	100% {
		opacity: 1;
	}
	50% {
		opacity: 0;
	}
}

.compact-header .headImgBox {
	height: 180px;
	margin-bottom: 24px;
}

.compact-header .h-information,
.compact-header .scene {
	display: none;
}

@media screen and (max-width: 700px) {
	.compact-header .headImgBox {
		height: 112px!important;
		margin-bottom: 14px!important;
	}
}
</style>
