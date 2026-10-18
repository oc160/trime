// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.bar.ui

/**
 * 无调拼音音节表 + T9 数字编码，用于"从输入数字枚举可能音节"（Gboard 式）。
 * 枚举时同时包含 zh/ch/sh 与单字母这类"音节前缀"，方便逐位消歧。
 */
object PinyinSyllables {
    private val SYLLABLE_TEXT =
        "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong " +
            "chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo " +
            "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan " +
            "dui dun duo e ei en eng er fa fan fang fei fen feng fo fou fu " +
            "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
            "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
            "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
            "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
            "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou " +
            "lu luan lun luo lv lve " +
            "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
            "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou " +
            "nu nuan nuo nv nve o ou " +
            "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
            "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
            "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
            "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi " +
            "shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo " +
            "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
            "wa wai wan wang wei wen weng wo wu " +
            "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
            "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng " +
            "zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"

    val syllables: List<String> = SYLLABLE_TEXT.split(' ').filter { it.isNotEmpty() }

    private val digitOf: Map<Char, Char> =
        buildMap {
            for (c in "abc") put(c, '2')
            for (c in "def") put(c, '3')
            for (c in "ghi") put(c, '4')
            for (c in "jkl") put(c, '5')
            for (c in "mno") put(c, '6')
            for (c in "pqrs") put(c, '7')
            for (c in "tuv") put(c, '8')
            for (c in "wxyz") put(c, '9')
        }

    /** 字母串 -> T9 数字码；含非字母返回空串。 */
    fun t9Code(text: String): String = buildString {
        for (ch in text) {
            append(digitOf[ch.lowercaseChar()] ?: return "")
        }
    }

    /**
     * 枚举可消耗 [digits] 前若干位的音节（含 zh/ch/sh 与单字母前缀）。
     * 返回按"长度降序、同长按字典序"的音节列表。
     */
    fun candidatesFor(digits: String): List<String> {
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return emptyList()
        val found = LinkedHashSet<String>()
        // 首位数字对应的全部字母（w/x/y/z 这类），便于逐位消歧
        for (c in "wxyz") if (digits[0] == '9') found.add(c.toString())
        for (c in "abc") if (digits[0] == '2') found.add(c.toString())
        for (c in "def") if (digits[0] == '3') found.add(c.toString())
        for (c in "ghi") if (digits[0] == '4') found.add(c.toString())
        for (c in "jkl") if (digits[0] == '5') found.add(c.toString())
        for (c in "mno") if (digits[0] == '6') found.add(c.toString())
        for (c in "pqrs") if (digits[0] == '7') found.add(c.toString())
        for (c in "tuv") if (digits[0] == '8') found.add(c.toString())
        for (syl in syllables) {
            if (syl.length > digits.length) continue
            if (digits.startsWith(t9Code(syl))) found.add(syl)
            // 音节前缀（如 zh / ch / sh / 单字母），长度 1..len-1
            for (l in 1 until syl.length) {
                val p = syl.substring(0, l)
                if (digits.startsWith(t9Code(p))) found.add(p)
            }
        }
        return found.sortedWith(compareByDescending<String> { it.length }.thenBy { it })
    }
}
