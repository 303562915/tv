package com.tv.ball.feature.f046

/**
 * F046「拼音首字母匹配」的能力层。
 *
 * 纯 Kotlin，不引第三方拼音库、也不碰任何 Android API：拼音来自文件内的 [RAW_TABLE]
 * 一张「汉字 → 无声调拼音」表。首字母就是拼音的第一个字母，所以同一张表同时喂
 * [initials] 与 [fullPinyin]，界面能直接演示「lsh 命中蓝色海洋，lanse 也能命中」，
 * 单测也能对具体命中顺序做断言。
 *
 * 表覆盖范围：共 1466 个常用汉字（运行时以 [dictionarySize] 为准），按声母 A..Z 分组
 * 手写，影视 / 剧集标题里的高频字优先。已知取舍：
 * 1. 多音字只收最常见的一个读音（长 = chang、重 = zhong、乐 = le），不去猜上下文；
 * 2. 表外的生僻字不产生拼音，[initials] / [fullPinyin] 会跳过它（宁可少命中，不误命中）；
 * 3. 英文、数字原样保留并转小写，走 LITERAL 通道做前缀匹配。
 */
object F046Engine {

    /** 一条标题的匹配强度，声明顺序就是排序优先级。 */
    enum class MatchKind { EXACT_INITIALS, PREFIX, CONTAINS, NONE }

    /** 命中的是哪套键：首字母串 / 全拼串 / 原文（英文、数字标题走这一路）。 */
    enum class HitChannel { INITIALS, FULL_PINYIN, LITERAL, NONE }

    /** 一次命中：强度 + 通道。界面拿它显示「全拼前缀」这类文案。 */
    data class Hit(val kind: MatchKind, val channel: HitChannel)

    /**
     * [rank] 的一行结果。
     *
     * [initials] / [fullPinyin] 是这条标题的匹配键，一并带出来是为了界面上能直接看到
     * 「为什么命中」，排查匹配问题时不用再自己算一遍。
     */
    data class Ranked(
        val title: String,
        val kind: MatchKind,
        val channel: HitChannel,
        val initials: String,
        val fullPinyin: String,
        val score: Int,
        val keyLength: Int,
        val sourceIndex: Int
    )

    /** 拼音表收录多少个字（界面用来说明覆盖范围）。 */
    val dictionarySize: Int get() = dictionary.size

    // region 匹配

    /** 只要强度，不看通道。没有命中返回 [MatchKind.NONE]。 */
    fun match(title: String, query: String): MatchKind = matchDetail(title, query).kind

    /** 强度 + 通道。 */
    fun matchDetail(title: String, query: String): Hit {
        val ranked = evaluate(title, query, 0) ?: return Hit(MatchKind.NONE, HitChannel.NONE)
        return Hit(ranked.kind, ranked.channel)
    }

    /**
     * 按匹配度排序后的命中列表。
     *
     * 排序规则：先比 [MatchKind]（首字母整串 > 前缀 > 包含），再比分数（同样前缀时
     * 覆盖整条标题比例更高的在前），再比匹配键更短的（标题更聚焦），最后按原顺序兜底，
     * 保证同样分数下顺序稳定、不会每次刷新乱跳。
     *
     * 未命中的标题不会出现在结果里——空列表就是界面上要显示的空态。
     */
    fun rank(titles: List<String>, query: String): List<Ranked> =
        titles.mapIndexedNotNull { index, title -> evaluate(title, query, index) }
            .sortedWith(RANKING)

    /**
     * 单个标题的完整评估。query 为空（或全是标点）时返回 null，表示不参与匹配。
     *
     * 判定顺序是「越确定的越先返回」：首字母整串相等 → 首字母前缀 → 全拼前缀 →
     * 原文前缀 → 首字母包含 → 全拼包含 → 原文包含。
     */
    private fun evaluate(title: String, query: String, sourceIndex: Int): Ranked? {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return null

        val ini = initials(title)
        val pin = fullPinyin(title)
        val lit = literalKey(title)

        fun build(kind: MatchKind, channel: HitChannel, key: String, at: Int): Ranked {
            val score = scoreOf(kind, channel, q.length, key.length, at)
            return Ranked(title, kind, channel, ini, pin, score, key.length, sourceIndex)
        }

        if (ini == q) return build(MatchKind.EXACT_INITIALS, HitChannel.INITIALS, ini, 0)
        if (ini.startsWith(q)) return build(MatchKind.PREFIX, HitChannel.INITIALS, ini, 0)
        if (pin.startsWith(q)) return build(MatchKind.PREFIX, HitChannel.FULL_PINYIN, pin, 0)
        if (lit.startsWith(q)) return build(MatchKind.PREFIX, HitChannel.LITERAL, lit, 0)

        val atInitials = ini.indexOf(q)
        if (atInitials > 0) return build(MatchKind.CONTAINS, HitChannel.INITIALS, ini, atInitials)
        val atPinyin = pin.indexOf(q)
        if (atPinyin > 0) return build(MatchKind.CONTAINS, HitChannel.FULL_PINYIN, pin, atPinyin)
        val atLiteral = lit.indexOf(q)
        if (atLiteral > 0) return build(MatchKind.CONTAINS, HitChannel.LITERAL, lit, atLiteral)

        return null
    }

    /**
     * 分数：强度基数 + 通道权重 + 覆盖率 - 命中位置。
     *
     * 覆盖率 = 查询串长度 / 整条匹配键长度。前缀命中时这个值很有用：
     * 「lanse」打到「lansexingqiu」比打到「lansehaiyang…」更接近整串，排前面更合理。
     */
    private fun scoreOf(
        kind: MatchKind,
        channel: HitChannel,
        matchedLength: Int,
        keyLength: Int,
        position: Int
    ): Int {
        val base = when (kind) {
            MatchKind.EXACT_INITIALS -> 400
            MatchKind.PREFIX -> 300
            MatchKind.CONTAINS -> 200
            MatchKind.NONE -> return 0
        }
        val channelBonus = when (channel) {
            HitChannel.INITIALS -> 30
            HitChannel.FULL_PINYIN -> 20
            HitChannel.LITERAL -> 10
            HitChannel.NONE -> 0
        }
        val coverage = if (keyLength <= 0) 0 else matchedLength * 100 / keyLength
        return base + channelBonus + coverage - position.coerceAtLeast(0)
    }

    private val RANKING: Comparator<Ranked> =
        compareBy<Ranked> { it.kind.ordinal }
            .thenByDescending { it.score }
            .thenBy { it.keyLength }
            .thenBy { it.sourceIndex }

    // endregion

    // region 拼音

    /**
     * 汉字 → 无声调拼音。
     *
     * 每项写成「汉字拼音」，空格分隔，按声母 A..Z 分行排列（行顺序不影响解析）。
     * 首字母取拼音的第一个字母，所以不用再单独维护 26 张首字母区间表；
     * 也不存在「Unicode 码点区间反推拼音」那种按部首排序的错位问题。
     */
    private val RAW_TABLE: String = """
        啊a 阿a 埃ai 挨ai 哎ai 爱ai 艾ai 碍ai 矮ai 安an 岸an 按an 案an 暗an 昂ang 傲ao 奥ao 澳ao 熬ao
        八ba 巴ba 拔ba 把ba 爸ba 吧ba 白bai 百bai 摆bai 败bai 拜bai 班ban 半ban 办ban 版ban 板ban 伴ban 帮bang 榜bang 棒bang
        包bao 宝bao 抱bao 报bao 暴bao 杯bei 北bei 背bei 被bei 备bei 悲bei 贝bei 本ben 笨ben 逼bi 比bi 必bi 笔bi 避bi 碧bi 毕bi
        边bian 编bian 变bian 便bian 遍bian 表biao 别bie 兵bing 冰bing 并bing 病bing 波bo 播bo 博bo 伯bo 泊bo 不bu 布bu 步bu 部bu 补bu 捕bu
        擦ca 猜cai 才cai 材cai 财cai 彩cai 采cai 菜cai 参can 残can 灿can 仓cang 苍cang 藏cang 操cao 草cao 策ce 层ceng 曾ceng
        叉cha 差cha 茶cha 查cha 察cha 拆chai 柴chai 产chan 缠chan 长chang 场chang 常chang 唱chang 畅chang 超chao 朝chao 潮chao 炒chao
        车che 彻che 尘chen 沉chen 陈chen 晨chen 称cheng 城cheng 成cheng 程cheng 承cheng 乘cheng 吃chi 池chi 迟chi 持chi 尺chi 翅chi
        冲chong 虫chong 重chong 抽chou 仇chou 愁chou 丑chou 臭chou 出chu 初chu 除chu 楚chu 处chu 触chu 穿chuan 传chuan 船chuan 串chuan
        窗chuang 床chuang 创chuang 吹chui 垂chui 春chun 纯chun 唇chun 词ci 此ci 次ci 刺ci 从cong 聪cong 葱cong 凑cou 粗cu 促cu 醋cu
        窜cuan 摧cui 脆cui 翠cui 村cun 存cun 寸cun 错cuo 搓cuo
        搭da 达da 答da 打da 大da 呆dai 带dai 代dai 袋dai 待dai 戴dai 丹dan 单dan 担dan 胆dan 但dan 淡dan 蛋dan 当dang 挡dang 党dang 荡dang
        刀dao 到dao 道dao 盗dao 岛dao 倒dao 灯deng 等deng 登deng 凳deng 低di 底di 地di 第di 帝di 敌di 滴di 点dian 店dian 电dian 垫dian 典dian 殿dian
        的de 得de 雕diao 掉diao 调diao 跌die 叠die 蝶die 丁ding 盯ding 顶ding 定ding 订ding 丢diu 东dong 冬dong 懂dong 洞dong 动dong 都dou 斗dou 豆dou
        独du 读du 度du 渡du 毒du 堵du 短duan 段duan 断duan 缎duan 堆dui 队dui 对dui 兑dui 吨dun 蹲dun 顿dun 多duo 夺duo 朵duo 躲duo 舵duo
        额e 俄e 鹅e 恶e 饿e 恩en 儿er 而er 耳er 二er
        发fa 法fa 罚fa 番fan 翻fan 凡fan 反fan 返fan 饭fan 犯fan 范fan 方fang 房fang 防fang 放fang 访fang 仿fang
        飞fei 非fei 肥fei 肺fei 费fei 废fei 分fen 纷fen 坟fen 粉fen 风feng 封feng 峰feng 疯feng 逢feng 缝feng 佛fo 否fou
        夫fu 服fu 浮fu 福fu 府fu 父fu 负fu 复fu 富fu 付fu 附fu 妇fu 腐fu 副fu
        该gai 改gai 盖gai 概gai 干gan 甘gan 敢gan 感gan 赶gan 刚gang 钢gang 岗gang 港gang 高gao 稿gao 告gao
        隔ge 歌ge 格ge 革ge 个ge 各ge 给gei 根gen 跟gen 更geng 耕geng 工gong 公gong 功gong 攻gong 供gong 宫gong 共gong 贡gong
        狗gou 勾gou 沟gou 够gou 构gou 姑gu 古gu 骨gu 谷gu 故gu 顾gu 固gu 鼓gu 挂gua 乖guai 怪guai
        关guan 观guan 官guan 管guan 馆guan 惯guan 光guang 广guang 逛guang 归gui 规gui 鬼gui 贵gui 柜gui 滚gun 锅guo 国guo 果guo 过guo
        哈ha 还hai 孩hai 海hai 害hai 含han 寒han 喊han 汉han 汗han 航hang 毫hao 好hao 号hao 浩hao
        喝he 和he 河he 合he 何he 荷he 贺he 黑hei 很hen 狠hen 恨hen 横heng 哼heng 红hong 洪hong 虹hong
        后hou 厚hou 候hou 猴hou 呼hu 湖hu 胡hu 虎hu 互hu 户hu 护hu 花hua 华hua 划hua 画hua 话hua
        怀huai 坏huai 欢huan 环huan 缓huan 换huan 唤huan 荒huang 皇huang 黄huang 晃huang 灰hui 回hui 会hui 汇hui 毁hui 慧hui 婚hun 混hun 活huo 火huo 或huo 货huo 获huo
        击ji 机ji 鸡ji 极ji 级ji 即ji 急ji 集ji 几ji 己ji 记ji 纪ji 技ji 际ji 季ji 寄ji 计ji 加jia 佳jia 家jia 假jia 价jia 架jia
        尖jian 间jian 坚jian 肩jian 见jian 件jian 建jian 剑jian 渐jian 箭jian 江jiang 将jiang 讲jiang 降jiang
        交jiao 角jiao 脚jiao 叫jiao 教jiao 阶jie 接jie 街jie 节jie 结jie 解jie 姐jie 借jie 介jie 界jie
        今jin 金jin 紧jin 近jin 进jin 尽jin 劲jin 惊jing 京jing 精jing 经jing 井jing 警jing 静jing 镜jing
        究jiu 九jiu 久jiu 酒jiu 旧jiu 救jiu 就jiu 居ju 局ju 举ju 巨ju 句ju 具ju 剧ju 聚ju 拒ju 卷juan 决jue 绝jue 觉jue 军jun 均jun 君jun
        卡ka 开kai 凯kai 慨kai 刊kan 看kan 砍kan 抗kang 康kang 考kao 靠kao 科ke 可ke 克ke 刻ke 客ke 课ke 肯ken 坑keng
        空kong 孔kong 恐kong 控kong 口kou 扣kou 哭ku 苦ku 库ku 裤ku 夸kua 快kuai 块kuai 宽kuan 款kuan 筐kuang 狂kuang 况kuang 亏kui 葵kui 愧kui 昆kun 困kun 扩kuo 阔kuo
        拉la 啦la 来lai 赖lai 兰lan 蓝lan 拦lan 栏lan 篮lan 览lan 懒lan 烂lan 狼lang 浪lang 朗lang 劳lao 老lao 落lao
        乐le 了le 雷lei 泪lei 累lei 类lei 冷leng 离li 里li 理li 力li 立li 李li 利li 丽li 历li 厉li 例li
        连lian 联lian 莲lian 脸lian 练lian 恋lian 两liang 亮liang 量liang 良liang 凉liang 料liao 列lie 烈lie 猎lie
        林lin 临lin 邻lin 灵ling 铃ling 零ling 领ling 另ling 令ling 留liu 流liu 柳liu 六liu 龙long 隆long 笼long 楼lou 漏lou
        露lu 鲁lu 路lu 陆lu 鹿lu 旅lv 律lv 绿lv 乱luan 掠lve 轮lun 论lun 罗luo 洛luo 络luo 裸luo
        妈ma 麻ma 马ma 码ma 骂ma 吗ma 埋mai 买mai 卖mai 迈mai 满man 慢man 漫man 蛮man 忙mang 盲mang 猫mao 毛mao 冒mao 貌mao 么me
        眉mei 梅mei 每mei 美mei 妹mei 门men 闷men 们men 梦meng 猛meng 蒙meng 迷mi 米mi 密mi 秘mi 蜜mi 面mian 免mian 勉mian 苗miao 秒miao 妙miao
        灭mie 民min 敏min 名ming 明ming 鸣ming 命ming 摸mo 摩mo 魔mo 末mo 莫mo 墨mo 默mo 某mou 母mu 木mu 目mu 幕mu 牧mu 慕mu
        拿na 哪na 那na 纳na 奶nai 耐nai 男nan 南nan 难nan 脑nao 闹nao 呢ne 内nei 嫩nen 能neng 你ni 尼ni 泥ni 拟ni 逆ni
        年nian 念nian 娘niang 鸟niao 您nin 宁ning 凝ning 牛niu 扭niu 农nong 浓nong 弄nong 怒nu 女nv 暖nuan 虐nve 挪nuo 诺nuo
        哦o 欧ou 偶ou 藕ou
        爬pa 怕pa 拍pai 排pai 牌pai 派pai 盘pan 盼pan 判pan 叛pan 旁pang 胖pang 抛pao 跑pao 泡pao 炮pao
        陪pei 培pei 配pei 佩pei 喷pen 盆pen 朋peng 棚peng 碰peng 批pi 皮pi 疲pi 匹pi 偏pian 篇pian 片pian 骗pian 飘piao 票piao
        拼pin 贫pin 品pin 平ping 评ping 凭ping 瓶ping 破po 婆po 迫po 扑pu 铺pu 普pu 谱pu 瀑pu
        七qi 妻qi 期qi 欺qi 齐qi 其qi 奇qi 骑qi 棋qi 旗qi 起qi 气qi 汽qi 器qi
        千qian 迁qian 牵qian 前qian 钱qian 浅qian 欠qian 枪qiang 强qiang 墙qiang 抢qiang
        敲qiao 桥qiao 悄qiao 巧qiao 切qie 且qie 亲qin 侵qin 琴qin 勤qin 青qing 轻qing 倾qing 清qing 情qing 晴qing 请qing 庆qing
        穷qiong 秋qiu 求qiu 球qiu 囚qiu 区qu 曲qu 驱qu 取qu 去qu 趣qu 圈quan 全quan 权quan 泉quan 拳quan 劝quan 缺que 却que 确que 群qun
        然ran 燃ran 染ran 让rang 饶rao 扰rao 绕rao 热re 人ren 忍ren 认ren 任ren 扔reng 日ri 容rong 荣rong 融rong 肉rou 如ru 入ru 软ruan 锐rui 润run 若ruo 弱ruo
        撒sa 洒sa 塞sai 赛sai 三san 伞san 散san 嗓sang 丧sang 扫sao 色se 森sen 僧seng
        杀sha 沙sha 傻sha 晒shai 山shan 闪shan 扇shan 善shan 伤shang 商shang 上shang 尚shang 烧shao 少shao 绍shao
        蛇she 设she 社she 舍she 深shen 神shen 审shen 甚shen 声sheng 生sheng 升sheng 省sheng 圣sheng 剩sheng
        失shi 师shi 诗shi 十shi 石shi 时shi 识shi 实shi 食shi 使shi 始shi 士shi 世shi 市shi 示shi 事shi 是shi 适shi 室shi 释shi
        收shou 手shou 守shou 首shou 受shou 兽shou 书shu 叔shu 树shu 数shu 属shu 术shu 束shu 述shu
        刷shua 摔shuai 甩shuai 帅shuai 双shuang 霜shuang 水shui 睡shui 顺shun 说shuo 硕shuo
        司si 丝si 私si 思si 死si 四si 似si 送song 松song 宋song 搜sou 苏su 诉su 素su 速su 宿su
        酸suan 算suan 虽sui 随sui 岁sui 碎sui 孙sun 损sun 缩suo 所suo 索suo 锁suo
        他ta 她ta 它ta 塔ta 踏ta 胎tai 台tai 抬tai 太tai 态tai 谈tan 贪tan 滩tan 坦tan 叹tan 碳tan 汤tang 唐tang 堂tang 躺tang 烫tang
        掏tao 逃tao 桃tao 讨tao 套tao 特te 疼teng 梯ti 提ti 题ti 体ti 替ti 天tian 田tian 甜tian 填tian 挑tiao 条tiao 跳tiao 贴tie 铁tie
        听ting 停ting 挺ting 通tong 同tong 铜tong 童tong 统tong 痛tong 偷tou 头tou 透tou 突tu 图tu 土tu 兔tu 团tuan 推tui 腿tui 退tui 吞tun 托tuo 脱tuo 拖tuo 妥tuo
        挖wa 娃wa 瓦wa 歪wai 外wai 弯wan 完wan 玩wan 晚wan 万wan 碗wan 王wang 网wang 往wang 忘wang 望wang
        危wei 威wei 微wei 为wei 围wei 违wei 唯wei 尾wei 委wei 未wei 位wei 味wei 卫wei 胃wei 温wen 文wen 闻wen 问wen 稳wen
        我wo 卧wo 握wo 屋wu 无wu 五wu 午wu 武wu 舞wu 物wu 务wu 悟wu 雾wu
        西xi 吸xi 希xi 息xi 稀xi 溪xi 洗xi 喜xi 系xi 细xi 戏xi 下xia 夏xia 吓xia
        先xian 仙xian 掀xian 鲜xian 闲xian 显xian 现xian 线xian 限xian 陷xian 相xiang 香xiang 想xiang 响xiang 向xiang 项xiang 象xiang 像xiang
        消xiao 小xiao 笑xiao 效xiao 些xie 写xie 谢xie 心xin 辛xin 新xin 信xin 星xing 行xing 醒xing 性xing 幸xing 姓xing
        兄xiong 雄xiong 熊xiong 休xiu 修xiu 秀xiu 袖xiu 需xu 虚xu 许xu 序xu 续xu 宣xuan 悬xuan 旋xuan 选xuan 学xue 雪xue 寻xun 询xun 训xun 讯xun 迅xun
        压ya 呀ya 牙ya 芽ya 亚ya 烟yan 严yan 言yan 岩yan 沿yan 眼yan 演yan 验yan 燕yan
        央yang 羊yang 阳yang 洋yang 养yang 样yang 腰yao 邀yao 摇yao 药yao 要yao 耀yao 爷ye 也ye 夜ye 业ye 叶ye 页ye
        一yi 衣yi 医yi 依yi 疑yi 移yi 遗yi 以yi 已yi 意yi 义yi 亿yi 忆yi 艺yi 易yi 疫yi 音yin 阴yin 银yin 引yin 隐yin 印yin
        应ying 英ying 樱ying 迎ying 影ying 硬ying 优you 幽you 悠you 由you 游you 有you 友you 又you 右you 幼you
        于yu 与yu 宇yu 余yu 鱼yu 愉yu 渔yu 雨yu 语yu 玉yu 域yu 遇yu 预yu 御yu 元yuan 园yuan 员yuan 原yuan 圆yuan 远yuan 院yuan 愿yuan
        月yue 越yue 云yun 匀yun 允yun 运yun 晕yun 韵yun
        杂za 砸za 再zai 在zai 载zai 咱zan 暂zan 赞zan 脏zang 葬zang 遭zao 早zao 造zao 噪zao 泽ze 贼zei 怎zen 增zeng
        扎zha 眨zha 炸zha 摘zhai 窄zhai 债zhai 展zhan 站zhan 战zhan 占zhan 张zhang 章zhang 掌zhang 涨zhang 找zhao 照zhao 罩zhao
        折zhe 者zhe 这zhe 着zhe 真zhen 针zhen 珍zhen 阵zhen 振zhen 镇zhen 争zheng 征zheng 睁zheng 整zheng 正zheng 证zheng
        支zhi 只zhi 汁zhi 之zhi 知zhi 织zhi 直zhi 值zhi 职zhi 止zhi 纸zhi 指zhi 至zhi 志zhi 制zhi 治zhi 质zhi
        中zhong 忠zhong 终zhong 钟zhong 重zhong 众zhong 州zhou 周zhou 洲zhou 舟zhou 宙zhou 轴zhou 皱zhou
        朱zhu 逐zhu 竹zhu 主zhu 住zhu 助zhu 注zhu 柱zhu 祝zhu 著zhu 抓zhua 专zhuan 转zhuan 传zhuan 装zhuang 壮zhuang 状zhuang 撞zhuang 追zhui 坠zhui 准zhun 捉zhuo 桌zhuo
        资zi 姿zi 子zi 紫zi 字zi 自zi 宗zong 总zong 走zou 租zu 足zu 族zu 阻zu 组zu 祖zu 钻zuan 嘴zui 最zui 罪zui 尊zun 昨zuo 左zuo 作zuo 做zuo 坐zuo 座zuo
    """.trimIndent()

    /** 第一次用到时才解析，纯查表不产生额外分配。 */
    private val dictionary: Map<Char, String> by lazy { parse(RAW_TABLE) }

    private fun parse(raw: String): Map<Char, String> {
        val map = HashMap<Char, String>(raw.length / 4)
        raw.split('\n').forEach { line ->
            line.split(' ').forEach { token ->
                // 同一汉字出现多次（多音字）时后写的覆盖先写的，取靠后那个更通用的读音。
                if (token.length >= 2) map[token[0]] = token.substring(1)
            }
        }
        return map
    }

    /**
     * 首字母串：汉字取拼音首字母，英文/数字原样（转小写），标点与空格直接丢掉。
     *
     * 于是「蓝色海洋」→ "lshy"，「Discovery 频道」→ "discovery"，
     * 遥控器上按三下 lsh 就能把「蓝色海洋」顶出来。
     */
    fun initials(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            val syllable = dictionary[ch]
            if (syllable == null) {
                asciiKey(ch)?.let { append(it) }
            } else {
                append(syllable[0])
            }
        }
    }

    /**
     * 全拼串：汉字取完整无声调拼音，英文/数字原样（转小写），标点丢掉。
     *
     * 「蓝色海洋」→ "lansehaiyang"。同样能当查询串用——[rank] 内部先把查询串过一遍
     * 这个方法，所以用户直接打中文「蓝色」也会被转成 "lanse" 再匹配。
     */
    fun fullPinyin(text: String): String = buildString(text.length * 4) {
        text.forEach { ch ->
            val syllable = dictionary[ch]
            if (syllable == null) {
                asciiKey(ch)?.let { append(it) }
            } else {
                append(syllable)
            }
        }
    }

    /**
     * 这些标题里有多少汉字不在拼音表内（界面用来提示覆盖缺口）。
     *
     * 生僻字只会「查不到」，不会让匹配出错；但先亮出来，免得有人以为是匹配逻辑坏了。
     */
    fun missingChars(titles: List<String>): List<Char> {
        val missing = LinkedHashSet<Char>()
        titles.forEach { title ->
            title.forEach { ch ->
                if (ch in '\u4E00'..'\u9FFF' && !dictionary.containsKey(ch)) missing.add(ch)
            }
        }
        return missing.toList()
    }

    /** 查询串归一化：中文转全拼、英文数字转小写、标点空格丢弃。 */
    private fun normalizeQuery(raw: String): String = fullPinyin(raw)

    /** 原文键：只留英文和数字并转小写，用来给英文标题做前缀匹配。 */
    private fun literalKey(title: String): String = buildString(title.length) {
        title.forEach { ch -> asciiKey(ch)?.let { append(it) } }
    }

    private fun asciiKey(ch: Char): Char? = when {
        ch in 'A'..'Z' -> ch + 32
        ch in 'a'..'z' -> ch
        ch in '0'..'9' -> ch
        else -> null
    }

    // endregion
}
