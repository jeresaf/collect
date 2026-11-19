package org.odk.collect.android.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.odk.collect.android.BuildConfig

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrandedContentProviderContractsTest {

    @Test
    fun `forms authority follows the configured Android application ID`() {
        assertEquals(
            BuildConfig.APPLICATION_ID + ".provider.odk.forms",
            FormsContract.getUri("project").authority
        )
    }

    @Test
    fun `instances authority follows the configured Android application ID`() {
        assertEquals(
            BuildConfig.APPLICATION_ID + ".provider.odk.instances",
            InstancesContract.getUri("project").authority
        )
    }

    @Test
    fun `custom app cannot unintentionally replace official ODK application`() {
        if (BuildConfig.CUSTOM_DISTRIBUTION) {
            assertNotEquals("org.odk.collect.android", BuildConfig.APPLICATION_ID)
        }
    }
}
