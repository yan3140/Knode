<!-- 友情链接模块 -->
<template>
<div class="tFriendsBox">
    <h1>友链申请格式
        <h3>        网站名称: 三更博客</h3>
        <h3>网站地址: https://www.baidu.com </h3>
        <h3>网站描述: 百度你懂的</h3>
        <h3>网站logo: https://www.sg.com/1.png</h3>

</h1>
    <div v-if="loadError" class="friends-error">
        <span>{{ loadError }}</span>
        <el-button size="mini" type="text" @click="getList">重试</el-button>
    </div>
    <el-row v-else>
        <el-col :span="12" class="tf-item" v-for="(item,index) in friendslink" :key="'f'+index">
            <a v-if="safeHref(item.address)" :href="safeHref(item.address)" target="_blank" rel="noopener noreferrer">
                <img :src="safeHref(item.logo) || 'static/img/tou.jpg'" :onerror="$store.state.errorImg">
                <h4>{{item.name}}</h4>
                <p>{{item.description}}</p>
            </a>
            <div v-else class="tf-item-disabled">
                <img :src="safeHref(item.logo) || 'static/img/tou.jpg'" :onerror="$store.state.errorImg">
                <h4>{{item.name}}</h4>
                <p>{{item.description}}</p>
            </div>
        </el-col>
    </el-row>
</div>
</template>

<script>
import {getAllLink} from '../api/link.js'
export default {
    data() { //选项 / 数据
        return {
            friendslink: [], //友情链接
            loadError: ''
        }
    },
    methods: { //事件处理器
        safeHref(url) {
            if (typeof url !== 'string') return ''
            const trimmed = url.trim()
            if (/^https?:\/\//i.test(trimmed)) {
                return trimmed
            }
            return ''
        },
        getList(){
            this.loadError = ''
            getAllLink().then((response)=>{
                this.friendslink = Array.isArray(response) ? response : []
            }).catch(() => {
                this.friendslink = []
                this.loadError = '友链加载失败，请稍后重试'
            })
        }
    },
    components: { //定义组件

    },
    created() { //生命周期函数
        this.getList()
    }
}
</script>

<style>
.tFriendsBox{
    background: #fff;
    padding:15px;
    border-radius: 5px;
    margin-bottom: 40px;
}
.tFriendsBox h1{
    padding:15px 0;
    font-size: 25px;
    font-weight: bold;
}
.tFriendsBox .tf-item{
    transition: all 0.3s ease-out;
    border-radius: 5px;
    position: relative;
}
.tFriendsBox .tf-item:hover{
    background: rgba(230,244,250,.5);
}
.tFriendsBox .tf-item a{
    display: block;
    padding:0 10px 0 90px;
    height:90px;
}
.tFriendsBox .tf-item a img{
    width:60px;
    height:60px;
    border-radius: 50%;
    position: absolute;
    top:15px;
    left:15px;
    cursor: pointer;
    object-fit: cover;
}
.tFriendsBox .tf-item a h4{
    cursor: pointer;
    white-space: nowrap;
    text-overflow: ellipsis;
    overflow: hidden;
    font-size: 20px;
    padding-top:15px;
    font-weight: bold;
}
.tFriendsBox .tf-item a p{
    margin:10px 0;
    font-size: 12px;
    line-height: 24px;
    color:#999;
    cursor: pointer;
    white-space: nowrap;
    text-overflow: ellipsis;
}
</style>
