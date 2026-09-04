package com.autocarpet.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 中文名拼音检索键 (物品搜索用):
 * keys[0] = 全拼无空格 (白桦原木 -> baihuayuanmu)
 * keys[1] = 拼音首字母串 (白桦原木 -> bhym)
 * 非汉字字母/数字原样并入, 空格与符号忽略。多音字取常用音(字库中排首位)。
 */
public class PinyinUtils {
    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();
    private static final Map<String, String[]> cache = new HashMap<>();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);   // 绿 lv / 略 lve
    }

    public static String[] keys(String displayName) {
        String key = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String[] hit = cache.get(key);
        if (hit != null) return hit;
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                try {
                    String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
                    if (pinyin != null && pinyin.length > 0) {
                        full.append(pinyin[0]);
                        initials.append(pinyin[0].charAt(0));
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                full.append(c);   // 拼音库未覆盖的汉字原样保留
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                full.append(c);
                initials.append(c);
            }
            // 其它字符 (空格/标点/§格式符) 不参与拼音键
        }
        String[] result = {full.toString(), initials.toString()};
        cache.put(key, result);
        return result;
    }
}
