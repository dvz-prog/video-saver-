package com.videosaver.app

import android.Manifest
import com.yausername.youtubedl_android.YoutubeDL
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // ─── Views ───────────────────────────────────────────────────────
    private lateinit var etUrl: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnGo: Button
    private lateinit var btnPick: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnDefault: Button
    private lateinit var tvDir: TextView
    private lateinit var tvStatus: TextView
    private lateinit var jobsBox: LinearLayout
    private lateinit var cbPlaylist: CheckBox
    private lateinit var spMode: Spinner  // Spinner para escolher Vídeo / Áudio / Foto

    // ─── State ───────────────────────────────────────────────────────
    private var treeUri: Uri? = null

    // ─── Launchers ───────────────────────────────────────────────────
    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                treeUri = uri
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putString("treeUri", uri.toString()).apply()
                updateDirLabel()
                Toast.makeText(this, "Pasta escolhida!", Toast.LENGTH_SHORT).show()
            }
        }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val askStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // ─── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupModeSpinner()
        loadPrefs()
        setupListeners()
        checkPermissions()

        JobStore.addListener(jobListener)
        render()

        handleIntent(intent)

        // Verificar init error
        App.initError?.let {
            tvStatus.text = "Falha ao iniciar o yt-dlp: $it"
            tvStatus.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onDestroy() {
        JobStore.removeListener(jobListener)
        super.onDestroy()
    }

    // ─── Setup ───────────────────────────────────────────────────────

    private fun bindViews() {
        etUrl = findViewById(R.id.etUrl)
        btnPaste = findViewById(R.id.btnPaste)
        btnGo = findViewById(R.id.btnGo)
        btnPick = findViewById(R.id.btnPick)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnDefault = findViewById(R.id.btnDefault)
        tvDir = findViewById(R.id.tvDir)
        tvStatus = findViewById(R.id.tvStatus)
        jobsBox = findViewById(R.id.jobsBox)
        cbPlaylist = findViewById(R.id.cbPlaylist)
        spMode = findViewById(R.id.spMode)
    }

    private fun setupModeSpinner() {
        val options = arrayOf("🎬 Vídeo", "🎵 Áudio (MP3)", "🖼️ Foto/Imagem")
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val saved = prefs.getString("treeUri", null)
        if (saved != null) treeUri = Uri.parse(saved)
        updateDirLabel()
    }

    private fun setupListeners() {
        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnGo.setOnClickListener { startDownload() }
        btnPick.setOnClickListener { pickTree.launch(null) }
        btnDefault.setOnClickListener {
            treeUri = null
            getSharedPreferences("prefs", MODE_PRIVATE).edit().remove("treeUri").apply()
            updateDirLabel()
            Toast.makeText(this, "Usando pasta padrão (Downloads/Video Saver)", Toast.LENGTH_SHORT).show()
        }
        btnUpdate.setOnClickListener { updateYtDlp() }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 13+ usa READ_MEDIA_VIDEO e READ_MEDIA_IMAGES (já declaradas no manifest)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    // ─── Intent handling (compartilhamento de outros apps) ───────────

    /**
     * Recebe links ou imagens compartilhadas de qualquer app:
     * YouTube, TikTok, Instagram, Twitter/X, Facebook, etc.
     */
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: ""
                when {
                    // Link de texto (YouTube, TikTok, Instagram, etc.)
                    mimeType == "text/plain" -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                        // Extrair URL do texto (apps às vezes mandam texto + link)
                        val url = extractUrl(text)
                        if (url != null) {
                            etUrl.setText(url)
                            // Auto-detectar modo: se for Instagram/Pinterest provavelmente tem foto
                            if (url.contains("instagram.com") || url.contains("pinterest.com")) {
                                showModeDialog(url)
                            } else {
                                startDownload(url)
                            }
                        }
                    }
                    // Imagem compartilhada diretamente (já é uma foto local)
                    mimeType.startsWith("image/") -> {
                        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        if (imageUri != null) {
                            Toast.makeText(this, "Imagem recebida! Use um link para baixar de apps.", Toast.LENGTH_LONG).show()
                        }
                    }
                    // Vídeo compartilhado diretamente
                    mimeType.startsWith("video/") -> {
                        Toast.makeText(this, "Vídeo recebido via compartilhamento direto.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://\\S+")
        return regex.find(text)?.value
    }

    /**
     * Para sites como Instagram que têm tanto vídeo quanto foto,
     * pergunta ao usuário o que ele quer baixar.
     */
    private fun showModeDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("O que deseja baixar?")
            .setItems(arrayOf("🎬 Vídeo", "🖼️ Foto/Imagem", "🎵 Áudio (MP3)")) { _, which ->
                val mode = when (which) {
                    1 -> DownloadService.MODE_PHOTO
                    2 -> DownloadService.MODE_AUDIO
                    else -> DownloadService.MODE_VIDEO
                }
                startDownload(url, mode = mode)
            }
            .show()
    }

    // ─── Download ────────────────────────────────────────────────────

    private fun startDownload(
        url: String = etUrl.text.toString().trim(),
        playlist: Boolean = cbPlaylist.isChecked,
        mode: Int = spMode.selectedItemPosition
    ) {
        if (!Regex("^https?://").containsMatchIn(url)) {
            Toast.makeText(this, "Cole um link válido (começando com https://).", Toast.LENGTH_LONG).show()
            return
        }
        if (!App.ready) {
            Toast.makeText(this, App.initError ?: "yt-dlp não inicializado.", Toast.LENGTH_LONG).show()
            return
        }

        DownloadService.start(this, url, playlist, mode)
        etUrl.setText("")
        Toast.makeText(this, "Download iniciado!", Toast.LENGTH_SHORT).show()
    }

    // ─── UI ──────────────────────────────────────────────────────────

    private val jobListener: () -> Unit = { runOnUiThread { render() } }

    private fun render() {
        jobsBox.removeAllViews()
        val jobs = JobStore.entries.toList()

        tvStatus.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE

        for (job in jobs) {
            val view = LayoutInflater.from(this).inflate(R.layout.custom_dialog, jobsBox, false)

            val tvStatusJob = view.findViewById<TextView>(R.id.tvStatus)
            val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
            val btnCancel = view.findViewById<Button>(R.id.btnCancel)

            val modeLabel = when (job.mode) {
                DownloadService.MODE_AUDIO -> "[Áudio] "
                DownloadService.MODE_PHOTO -> "[Foto] "
                else -> ""
            }

            tvStatusJob.text = "$modeLabel${job.url.take(40)}…\n${job.status}"
            progressBar.progress = job.percent.toInt()
            progressBar.isIndeterminate = job.percent == 0f && job.status == "Preparando"

            btnCancel.text = if (job.cancelled || job.status == "Concluído") "Remover" else "Cancelar"
            btnCancel.setOnClickListener {
                if (!job.cancelled && job.status != "Concluído") {
                    job.cancelled = true
                    val i = Intent(this, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_CANCEL
                        putExtra(DownloadService.EXTRA_ID, job.id)
                    }
                    startService(i)
                }
                JobStore.remove(job)
                render()
            }

            jobsBox.addView(view)
        }

        if (jobs.any { it.status == "Concluído" || it.cancelled }) {
            val btnClear = Button(this).apply {
                text = "Limpar concluídos"
                setOnClickListener {
                    JobStore.clearFinished()
                    render()
                }
            }
            jobsBox.addView(btnClear)
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val url = extractUrl(text) ?: text
        etUrl.setText(url)
    }

    private fun updateDirLabel() {
        tvDir.text = if (treeUri != null) {
            "Salvar em: pasta escolhida"
        } else {
            "Salvar em: Downloads/Video Saver (padrão)"
        }
    }

    private fun updateYtDlp() {
        if (!App.ready) return
        tvStatus.text = "Atualizando o yt-dlp\n(precisa de internet)"
        tvStatus.visibility = View.VISIBLE
        Thread {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(this)
                runOnUiThread {
                    tvStatus.text = "yt-dlp atualizado: $status"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Falha ao atualizar: ${e.message}"
                }
            }
        }.start()
    }
}
