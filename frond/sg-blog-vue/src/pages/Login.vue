<!-- 用户登录与注册 -->
<template>
    <div class="auth-page">
        <section class="auth-story">
            <a href="#/" class="brand">
                <span class="brand-symbol">Y</span>
                <span>YanBlog</span>
            </a>
            <div class="story-copy">
                <p class="eyebrow">READ · THINK · WRITE</p>
                <h1>在文字里相遇，<br>在思考中前行。</h1>
                <p>登录后参与评论、收藏内容，并管理你的个人资料。</p>
            </div>
            <p class="story-footer">一个专注阅读与分享的个人博客</p>
        </section>

        <main class="auth-main">
            <div class="auth-card">
                <a href="#/" class="back-link"><i class="el-icon-arrow-left"></i> 返回博客首页</a>

                <div class="role-switch">
                    <button :class="{ active: login == 1 }" type="button" @click="goLogin">用户登录</button>
                    <button :class="{ active: login != 1 }" type="button" @click="goRegister">注册账号</button>
                </div>

                <div v-if="login == 1" class="auth-form login-form">
                    <div class="form-heading">
                        <p class="eyebrow">WELCOME BACK</p>
                        <h2>欢迎回来</h2>
                        <span>继续你的阅读与交流</span>
                    </div>

                    <el-alert v-show="loginErr" :title="loginTitle" type="error" show-icon :closable="false"></el-alert>

                    <label>用户名</label>
                    <el-input type="text" placeholder="请输入用户名" prefix-icon="el-icon-user" v-model="username"></el-input>
                    <label>密码</label>
                    <el-input type="password" placeholder="请输入密码" prefix-icon="el-icon-lock" @keyup.enter.native="loginEnterFun" v-model="password"></el-input>

                    <el-button type="primary" class="submit-button" @click="gotoHome">登录</el-button>
                    <p class="form-help">还没有账号？<button type="button" @click="goRegister">立即注册</button></p>
                </div>

                <div v-else class="auth-form register-form">
                    <div class="form-heading">
                        <p class="eyebrow">CREATE ACCOUNT</p>
                        <h2>加入 YanBlog</h2>
                        <span>创建账号，开始参与交流</span>
                    </div>

                    <el-alert v-show="registerErr" :title="registerTitle" type="error" show-icon :closable="false"></el-alert>
                    <label>用户名</label>
                    <el-input type="text" placeholder="请输入用户名" v-model="nusername"></el-input>
                    <el-alert v-show="nusernameErr" title="请输入用户名" type="error" show-icon :closable="false"></el-alert>
                    <label>昵称</label>
                    <el-input type="text" placeholder="希望大家如何称呼你" v-model="nnickName"></el-input>
                    <label>邮箱</label>
                    <el-input type="email" placeholder="请输入邮箱" v-model="nemail"></el-input>
                    <el-alert v-show="nemailErr" title="请输入正确的邮箱" type="error" show-icon :closable="false"></el-alert>
                    <label>密码</label>
                    <el-input type="password" placeholder="6-12 位英文、数字或下划线" v-model="npassword"></el-input>
                    <el-alert v-show="npasswordErr" title="密码格式不正确" type="error" show-icon :closable="false"></el-alert>
                    <label>确认密码</label>
                    <el-input type="password" placeholder="再次输入密码" @keyup.enter.native="registerEnterFun" v-model="npassword2"></el-input>
                    <el-alert v-show="npassword2Err" title="两次输入的密码不一致" type="error" show-icon :closable="false"></el-alert>

                    <el-button type="primary" class="submit-button" @click="newRegister" v-loading.fullscreen.lock="fullscreenLoading" element-loading-text="提交中">创建账号</el-button>
                    <p class="form-help">已有账号？<button type="button" @click="goLogin">直接登录</button></p>
                </div>

                <a :href="adminUrl" class="admin-entry">
                    <span><i class="el-icon-setting"></i> 我是管理员</span>
                    <strong>进入管理工作台 <i class="el-icon-arrow-right"></i></strong>
                </a>
            </div>
        </main>
    </div>
</template>

<script>
import { userLogin, userRegister } from '../api/user.js'
import { setToken } from '../utils/auth.js'

export default {
    name: 'Login',
    data() {
        return {
            username: '',
            password: '',
            nusername: '',
            nnickName: '',
            nemail: '',
            npassword: '',
            npassword2: '',
            login: 1,
            loginErr: false,
            loginTitle: '用户名或密码错误',
            nusernameErr: false,
            nemailErr: false,
            npasswordErr: false,
            npassword2Err: false,
            registerErr: false,
            registerTitle: '该邮箱已注册',
            fullscreenLoading: false,
            urlstate: 0,
            adminUrl: process.env.ADMIN_URL || '/admin/#/login'
        }
    },
    methods: {
        routeChange() {
            this.login = this.$route.query.login === undefined ? 1 : parseInt(this.$route.query.login)
            this.urlstate = this.$route.query.urlstate === undefined ? 0 : this.$route.query.urlstate
        },
        loginEnterFun(e) {
            const keyCode = window.event ? e.keyCode : e.which
            if (keyCode === 13) this.gotoHome()
        },
        gotoHome() {
            this.loginErr = false
            userLogin(this.username, this.password).then((response) => {
                setToken(response.token)
                localStorage.setItem('userInfo', JSON.stringify(response.userInfo))
                const redirect = this.$route.query.redirect
                this.$router.replace(redirect && !redirect.startsWith('/Login') ? redirect : '/')
            }).catch(error => {
                this.loginErr = !!error && error.code === 505
            })
        },
        registerEnterFun(e) {
            const keyCode = window.event ? e.keyCode : e.which
            if (keyCode === 13) this.newRegister()
        },
        newRegister() {
            const reg = /^([a-zA-Z0-9_-])+@([a-zA-Z0-9_-])+(\.[a-zA-Z0-9_-])+/ 
            const preg = /^(\w){6,12}$/
            this.nusernameErr = !this.nusername
            this.nemailErr = !reg.test(this.nemail)
            this.npasswordErr = !(this.npassword && preg.test(this.npassword))
            this.npassword2Err = !this.npasswordErr && this.npassword !== this.npassword2
            if (!this.nusernameErr && !this.nemailErr && !this.npasswordErr && !this.npassword2Err) {
                this.fullscreenLoading = true
                userRegister(this.nusername, this.nnickName, this.nemail, this.npassword).then(() => {
                    this.fullscreenLoading = false
                    this.goLogin()
                }).catch(() => {
                    this.fullscreenLoading = false
                    this.registerErr = true
                })
            }
        },
        goLogin() {
            this.$router.push({ name: 'Login', query: { login: '1', redirect: this.$route.query.redirect } })
        },
        goRegister() {
            this.$router.push({ name: 'Login', query: { login: '0', redirect: this.$route.query.redirect } })
        }
    },
    watch: { '$route': 'routeChange' },
    created() { this.routeChange() }
}
</script>

<style scoped>
.auth-page { min-height: 100vh; display: grid; grid-template-columns: minmax(360px, .9fr) minmax(500px, 1.1fr); color: #242723; background: #f4f1eb; }
.auth-story { position: relative; display: flex; flex-direction: column; min-height: 100vh; padding: 44px clamp(44px, 6vw, 90px); overflow: hidden; color: #f8f5ef; background: #1b2024; }
.auth-story::after { content: ''; position: absolute; width: 330px; height: 330px; left: -150px; bottom: -120px; border: 1px solid rgba(151, 223, 253, .22); border-radius: 50%; box-shadow: 0 0 0 70px rgba(151, 223, 253, .025), 0 0 0 140px rgba(151, 223, 253, .018); }
.brand { position: relative; z-index: 1; display: inline-flex; align-items: center; gap: 12px; width: max-content; color: #fff; font-size: 20px; font-weight: 600; }
.brand-symbol { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; color: #fff; background: #d97757; font: 600 22px/1 Georgia, serif; }
.story-copy { position: relative; z-index: 1; max-width: 520px; margin: auto 0; }
.story-copy h1 { margin: 16px 0 24px; font: 500 clamp(38px, 4.5vw, 62px)/1.18 Georgia, "Songti SC", serif; letter-spacing: -.05em; }
.story-copy > p:last-child { max-width: 420px; color: rgba(248, 245, 239, .6); font-size: 15px; line-height: 1.9; }
.eyebrow { margin: 0; color: #d97757; font-size: 11px; font-weight: 700; letter-spacing: .18em; }
.story-footer { position: relative; z-index: 1; color: rgba(248, 245, 239, .36); font-size: 12px; }
.auth-main { display: grid; place-items: center; min-height: 100vh; padding: 48px; }
.auth-card { width: min(100%, 440px); padding: 38px 40px; border: 1px solid #e3ddd4; border-radius: 16px; background: rgba(255, 253, 249, .95); box-shadow: 0 22px 70px rgba(59, 49, 38, .08); }
.back-link { display: inline-flex; align-items: center; gap: 5px; margin-bottom: 30px; color: #7f817c; font-size: 13px; }
.back-link:hover { color: #d97757; }
.role-switch { display: grid; grid-template-columns: 1fr 1fr; margin-bottom: 30px; padding: 4px; border-radius: 10px; background: #efebe5; }
.role-switch button { height: 38px; border: 0; border-radius: 7px; color: #858781; background: transparent; cursor: pointer; font-weight: 600; }
.role-switch button.active { color: #282b28; background: #fffdfa; box-shadow: 0 2px 8px rgba(54, 46, 38, .07); }
.form-heading { margin-bottom: 26px; }
.form-heading h2 { margin: 9px 0 7px; font-size: 28px; font-weight: 650; letter-spacing: -.03em; }
.form-heading span { color: #858781; font-size: 13px; }
.auth-form label { display: block; margin: 0 0 8px; color: #4b4e49; font-size: 12px; font-weight: 600; }
.auth-form .el-input { margin-bottom: 18px; }
.auth-form >>> .el-input__inner { height: 44px; border-color: #ded8cf; border-radius: 8px; background: #fffefb; }
.auth-form >>> .el-input__inner:focus { border-color: #d97757; }
.auth-form .el-alert { margin-bottom: 18px; }
.submit-button { width: 100%; height: 44px; margin-top: 5px; border-color: #d97757; border-radius: 8px; background: #d97757; font-weight: 600; }
.submit-button:hover, .submit-button:focus { border-color: #c96849; background: #c96849; }
.form-help { margin: 20px 0 0; color: #92948f; text-align: center; font-size: 12px; }
.form-help button { padding: 0; border: 0; color: #c96849; background: transparent; cursor: pointer; font-weight: 600; }
.admin-entry { display: flex; align-items: center; justify-content: space-between; margin-top: 28px; padding: 16px; border: 1px solid #e5ded5; border-radius: 10px; color: #5d605b; background: #f8f5f0; font-size: 12px; transition: all .2s; }
.admin-entry span i { margin-right: 6px; color: #81847e; }
.admin-entry strong { color: #c96849; font-weight: 600; }
.admin-entry:hover { border-color: #d8b8aa; background: #fff8f5; }
@media (max-width: 900px) {
    .auth-page { grid-template-columns: 1fr; }
    .auth-story { min-height: auto; padding: 26px 30px; }
    .story-copy, .story-footer { display: none; }
    .auth-main { place-items: start center; min-height: calc(100vh - 90px); padding: 18px; }
    .auth-card { padding: 28px 24px; }
}
</style>
