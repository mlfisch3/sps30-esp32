package com.sensirion.sps30.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.sensirion.sps30.config.Exports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker for "Send sessions back to the sensor" opens on this app's own
 * archives.
 *
 * It used to open on whatever the system picker was last left on, which is the
 * device's general Downloads folder: hundreds of unrelated files as the first
 * screen of the feature, none of them the one being looked for. On the demo
 * tablet that folder holds the captain's personal media, so it is also the first
 * thing an audience would have seen.
 *
 * Every archive this app writes goes to one folder, so that is where it opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPickerTest {

    private fun initialUri(): Uri? {
        val intent = OpenArchive().createIntent(
            ApplicationProvider.getApplicationContext(),
            arrayOf("application/zip"),
        )
        return IntentCompat.getParcelableExtra(
            intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java,
        )
    }

    @Test fun itOpensOnTheFolderTheArchivesAreWrittenTo() {
        val uri = initialUri()
        assertNotNull("the picker opens wherever it was last left", uri)
        assertEquals(Exports.EXTERNAL_STORAGE_AUTHORITY, uri!!.authority)
        assertTrue(
            "it does not point at Downloads/SPS30: $uri",
            Uri.decode(uri.toString()).endsWith("primary:Download/SPS30"),
        )
    }

    /**
     * And it points at the folder the writers actually use, rather than at a
     * path spelled out a second time - which is how the two drift apart.
     */
    @Test fun thatFolderIsTheOneTheAppExportsInto() {
        assertEquals("primary:Download/${Exports.SUBDIR}", Exports.EXPORT_FOLDER_DOCUMENT_ID)
        assertTrue(
            "the archives go to Downloads/${Exports.SUBDIR} and the picker opens elsewhere",
            Uri.decode(initialUri().toString()).endsWith(Exports.EXPORT_FOLDER_DOCUMENT_ID),
        )
    }
}
