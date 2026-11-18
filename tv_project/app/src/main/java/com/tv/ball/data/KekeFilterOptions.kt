package com.tv.ball.data

/**
 * 可可影视筛选选项
 */
object KekeFilterOptions {
    
    /**
     * 类型筛选（对应顶部分类栏）
     */
    val types = mapOf(
        "" to "全部",
        "%E5%89%A7%E6%83%85" to "剧情",
        "%E5%96%9C%E5%89%A7" to "喜剧",
        "%E5%8A%A8%E4%BD%9C" to "动作",
        "%E7%88%B1%E6%83%85" to "爱情",
        "%E7%A7%91%E5%B9%BB" to "科幻",
        "%E6%81%90%E6%80%96" to "恐怖",
        "%E7%8A%AF%E7%BD%AA" to "犯罪",
        "%E6%83%8A%E6%82%9A" to "惊悚",
        "%E5%86%92%E9%99%A9" to "冒险",
        "%E6%88%98%E4%BA%89" to "战争",
        "%E5%8F%A4%E8%A3%85" to "古装",
        "%E5%AE%B6%E5%BA%AD" to "家庭"
    )
    
    /**
     * 地区筛选
     */
    val areas = mapOf(
        "" to "全部",
        "%E9%9F%A9%E5%9B%BD" to "韩国",
        "%E5%A4%A7%E9%99%86" to "大陆",
        "%E9%A6%99%E6%B8%AF" to "香港",
        "%E5%8F%B0%E6%B9%BE" to "台湾",
        "%E6%97%A5%E6%9C%AC" to "日本",
        "%E7%BE%8E%E5%9B%BD" to "美国",
        "%E6%B3%B0%E5%9B%BD" to "泰国",
        "%E8%8B%B1%E5%9B%BD" to "英国",
        "%E6%96%B0%E5%8A%A0%E5%9D%A1" to "新加坡",
        "%E5%85%B6%E4%BB%96" to "其他"
    )
    
    /**
     * 年份筛选
     */
    val years = mapOf(
        "" to "全部",
        "2024" to "2024",
        "2023" to "2023",
        "2022" to "2022",
        "2021" to "2021",
        "2020" to "2020",
        "2019" to "2019",
        "2018" to "2018",
        "2017" to "2017",
        "2016" to "2016",
        "2015" to "2015"
    )
    
    /**
     * 语言筛选
     */
    val languages = mapOf(
        "" to "全部",
        "%E5%9B%BD%E8%AF%AD" to "国语",
        "%E8%8B%B1%E8%AF%AD" to "英语",
        "%E7%B2%A4%E8%AF%AD" to "粤语",
        "%E9%97%BD%E5%8D%97%E8%AF%AD" to "闽南语",
        "%E9%9F%A9%E8%AF%AD" to "韩语",
        "%E6%97%A5%E8%AF%AD" to "日语",
        "%E6%B3%95%E8%AF%AD" to "法语",
        "%E5%BE%B7%E8%AF%AD" to "德语",
        "%E5%85%B6%E5%AE%83" to "其它"
    )
    
    /**
     * 获取类型的显示名称
     */
    fun getTypeName(typeCode: String): String {
        return types[typeCode] ?: "全部"
    }
    
    /**
     * 获取地区的显示名称
     */
    fun getAreaName(areaCode: String): String {
        return areas[areaCode] ?: "全部"
    }
    
    /**
     * 获取年份的显示名称
     */
    fun getYearName(yearCode: String): String {
        return years[yearCode] ?: "全部"
    }
    
    /**
     * 获取语言的显示名称
     */
    fun getLanguageName(langCode: String): String {
        return languages[langCode] ?: "全部"
    }
}
