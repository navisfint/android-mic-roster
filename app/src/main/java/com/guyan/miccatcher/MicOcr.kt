package com.guyan.miccatcher

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * 名单名字识别。
 *
 * 流程：整屏 OCR -> 按用户框选的名单带过滤 -> 文本清洗 -> 按视觉位置排成 行/列 -> 输出
 *
 * 只识别「名单带」是准确的唯一前提：目标画面里弹幕、礼物飘屏、标题栏同样有大量文字，
 * 一刀切靠关键词过滤必然误伤，靠纵向区域过滤才干净。
 */
object MicOcr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    data class Outcome(
        val names: List<String>,
        val totalLines: Int,
        val kept: Int
    )

    private data class Item(val text: String, val box: Rect)

    private val TRIM_EDGE = charArrayOf(
        ' ', '　', '·', '•', '●', '|', '丨', '、', ',', '.', '，', '。',
        '-', '_', '~', '～', '*', '#', '^', '=', '+', '—', '…'
    )

    /**
     * 名单左边的麦克风小图标，OCR 常把它认成这些字形最像的字符
     * （圆头 + 直杆 → Q / O / o / D / 0；再加几个同形异体）。
     */
    private const val ICON_GLYPHS = "QOoDdCcGgUuPpBbq0"

    /** 图标和名字之间偶尔夹的分隔符 */
    private const val HEAD_SEP = " .·|丨-_、，,：:"

    /** 「1~2 个非汉字 + 后面至少 2 个汉字」= 疑似图标前缀 */
    private val RE_ICON_HEAD = Regex("^([^\\u4E00-\\u9FFF]{1,2})(?=[\\u4E00-\\u9FFF]{2,})")

    /** 「至少 2 个汉字 + 尾部 1 个孤立字母」= 表情被认成字母 */
    private val RE_TRAIL_LETTER = Regex("([\\u4E00-\\u9FFF]{2,})[\\s·|丨]*[A-Za-z]$")

    /** 整行就是这些词的，一律不是名字 */
    private val EXACT_BAD = setOf(
        "首页", "推荐", "关注", "热门", "在线", "公告", "设置", "更多",
        "查看全部", "说点什么", "说点什么...", "暂无内容"
    )

    /** 含有这些片段的，是系统提示 / 横幅 / 占位文案 */
    private val CONTAIN_BAD = listOf(
        "点击加入", "点击查看", "虚位以待", "加载中", "暂无内容",
        "请谨慎", "注意保护", "查看全部", "更多精彩", "正在加载"
    )

    /** 亲密度/人气值常见的「2.5万」「3.9万」「1.2w」这类，不是名字 */
    private val NUMERIC_UNIT = Regex("^[\\d.]+[万千百亿wWkK]$")

    suspend fun recognize(bitmap: Bitmap, topRatio: Float, bottomRatio: Float): Outcome {
        val screenW = bitmap.width
        val screenH = bitmap.height
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

        val topPx = (topRatio * screenH).toInt()
        val bottomPx = (bottomRatio * screenH).toInt()

        var total = 0
        val items = mutableListOf<Item>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                total++
                for (piece in explodeLine(line, screenW)) {
                    val cy = piece.box.centerY()
                    if (cy < topPx || cy > bottomPx) continue
                    val cleaned = clean(piece.text)
                    if (cleaned.isNotEmpty()) items.add(Item(cleaned, piece.box))
                }
            }
        }

        val ordered = sortVisual(items)

        // 同一帧内去重（跨帧去重由 NameStore 负责）
        val uniq = LinkedHashMap<String, String>()
        for (n in ordered) uniq.putIfAbsent(dedupKey(n), n)
        return Outcome(uniq.values.toList(), total, uniq.size)
    }

    /**
     * ML Kit 有时会把同一水平线上的多个名字并成一行。
     * 这里用元素之间的横向间隙把它们重新切开。
     */
    private fun explodeLine(line: Text.Line, screenW: Int): List<Item> {
        val box = line.boundingBox ?: return emptyList()
        var elements = line.elements.mapNotNull { e -> e.boundingBox?.let { e.text to it } }

        // 图标（话筒/等级徽章/表情）被单独认出来的那种：高度和同行文字差太多，又不含汉字
        elements = dropIconElements(elements)

        if (elements.size >= 2 && box.width() > screenW * 0.30f) {
            val tol = maxOf(screenW * 0.035f, 18f)
            val groups = mutableListOf<MutableList<Pair<String, Rect>>>()
            var prevRight = Int.MIN_VALUE

            for ((t, r) in elements.sortedBy { it.second.left }) {
                val gap = if (prevRight == Int.MIN_VALUE) 0 else r.left - prevRight
                if (groups.isEmpty() || gap > tol) groups.add(mutableListOf(t to r))
                else groups.last().add(t to r)
                prevRight = maxOf(prevRight, r.right)
            }

            if (groups.size > 1) {
                return groups.map { g ->
                    val rect = Rect(g.first().second)
                    for (i in 1 until g.size) rect.union(g[i].second)
                    Item(g.joinToString("") { it.first }, rect)
                }
            }
        }

        // 只剩一个元素：可能是「图标 + 名字」里的图标刚被滤掉
        // 这时候必须用剩下的这个元素的文本，不能回退到 line.text，否则图标又回来了
        if (elements.size == 1) {
            val (t, r) = elements[0]
            return listOf(Item(t, r))
        }
        return listOf(Item(line.text, box))
    }

    /**
     * 丢掉「看着像图标」的文本元素。
     *
     * 依据是几何形状而不是字面：同一行里正常文字高度接近，被误认的图标要么明显矮小（小话筒、徽章），
     * 要么明显高出一截（大号表情）。只在不含汉字时才丢，避免误伤正常词组。
     */
    private fun dropIconElements(
        elements: List<Pair<String, Rect>>
    ): List<Pair<String, Rect>> {
        if (elements.size < 2) return elements

        // 基准高度取「含汉字的元素」，别被图标自己的框带偏
        val hanH = elements.filter { (t, _) -> t.any { isHan(it) } }
            .map { it.second.height() }.sorted()
        val baseH = if (hanH.isNotEmpty()) {
            hanH[hanH.size / 2].toFloat()
        } else {
            elements.map { it.second.height() }.sorted()[elements.size / 2].toFloat()
        }
        if (baseH <= 4f) return elements

        val kept = elements.filter { (t, r) ->
            if (t.any { isHan(it) }) return@filter true
            val h = r.height().toFloat()
            h >= baseH * 0.6f && h <= baseH * 1.5f
        }
        // 全被过滤掉说明判断错了，保守起见保留原样
        return if (kept.isEmpty()) elements else kept
    }

    /** 按视觉行列排序：从上到下、从左到右 —— 出来的顺序跟你在屏幕上看到的顺序一致 */
    private fun sortVisual(items: List<Item>): List<String> {
        if (items.size <= 1) return items.map { it.text }

        val heights = items.map { it.box.height() }.sorted()
        val medianH = heights[heights.size / 2].coerceAtLeast(10)
        val rowTolerance = medianH * 0.65f

        val rows = mutableListOf<MutableList<Item>>()
        for (item in items.sortedBy { it.box.centerY() }) {
            val last = rows.lastOrNull()
            if (last != null) {
                val refY = last.sumOf { it.box.centerY().toDouble() } / last.size
                if (abs(item.box.centerY() - refY) <= rowTolerance) {
                    last.add(item)
                    continue
                }
            }
            rows.add(mutableListOf(item))
        }

        return rows.flatMap { row -> row.sortedBy { it.box.left }.map { it.text } }
    }

    private fun clean(raw: String): String {
        var s = raw
            .replace(Regex("[\\u200B-\\u200F\\uFEFF\\u00AD]"), " ")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()

        // ① 表情/罕见符号本身：偶尔 OCR 会把 emoji 原样吐出来
        s = sanitize(s).trim()
        if (s.isEmpty()) return ""

        // ② 首尾装饰符号
        s = s.trim(*TRIM_EDGE)

        // ③ 名字左侧的麦克风图标被认成 Q / o / D / 0 之类 → 剥掉
        s = stripIconPrefix(s).trim(*TRIM_EDGE)

        // ④ 名字被 OCR 和右侧的亲密值数字并成一行时，砍掉尾部纯数字
        s = s.replace(Regex("\\s+\\d{1,7}$"), "").trim(*TRIM_EDGE)

        // ⑤ 名字末尾的表情被认成孤立字母（月叙漫语O → 月叙漫语）
        s = stripTrailLetter(s).trim(*TRIM_EDGE)

        if (s.length < 2 || s.length > 24) return ""
        if (s.none { it.isLetter() }) return ""          // 纯数字 / 纯符号挡掉

        // 不含汉字、又被空格切成多个单字符（"Q O"、"Q · D"）—— 图标被认成的假字，凑不成名字
        if (s.none { isHan(it) }) {
            val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size >= 2 && tokens.all { it.length == 1 }) return ""
        }

        if (s in EXACT_BAD) return ""
        if (s.contains('：') || s.contains(':')) return "" // 弹幕基本都有冒号
        if (CONTAIN_BAD.any { s.contains(it) }) return ""

        // 「2.5万」「♥190」这种数值标签
        val compact = s.filter { it.isLetterOrDigit() || it == '.' }
        if (compact.isNotEmpty() && NUMERIC_UNIT.matches(compact)) return ""

        val solid = s.count { it.isLetterOrDigit() }
        if (solid * 2 < s.length) return ""               // 一半以上是装饰符号，丢

        return s
    }

    /**
     * 剥掉名字左边那个话筒图标。
     * 只在「图标候选字符 + 后面跟着至少 2 个汉字」时才动，所以 Amy / DJ小明 / 520表白 都不受影响。
     */
    private fun stripIconPrefix(s: String): String {
        val m = RE_ICON_HEAD.find(s) ?: return s
        val head = m.groupValues[1]
        // DJ / MC / AK 这类两个大写字母开头是正常名字，别砍
        if (head.length == 2 && head[0].isUpperCase() && head[1].isUpperCase()) return s
        if (!head.all { it in ICON_GLYPHS || it in HEAD_SEP }) return s
        val rest = s.substring(head.length)
        return if (rest.count { isHan(it) } >= 2) rest else s
    }

    /**
     * 剥掉名字末尾那个被认成字母的表情。
     * 必须是「≥2 个汉字 + 1 个孤立字母」——「小A」这种两字名字不会被砍成一个字，
     * 数字一律不动（「元气满满6」是真人名）。
     */
    private fun stripTrailLetter(s: String): String =
        RE_TRAIL_LETTER.find(s)?.groupValues?.get(1) ?: s

    /** 只留汉字、ASCII 可打印字符和常用中文标点，其余（emoji、®™、箭头、带圈字）全删 */
    private fun sanitize(s: String): String = buildString(s.length) {
        for (c in s) if (isPrintable(c)) append(c)
    }

    private fun isPrintable(c: Char): Boolean {
        if (isHan(c)) return true
        if (c.code in 0x20..0x7E) return true
        return c in "　、。，！？；：·—…《》【】（）「」『』〈〉“”‘’"
    }

    private fun isHan(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    private fun dedupKey(s: String): String =
        s.replace(Regex("[\\s\\u200B-\\u200F\\uFEFF]"), "").lowercase()
}
