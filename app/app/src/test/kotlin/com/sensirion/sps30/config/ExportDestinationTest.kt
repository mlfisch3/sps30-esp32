package com.sensirion.sps30.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where an export actually lands.
 *
 * This is not a documentation test dressed up as a unit test. The privacy policy
 * and the Play Data Safety declaration are both drafted from what the README
 * says about export destinations, and the README said the app's own private
 * files dir - `Android/data/.../files/exports/` - while the code has written to
 * **shared** Downloads and Pictures through MediaStore since it gained
 * MediaStore support. A wrong answer there is a false statement in a filing, not
 * a docs nit, so the truthful answer is pinned here where it cannot drift back.
 *
 * The distinction that matters to a reader of the policy: a file in shared
 * storage is visible to other apps and **survives uninstall**; a file in the
 * app's own external files dir does not. They are different promises.
 *
 * Run at **API 35**, which is the highest Robolectric will build a sandbox for
 * under this project's JDK 17 - a 36 sandbox needs Java 21 and fails outright.
 * 35 is where scoped storage already applies in full, so the inserts exercised
 * here are the ones a 36 device makes; what 35 cannot show is anything Android
 * 16 changed on top, and nothing it changed touches these two collections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportDestinationTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun aCsvGoesToSharedDownloads() {
        val provider = install()
        val shown = Exports.writeCsv(app, "timestamp,pm2_5\n1,2\n")

        assertEquals(
            "a CSV is inserted into the shared Downloads collection",
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            provider.insertedInto,
        )
        assertEquals(
            "and into the SPS30 folder inside it",
            "${Environment.DIRECTORY_DOWNLOADS}/${Exports.SUBDIR}",
            provider.values?.getAsString(MediaStore.Downloads.RELATIVE_PATH),
        )
        assertEquals("text/csv", provider.values?.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertTrue(
            "the toast names the shared folder, not the app's private one: $shown",
            shown.startsWith("Downloads/${Exports.SUBDIR}/") && shown.endsWith(".csv"),
        )
        assertEquals("timestamp,pm2_5\n1,2\n", provider.written())
    }

    @Test
    fun aPngGoesToSharedPictures() {
        val provider = install()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val shown = Exports.writePng(app, bitmap)

        assertEquals(
            "a chart image is inserted into the shared Images collection so it reaches " +
                "the gallery",
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            provider.insertedInto,
        )
        assertEquals(
            "${Environment.DIRECTORY_PICTURES}/${Exports.SUBDIR}",
            provider.values?.getAsString(MediaStore.Images.Media.RELATIVE_PATH),
        )
        assertEquals("image/png", provider.values?.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertTrue(
            "the toast names Pictures, not the app's private files dir: $shown",
            shown.startsWith("Pictures/${Exports.SUBDIR}/") && shown.endsWith(".png"),
        )
    }

    @Test
    fun anArchiveGoesToSharedDownloads() {
        val provider = install()
        val source = File.createTempFile("sps30-archive", ".zip").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            deleteOnExit()
        }
        val shown = Exports.writeArchive(app, source, "sps30-backup-20260806-120000.zip")

        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.insertedInto)
        assertEquals(
            "${Environment.DIRECTORY_DOWNLOADS}/${Exports.SUBDIR}",
            provider.values?.getAsString(MediaStore.Downloads.RELATIVE_PATH),
        )
        assertEquals(
            "Downloads/${Exports.SUBDIR}/sps30-backup-20260806-120000.zip",
            shown,
        )
    }

    private fun install(): RecordingMediaProvider =
        Robolectric.buildContentProvider(RecordingMediaProvider::class.java)
            .create(MediaStore.AUTHORITY)
            .get()

    /**
     * Stands in for the platform's media provider: records the collection and
     * the [ContentValues] an insert was made with, and hands back a real file to
     * write into so the bytes can be read afterwards.
     *
     * Robolectric has no working MediaStore, so without this every insert
     * returns null and [Exports] silently takes its legacy branch - which would
     * make these tests pass against exactly the behaviour they exist to rule
     * out.
     */
    class RecordingMediaProvider : ContentProvider() {
        var insertedInto: Uri? = null
        var values: ContentValues? = null
        private var sink: File? = null

        fun written(): String = sink?.readText() ?: ""

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertedInto = uri
            this.values = values
            sink = File.createTempFile("sps30-export", ".bin").apply { deleteOnExit() }
            return uri.buildUpon().appendPath("1").build()
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                sink ?: error("openFile before insert"),
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = values?.getAsString(MediaStore.MediaColumns.MIME_TYPE)

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
