package com.videosaver.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_ID = "EXTRA_ID"
        const val EXTRA_PLAYLIST = "EXTRA_PLAYLIST"
        const val EXTRA_MODE = "EXTRA_MODE"  // 0=vídeo, 1=áudio, 2=foto

        const val NOTIF_ID = 1
        private const val CH_PROGRESS = "ch_progress"
        private const val CH_DONE = "ch_done"

        // Modos de download
        const val MODE_VIDEO = 0
        const val MODE_AUDIO = 1
        const val MODE_PHOTO = 2

        fun start(context: Context, url: String, playlist: Boolean, mode: Int = MODE_VIDEO) {
            val i = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_PLAYLIST, playlist)
                putExtra(EXTRA_MODE, mode)
            }
            context.startForegroundService(i)
        }
    }

    private val executor = Executors.newFixedThreadPool(3)
    private var treeUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIF_ID, buildProgressNotif("Aguardando downloads...", 0, false).build())

        // Carregar pasta salva pelo usuário
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedUri = prefs.getString("treeUri", null)
        if (savedUri != null) treeUri = Uri.parse(savedUri)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val playlist = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
                val mode = intent.getIntExtra(EXTRA_MODE, MODE_VIDEO)
                val job = JobStore.add(url, playlist, mode)
                executor.submit { runJob(job, startId) }
            }
            ACTION_CANCEL -> {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                JobStore.entries.find { it.id == id }?.cancelled = true
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(job: Job, startId: Int) {
        val cacheDir = File(cacheDir, "dl_${job.id}")
        cacheDir.mkdirs()

        try {
            job.status = "Preparando"
            notifyProgress()

            val request = buildRequest(job, cacheDir)

            YoutubeDL.getInstance().execute(request) { percent, _, line ->
                if (job.cancelled) {
                    YoutubeDL.getInstance().destroyProcessById(job.id.toString())
                    return@execute
                }
                job.percent = percent
                val faltam = estimateSeconds(line)
                job.status = if (faltam > 0) "Baixando (faltam ${faltam}s)" else "Baixando"
                notifyProgress()
            }

            if (job.cancelled) {
                job.status = "Cancelado"
                notifyProgress()
                return
            }

            // Pegar todos os arquivos gerados (vídeos, áudios, imagens)
            val files = cacheDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            if (files.isEmpty()) {
                job.status = "Erro: Nenhum arquivo gerado."
                notifyProgress()
                return
            }

            job.status = "Salvando o arquivo"
            notifyProgress()

            var allOk = true
            for (f in files) {
                val ok = Storage.save(this, f, treeUri)
                if (!ok) allOk = false
            }
            job.file = files.last()
            job.status = if (allOk) "Concluído" else cleanError(
                if (treeUri != null) "Não foi possível criar o arquivo na pasta escolhida"
                else "Não foi possível criar o arquivo em Downloads"
            )

            notifyDone(job)
        } catch (e: Exception) {
            if (!job.cancelled) {
                job.status = cleanError(e.message ?: "Erro desconhecido")
            }
            notifyProgress()
        } finally {
            cacheDir.deleteRecursively()
            stopSelfClean(startId)
        }
    }

    /**
     * Constrói o YoutubeDLRequest de acordo com o modo selecionado:
     * MODE_VIDEO → baixa o melhor vídeo+áudio
     * MODE_AUDIO → extrai somente o áudio (mp3/m4a)
     * MODE_PHOTO → baixa a melhor imagem/thumbnail disponível
     */
    private fun buildRequest(job: Job, cacheDir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(job.url)

        // Configurações gerais
        request.addOption("--cache-dir", cacheDir.absolutePath)
        request.addOption("--windows-filenames")
        request.addOption("--concurrent-fragments", "4")

        when (job.mode) {
            MODE_AUDIO -> {
                // Baixar apenas áudio e converter para mp3/m4a
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
                request.addOption("-o", "${cacheDir.absolutePath}/%(title).150B [%(id)s].%(ext)s")
            }
            MODE_PHOTO -> {
                // Baixar thumbnail/imagem do vídeo ou post
                request.addOption("--write-thumbnail")
                request.addOption("--skip-download")
                request.addOption("--convert-thumbnails", "jpg")
                request.addOption("-o", "${cacheDir.absolutePath}/%(title).150B [%(id)s].%(ext)s")
            }
            else -> {
                // MODE_VIDEO: baixar melhor qualidade de vídeo+áudio
                if (job.playlist) {
                    request.addOption("--yes-playlist")
                } else {
                    request.addOption("--no-playlist")
                }
                request.addOption(
                    "-f",
                    "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b[height<=1080]/best"
                )
                request.addOption("--merge-output-format", "mp4")
                request.addOption("-o", "${cacheDir.absolutePath}/%(title).150B [%(id)s].%(ext)s")
            }
        }

        return request
    }

    private fun estimateSeconds(line: String): Int {
        val match = Regex("(\\d+)s").find(line) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private fun cleanError(msg: String?): String {
        return msg?.lines()?.lastOrNull { it.isNotBlank() }?.take(120) ?: "Erro desconhecido"
    }

    private fun stopSelfClean(startId: Int) {
        val running = JobStore.entries.any { !it.cancelled && it.status !in listOf("Concluído", "Cancelado") && !it.status.startsWith("Erro") }
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    // ─── Notificações ────────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Downloads concluídos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildProgressNotif(text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Video Saver")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())

    private fun notifyProgress() {
        val active = JobStore.entries.filter { !it.cancelled && it.status != "Concluído" }
        val text = when {
            active.isEmpty() -> "Downloads concluídos"
            active.size == 1 -> active[0].status
            else -> "${active.size} downloads em andamento"
        }
        val percent = active.map { it.percent }.average().toInt().coerceIn(0, 100)
        val indeterminate = active.any { it.percent == 0f }
        startForeground(NOTIF_ID, buildProgressNotif(text, percent, indeterminate).build())
        JobStore.entries.forEach { }   // dispara listeners via JVM (já notificados no setter)
    }

    private fun notifyDone(job: Job) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (job.status == "Concluído") "Download concluído" else "Erro no download")
            .setContentText(job.status)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(job.id + 100, n)
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
