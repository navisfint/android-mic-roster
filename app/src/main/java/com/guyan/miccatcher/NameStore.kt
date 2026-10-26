package com.guyan.miccatcher

/**
 * 名单仓库：跨多次识别累加、去重、上限控制。
 * 所有方法都在主线程调用（数据量小，无需额外加锁开销）。
 */
object NameStore {

    private val items = mutableListOf<String>()
    private val keys = mutableSetOf<String>()

    /**
     * 被用户删掉或改掉的名字（归一化后的 key）。
     * 不加这个的话，删掉一个认错的字，下一轮识别又把它加回来了。
     * 只在本次会话内有效，点「清空」会一起清掉。
     */
    private val ignored = mutableSetOf<String>()

    private val listeners = mutableListOf<(List<String>) -> Unit>()

    var limit: Int = 30
        private set

    val list: List<String> get() = items
    val size: Int get() = items.size
    val isFull: Boolean get() = items.size >= limit

    fun setLimit(v: Int) {
        limit = v.coerceIn(1, 999)
        notifyChanged()
    }

    fun observe(l: (List<String>) -> Unit) {
        listeners.add(l)
        l(items.toList())
    }

    fun unobserve(l: (List<String>) -> Unit) {
        listeners.remove(l)
    }

    data class AddResult(
        val added: Int,          // 本次新增几个
        val total: Int,          // 当前总数
        val reachedLimit: Boolean,
        val blockedByLimit: Int  // 因为达到上限被丢弃几个
    )

    /**
     * 追加候选名字。
     * @return 本次结果，调用方据此决定是否震动/弹提示
     */
    fun add(candidates: List<String>): AddResult {
        var added = 0
        var blocked = 0

        for (raw in candidates) {
            val k = keyOf(raw)
            if (k.isEmpty() || keys.contains(k) || ignored.contains(k)) continue

            if (items.size >= limit) {
                blocked++
                continue
            }
            keys.add(k)
            items.add(raw)
            added++
        }

        val reached = items.size >= limit
        if (added > 0) notifyChanged()
        return AddResult(added, items.size, reached, blocked)
    }

    fun removeAt(index: Int) {
        val name = items.getOrNull(index) ?: return
        items.removeAt(index)
        val k = keyOf(name)
        keys.remove(k)
        ignored.add(k)           // 删掉就别再自动加回来
        notifyChanged()
    }

    /**
     * 手工纠正一个名字（OCR 认错的字）。
     * 原错字样形态会进 ignored，下一轮识别到同样的错字不会再进来。
     */
    fun updateAt(index: Int, raw: String): Boolean {
        val old = items.getOrNull(index) ?: return false
        val name = raw.trim()
        if (name.isEmpty()) return false

        val oldKey = keyOf(old)
        val newKey = keyOf(name)
        if (newKey.isEmpty() || newKey == oldKey) return false
        if (keys.contains(newKey)) return false     // 跟列表里别的条目重了

        items[index] = name
        keys.remove(oldKey)
        keys.add(newKey)
        ignored.add(oldKey)
        notifyChanged()
        return true
    }

    fun clear() {
        if (items.isEmpty() && ignored.isEmpty()) return
        items.clear()
        keys.clear()
        ignored.clear()
        notifyChanged()
    }

    /**
     * 复制用文本：一行一个名字，不带序号。
     * 直接用 joinToString 保持插入顺序，粘贴到表格/文档里就是一列。
     */
    fun exportText(): String = items.joinToString("\n")

    private fun notifyChanged() {
        val snapshot = items.toList()
        listeners.forEach { it(snapshot) }
    }

    /** 去重用的归一化 key：去零宽字符、去所有空白、转小写 */
    private fun keyOf(s: String): String = s
        .replace(Regex("[\\u200B-\\u200F\\uFEFF\\u00AD]"), "")
        .replace(Regex("\\s+"), "")
        .lowercase()
        .trim()
}
