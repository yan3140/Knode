<!-- 留言评论模块 -->
<template>
    <div class="tmsgBox"  ref="tmsgBox">
        <div class="tmsg-respond"  ref="respondBox">
            <h3>发表评论 <small v-show="isRespond" class="tcolorm" @click="removeRespond">取消回复</small></h3>
            <form class=""  >
                <el-input
                  type="textarea"
                  :rows="2"
                  placeholder="说点什么呢``"
                  v-model="textarea">
                </el-input>
                <div :class="pBody?'OwO':'OwO OwO-open'">
                    <div class="OwO-logo" @click="pBody=!pBody">
                        <span>OwO表情</span>
                    </div>
                    <div class="OwO-body">
                        <ul class="OwO-items OwO-items-show">
                            <li class="OwO-item" v-for="(oitem,index) in OwOlist" :key="'oitem'+index" @click="choseEmoji(oitem.title)">
                                <img :src="'static/img/emot/image/'+oitem.url" alt="">
                            </li>
                        </ul>
                        <div class="OwO-bar">
                            <ul class="OwO-packages">
                                <li class="OwO-package-active">Emoji</li>
                            </ul>
                        </div>
                    </div>
                </div>
                <el-row class="tmsg-r-info">
                    <el-col :span="24" class="info-submit">
                        <p class="tcolors-bg" @click="sendMsg">{{sendTip}}</p>
                    </el-col>
                </el-row>
            </form>
        </div>
        <div class="tmsg-comments"  ref="listDom">
            <a href="#" class="tmsg-comments-tip">活捉 {{commentList?commentList.length:0}} 条</a>
            <div class="tmsg-commentshow">
                <ul class="tmsg-commentlist">
                    <li :id="'comment-' + item.id" class="tmsg-c-item" v-for="(item,index) in commentList" :key="'common'+index">
                        <article class="">
                            <header>
                                <img  :src="$store.state.errorImg"  :onerror="$store.state.errorImg">
                                <div class="i-name">
                                    {{ commentUserName(item.username, item.userInfoStatus) }}
                                </div>
                                <!-- <div class="i-class">
                                    {{item.label}}
                                </div> -->
                                <div class="i-time">
                                    <time>{{item.createTime}}</time>
                                </div>
                            </header>
                            <section>
                                <p><template v-for="(part, partIndex) in commentParts(item.content)"><img v-if="part.src" :key="'emoji-'+partIndex" :src="part.src" :alt="part.text"><span v-else :key="'text-'+partIndex">{{part.text}}</span></template></p>
                                <div v-if="haslogin" class="tmsg-replay" @click="respondMsg(item.id,item.id,item.createBy,$event)">
                                    回复
                                </div>
                            </section>
                        </article>
                        <ul v-show="item.children" class="tmsg-commentlist" style="padding-left:60px;">
                            <li :id="'comment-' + citem.id" class="tmsg-c-item" v-for="(citem,cindex) in item.children" :key="'citem'+cindex">
                                <article class="">
                                    <header>
                                            <img :src="$store.state.errorImg"  :onerror="$store.state.errorImg">
                                            <div class="i-name">
                                                {{ commentUserName(citem.username, citem.userInfoStatus) }} <span>回复</span> {{ commentUserName(citem.toCommentUserName, citem.toCommentUserInfoStatus) }}
                                            </div>
                                            <div class="i-time">
                                                <time>{{citem.createTime}}</time>
                                            </div>
                                    </header>
                                    <section>
                                        <p><template v-for="(part, partIndex) in commentParts(citem.content)"><img v-if="part.src" :key="'emoji-'+partIndex" :src="part.src" :alt="part.text"><span v-else :key="'text-'+partIndex">{{part.text}}</span></template></p>
                                        <div v-show="haslogin" class="tmsg-replay" @click="respondMsg(item.id,citem.id,citem.createBy,$event)">
                                            回复
                                        </div>
                                    </section>
                                </article>
                            </li>
                        </ul>
                    </li>

                </ul>
                <p v-if="commentError" class="tmsg-error">{{ commentError }}</p>
                <h1 v-if="commentError" class="tcolors-bg" @click="retryCommentPage">重试加载</h1>
                <h1 v-else-if="loadingComments" class="tcolors-bg">加载中...</h1>
                <h1 v-else-if='hasMore' class="tcolors-bg" @click="addMoreFun" >查看更多</h1>
                <h1 v-else class="tcolors-bg" >没有更多</h1>
            </div>
        </div>
    </div>
</template>

<script>
    import {sendComment,getArticleComment,getLinkComment} from '../api/comment.js'
    import { getToken } from '../utils/auth.js'
    export default {
        data() { //选项 / 数据
            return {
                respondBox:'',//评论表单
                listDom:'',//评论列表
                tmsgBox:'',//总评论盒子
                // 查询参数
                queryParams: {
                    pageNum: 1,
                    pageSize: 10,
                    articleId: 0
                },
                isRespond:false,
                textarea: '',//文本框输入内容
                pBody:true,//表情打开控制
                commentList:[],//评论列表数据
                aid:'',//文章id（十进制字符串，友链评论为空）
                hasMore:false,
                loadingComments:false,
                commentRequestSeq:0,
                nextPageNum:1,
                failedPageNum:null,
                sending:false,
                sendRequestSeq:0,
                activeTargetKey:'',
                sendTipTimer:null,
                commentError:'',
                haslogin:false,
                userId:'',//用户id
                type:0,//回复评论的当前的commentId
                leavePid:'',//赞赏等其他模块的分类id
                pid:'',//回复评论的一级commentId
                rootId:-1,//根评论id，如果是针对文字评论直接用-1表示
                toCommentId:-1,//所回复评论的id
                toCommentUserId:-1,//所评论的用户id
                sendTip:'发送~',
                OwOlist:[//表情包和表情路径
                    {'title':'微笑','url':'weixiao.gif'},
                   {'title':'嘻嘻','url':'xixi.gif'},
                   {'title':'哈哈','url':'haha.gif'},
                   {'title':'可爱','url':'keai.gif'},
                   {'title':'可怜','url':'kelian.gif'},
                   {'title':'挖鼻','url':'wabi.gif'},
                   {'title':'吃惊','url':'chijing.gif'},
                   {'title':'害羞','url':'haixiu.gif'},
                   {'title':'挤眼','url':'jiyan.gif'},
                   {'title':'闭嘴','url':'bizui.gif'},
                   {'title':'鄙视','url':'bishi.gif'},
                   {'title':'爱你','url':'aini.gif'},
                   {'title':'泪','url':'lei.gif'},
                   {'title':'偷笑','url':'touxiao.gif'},
                   {'title':'亲亲','url':'qinqin.gif'},
                   {'title':'生病','url':'shengbing.gif'},
                   {'title':'太开心','url':'taikaixin.gif'},
                   {'title':'白眼','url':'baiyan.gif'},
                   {'title':'右哼哼','url':'youhengheng.gif'},
                   {'title':'左哼哼','url':'zuohengheng.gif'},
                   {'title':'嘘','url':'xu.gif'},
                   {'title':'衰','url':'shuai.gif'},
                   {'title':'吐','url':'tu.gif'},
                   {'title':'哈欠','url':'haqian.gif'},
                   {'title':'抱抱','url':'baobao.gif'},
                   {'title':'怒','url':'nu.gif'},
                   {'title':'疑问','url':'yiwen.gif'},
                   {'title':'馋嘴','url':'chanzui.gif'},
                   {'title':'拜拜','url':'baibai.gif'},
                   {'title':'思考','url':'sikao.gif'},
                   {'title':'汗','url':'han.gif'},
                   {'title':'困','url':'kun.gif'},
                   {'title':'睡','url':'shui.gif'},
                   {'title':'钱','url':'qian.gif'},
                   {'title':'失望','url':'shiwang.gif'},
                   {'title':'酷','url':'ku.gif'},
                   {'title':'色','url':'se.gif'},
                   {'title':'哼','url':'heng.gif'},
                   {'title':'鼓掌','url':'guzhang.gif'},
                   {'title':'晕','url':'yun.gif'},
                   {'title':'悲伤','url':'beishang.gif'},
                   {'title':'抓狂','url':'zhuakuang.gif'},
                   {'title':'黑线','url':'heixian.gif'},
                   {'title':'阴险','url':'yinxian.gif'},
                   {'title':'怒骂','url':'numa.gif'},
                   {'title':'互粉','url':'hufen.gif'},
                   {'title':'书呆子','url':'shudaizi.gif'},
                   {'title':'愤怒','url':'fennu.gif'},
                   {'title':'感冒','url':'ganmao.gif'},
                   {'title':'心','url':'xin.gif'},
                   {'title':'伤心','url':'shangxin.gif'},
                   {'title':'猪','url':'zhu.gif'},
                   {'title':'熊猫','url':'xiongmao.gif'},
                   {'title':'兔子','url':'tuzi.gif'},
                   {'title':'喔克','url':'ok.gif'},
                   {'title':'耶','url':'ye.gif'},
                   {'title':'棒棒','url':'good.gif'},
                   {'title':'不','url':'no.gif'},
                   {'title':'赞','url':'zan.gif'},
                   {'title':'来','url':'lai.gif'},
                   {'title':'弱','url':'ruo.gif'},
                   {'title':'草泥马','url':'caonima.gif'},
                   {'title':'神马','url':'shenma.gif'},
                   {'title':'囧','url':'jiong.gif'},
                   {'title':'浮云','url':'fuyun.gif'},
                   {'title':'给力','url':'geili.gif'},
                   {'title':'围观','url':'weiguan.gif'},
                   {'title':'威武','url':'weiwu.gif'},
                   {'title':'话筒','url':'huatong.gif'},
                   {'title':'蜡烛','url':'lazhu.gif'},
                   {'title':'蛋糕','url':'dangao.gif'},
                   {'title':'发红包','url':'fahongbao.gif'}
                ]
            }
        },
        methods: { //事件处理器
            commentUserName(name, status){
                if (name) return name
                if (status === 'UNAVAILABLE') return '用户昵称暂不可用'
                if (status === 'MISSING') return '已注销用户'
                return '匿名用户'
            },
            currentTargetKey(){
                var routeName = this.$route && this.$route.name ? String(this.$route.name) : '';
                if (routeName === 'DetailArticle') {
                    var rawAid = this.$route.query && this.$route.query.aid;
                    var normalizedAid = rawAid != null ? String(rawAid).trim() : '';
                    var validAid = /^[1-9]\d{0,18}$/.test(normalizedAid) ? normalizedAid : '';
                    return routeName + ':' + validAid;
                }
                return routeName + ':';
            },
            setData(initData, result, loadedPage){
                var msg = (result && result.rows) || [];
                var total = Number(result && result.total) || 0;
                this.commentError = '';
                this.failedPageNum = null;
                if(initData || loadedPage === 1){
                    //刷新列表
                    this.commentList = msg.slice();
                }else{
                    //加载更多（按 id 防重兜底）
                    var seen = {};
                    for (var i = 0; i < this.commentList.length; i++) {
                        if (this.commentList[i] && this.commentList[i].id != null) {
                            seen[String(this.commentList[i].id)] = true;
                        }
                    }
                    var appended = [];
                    for (var j = 0; j < msg.length; j++) {
                        var row = msg[j];
                        var key = row && row.id != null ? String(row.id) : null;
                        if (key && seen[key]) continue;
                        if (key) seen[key] = true;
                        appended.push(row);
                    }
                    this.commentList = this.commentList.concat(appended);
                }
                this.nextPageNum = loadedPage + 1;
                this.queryParams.pageNum = loadedPage;
                this.hasMore = total > this.commentList.length
                this.$nextTick(() => this.focusNotificationComment())
              },
          //选择表情包
          choseEmoji:function(inner){
              this.textarea +='[' + inner + ']';
          },
          commentParts:function(content){
              var text = typeof content === 'string' ? content : '';
              var pattern = /\[([\u4e00-\u9fa5]+)\]/g;
              var parts = [], last = 0, match;
              while ((match = pattern.exec(text)) !== null) {
                  if (match.index > last) parts.push({text:text.slice(last, match.index)});
                  var known = null;
                  for (var i = 0; i < this.OwOlist.length; i++) {
                      if (this.OwOlist[i].title === match[1]) { known = this.OwOlist[i]; break; }
                  }
                  if (known) parts.push({text:match[0], src:'static/img/emot/image/'+known.url});
                  else parts.push({text:match[0]});
                  last = pattern.lastIndex;
              }
              if (last < text.length) parts.push({text:text.slice(last)});
              return parts.length ? parts : [{text:''}];
          },
          //发送留言
          sendMsg:function(){//留言
              var that = this;
              if (that.sending) return;
              if(that.textarea && that.textarea.trim()){
                  var submitTargetKey = that.currentTargetKey();
                  var sendSeq = ++that.sendRequestSeq;
                  var submitType = that.type;
                  var targetArticleId = submitType === 1 ? null : that.aid;
                  var submitRootId = that.rootId;
                  var submitToCommentId = that.toCommentId;
                  var submitToCommentUserId = that.toCommentUserId;
                  var submitContent = that.textarea;
                  that.sending = true;
                  that.sendTip = '咻~~';
                  if (that.sendTipTimer) {
                      clearTimeout(that.sendTipTimer);
                      that.sendTipTimer = null;
                  }
                  sendComment(submitType, targetArticleId, submitRootId, submitToCommentId, submitToCommentUserId, submitContent).then((response)=>{
                      if (sendSeq !== that.sendRequestSeq || that.currentTargetKey() !== submitTargetKey) {
                          return;
                      }
                      that.textarea = '';
                      that.rootId = -1;
                      that.toCommentId = -1;
                      that.toCommentUserId = -1;
                      that.showCommentList(true, 1);
                      that.removeRespond();
                      that.sendTipTimer = setTimeout(function(){
                          if (sendSeq === that.sendRequestSeq && that.currentTargetKey() === submitTargetKey) {
                              that.sendTip = '发送~';
                          }
                          that.sendTipTimer = null;
                      }, 1000);
                  }).catch(function(){
                      if (sendSeq !== that.sendRequestSeq || that.currentTargetKey() !== submitTargetKey) {
                          return;
                      }
                      that.sendTip = '发送~';
                  }).finally(function(){
                      if (sendSeq === that.sendRequestSeq) {
                          that.sending = false;
                      }
                  });
              }else{
                  var emptySeq = that.sendRequestSeq;
                  var emptyTargetKey = that.currentTargetKey();
                  that.sendTip = '内容不能为空~';
                  if (that.sendTipTimer) {
                      clearTimeout(that.sendTipTimer);
                  }
                  that.sendTipTimer = setTimeout(function(){
                      if (emptySeq === that.sendRequestSeq && that.currentTargetKey() === emptyTargetKey) {
                          that.sendTip = '发送~';
                      }
                      that.sendTipTimer = null;
                  }, 3000);
              }
          },
          respondMsg:function(rootId,toCommentId,toCommentUserId,evt){//回复留言
              var that = this;
              if(localStorage.getItem('userInfo')){
                  var sourceEvent = evt || (typeof event !== 'undefined' ? event : null);
                  var dom = sourceEvent && sourceEvent.currentTarget ? sourceEvent.currentTarget.parentNode : null;
                  this.isRespond = true;
                  this.rootId = String(rootId);
                  this.toCommentId = String(toCommentId);
                  this.toCommentUserId = String(toCommentUserId);
                  if (dom && this.$refs.respondBox) {
                      dom.appendChild(this.$refs.respondBox);
                  }
              }else{
                  that.$confirm('登录后即可点赞和收藏，是否前往登录页面?', '提示', {
                    confirmButtonText: '确定',
                    cancelButtonText: '取消',
                    type: 'warning'
                    }).then(() => {//确定，跳转至登录页面
                        //储存当前页面路径，登录成功后跳回来
                        localStorage.setItem('logUrl',that.$route.fullPath);
                        that.$router.push({path:'/Login?login=1'});
                   }).catch(() => {

                   });
              }
          },
          removeRespond:function(){//取消回复留言
            this.isRespond = false;
            this.rootId = -1;
            this.toCommentId = -1;
            this.toCommentUserId=-1;
            if (this.$refs.tmsgBox && this.$refs.respondBox && this.$refs.listDom) {
                this.$refs.tmsgBox.insertBefore(this.$refs.respondBox,this.$refs.listDom);
            }
          },
          handleCommentLoadError: function(initData, failedPage, err) {
              var status = err && err.response && err.response.status;
              var code = err && (err.code || (err.response && err.response.data && err.response.data.code));
              if ((status === 413 || code === 515) && this.queryParams.pageSize > 1) {
                  this.queryParams.pageSize = 1;
                  this.loadingComments = false;
                  this.failedPageNum = null;
                  this.nextPageNum = 1;
                  this.queryParams.pageNum = 1;
                  // pageSize 变更后分页边界变化，从第 1 页重新加载替换列表，避免重复或漏项
                  this.showCommentList(true, 1);
                  return;
              }
              this.failedPageNum = failedPage;
              this.nextPageNum = failedPage;
              this.queryParams.pageNum = failedPage;
              this.commentError = status === 413 ? '单条评论回复过多，暂无法一次展示' : ((err && err.message) || '评论加载失败，请稍后重试');
          },
          retryCommentPage: function() {
              if (this.loadingComments) return;
              var retryPage = this.failedPageNum || this.nextPageNum || 1;
              this.showCommentList(retryPage === 1, retryPage);
          },
          showCommentList: function(initData, explicitPageNum){//评论列表
              var that = this;
              var isInit = !!initData;
              if (!isInit && that.loadingComments) {
                  return;
              }
              var rawAid = that.$route.query && that.$route.query.aid;
              var normalizedAid = rawAid != null ? String(rawAid).trim() : '';
              that.aid = /^[1-9]\d{0,18}$/.test(normalizedAid) ? normalizedAid : '';
              var targetPage = isInit ? 1 : (typeof explicitPageNum === 'number' && explicitPageNum >= 1 ? explicitPageNum : (that.failedPageNum || that.nextPageNum || 1));
              var seq = ++that.commentRequestSeq;
              var requestTargetKey = that.currentTargetKey();
              //判断当前用户是否登录
              var token = getToken();
              that.haslogin = !!token;
              that.commentError = '';
              that.failedPageNum = null;
              that.queryParams.pageNum = targetPage;
              if (isInit) {
                  that.commentList = [];
                  that.hasMore = false;
                  that.nextPageNum = 1;
              }

              if(that.$route.name=='DetailArticle'){//文章列表的评论
                  that.type = 0;
                  if (!that.aid) {
                      that.commentList = [];
                      that.hasMore = false;
                      that.loadingComments = false;
                      return;
                  }
                  that.queryParams.articleId = that.aid;
                  that.loadingComments = true;
                  getArticleComment({
                      articleId: that.aid,
                      pageNum: targetPage,
                      pageSize: that.queryParams.pageSize
                  }).then((response)=>{
                     if (seq !== that.commentRequestSeq || that.currentTargetKey() !== requestTargetKey) return;
                     that.setData(isInit, response, targetPage);
                  }).catch((err)=>{
                     if (seq !== that.commentRequestSeq || that.currentTargetKey() !== requestTargetKey) return;
                     that.handleCommentLoadError(isInit, targetPage, err);
                  }).finally(()=>{
                     if (seq === that.commentRequestSeq) that.loadingComments = false;
                  })
              }else if(that.$route.name == 'FriendsLink'){//友链评论显式不传articleId
                  that.type = 1;
                  that.aid = '';
                  that.queryParams.articleId = null;
                  that.loadingComments = true;
                  getLinkComment({
                      pageNum: targetPage,
                      pageSize: that.queryParams.pageSize
                  }).then((response)=>{
                      if (seq !== that.commentRequestSeq || that.currentTargetKey() !== requestTargetKey) return;
                      that.setData(isInit, response, targetPage);
                  }).catch((err)=>{
                      if (seq !== that.commentRequestSeq || that.currentTargetKey() !== requestTargetKey) return;
                      that.handleCommentLoadError(isInit, targetPage, err);
                  }).finally(()=>{
                      if (seq === that.commentRequestSeq) that.loadingComments = false;
                  })
              }
          },
          addMoreFun:function(){//查看更多
              if (this.loadingComments || !this.hasMore) return;
              this.showCommentList(false, this.nextPageNum);
          },
          routeChange:function(){//重新加载
              var nextTargetKey = this.currentTargetKey();
              var targetChanged = this.activeTargetKey !== '' && this.activeTargetKey !== nextTargetKey;
              this.activeTargetKey = nextTargetKey;
              if (targetChanged) {
                  this.sendRequestSeq++;
                  this.sending = false;
                  this.textarea = '';
                  this.sendTip = '发送~';
                  if (this.sendTipTimer) {
                      clearTimeout(this.sendTipTimer);
                      this.sendTipTimer = null;
                  }
                  this.removeRespond();
              }
              this.commentRequestSeq++;
              this.loadingComments = false;
              this.nextPageNum = 1;
              this.failedPageNum = null;
              this.queryParams.pageNum = 1;
              this.queryParams.pageSize = 10;
              this.showCommentList(true, 1);
          },
          focusNotificationComment:function(){
              var commentId = this.$route.query.commentId;
              if(!commentId){ return; }
              var target = document.getElementById('comment-' + commentId) || this.$refs.tmsgBox;
              if(target){
                  setTimeout(function(){ target.scrollIntoView({ behavior:'smooth', block:'center' }); }, 0);
              }
          }
        },
        components: { //定义组件

        },
        watch: {
           '$route':'routeChange'
         },
        created() { //生命周期函数
            // console.log(this.$route);
            var that = this;
            that.routeChange();
        },
        mounted(){//页面加载完成后

        },
        beforeDestroy() {
            this.sendRequestSeq++;
            this.commentRequestSeq++;
            if (this.sendTipTimer) {
                clearTimeout(this.sendTipTimer);
                this.sendTipTimer = null;
            }
        }
    }
</script>

<style>
.tmsgBox{
    position: relative;
    background: #fff;
    padding:15px;
    margin-bottom: 20px;
    border-radius: 5px;
}
.tmsg-respond h3{
    font-size: 18px;
    font-weight: bold;
    margin-bottom: 8px;
}
.tmsg-respond h3 small{
    font-size: smaller;
    cursor: pointer;
}
.tmsg-respond textarea{
    background:#f4f6f7;
    height:100px;
    margin-bottom: 10px;
}
.OwO{
    position: relative;
    z-index: 1;
}
.OwO .OwO-logo{
    position: relative;
    border-radius: 4px;
    color:#444;
    display: inline-block;
    background: #fff;
    border:1px solid #ddd;
    font-size: 13px;
    padding:0 6px;
    cursor: pointer;
    height:30px;
    box-sizing: border-box;
    z-index: 2;
    line-height: 30px;
}
.OwO .OwO-logo:hover{
    animation:a 5s infinite ease-in-out;
    -webkit-animation:a 5s infinite ease-in-out;
}
.OwO .OwO-body{
    position: absolute;
    background: #fff;
    border:1px solid #ddd;
    z-index: 1;
    top:29px;
    border-radius: 0 4px 4px 4px;
    display: none;
}
.OwO-open .OwO-body{
    display: block;
}
.OwO-open .OwO-logo{
    border-radius: 4px 4px 0 0;
    border-bottom: none;
}
.OwO-open .OwO-logo:hover{
    animation:none;
    -webkit-animation:none;
}
.OwO .OwO-items {
    max-height: 197px;
    overflow: scroll;
    font-size: 0;
    padding:10px;
    z-index: 1
}
.OwO .OwO-items .OwO-item{
    background: #f7f7f7;
    padding:5px 10px;
    border-radius: 5px;
    display: inline-block;
    margin: 0 10px 12px 0;
    transition: 0.3s;
    line-height: 19px;
    font-size: 20px;
    cursor: pointer;
}
.OwO .OwO-items .OwO-item:hover{
    background: #eee;
    box-shadow: 0 2px 2px 0 rgba(0,0,0,.14),
                0 3px 1px -2px rgba(0,0,0,.2),
                0 1px 5px 0 rgba(0,0,0,.12);
    animation:a 5s infinite ease-in-out;
    -webkit-animation:a 5s infinite ease-in-out;
}
.OwO .OwO-body .OwO-bar{
    width:100%;
    height:30px;
    border-top: 1px solid #ddd;
    background: #fff;
    border-radius: 0 0 4px 4px;
    color: #444;
}
.OwO .OwO-body .OwO-bar .OwO-packages li{
    display: inline-block;
    line-height: 30px;
    font-size: 14px;
    padding: 0 10px;
    cursor: pointer;
    margin-right: 3px;
    text-align: center;
}
.OwO .OwO-body .OwO-bar .OwO-packages li:first-of-type{
    border-radius: 0 0 0 3px;
}
@-webkit-keyframes a{
    2%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    4%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    6%{-webkit-transform:translateY(1.5px) rotate(-1.5deg);transform:translateY(1.5px) rotate(-1.5deg)}
    8%{-webkit-transform:translateY(-1.5px) rotate(-1.5deg);transform:translateY(-1.5px) rotate(-1.5deg)}
    10%{-webkit-transform:translateY(2.5px) rotate(1.5deg);transform:translateY(2.5px) rotate(1.5deg)}
    12%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    14%{-webkit-transform:translateY(-1.5px) rotate(1.5deg);transform:translateY(-1.5px) rotate(1.5deg)}
    16%{-webkit-transform:translateY(-.5px) rotate(-1.5deg);transform:translateY(-.5px) rotate(-1.5deg)}
    18%{-webkit-transform:translateY(.5px) rotate(-1.5deg);transform:translateY(.5px) rotate(-1.5deg)}
    20%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    22%{-webkit-transform:translateY(.5px) rotate(-1.5deg);transform:translateY(.5px) rotate(-1.5deg)}
    24%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    26%{-webkit-transform:translateY(.5px) rotate(.5deg);transform:translateY(.5px) rotate(.5deg)}
    28%{-webkit-transform:translateY(.5px) rotate(1.5deg);transform:translateY(.5px) rotate(1.5deg)}
    30%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    32%,34%{-webkit-transform:translateY(1.5px) rotate(-.5deg);transform:translateY(1.5px) rotate(-.5deg)}
    36%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    38%{-webkit-transform:translateY(1.5px) rotate(-1.5deg);transform:translateY(1.5px) rotate(-1.5deg)}
    40%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    42%{-webkit-transform:translateY(2.5px) rotate(-1.5deg);transform:translateY(2.5px) rotate(-1.5deg)}
    44%{-webkit-transform:translateY(1.5px) rotate(.5deg);transform:translateY(1.5px) rotate(.5deg)}
    46%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    48%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    50%{-webkit-transform:translateY(.5px) rotate(.5deg);transform:translateY(.5px) rotate(.5deg)}
    52%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    54%{-webkit-transform:translateY(-1.5px) rotate(1.5deg);transform:translateY(-1.5px) rotate(1.5deg)}
    56%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    58%{-webkit-transform:translateY(.5px) rotate(2.5deg);transform:translateY(.5px) rotate(2.5deg)}
    60%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    62%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    64%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    66%{-webkit-transform:translateY(1.5px) rotate(-.5deg);transform:translateY(1.5px) rotate(-.5deg)}
    68%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    70%{-webkit-transform:translateY(1.5px) rotate(.5deg);transform:translateY(1.5px) rotate(.5deg)}
    72%{-webkit-transform:translateY(2.5px) rotate(1.5deg);transform:translateY(2.5px) rotate(1.5deg)}
    74%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    76%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    78%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    80%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    82%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    84%{-webkit-transform:translateY(1.5px) rotate(2.5deg);transform:translateY(1.5px) rotate(2.5deg)}
    86%{-webkit-transform:translateY(-1.5px) rotate(-1.5deg);transform:translateY(-1.5px) rotate(-1.5deg)}
    88%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    90%{-webkit-transform:translateY(2.5px) rotate(-.5deg);transform:translateY(2.5px) rotate(-.5deg)}
    92%{-webkit-transform:translateY(.5px) rotate(-.5deg);transform:translateY(.5px) rotate(-.5deg)}
    94%{-webkit-transform:translateY(2.5px) rotate(.5deg);transform:translateY(2.5px) rotate(.5deg)}
    96%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    98%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    0%,to{-webkit-transform:translate(0) rotate(0deg);transform:translate(0) rotate(0deg)}
}
@keyframes a{
    2%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    4%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    6%{-webkit-transform:translateY(1.5px) rotate(-1.5deg);transform:translateY(1.5px) rotate(-1.5deg)}
    8%{-webkit-transform:translateY(-1.5px) rotate(-1.5deg);transform:translateY(-1.5px) rotate(-1.5deg)}
    10%{-webkit-transform:translateY(2.5px) rotate(1.5deg);transform:translateY(2.5px) rotate(1.5deg)}
    12%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    14%{-webkit-transform:translateY(-1.5px) rotate(1.5deg);transform:translateY(-1.5px) rotate(1.5deg)}
    16%{-webkit-transform:translateY(-.5px) rotate(-1.5deg);transform:translateY(-.5px) rotate(-1.5deg)}
    18%{-webkit-transform:translateY(.5px) rotate(-1.5deg);transform:translateY(.5px) rotate(-1.5deg)}
    20%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    22%{-webkit-transform:translateY(.5px) rotate(-1.5deg);transform:translateY(.5px) rotate(-1.5deg)}
    24%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    26%{-webkit-transform:translateY(.5px) rotate(.5deg);transform:translateY(.5px) rotate(.5deg)}
    28%{-webkit-transform:translateY(.5px) rotate(1.5deg);transform:translateY(.5px) rotate(1.5deg)}
    30%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    32%,34%{-webkit-transform:translateY(1.5px) rotate(-.5deg);transform:translateY(1.5px) rotate(-.5deg)}
    36%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    38%{-webkit-transform:translateY(1.5px) rotate(-1.5deg);transform:translateY(1.5px) rotate(-1.5deg)}
    40%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    42%{-webkit-transform:translateY(2.5px) rotate(-1.5deg);transform:translateY(2.5px) rotate(-1.5deg)}
    44%{-webkit-transform:translateY(1.5px) rotate(.5deg);transform:translateY(1.5px) rotate(.5deg)}
    46%{-webkit-transform:translateY(-1.5px) rotate(2.5deg);transform:translateY(-1.5px) rotate(2.5deg)}
    48%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    50%{-webkit-transform:translateY(.5px) rotate(.5deg);transform:translateY(.5px) rotate(.5deg)}
    52%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    54%{-webkit-transform:translateY(-1.5px) rotate(1.5deg);transform:translateY(-1.5px) rotate(1.5deg)}
    56%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    58%{-webkit-transform:translateY(.5px) rotate(2.5deg);transform:translateY(.5px) rotate(2.5deg)}
    60%{-webkit-transform:translateY(2.5px) rotate(2.5deg);transform:translateY(2.5px) rotate(2.5deg)}
    62%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    64%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    66%{-webkit-transform:translateY(1.5px) rotate(-.5deg);transform:translateY(1.5px) rotate(-.5deg)}
    68%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    70%{-webkit-transform:translateY(1.5px) rotate(.5deg);transform:translateY(1.5px) rotate(.5deg)}
    72%{-webkit-transform:translateY(2.5px) rotate(1.5deg);transform:translateY(2.5px) rotate(1.5deg)}
    74%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    76%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    78%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    80%{-webkit-transform:translateY(1.5px) rotate(1.5deg);transform:translateY(1.5px) rotate(1.5deg)}
    82%{-webkit-transform:translateY(-.5px) rotate(.5deg);transform:translateY(-.5px) rotate(.5deg)}
    84%{-webkit-transform:translateY(1.5px) rotate(2.5deg);transform:translateY(1.5px) rotate(2.5deg)}
    86%{-webkit-transform:translateY(-1.5px) rotate(-1.5deg);transform:translateY(-1.5px) rotate(-1.5deg)}
    88%{-webkit-transform:translateY(-.5px) rotate(2.5deg);transform:translateY(-.5px) rotate(2.5deg)}
    90%{-webkit-transform:translateY(2.5px) rotate(-.5deg);transform:translateY(2.5px) rotate(-.5deg)}
    92%{-webkit-transform:translateY(.5px) rotate(-.5deg);transform:translateY(.5px) rotate(-.5deg)}
    94%{-webkit-transform:translateY(2.5px) rotate(.5deg);transform:translateY(2.5px) rotate(.5deg)}
    96%{-webkit-transform:translateY(-.5px) rotate(1.5deg);transform:translateY(-.5px) rotate(1.5deg)}
    98%{-webkit-transform:translateY(-1.5px) rotate(-.5deg);transform:translateY(-1.5px) rotate(-.5deg)}
    0%,to{-webkit-transform:translate(0) rotate(0deg);transform:translate(0) rotate(0deg)}
}
/*用户输入表单*/
.tmsg-r-info{
    margin:10px 0;
}
.tmsg-r-info input{
    height:30px;
    border-radius: 4px;
    background: #f4f6f7;
}
.tmsg-r-info .info-submit{
    margin: 10px 0;
    text-align: center;
}
.tmsg-r-info .info-submit p,.tmsg-commentshow h1{
    /*background: #97dffd;*/
    color:#fff;
    border-radius: 5px;
    cursor: pointer;
    /*transition: all .3s ease-in-out;*/
    height:30px;
    line-height: 30px;
    text-align: center;
}
/*.tmsg-r-info .info-submit p:hover{
    background: #47456d;
}*/
/*评论列表*/
.tmsg-comments .tmsg-comments-tip{
    display: block;
    border-left: 2px solid #363d4c;
    padding: 0 10px;
    margin: 40px 0;
    font-size: 20px;
}
.tmsg-commentlist {
    margin-bottom:20px;

}
.tmsg-commentshow>.tmsg-commentlist{
    border-bottom: 1px solid #e5eaed;
}
.tmsg-c-item{
    border-top: 1px solid #e5eaed;
}
.tmsg-c-item article{
    margin:20px 0;
}
.tmsg-c-item article header{
    margin-bottom:10px;
}
.tmsg-c-item article header img{
    width: 65px;
    height: 65px;
    border-radius: 50%;
    float: left;
    transition: all .4s ease-in-out;
    -webkit-transition: all .4s ease-in-out;
    margin-right: 15px;
    object-fit: cover;
}
.tmsg-c-item article header img:hover{
    transform: rotate(360deg);
    -webkit-transform: rotate(360deg);
}
.tmsg-c-item article header .i-name{
    font-size: 14px;
    margin:5px 8px 7px 0;
    color:#444;
    font-weight: bold;
    display: inline-block;
}
.tmsg-c-item article header .i-class{
    display: inline-block;
    margin-left:10px;
    background: #dff0d8;
    color:#3c763d;
    border-radius: 5px;
    padding: 3px 6px;
    font-size: 12px;
    font-weight: 400;
}
.tmsg-c-item article header .i-time{
    color:#aaa;
    font-size: 12px;
}
.tmsg-c-item article section{
    margin-left: 80px;
}
.tmsg-c-item article section p img{
    vertical-align: middle;
}
.tmsg-c-item article section .tmsg-replay{
    margin:10px 0;
    font-size: 12px;
    color:#64609E;
    cursor: pointer;
}

</style>
