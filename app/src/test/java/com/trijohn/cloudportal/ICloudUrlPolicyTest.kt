package com.trijohn.cloudportal

import org.junit.Assert.assertFalse
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
}
