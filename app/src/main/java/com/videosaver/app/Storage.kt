package com.videosaver.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

object Storage {

    // Pasta padrão no Downloads
    private const val FOLDER_NAME = "Video Saver"

    /**
     * Salva qualquer arquivo (vídeo, foto, áudio) no MediaStore ou pasta escolhida.
     * Retorna true se bem-sucedido.
     */
    fun save(context: Context, src: File, treeUri: Uri?): Boolean {
        val ext = src.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: guessType(ext)

        return if (treeUri != null) {
            saveToTree(context, src, treeUri, mimeType)
        } else {
            saveToMediaStore(context, src, mimeType, ext)
        }
    }

    private fun guessType(ext: String): String {
        return when {
            ext in listOf("mp4", "mkv", "webm", "mov", "avi", "flv", "ts") -> "video/mp4"
            ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav") -> "audio/mpeg"
            ext in listOf("jpg", "jpeg") -> "image/jpeg"
            ext == "png" -> "image/png"
            ext == "gif" -> "image/gif"
            ext == "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun isImage(mimeType: String) = mimeType.startsWith("image/")
    private fun isAudio(mimeType: String) = mimeType.startsWith("audio/")

    /**
     * Salva via MediaStore (método principal – funciona sem permissão de armazenamento no Android 10+)
     */
    private fun saveToMediaStore(
        context: Context, src: File, mimeType: String, ext: String
    ): Boolean {
        val resolver = context.contentResolver

        val (collection, relativePath) = when {
            isImage(mimeType) -> Pair(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME"
            )
            isAudio(mimeType) -> Pair(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME"
            )
            else -> Pair(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME"
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri: Uri = resolver.insert(collection, values) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return false
        }
    }

    /**
     * Salva na pasta escolhida pelo usuário via SAF (Storage Access Framework)
     */
    private fun saveToTree(
        context: Context, src: File, treeUri: Uri, mimeType: String
    ): Boolean {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val dest = tree.createFile(mimeType, src.nameWithoutExtension) ?: return false
        return try {
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                FileInputStream(src).use { input -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            dest.delete()
            false
        }
    }
}
