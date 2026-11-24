<!-- 文章列表 -->
<template>
    <el-row class="sharelistBox">

        <el-col :span="24" class="s-item tcommonBox" v-for="(item,index) in articleList" :key="item.id || ('article'+index)">
            <span class="s-round-date">
                <span class="month">{{ showInitDate(item.createTime,'month') }}月</span>
                <span class="day">{{ showInitDate(item.createTime,'date') }}</span>
            </span>
            <header>
                <h1>
                    <a :href="'#/DetailArticle?aid='+item.id" target="_blank">
                        {{item.title}}
                    </a>
                </h1>
                <h2>
                    <a v-if="item.authorId" class="article-author" :href="'#/UserProfile?userId='+item.authorId"><i class="fa fa-fw fa-user"></i>{{ authorName(item) }}</a>
                    <span v-else><i class="fa fa-fw fa-user"></i>{{ authorName(item) }}</span> ·
                    <i class="fa fa-fw fa-clock-o"></i><span>{{showInitDate(item.createTime,'all')}}</span> •
                    <i class="fa fa-fw fa-eye"></i>{{item.viewCount}} 次围观 •

                </h2>
                <div class="ui label">
                    <a :href="'#/Share?classId='+(item.categoryId || item.class_id || '')">{{item.categoryName}}</a>
                </div>
            </header>
            <div class="article-content">
                <p style="text-indent:2em;">
                    {{item.summary}}
                </p>
                <p  style="max-height:300px;overflow:hidden;text-align:center;">
                    <img :src="item.thumbnail" alt="" class="maxW">
                </p>
            </div>
            <div class="viewdetail">
                <a class="tcolors-bg" :href="'#/DetailArticle?aid='+item.id" target="_blank">
                    阅读全文>>
                </a>
            </div>

        </el-col>
         <el-col class="viewmore">
            <a v-if="loadError" class="tcolors-bg" href="javascript:void(0);" @click="retryList">{{ loadError }}（点击重试）</a>
            <a v-else-if="loadingList" class="tcolors-bg" href="javascript:void(0);">加载中...</a>
            <a v-else-if="hasMore" class="tcolors-bg" href="javascript:void(0);" @click="addMoreFun">点击加载更多</a>
            <a v-else class="tcolors-bg" href="javascript:void(0);">暂无更多数据</a>
        </el-col>
    </el-row>
</template>

<script>
import {initDate} from '../utils/server.js'
import {articleList} from '../api/article'
const POSITIVE_INT64_RE = /^[1-9]\d{0,18}$/
    export default {
        name:'Share',
        data() { //选项 / 数据
            return {
                // 查询参数
                queryParams: {
                    pageNum: 1,
                    pageSize: 10,
                    categoryId: '0'
                },
                articleList:[],
                hasMore:true,
                loadingList:false,
                listRequestSeq:0,
                nextPageNum:1,
                failedPageNum:null,
                loadError:''
            }
        },

        methods: { //事件处理器
            showInitDate: function(oldDate,full){
                return initDate(oldDate,full)
            },
            authorName: function(item){
                if (item.authorName) return item.authorName
                if (item.authorInfoStatus === 'UNAVAILABLE') return '作者信息暂不可用'
                if (item.authorInfoStatus === 'MISSING') return '已注销用户'
                return '作者'
            },
            getList(initData, explicitPageNum){
                const isInit = !!initData
                if (!isInit && this.loadingList) {
                    return
                }
                const targetPage = isInit
                    ? 1
                    : (typeof explicitPageNum === 'number' && explicitPageNum >= 1 ? explicitPageNum : (this.failedPageNum || this.nextPageNum || 1))
                const seq = ++this.listRequestSeq
                const targetCategory = this.queryParams.categoryId
                this.loadingList = true
                this.loadError = ''
                this.failedPageNum = null
                this.queryParams.pageNum = targetPage
                if (isInit) {
                    this.articleList = []
                    this.hasMore = true
                    this.nextPageNum = 1
                }
                articleList({
                    pageNum: targetPage,
                    pageSize: this.queryParams.pageSize,
                    categoryId: targetCategory
                }).then((response)=>{
                    if (seq !== this.listRequestSeq || targetCategory !== this.queryParams.categoryId) {
                        return
                    }
                    const rows = (response && response.rows) || []
                    const total = Number(response && response.total) || 0
                    if (isInit || targetPage === 1) {
                        this.articleList = rows.slice()
                    } else {
                        const seen = {}
                        for (let i = 0; i < this.articleList.length; i++) {
                            if (this.articleList[i] && this.articleList[i].id != null) {
                                seen[String(this.articleList[i].id)] = true
                            }
                        }
                        const appended = []
                        for (let j = 0; j < rows.length; j++) {
                            const row = rows[j]
                            const key = row && row.id != null ? String(row.id) : null
                            if (key && seen[key]) continue
                            if (key) seen[key] = true
                            appended.push(row)
                        }
                        this.articleList = this.articleList.concat(appended)
                    }
                    this.nextPageNum = targetPage + 1
                    this.queryParams.pageNum = this.nextPageNum
                    this.failedPageNum = null
                    this.hasMore = total > this.articleList.length
                }).catch((err)=>{
                    if (seq !== this.listRequestSeq || targetCategory !== this.queryParams.categoryId) {
                        return
                    }
                    this.failedPageNum = targetPage
                    this.nextPageNum = targetPage
                    this.queryParams.pageNum = targetPage
                    this.loadError = (err && err.message) || '文章列表暂不可用'
                }).finally(()=>{
                    if (seq === this.listRequestSeq) {
                        this.loadingList = false
                    }
                })
            },
            retryList: function(){
                if (this.loadingList) return
                const retryPage = this.failedPageNum || this.nextPageNum || 1
                this.getList(retryPage === 1, retryPage)
            },
            showSearchShowList:function(initData){//展示数据
                if(initData){
                    this.getList(true, 1)
                }else{
                    this.getList(false, this.nextPageNum)
                }
            },
            addMoreFun:function(){//查看更多
                if (this.loadingList || !this.hasMore) return
                this.getList(false, this.nextPageNum);
            },
            routeChange:function(){
                var raw = this.$route.query.classId
                var trimmed = raw == null ? '' : String(raw).trim()
                this.queryParams.categoryId = POSITIVE_INT64_RE.test(trimmed) ? trimmed : '0'
                this.listRequestSeq++
                this.loadingList = false
                this.failedPageNum = null
                this.nextPageNum = 1
                this.showSearchShowList(true);
            }
        },
        components: { //定义组件

        },
        watch: {
           // 如果路由有变化，会再次执行该方法
           '$route':'routeChange',
           '$store.state.keywords':'routeChange'
         },
        created() { //生命周期函数
            // console.log(this.$route);
            var that = this;
            that.routeChange();
        }
    }
</script>

<style>
/*分享标题*/
.shareTitle{
    margin-bottom: 40px;
    position: relative;
    border-radius: 5px;
    background: #fff;
    padding:15px;
}
.shareclassTwo{
    width:100%;
}
.shareclassTwo li{
    display: inline-block;
}
.shareclassTwo li a{
    display: inline-block;
    padding:3px 7px;
    margin:5px 10px;
    color:#fff;
    border-radius: 4px;
    background: #64609E;
    border: 1px solid #64609E;
    transition: transform 0.2s linear;
    -webkit-transition: transform 0.2s linear;
}
.shareclassTwo li a:hover{
    transform: translate(0,-3px);
    -webkit-transform: translate(0,-3px);
}
.shareclassTwo li a.active{
    background: #fff;
    color:#64609E;

}
/*文章列表*/
.article-author{ color:#64609E; font-weight:600; }
.article-author:hover{ color:#df2050; }
    .sharelistBox{
        transition: all 0.5s ease-out;
        font-size: 15px;
    }


    /*.sharelistBox .viewmore a:hover,.s-item .viewdetail a:hover{
        background: #48456C;
    }*/
</style>
