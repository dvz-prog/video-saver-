package com.videosaver.app

import java.io.File

data class Job(
    val id: Int,
    val url: String,
    val playlist: Boolean = false,
    val audioOnly: Boolean = false,
    var status: String = "Preparando",
    var percent: Float = 0f,
    var file: File? = null,
    var cancelled: Boolean = false,
    var mode: Int = 0  // 0 = vídeo, 1 = áudio, 2 = foto/imagem
)
