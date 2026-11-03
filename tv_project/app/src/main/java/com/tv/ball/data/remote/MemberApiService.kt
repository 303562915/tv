package com.tv.ball.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MemberApiService(private val okHttpClient: OkHttpClient) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 发送邮箱验证码
     */
    suspend fun sendEmailCode(email: String, type: String): ApiResponse<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestData = mapOf(
                "email" to email,
                "type" to type  // register 或 login
            )
            val json = gson.toJson(requestData)
            val body = json.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(ApiConfig.Member.SEND_EMAIL_CODE)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            android.util.Log.d("MemberApiService", "发送验证码响应: $responseBody")

            if (response.isSuccessful) {
                // 解析JSON，支持msg和message两种字段
                val jsonObject = org.json.JSONObject(responseBody)
                val code = jsonObject.optInt("code", -1)
                val message = jsonObject.optString("msg")
                    .ifEmpty { jsonObject.optString("message") }
                    .ifEmpty { "验证码已发送" }

                ApiResponse(
                    code = code,
                    message = message,
                    data = Unit
                )
            } else {
                ApiResponse(code = -1, message = "网络请求失败: ${response.code}", data = Unit)
            }
        } catch (e: Exception) {
            android.util.Log.e("MemberApiService", "发送验证码失败", e)
            ApiResponse(code = -1, message = "发送失败: ${e.message}", data = Unit)
        }
    }

    /**
     * 邮箱注册
     */
    suspend fun register(
        email: String,
        code: String,
        password: String,
        inviteCode: String? = null,
        deviceId: String = "",
        deviceType: String = "android",
        appVersion: String = "1.0"
    ): ApiResponse<MemberData> = withContext(Dispatchers.IO) {
        try {
            val requestData = mutableMapOf(
                "type" to "email",
                "email" to email,
                "code" to code,
                "password" to password,
                "device_id" to deviceId,
                "device_type" to deviceType,
                "app_version" to appVersion
            )

            if (!inviteCode.isNullOrEmpty()) {
                requestData["invite_code"] = inviteCode
            }

            val json = gson.toJson(requestData)
            val body = json.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(ApiConfig.Member.REGISTER)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            android.util.Log.d("MemberApiService", "注册响应: $responseBody")

            if (response.isSuccessful) {
                // 手动解析JSON，支持msg字段
                val jsonObject = org.json.JSONObject(responseBody)
                val code = jsonObject.optInt("code", -1)
                val message = jsonObject.optString("msg")
                    .ifEmpty { jsonObject.optString("message") }

                if (code == 0 && jsonObject.has("data")) {
                    val dataObject = jsonObject.getJSONObject("data")
                    val memberData = MemberData(
                        memberId = dataObject.optInt("member_id"),
                        username = dataObject.optString("username"),
                        email = dataObject.optString("email"),
                        nickname = dataObject.optString("nickname"),
                        avatar = dataObject.optString("avatar"),
                        level = dataObject.optString("level"),
                        vipExpireTime = dataObject.optString("vip_expire_time"),
                        points = dataObject.optString("points"),
                        balance = dataObject.optString("balance"),
                        inviteCode = dataObject.optString("invite_code"),
                        token = dataObject.optString("token")
                    )
                    ApiResponse(code, message, memberData)
                } else {
                    ApiResponse(code, message, null)
                }
            } else {
                ApiResponse(code = -1, message = "网络请求失败: ${response.code}", data = null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MemberApiService", "注册失败", e)
            ApiResponse(code = -1, message = "注册失败: ${e.message}", data = null)
        }
    }

    /**
     * 邮箱登录
     */
    suspend fun login(
        email: String,
        code: String? = null,
        password: String? = null,
        deviceId: String = "",
        deviceType: String = "android",
        appVersion: String = "1.0"
    ): ApiResponse<MemberData> = withContext(Dispatchers.IO) {
        try {
            val requestData = mutableMapOf(
                "type" to "email",
                "account" to email,
                "device_id" to deviceId,
                "device_type" to deviceType,
                "app_version" to appVersion
            )

            if (!code.isNullOrEmpty()) {
                requestData["code"] = code
            }

            if (!password.isNullOrEmpty()) {
                requestData["password"] = password
            }

            val json = gson.toJson(requestData)
            val body = json.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url(ApiConfig.Member.LOGIN)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            android.util.Log.d("MemberApiService", "登录响应: $responseBody")

            if (response.isSuccessful) {
                // 手动解析JSON，支持msg字段
                val jsonObject = org.json.JSONObject(responseBody)
                val code = jsonObject.optInt("code", -1)
                val message = jsonObject.optString("msg")
                    .ifEmpty { jsonObject.optString("message") }

                if (code == 0 && jsonObject.has("data")) {
                    val dataObject = jsonObject.getJSONObject("data")
                    val memberData = MemberData(
                        memberId = dataObject.optInt("member_id"),
                        username = dataObject.optString("username"),
                        email = dataObject.optString("email"),
                        nickname = dataObject.optString("nickname"),
                        avatar = dataObject.optString("avatar"),
                        level = dataObject.optString("level"),
                        vipExpireTime = dataObject.optString("vip_expire_time"),
                        points = dataObject.optString("points"),
                        balance = dataObject.optString("balance"),
                        inviteCode = dataObject.optString("invite_code"),
                        token = dataObject.optString("token")
                    )
                    ApiResponse(code, message, memberData)
                } else {
                    ApiResponse(code, message, null)
                }
            } else {
                ApiResponse(code = -1, message = "网络请求失败: ${response.code}", data = null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MemberApiService", "登录失败", e)
            ApiResponse(code = -1, message = "登录失败: ${e.message}", data = null)
        }
    }
}

// API 响应基础类
data class ApiResponse<T>(
    val code: Int,
    val message: String?,
    val data: T?
)

// 会员数据响应（用于解析 JSON）
private data class MemberDataResponse(
    val code: Int,
    val message: String?,
    val data: MemberData?
) {
    fun toApiResponse() = ApiResponse(code, message, data)
}

// 会员数据
data class MemberData(
    @SerializedName("member_id")
    val memberId: Int?,
    val username: String?,
    val email: String?,
    val nickname: String?,
    val avatar: String?,
    val level: String?,  // 改为String，因为可能是"normal"等字符串
    @SerializedName("vip_expire_time")
    val vipExpireTime: String?,
    val points: String?,  // 改为String，避免类型转换问题
    val balance: String?,
    @SerializedName("invite_code")
    val inviteCode: String?,
    val token: String?
)
