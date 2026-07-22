<!-- 首页 -->
<template>
    <div>
        <sg-navbar></sg-navbar>
        <div class="container">
            <div class="home-workbench">
                <div><span>YANBLOG / COMMUNITY</span><strong>把你的思考，写成可被发现的内容</strong></div>
                <el-button class="home-write" type="primary" icon="el-icon-edit" @click="writeArticle">写博客</el-button>
            </div>
            <el-row  :gutter="30">
                <el-col :sm="24" :md="16" style="transition:all .5s ease-out;margin-bottom:30px;">
                    <sg-articlelist></sg-articlelist>
                </el-col>
                <el-col :sm="24"  :md="8" >
                    <sg-rightlist></sg-rightlist>
                </el-col>
            </el-row>
        </div>
    </div>
</template>

<script>
import header from '../components/header.vue'
import articlelist from '../components/articlelist.vue'
import rightlist from '../components/rightlist.vue'
    export default {
        name:'Home',
        data() { //选项 / 数据
            return {

            }
        },
        methods: { //事件处理器
            writeArticle() {
                this.$router.push({ path: '/WriteArticle' })
            }
        },
        components: { //定义组件
            'sg-navbar':header,
            'sg-articlelist':articlelist,
            'sg-rightlist':rightlist,
        },
        created() { //生命周期函数

        }
    }
</script>

<style scoped>
.home-workbench{ display:flex; align-items:center; justify-content:space-between; margin:0 0 22px; padding:20px 24px; border:1px solid rgba(100,96,158,.16); border-radius:10px; color:#fff; background:linear-gradient(120deg,#272449,#5d5a9a); box-shadow:0 14px 30px rgba(72,69,109,.2); }
.home-workbench span{ display:block; margin-bottom:6px; color:#bdbbe8; font-size:11px; letter-spacing:1.7px; }
.home-workbench strong{ font-size:18px; font-weight:600; }
.home-write{ border:0; color:#302d58; background:#d8f5ff; font-weight:700; }
@media screen and (max-width:700px){ .home-workbench{ align-items:flex-start; flex-direction:column; gap:14px; margin:0 0 15px; padding:18px; } }
</style>
