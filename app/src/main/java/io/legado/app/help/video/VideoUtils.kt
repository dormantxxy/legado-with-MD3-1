package io.legado.app.help.video

import android.util.Log

/**
 * 视频内容识别：书源章节内容直接给出视频地址时，按视频处理而不是电子书文本
 */
object VideoUtils {

    /** 常见视频直链 / 流媒体格式 */
    private val videoUrlRegex = Regex(
        pattern = """https?://[^\s"'<>]+?\.(?:mp4|m3u8|mkv|avi|mov|webm|flv|ts)(?:\?[^\s"'<>]*)?""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /** <video> 标签的 src 属性 */
    private val videoTagRegex = Regex(
        pattern = """<video[^>]+src\s*=\s*["']([^"']+)["']""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * 从章节内容中提取视频地址，无视频返回 null
     */
    fun extractVideoUrl(text: String): String? {
        if (text.length > 100_000) {
            return null
        }
        videoTagRegex.find(text)?.let { return it.groupValues[1] }
        return videoUrlRegex.find(text)?.value
    }

    /** 是否疑似视频内容页 */
    fun isVideoPage(text: String): Boolean = extractVideoUrl(text) != null
}
