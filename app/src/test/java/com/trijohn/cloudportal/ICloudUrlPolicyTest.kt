package com.trijohn.cloudportal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ICloudUrlPolicyTest {
    @Test
    fun `allows official iCloud HTTPS destinations`() {
        assertTrue(ICloudUrlPolicy.isAllowed("https://www.icloud.com/photos/"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://icloud.com/"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://idmsa.apple.com/appleauth/auth/signin"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://p123-photosws.icloud.com/"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://cvws.icloud-content.com/file"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://download.apple-livephotoskit.com/video"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://content.apzones.com/file"))
        assertTrue(ICloudUrlPolicy.isAllowed("https://www.icloud.com.cn/photos/"))
    }

    @Test
    fun `rejects insecure and lookalike destinations`() {
        assertFalse(ICloudUrlPolicy.isAllowed("http://www.icloud.com/photos/"))
        assertFalse(ICloudUrlPolicy.isAllowed("https://icloud.com.example.org/photos/"))
        assertFalse(ICloudUrlPolicy.isAllowed("https://notapple.com/"))
        assertFalse(ICloudUrlPolicy.isAllowed("javascript://icloud.com/alert(1)"))
        assertFalse(ICloudUrlPolicy.isAllowed("https://evil.example/?next=icloud.com"))
        assertFalse(ICloudUrlPolicy.isAllowed("https://user@icloud.com/"))
        assertFalse(ICloudUrlPolicy.isAllowed("not a url"))
    }

    @Test
    fun `describes blocked source without exposing its path or query`() {
        assertEquals("media.example.org", ICloudUrlPolicy.sourceLabel("https://media.example.org/private?id=123"))
        assertEquals("blob (dữ liệu tạm của iCloud)", ICloudUrlPolicy.sourceLabel("blob:https://www.icloud.com/id"))
    }

    @Test
    fun `allows blob downloads only from the top level icloud origins`() {
        assertTrue(ICloudUrlPolicy.isTrustedBlob("blob:https://www.icloud.com/123"))
        assertTrue(ICloudUrlPolicy.isTrustedBlob("blob:https://www.icloud.com.cn/123"))

        assertFalse(ICloudUrlPolicy.isTrustedBlob("blob:http://www.icloud.com/123"))
        assertFalse(ICloudUrlPolicy.isTrustedBlob("blob:https://photos.icloud.com/123"))
        assertFalse(ICloudUrlPolicy.isTrustedBlob("blob:https://www.icloud.com.evil.example/123"))
        assertFalse(ICloudUrlPolicy.isTrustedBlob("https://www.icloud.com/123"))
    }
}
