package com.tv.ball.data.remote

object ApiConfig {
    // 后端API地址 - 请根据实际情况修改
    const val BASE_URL = "https://sk.nasxiaolin.top"

    object Member {
        const val SEND_EMAIL_CODE = "$BASE_URL/app/api/member/send_email_code.php"
        const val REGISTER = "$BASE_URL/app/api/member/register.php"
        const val LOGIN = "$BASE_URL/app/api/member/login.php"
        const val INFO = "$BASE_URL/app/api/member/info.php"
    }
}
