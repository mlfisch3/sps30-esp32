package com.sensirion.sps30.config

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves exports to shared, user-visible storage via MediaStore (Android 10+):
 * PNGs into **Pictures/SPS30** (so they appear in the gallery) and CSVs into
 * **Downloads/SPS30** (visible in Files / Downloads). No storage permission is
 * needed - an app may always insert its own items. On Android 9 and older it
 * falls back to the app's external files dir. Returns a short, user-facing
 * location string for the confirmation toast.
 */
object Exports {
    internal const val SUBDIR = "SPS30"

    /**
     * The folder this app's archives are written to, as a Storage Access
     * Framework document id, for opening a file picker on it.
     *
     * Derived from the same two pieces every writer above uses, so the picker
     * cannot drift away from the folder the files are actually in. `primary:` is
     * the external-storage provider's name for the device's own shared storage,
     * and the rest is the relative path - the identical `Download/SPS30` that
     * goes into `RELATIVE_PATH` above.
     */
    internal val EXPORT_FOLDER_DOCUMENT_ID: String
        get() = "primary:${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR"

    /** The provider that owns [EXPORT_FOLDER_DOCUMENT_ID]. */
    internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** A file name for an archive this app is about to write. Derived here. */
    fun archiveName(kind: String): String = "sps30-$kind-${stamp()}.zip"

    /**
     * The characters a name written by this object may contain.
     *
     * Deliberately a short allow-list rather than a list of things to strip: a
     * blocklist is a guess about what is dangerous, and this is the last point
     * before a string becomes a path on the user's device.
     */
    private val SAFE_NAME = Regex("""^[A-Za-z0-9._-]{1,120}$""")

    /**
     * [name] if it is a plain file name, else [fallback].
     *
     * Nothing that reaches here should ever have come off the network - the
     * callers derive their own names, which is the actual protection - but this
     * is where a name stops being a string and becomes a file, so it is checked
     * here too. On API 29+ MediaStore sanitises `DISPLAY_NAME`; [legacy] does
     * `File(dir, name)` with no sanitisation at all, and `minSdk` is 26, so the
     * traversal is reachable on Android 8 and on any device where the
     * MediaStore insert fails.
     */
    internal fun safeName(name: String, fallback: String): String =
        if (SAFE_NAME.matches(name) && name != "." && name != "..") name else fallback

    fun writeCsv(context: Context, csv: String): String {
        val name = "sps30-${stamp()}.csv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                return "Downloads/$SUBDIR/$name"
            }
        }
        return legacy(context, name) { it.writeText(csv) }
    }

    fun writePng(context: Context, bitmap: Bitmap): String {
        val name = "sps30-${stamp()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBDIR")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)
                    ?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return "Pictures/$SUBDIR/$name"
            }
        }
        return legacy(context, name) { it.outputStream().use { os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os) } }
    }

    /**
     * Copy a session archive received from the sensor into Downloads/SPS30.
     *
     * Streamed from a temp file rather than held in memory: a long-running
     * sensor can hold hundreds of megabytes of sessions, and materialising that
     * as one byte array to hand to the writer would be an avoidable way to run
     * a phone out of heap.
     *
     * [name] must be derived by this app - see [archiveName]. It is checked
     * again here regardless, because this is the call that turns a string into
     * a file in the user's Downloads folder.
     */
    fun writeArchive(context: Context, source: File, requestedName: String): String {
        val name = safeName(requestedName, archiveName("archive"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
                return "Downloads/$SUBDIR/$name"
            }
        }
        return legacy(context, name) { dest -> source.inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } } }
    }

    /** Android 9 and older, or if the MediaStore insert fails: app files dir. */
    private fun legacy(context: Context, name: String, write: (File) -> Unit): String {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, name)
        write(file)
        return file.absolutePath
    }
}
