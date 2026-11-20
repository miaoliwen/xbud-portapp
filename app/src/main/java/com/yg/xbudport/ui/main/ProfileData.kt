package com.yg.xbudport.ui.main

import org.json.JSONObject

// /v4/user/account/get_info 的展示模型。字段名与原 App UserInfoTable 一致；
// 只挑个人主页需要的字段，敏感/内部字段（token/alias/psign 等）不落地。

data class ProfileInfo(
    val uid: Long = 0L,
    val phone: String = "",
    val name: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val studentId: String = "",
    val campusName: String = "",
    val department: String = "",
    val gradeClass: String = "",
    val enrollmentYear: Int = 0,
    val sex: Int = -1,
    val identificationStatus: Int = -1,
    val infoComplete: Boolean = false,
    val bindingWechat: Boolean = false,
    val bindingQQ: Boolean = false,
    val bindingApple: Boolean = false,
) {
    fun genderLabel(): String = when (sex) {
        1 -> "男"
        0 -> "女"
        else -> "—"
    }

    fun identificationLabel(): String = when (identificationStatus) {
        0 -> "未认证"
        1 -> "已认证"
        2 -> "审核中"
        3 -> "认证未通过"
        else -> "—"
    }

    fun bindingLabel(): String {
        val list = buildList {
            if (bindingWechat) add("微信")
            if (bindingQQ) add("QQ")
            if (bindingApple) add("Apple")
        }
        return if (list.isEmpty()) "未绑定" else list.joinToString("、")
    }
}

object ProfileData {
    fun parse(raw: String): ProfileInfo? {
        val root = JSONObject(raw)
        val d = root.optJSONObject("data") ?: return null
        return ProfileInfo(
            uid = d.optLong("uid", 0L),
            phone = d.optString("username", ""),
            name = d.optString("name", ""),
            nickname = d.optString("nickname", ""),
            avatarUrl = d.optString("icon", ""),
            studentId = d.optString("campusId", ""),
            campusName = d.optString("campusName", ""),
            department = d.optString("depart", ""),
            gradeClass = d.optString("gradeClass", ""),
            enrollmentYear = d.optInt("enrollmentYear", 0),
            sex = d.optInt("sex", -1),
            identificationStatus = d.optInt("identificationStatus", -1),
            infoComplete = d.optBoolean("infoComplete", false),
            bindingWechat = d.optBoolean("bindingWeChat", false),
            bindingQQ = d.optBoolean("bindingQQ", false),
            bindingApple = d.optBoolean("bindingApple", false),
        )
    }
}
