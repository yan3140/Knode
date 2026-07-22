import request from '@/utils/request'

// 登录
export function userLogin(userName,password) {
    return request({
        url: '/auth/login',
        method: 'post',
        headers: {
            isToken: false
          },
        data: { userName, password }
    })
}

export function userRegister(userName,nickName,email,password) {
    return request({
        url: '/user/register',
        method: 'post',
        headers: {
            isToken :false
        },
        data: { userName, nickName, email, password }
    })
}


export function logout() {
    return request({
        url: '/auth/logout',
        method: 'post'
    })
}

export function getUserInfo(userId) {
    return request ({
        url: '/user/userInfo',
        method: 'get',
        params: {"userId":userId}
    })
}


export function savaUserInfo(userinfo) {
    return request({
        url: '/user/userInfo',
        method: 'put',
        data: userinfo
    })
}
