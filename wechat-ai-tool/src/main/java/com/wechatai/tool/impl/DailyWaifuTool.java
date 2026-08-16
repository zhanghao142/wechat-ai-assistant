package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 每日二次元角色推荐工具。
 */
@Component
public class DailyWaifuTool {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final List<CharacterProfile> CHARACTERS = List.of(
            new CharacterProfile("阿尔托莉雅·潘德拉贡", "Fate/stay night", "Saber职阶从者",
                    "正直、克制、责任感强", "手持誓约胜利之剑的骑士王，外表冷静，内心珍视荣誉与守护。"),
            new CharacterProfile("牧濑红莉栖", "命运石之门", "脑科学研究员",
                    "理性、敏锐、外冷内热", "年轻的天才研究员，擅长用严密逻辑吐槽，却也会认真守护伙伴。"),
            new CharacterProfile("芙莉莲", "葬送的芙莉莲", "精灵魔法使",
                    "淡然、长情、热爱收集魔法", "以漫长寿命重新理解人与羁绊，在旅途中寻找昔日情感的重量。"),
            new CharacterProfile("喜多川海梦", "更衣人偶坠入爱河", "高中生、Cosplay爱好者",
                    "开朗、坦率、行动力强", "尊重每一种爱好，热情投入Cosplay，也总能给身边的人积极回应。"),
            new CharacterProfile("樱岛麻衣", "青春猪头少年系列", "演员",
                    "成熟、温柔、偶尔毒舌", "面对异常现象依然从容坚定，善于用不动声色的方式关心重要的人。"),
            new CharacterProfile("雷电将军", "原神", "稻妻统治者、雷神",
                    "威严、坚定、追寻永恒", "以雷霆守护稻妻，在对永恒的探索中逐渐理解变化与人的愿望。"),
            new CharacterProfile("刻晴", "原神", "璃月七星·玉衡星",
                    "务实、果断、工作认真", "相信人的命运应由自己创造，是效率极高且敢于质疑传统的行动派。"),
            new CharacterProfile("芙宁娜", "原神", "枫丹知名演员",
                    "戏剧感十足、坚韧、细腻", "舞台上光芒四射，夸张表演背后藏着超乎常人的坚持与勇气。"),
            new CharacterProfile("娜维娅", "原神", "刺玫会会长",
                    "热情、可靠、善于沟通", "以乐观和行动力解决棘手事件，是兼具亲和力与领导力的伙伴。"),
            new CharacterProfile("八重神子", "原神", "鸣神大社宫司",
                    "聪慧、从容、喜欢捉弄人", "经营八重堂的狐仙宫司，总能带着笑意看穿局势并掌握主动。"),
            new CharacterProfile("雷电芽衣", "崩坏3", "女武神",
                    "温柔、坚定、厨艺出色", "为了守护珍视之人不断成长，在温柔外表下拥有直面命运的力量。"),
            new CharacterProfile("爱莉希雅", "崩坏3", "逐火十三英桀",
                    "浪漫、真诚、热爱人类", "如飞花般明媚的少女，以毫无保留的善意拥抱世界和每一个人。"),
            new CharacterProfile("符华", "崩坏3", "女武神、武术家",
                    "沉稳、自律、守护欲强", "背负漫长记忆的武术家，习惯以冷静判断和可靠行动保护同伴。"),
            new CharacterProfile("姬子", "崩坏：星穹铁道", "星穹列车领航员",
                    "优雅、博学、富有冒险精神", "修复星穹列车并踏上开拓旅途，喜欢咖啡，也乐于见证年轻人的成长。"),
            new CharacterProfile("卡芙卡", "崩坏：星穹铁道", "星核猎手",
                    "从容、神秘、掌控力强", "无论局面多危险都保持优雅，擅长以言语和节奏引导事件走向。"),
            new CharacterProfile("流萤", "崩坏：星穹铁道", "星核猎手成员",
                    "温柔、坚韧、向往自由", "在严酷命运中仍珍惜平凡体验，为自己选择的未来坚定战斗。"),
            new CharacterProfile("朱鸢", "绝区零", "治安局刑侦特勤组组长",
                    "认真、可靠、生活中略显天然", "办案时雷厉风行，休息时亲切随和，是值得信赖的治安官。"),
            new CharacterProfile("星见雅", "绝区零", "对空六课课长",
                    "专注、克制、剑术卓越", "以极高标准要求自己，凭借精准判断与剑技守护新艾利都。"),
            new CharacterProfile("妮可·德玛拉", "绝区零", "狡兔屋事务所负责人",
                    "精明、仗义、乐观", "看似处处精打细算，真正遇到伙伴有难时却从不会袖手旁观。"),
            new CharacterProfile("简·杜", "绝区零", "犯罪行为学专家",
                    "机敏、善于伪装、观察力强", "能够迅速读懂他人与环境，用难以预测的方式完成高风险任务。"),
            new CharacterProfile("德克萨斯", "明日方舟", "企鹅物流干员",
                    "冷静、寡言、可靠", "习惯用简洁行动解决问题，沉默外表下十分重视并肩作战的伙伴。"),
            new CharacterProfile("能天使", "明日方舟", "企鹅物流干员",
                    "活泼、乐观、火力充足", "总能为队伍带来轻快气氛，在需要战斗时则展现出惊人的专业度。"),
            new CharacterProfile("凯尔希", "明日方舟", "罗德岛医疗负责人",
                    "博学、冷静、深谋远虑", "掌握大量历史与医学知识，以严格态度推动罗德岛走向正确方向。"),
            new CharacterProfile("斯卡蒂", "明日方舟", "深海猎人",
                    "沉静、强大、不善表达", "独自承担危险的深海猎人，疏离表象下始终保留对同伴的关怀。"),
            new CharacterProfile("陈", "明日方舟", "近卫局高级警司",
                    "正义、果断、要求严格", "以剑与原则维护秩序，面对复杂现实也不会轻易放弃自己的判断。"),
            new CharacterProfile("鬼方佳代子", "碧蓝档案", "便利屋68课长",
                    "冷静、成熟、喜欢音乐", "常被外表误解，实际是团队中稳重可靠、善于照顾同伴的人。"),
            new CharacterProfile("狐坂若藻", "碧蓝档案", "百鬼夜行停学生",
                    "自由、炽烈、行动大胆", "有着鲜明而直接的情感表达，认定目标后会展现惊人的执行力。"),
            new CharacterProfile("冬马和纱", "白色相簿2", "钢琴家",
                    "率直、敏感、不善言辞", "拥有出众音乐才华，习惯把难以表达的情绪寄托在钢琴声中。"),
            new CharacterProfile("古河早苗", "CLANNAD", "面包店经营者",
                    "温柔、乐观、富有包容力", "以明朗和体贴支撑家人，是能让日常生活变得温暖安心的存在。"),
            new CharacterProfile("川名美咲", "青空下的约定", "学生宿舍成员",
                    "开朗、体贴、善解人意", "擅长调节团队气氛，用自然的温柔帮助伙伴面对各自的烦恼。"),
            new CharacterProfile("丛雨", "千恋万花", "建实神社的守护者",
                    "认真、纯真、责任感强", "守护神社数百年的少女，在与伙伴相处中逐渐接触丰富的现代生活。"),
            new CharacterProfile("绫地宁宁", "魔女的夜宴", "学生会成员",
                    "认真、亲切、偶尔冒失", "为了帮助他人而积极行动，即使面对自己的烦恼也保持真诚与善意。"),
            new CharacterProfile("圣园未花", "碧蓝档案", "茶话会成员",
                    "天真、热情、意志坚强", "拥有惊人的行动力，在经历挫折后依然努力学习如何珍惜身边的羁绊。"),
            new CharacterProfile("虞美人", "Fate/Grand Order", "迦勒底从者",
                    "直率、高傲、重感情", "言辞犀利却感情专一，漫长岁月没有磨灭她对重要之人的珍视。"),
            new CharacterProfile("南小鸟", "Love Live!", "校园偶像、服装设计担当",
                    "温柔、细心、审美出众", "用细腻设计支持团队，也会以柔和而坚定的方式表达自己的选择。"),
            new CharacterProfile("高坂丽奈", "吹响吧！上低音号", "小号手",
                    "自信、专注、目标明确", "对音乐抱有毫不妥协的热情，愿意为成为特别的人持续付出努力。")
    );

    @Tool("今日老婆工具。当用户发送或明确询问‘今日老婆’时必须调用。每天为每位用户抽取一名二次元女性角色，并返回角色主要信息")
    public String getDailyWaifu(String userId) {
        LocalDate today = LocalDate.now(CHINA_ZONE);
        String identity = userId == null || userId.isBlank() ? "anonymous" : userId;
        int index = stableIndex(identity + ":" + today, CHARACTERS.size());
        CharacterProfile profile = CHARACTERS.get(index);

        return "今日老婆（" + today + "）\n"
                + "姓名：" + profile.name() + "\n"
                + "出处：" + profile.source() + "\n"
                + "身份：" + profile.identity() + "\n"
                + "特点：" + profile.traits() + "\n"
                + "角色介绍：" + profile.introduction();
    }

    private int stableIndex(String seed, int bound) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            long value = Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt());
            return (int) (value % bound);
        } catch (Exception e) {
            return Math.floorMod(seed.hashCode(), bound);
        }
    }

    private record CharacterProfile(
            String name,
            String source,
            String identity,
            String traits,
            String introduction) {
    }
}
