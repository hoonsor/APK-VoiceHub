package tw.hoonsor.voicehub.voice

/** 音量鍵手勢的按法；steps 是每一下的方向（+1＝加、-1＝減） */
enum class VolumePattern(val label: String, val steps: List<Int>) {
    UP_DOWN_UP("加、減、加", listOf(1, -1, 1)),
    DOWN_UP_DOWN("減、加、減", listOf(-1, 1, -1)),
    UP3("連按加 3 下", listOf(1, 1, 1)),
    DOWN3("連按減 3 下", listOf(-1, -1, -1)),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: UP_DOWN_UP
    }
}

/** 3 下要多快按完：快＝手機側鍵；慢＝藍牙耳機（ColorOS 會延遲 1～3 秒才把耳機的音量變化送到） */
enum class VolumeSpeed(val label: String, val gapMs: Long, val totalMs: Long) {
    FAST("快（2 秒內）", 900, 2000),
    SLOW("慢（5 秒內，藍牙耳機用）", 2500, 5000),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: FAST
    }
}

/**
 * 純邏輯：依音量變化事件判斷有沒有按出指定手勢（不碰 Android，JVM 測試直接跑）。
 * 每一下之間不能超過 [gapMs]，整組不能超過 [totalMs]；比對成功就清空，避免第 4 下又觸發。
 */
class VolumeGestureDetector(
    private val pattern: VolumePattern,
    private val gapMs: Long = 900,
    private val totalMs: Long = 2000,
) {
    constructor(pattern: VolumePattern, speed: VolumeSpeed) : this(pattern, speed.gapMs, speed.totalMs)

    data class Step(val dir: Int, val at: Long, val before: Int)

    private val recent = ArrayDeque<Step>()
    /** 上一次看到的音量；用來補回被系統合併掉的變化 */
    private var lastKnown: Int? = null

    /** 回傳成功時這組手勢第一下之前的音量（用來還原），沒成功回 null */
    fun feed(before: Int, after: Int, at: Long): Int? {
        val dir = Integer.signum(after - before)
        if (dir == 0) return null
        recent.lastOrNull()?.let { if (at - it.at > gapMs) recent.clear() }
        recent.addLast(Step(dir, at, before))
        while (recent.size > pattern.steps.size) recent.removeFirst()
        while (recent.isNotEmpty() && at - recent.first().at > totalMs) recent.removeFirst()
        if (recent.map { it.dir } != pattern.steps) return null
        val original = recent.first().before
        recent.clear()
        return original
    }

    /**
     * 給系統音量廣播用。廣播在 APP 忙或被凍結時只會留最新一筆，前面的變化會被蓋掉；
     * 這一筆的「變化前音量」跟上次看到的不同，就表示中間漏了一下，先補回那一下再算這一下。
     */
    fun feedObserved(before: Int, after: Int, at: Long): Int? {
        val last = lastKnown
        if (last != null && last != before) {
            lastKnown = after
            feed(last, before, at - 1)?.let { return it }
        }
        lastKnown = after
        return feed(before, after, at)
    }

    /** 自己調整音量（還原）後同步，避免被當成漏掉的一下 */
    fun sync(volume: Int) {
        lastKnown = volume
    }

    fun reset() = recent.clear()
}
