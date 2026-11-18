package com.tv.ball.util

/**
 * 热门搜索词管理
 */
object HotSearchManager {

    /**
     * 获取热门搜索词列表
     */
    fun getHotSearchWords(): List<String> {
        return listOf(
            "白日提灯",
            "庆余年",
            "三体",
            "繁花",
            "狂飙",
            "漫长的季节",
            "开端",
            "人世间",
            "风起洛阳",
            "雪中悍刀行"
        )
    }

    /**
     * 获取推荐搜索词（根据输入的关键词）
     */
    fun getSuggestions(query: String): List<String> {
        if (query.isEmpty()) return emptyList()

        val allWords = listOf(
            "白日提灯", "白日梦我", "白夜追凶",
            "庆余年", "庆余年第二季",
            "三体", "三体动画", "三体真人版",
            "繁花", "繁城之下",
            "狂飙", "狂飙之路",
            "漫长的季节", "漫长的告白",
            "开端", "开端电影版",
            "人世间", "人世间纪录片",
            "风起洛阳", "风起陇西", "风起霓裳",
            "雪中悍刀行", "雪豹", "雪山飞狐",
            "长相思", "长月烬明", "长安十二时辰",
            "斗罗大陆", "斗破苍穹",
            "鬼灭之刃", "鬼吹灯",
            "进击的巨人", "进击的巨人最终季",
            "海贼王", "海贼王剧场版",
            "火影忍者", "火影忍者疾风传",
            "名侦探柯南", "名侦探柯南剧场版"
        )

        return allWords.filter { it.contains(query, ignoreCase = true) }
            .take(5)
    }
}
