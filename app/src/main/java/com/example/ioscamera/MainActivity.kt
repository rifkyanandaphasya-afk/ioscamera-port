package com.example.ioscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.example.ioscamera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    enum class Mode { VIDEO, PHOTO, PORTRAIT, SQUARE }

    private lateinit var b: ActivityMainBinding
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var mode = Mode.PHOTO
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var timerSec = 0
    private var lastUri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it[Manifest.permission.CAMERA] == true) startCamera() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupUi()
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
            ef.addListener({ extensions = ef.get(); bind() }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()
        var selector = CameraSelector.Builder().requireLensFacing(lens).build()
        if (mode == Mode.PORTRAIT) {
            val em = extensions
            if (em != null && em.isExtensionAvailable(selector, ExtensionMode.BOKEH)) {
                selector = em.getExtensionEnabledCameraSelector(selector, ExtensionMode.BOKEH)
            } else toast("Mode potret (bokeh) tidak didukung perangkat ini")
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.viewFinder.surfaceProvider) }
        val group = UseCaseGroup.Builder().addUseCase(preview)
        imageCapture = null; videoCapture = null
        if (mode == Mode.VIDEO) {
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            ).build()
            videoCapture = VideoCapture.withOutput(rec)
            group.addUseCase(videoCapture!!)
        } else {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode).build()
            group.addUseCase(imageCapture!!)
        }
        if (mode == Mode.SQUARE) {
            group.setViewPort(ViewPort.Builder(Rational(1, 1), b.viewFinder.display.rotation).build())
        }
        try {
            camera = p.bindToLifecycle(this, selector, group.build())
        } catch (e: Exception) {
            toast("Gagal membuka kamera: ${e.message}"); return
        }
        camera?.cameraInfo?.zoomState?.observe(this) { updateZoomButtons(it) }
        torchOn = false
        updateModeUi()
    }

    private fun setupUi() {
        b.mVideo.setOnClickListener { setMode(Mode.VIDEO) }
        b.mPhoto.setOnClickListener { setMode(Mode.PHOTO) }
        b.mPortrait.setOnClickListener { setMode(Mode.PORTRAIT) }
        b.mSquare.setOnClickListener { setMode(Mode.SQUARE) }
        b.btnFlip.setOnClickListener {
            if (recording != null) return@setOnClickListener
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        b.btnFlash.setOnClickListener {
            if (mode == Mode.VIDEO) {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                b.btnFlash.setTextColor(if (torchOn) Color.YELLOW else Color.WHITE)
            } else {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = flashMode
                b.btnFlash.setTextColor(if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.WHITE else Color.YELLOW)
                toast(when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> "Flash: Otomatis"
                    ImageCapture.FLASH_MODE_ON -> "Flash: Hidup"
                    else -> "Flash: Mati"
                })
            }
        }
        b.btnTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            b.btnTimer.setTextColor(if (timerSec == 0) Color.WHITE else Color.YELLOW)
            toast(if (timerSec == 0) "Timer mati" else "Timer ${timerSec} detik")
        }
        b.btnGrid.setOnClickListener {
            val show = b.gridView.visibility != View.VISIBLE
            b.gridView.visibility = if (show) View.VISIBLE else View.GONE
            b.btnGrid.setTextColor(if (show) Color.YELLOW else Color.WHITE)
        }
        b.z05.setOnClickListener { setZoom(0.5f) }
        b.z1.setOnClickListener { setZoom(1f) }
        b.z2.setOnClickListener { setZoom(2f) }
        b.btnGallery.clipToOutline = true
        b.btnGallery.setOnClickListener {
            lastUri?.let {
                val type = if (it.toString().contains("video")) "video/*" else "image/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        b.shutterHit.setOnClickListener { onShutter() }
        setupTouch()
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value ?: return true
                camera?.cameraControl?.setZoomRatio(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        b.viewFinder.setOnTouchListener { v, e ->
            scale.onTouchEvent(e); tap.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val point = b.viewFinder.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        val r = b.focusRing
        r.x = b.viewFinder.left + x - r.width / 2f
        r.y = b.viewFinder.top + y - r.height / 2f
        r.visibility = View.VISIBLE; r.alpha = 1f; r.scaleX = 1.4f; r.scaleY = 1.4f
        r.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            r.animate().alpha(0f).setStartDelay(800).setDuration(300).withEndAction { r.visibility = View.GONE }
        }
    }

    private fun setZoom(ratio: Float) {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    private fun updateZoomButtons(z: ZoomState) {
        b.z05.visibility = if (z.minZoomRatio <= 0.55f) View.VISIBLE else View.GONE
        b.z2.visibility = if (z.maxZoomRatio >= 2f) View.VISIBLE else View.GONE
        val r = z.zoomRatio
        b.z05.setTextColor(if (r < 0.9f) Color.YELLOW else Color.WHITE)
        b.z1.setTextColor(if (r in 0.9f..1.9f) Color.YELLOW else Color.WHITE)
        b.z2.setTextColor(if (r >= 1.9f) Color.YELLOW else Color.WHITE)
        b.z1.text = if (r in 0.9f..1.9f) String.format(Locale.US, "%.1fx", r).replace(".0x", "x") else "1"
    }

    private fun setMode(m: Mode) { if (recording != null) return; mode = m; bind() }

    private fun updateModeUi() {
        val map = mapOf<Mode, TextView>(Mode.VIDEO to b.mVideo, Mode.PHOTO to b.mPhoto,
            Mode.PORTRAIT to b.mPortrait, Mode.SQUARE to b.mSquare)
        map.forEach { (k, v) -> v.setTextColor(if (k == mode) Color.YELLOW else Color.WHITE) }
        b.shutterInner.setBackgroundResource(if (mode == Mode.VIDEO) R.drawable.shutter_video else R.drawable.shutter_photo)
        b.btnFlash.setTextColor(
            if (mode != Mode.VIDEO && flashMode != ImageCapture.FLASH_MODE_OFF) Color.YELLOW else Color.WHITE)
    }

    private fun onShutter() {
        if (mode == Mode.VIDEO) { toggleRecording(); return }
        if (timerSec == 0) takePhoto() else countdown(timerSec)
    }

    private fun countdown(n: Int) {
        if (n == 0) { b.txtCountdown.visibility = View.GONE; takePhoto(); return }
        b.txtCountdown.visibility = View.VISIBLE
        b.txtCountdown.text = n.toString()
        handler.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/IOSCamera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
        b.viewFinder.alpha = 0.2f; b.viewFinder.animate().alpha(1f).setDuration(150)
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                lastUri = r.savedUri; b.btnGallery.setImageURI(lastUri)
            }
            override fun onError(e: ImageCaptureException) { toast("Gagal menyimpan: ${e.message}") }
        })
    }

    private fun toggleRecording() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/IOSCamera")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pending = vc.output.prepareRecording(this, out)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    b.txtRec.visibility = View.VISIBLE
                    b.shutterInner.animate().scaleX(0.6f).scaleY(0.6f).setDuration(150)
                }
                is VideoRecordEvent.Status -> {
                    val s = TimeUnit.NANOSECONDS.toSeconds(ev.recordingStats.recordedDurationNanos)
                    b.txtRec.text = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    b.txtRec.visibility = View.GONE
                    b.shutterInner.animate().scaleX(1f).scaleY(1f).setDuration(150)
                    recording = null
                    if (!ev.hasError()) { lastUri = ev.outputResults.outputUri; b.btnGallery.setImageURI(lastUri) }
                    else toast("Gagal merekam video")
                }
                else -> {}
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
