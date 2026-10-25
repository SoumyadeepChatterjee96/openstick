package org.openstick.bridge

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.openstick.engine.LinuxEnvironment
import java.io.File
import java.io.FileNotFoundException

class OpenStickFilesProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "openstick-home"
        private const val DOC_ROOT = "@home"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_FLAGS
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    private val env by lazy { LinuxEnvironment(context!!) }
    private val homeDir: File get() = env.getHomeDir("user")

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DOC_ROOT)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "OpenStick Linux")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Linux user home directory")
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.sym_def_app_icon)
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = fileFor(documentId)
        addFileRow(result, file, documentId)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileFor(parentDocumentId)
        val files = parent.listFiles() ?: emptyArray()

        for (file in files) {
            val childDocId = if (parentDocumentId == DOC_ROOT) {
                file.name
            } else {
                "$parentDocumentId/${file.name}"
            }
            addFileRow(result, file, childDocId)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileFor(parentDocumentId)
        val newFile = File(parent, displayName)

        if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
            if (!newFile.mkdirs()) throw FileNotFoundException("Could not create directory")
        } else {
            if (!newFile.createNewFile()) throw FileNotFoundException("Could not create file")
        }

        return if (parentDocumentId == DOC_ROOT) displayName else "$parentDocumentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Could not delete $documentId")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    private fun fileFor(documentId: String): File {
        return if (documentId == DOC_ROOT) {
            homeDir
        } else {
            File(homeDir, documentId)
        }
    }

    private fun addFileRow(cursor: MatrixCursor, file: File, docId: String) {
        var flags = 0
        if (file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }

        val mimeType = if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            val extension = file.extension
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { "OpenStick" })
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
    }
}
