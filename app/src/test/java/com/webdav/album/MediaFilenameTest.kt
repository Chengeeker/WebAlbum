package com.webdav.album

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFilenameTest {
    @Test fun fullNamesRemainUnchanged() {
        assertEquals("Screenshot_20261116_174500.png", displayMediaFilename("Screenshot_20261116_174500.png", false))
        assertEquals("微信视频 01.mp4", displayMediaFilename("微信视频 01.mp4", false))
    }
    @Test fun screenshotDatesRemainReadable() {
        assertEquals("20261116_174500", displayMediaFilename("Screenshot_20261116_174500.png", true))
        assertEquals("2026-11-16_174500", displayMediaFilename("ScreenShot-2026-11-16_174500.jpg", true))
        assertEquals("20261116-140000", displayMediaFilename("屏幕截图_20261116-140000.heic", true))
        assertEquals("202611161700", displayMediaFilename("截屏202611161700.webp", true))
    }
    @Test fun wechatPrefixesRetainDistinguishingNumbers() {
        assertEquals("1731111111111", displayMediaFilename("mmexport1731111111111.jpg", true))
        assertEquals("20261116174911", displayMediaFilename("MMP_20261116174911.png", true))
        assertEquals("202611161700", displayMediaFilename("MMPabcdef123456_202611161700.jpg", true))
    }
    @Test fun unfamiliarNamesAreNotDamaged() {
        assertEquals("ScreenshotHoliday", displayMediaFilename("ScreenshotHoliday.jpg", true))
        assertEquals("MMProject", displayMediaFilename("MMProject.mp4", true))
        assertEquals("MMP-short", displayMediaFilename("MMP-short.mp4", true))
        assertEquals("ScreenShot", displayMediaFilename("ScreenShot.png", true))
    }
    @Test fun cameraPrefixesStripOnlyWhenDateLikeContentFollows() {
        assertEquals("20261116_183012", displayMediaFilename("IMG_20261116_183012.jpg", true))
        assertEquals("20261116_182501", displayMediaFilename("VID_20261116_182501.mp4", true))
        assertEquals("20261116_171233", displayMediaFilename("PXL_20261116_171233.jpg", true))
        assertEquals("2026-11-16_1401", displayMediaFilename("DSC-2026-11-16_1401.heic", true))
        assertEquals("20261116_185000", displayMediaFilename("MVIMG20261116_185000.jpg", true))
        assertEquals("IMG_0001", displayMediaFilename("IMG_0001.jpg", true))
        assertEquals("IMG_Trip", displayMediaFilename("IMG_Trip.png", true))
        assertEquals("PXL-Mountain", displayMediaFilename("PXL-Mountain.png", true))
    }

    @Test fun extensionsAreOnlyHiddenForSupportedMedia() {
        assertEquals("2026_11_16", displayMediaFilename("IMG_2026_11_16.mp4", true))
        assertEquals("备份.tar", displayMediaFilename("备份.tar", true))
        assertEquals(".hidden", displayMediaFilename(".hidden", true))
        assertEquals("", displayMediaFilename("", true))
    }
}
