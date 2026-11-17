package com.webdav.album

/**
 * A UI visibility preference, not an audio-playback policy:
 * dismissing the mini-player never pauses or tears down MediaSession.
 */
enum class MiniPlayerRecovery(val key: String, val label: String, val description: String) {
    MANUAL("manual", "手动恢复（默认）", "下滑收起后保持隐藏；可从相册顶栏恢复"),
    TRACK("track", "切换歌曲时恢复", "自动播放下一首或切换歌曲时重新显示迷你播放器"),
    RESUME("resume", "切歌或恢复播放时显示", "切换歌曲，或暂停后再次播放时重新显示迷你播放器");

    companion object {
        fun from(key: String?): MiniPlayerRecovery =
            entries.firstOrNull { it.key == key } ?: MANUAL
    }
}

internal fun shouldRestoreMini(
    setting: MiniPlayerRecovery, isDismissed: Boolean,
    changedTrack: Boolean, resumedPlayback: Boolean,
): Boolean = isDismissed && when (setting) {
    MiniPlayerRecovery.MANUAL -> false
    MiniPlayerRecovery.TRACK -> changedTrack
    MiniPlayerRecovery.RESUME -> changedTrack || resumedPlayback
}
