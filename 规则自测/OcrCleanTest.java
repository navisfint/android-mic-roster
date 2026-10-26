import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 与 MicOcr.kt 的清洗链完全对齐的验证程序（Java 正则 == Kotlin 正则） */
public class OcrCleanTest {

    static final char[] TRIM_EDGE = {' ', '　', '·', '•', '●', '|', '丨', '、', ',', '.', '，', '。',
            '-', '_', '~', '～', '*', '#', '^', '=', '+', '—', '…'};
    static final String ICON_GLYPHS = "QOoDdCcGgUuPpBbq0";
    static final String HEAD_SEP = " .·|丨-_、，,：:";
    static final String PUNCT_OK = "　、。，！？；：·—…《》【】（）「」『』〈〉“”‘’";

    static final Pattern RE_ICON_HEAD = Pattern.compile("^([^\\u4E00-\\u9FFF]{1,2})(?=[\\u4E00-\\u9FFF]{2,})");
    static final Pattern RE_TRAIL_LETTER = Pattern.compile("([\\u4E00-\\u9FFF]{2,})[\\s·|丨]*[A-Za-z]$");
    static final Pattern RE_TRAIL_NUM = Pattern.compile("\\s+\\d{1,7}$");
    static final Pattern RE_NUMERIC_UNIT = Pattern.compile("^[\\d.]+[万千百亿wWkK]$");

    static final java.util.Set<String> EXACT_BAD = new java.util.HashSet<>(java.util.Arrays.asList(
            "关注", "点歌", "主持人", "加入", "空位", "说点什么", "说点什么...",
            "聊天室榜", "小时榜", "直播广场", "在线", "人气", "主持人。"));

    static final String[] CONTAIN_BAD = {"加入大作战", "大作战", "谨防", "诈骗", "诱导消费", "线下交易",
            "本场点赞", "小时榜", "聊天室榜", "直播广场", "粉丝群上麦", "新人创业",
            "财产损失", "请谨慎", "私下交易", "虚位以待", "点击加入", "上麦", "排队中"};

    static boolean isHan(char c) {
        int x = c;
        return (x >= 0x4E00 && x <= 0x9FFF) || (x >= 0x3400 && x <= 0x4DBF) || (x >= 0xF900 && x <= 0xFAFF);
    }

    static boolean isPrintable(char c) {
        if (isHan(c)) return true;
        int x = c;
        if (x >= 0x20 && x <= 0x7E) return true;
        return PUNCT_OK.indexOf(c) >= 0;
    }

    static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (isPrintable(c)) b.append(c);
        return b.toString();
    }

    static boolean inTrim(char c) {
        for (char t : TRIM_EDGE) if (t == c) return true;
        return false;
    }

    static String trimEdge(String s) {
        int a = 0, z = s.length();
        while (a < z && inTrim(s.charAt(a))) a++;
        while (z > a && inTrim(s.charAt(z - 1))) z--;
        return s.substring(a, z);
    }

    static String stripIconPrefix(String s) {
        Matcher m = RE_ICON_HEAD.matcher(s);
        if (!m.find()) return s;
        String head = m.group(1);
        if (head.length() == 2 && Character.isUpperCase(head.charAt(0)) && Character.isUpperCase(head.charAt(1))) return s;
        for (char c : head.toCharArray()) {
            if (ICON_GLYPHS.indexOf(c) < 0 && HEAD_SEP.indexOf(c) < 0) return s;
        }
        String rest = s.substring(head.length());
        int han = 0;
        for (char c : rest.toCharArray()) if (isHan(c)) han++;
        return han >= 2 ? rest : s;
    }

    static String stripTrailLetter(String s) {
        Matcher m = RE_TRAIL_LETTER.matcher(s);
        return m.find() ? m.group(1) : s;
    }

    static String clean(String raw) {
        String s = raw.replaceAll("[\\u200B-\\u200F\\uFEFF\\u00AD]", " ")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        s = sanitize(s).trim();
        if (s.isEmpty()) return "";
        s = trimEdge(s);
        s = trimEdge(stripIconPrefix(s));
        s = trimEdge(RE_TRAIL_NUM.matcher(s).replaceAll(""));
        s = trimEdge(stripTrailLetter(s));

        if (s.length() < 2 || s.length() > 24) return "";
        boolean hasLetter = false, hasHan = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            if (isHan(c)) hasHan = true;
        }
        if (!hasLetter) return "";

        if (!hasHan) {
            String[] tk = s.split("\\s+");
            int n = 0;
            boolean allOne = true;
            for (String t : tk) {
                if (t.isEmpty()) continue;
                n++;
                if (t.length() != 1) allOne = false;
            }
            if (n >= 2 && allOne) return "";
        }

        if (EXACT_BAD.contains(s)) return "";
        if (s.indexOf('：') >= 0 || s.indexOf(':') >= 0) return "";
        for (String bad : CONTAIN_BAD) if (s.contains(bad)) return "";

        StringBuilder compact = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isLetterOrDigit(c) || c == '.') compact.append(c);
        if (compact.length() > 0 && RE_NUMERIC_UNIT.matcher(compact.toString()).matches()) return "";

        int solid = 0;
        for (char c : s.toCharArray()) if (Character.isLetterOrDigit(c)) solid++;
        if (solid * 2 < s.length()) return "";

        return s;
    }

    public static void main(String[] args) {
        String[][] cases = {
                // ① 话筒图标贴名字左边
                {"Q大表姐", "大表姐"},
                {"o福袋惠惠", "福袋惠惠"},
                {"0朵朵剧场", "朵朵剧场"},
                {"D月叙漫语", "月叙漫语"},
                {"Q 大表姐", "大表姐"},
                {"O·福袋惠惠", "福袋惠惠"},
                {"Q、朵朵剧场", "朵朵剧场"},
                {"q朵朵剧场", "朵朵剧场"},
                {"C福袋惠惠", "福袋惠惠"},
                {"U朵朵剧场", "朵朵剧场"},
                // 不该被砍的正常名字
                {"DJ小明", "DJ小明"},
                {"MC阿哲", "MC阿哲"},
                {"Amy", "Amy"},
                {"520表白", "520表白"},
                {"1号麦", "1号麦"},
                {"小A", "小A"},
                {"A股老王", "A股老王"},
                {"小杨&鱼", "小杨&鱼"},
                // ② 表情被认成字母
                {"月叙漫语O", "月叙漫语"},
                {"大表姐D", "大表姐"},
                {"福袋惠惠 Q", "福袋惠惠"},
                {"小明KTV", "小明KTV"},
                {"元气满满6", "元气满满6"},
                // ③ 表情本体被原样吐出
                {"大表姐✨", "大表姐"},
                {"月叙漫语 🌸", "月叙漫语"},
                {"小杨®", "小杨"},
                {"朵朵剧场™", "朵朵剧场"},
                {"朵朵剧场◎", "朵朵剧场"},
                // ④ 图标被拆成两个假字
                {"Q O", ""},
                {"Q · D", ""},
                // ⑤ 既有规则不能被破坏
                {"九离 8", "九离"},
                {"2.5万", ""},
                {"♥190", ""},
                {"虚位以待", ""},
                {"上麦", ""},
                {"Q", ""},
                {"关注", ""},
                {"大表姐【新入直播】", "大表姐【新入直播】"},
        };

        int fail = 0;
        for (String[] c : cases) {
            String got = clean(c[0]);
            boolean ok = got.equals(c[1]);
            if (!ok) fail++;
            System.out.printf("%s | %-18s -> %-18s | 期望 %s%n", ok ? "PASS" : "FAIL", c[0], "[" + got + "]", "[" + c[1] + "]");
        }
        System.out.println(fail == 0 ? "\n全部通过 (" + cases.length + " 条)" : "\n失败 " + fail + " 条 / 共 " + cases.length);
    }
}
