package com.example.ioscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.ioscamera.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    enum class Mode { VIDEO, PHOTO, PORTRAIT }
    data class VOpt(val label: String, val q: Quality, val fps: Int, val mbps: Int)

    private lateinit var b: ActivityMainBinding
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var liveRec: Recording? = null

    private var mode = Mode.PHOTO
    private var lens = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var timerSec = 0
    private var live = false
    private var toneOn = false
    private var stabOn = false
    private var aiOn = false
    private var maxRes = false
    private var soundOn = true
    private var extBlocked = false
    private var startingRec = false
    private var rIdx = 0
    private val ratios = listOf("4:3", "16:9", "1:1")

    private val allVOpts = listOf(
        VOpt("HD·30", Quality.HD, 30, 8), VOpt("FHD·30", Quality.FHD, 30, 16),
        VOpt("FHD·60", Quality.FHD, 60, 24), VOpt("4K·30", Quality.UHD, 30, 50),
        VOpt("4K·60", Quality.UHD, 60, 70)
    )
    private var vIdx = 1

    // hasil deteksi hardware
    private var oisOk = false
    private var eisOk = false
    private var hq: List<Pair<CaptureRequest.Key<Int>, Int>> = emptyList()
    private var lensRatios: List<Float> = emptyList()
    private var lensText = "-"
    private var fpsText = "-"
    private var qualText = "-"
    private var extText = "-"
    private var fps4k = "-"

    private var zoomCur = 1f
    private var zoomTarget = 1f
    private var zooming = false
    private var zoomBuilt = false
    private val zoomBtns = mutableListOf<Pair<Float, TextView>>()
    private var lastUri: Uri? = null
    private var pendingJobs = 0

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun play(s: Int) { if (soundOn) sound.play(s) }

    // ------------------------------------------------------------ deteksi hardware

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
            ef.addListener({ extensions = ef.get(); bind() }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun probe(info: CameraInfo?, base: CameraSelector) {
        oisOk = false; eisOk = false; hq = emptyList()
        lensRatios = emptyList(); lensText = "-"; fpsText = "-"; qualText = "-"; fps4k = "-"
        val em = extensions
        extText = listOf("AUTO" to ExtensionMode.AUTO, "HDR" to ExtensionMode.HDR, "NIGHT" to ExtensionMode.NIGHT,
            "BOKEH" to ExtensionMode.BOKEH, "FACE" to ExtensionMode.FACE_RETOUCH)
            .filter { em != null && em.isExtensionAvailable(base, it.second) }
            .joinToString { it.first }.ifEmpty { "tidak ada" }
        if (info == null) return
        try {
            val c2 = Camera2CameraInfo.from(info)
            oisOk = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            eisOk = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            fpsText = ranges?.joinToString { "${it.lower}-${it.upper}" } ?: "-"
            qualText = QualitySelector.getSupportedQualities(info).joinToString { it.toString() }
            val list = mutableListOf<Pair<CaptureRequest.Key<Int>, Int>>()
            fun add(ch: CameraCharacteristics.Key<IntArray>, rq: CaptureRequest.Key<Int>, v: Int) {
                if (c2.getCameraCharacteristic(ch)?.contains(v) == true) list.add(rq to v)
            }
            add(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            add(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            add(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            add(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
                CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
            add(CameraCharacteristics.SHADING_AVAILABLE_MODES,
                CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
            hq = list
            try {
                val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val d = map?.getOutputMinFrameDuration(MediaRecorder::class.java, Size(3840, 2160)) ?: 0L
                fps4k = if (d > 0) "${(1e9 / d).roundToInt()} fps" else "tidak dilaporkan"
            } catch (e: Exception) { }
            detectLenses(c2.cameraId)
        } catch (e: Exception) { /* pakai bawaan */ }
    }

    private fun equiv(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return f * 43.27f / sqrt(s.width * s.width + s.height * s.height)
    }

    /** Deteksi lensa optik (UW / utama / tele) lewat focal length fisik. */
    private fun detectLenses(logicalId: String) {
        if (lens != CameraSelector.LENS_FACING_BACK) return
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val lc = cm.getCameraCharacteristics(logicalId)
        var ids = lc.physicalCameraIds.toList()
        if (ids.isEmpty()) ids = cm.cameraIdList.filter {
            try { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            catch (e: Exception) { false }
        }
        val eqs = ids.mapNotNull { try { equiv(cm.getCameraCharacteristics(it)) } catch (e: Exception) { null } }
            .map { it.roundToInt().toFloat() }.distinct().sorted()
        if (eqs.isEmpty()) return
        val main = eqs.filter { it in 18f..40f }.minByOrNull { kotlin.math.abs(it - 26f) } ?: equiv(lc) ?: eqs.first()
        val rs = eqs.map { e ->
            val r = e / main
            if (r < 0.95f) ((r * 2f).roundToInt() / 2f).coerceAtLeast(0.5f) else r.roundToInt().toFloat()
        }
        lensRatios = rs.distinct().sorted()
        lensText = eqs.zip(rs).joinToString(" · ") { "${it.first.roundToInt()}mm→${fmt(it.second)}x" }
    }

    // ------------------------------------------------------------ bind

    private fun <T> stabilize(ext: Camera2Interop.Extender<T>, eis: Boolean) {
        if (!stabOn) return
        if (oisOk) ext.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (eis && eisOk) ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    private fun <T> applyHq(ext: Camera2Interop.Extender<T>) {
        for ((k, v) in hq) ext.setCaptureRequestOption(k, v)
    }

    private fun resSel(tar: Int, high: Boolean): ResolutionSelector {
        val bld = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(tar, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        if (high) {
            bld.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            bld.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
        }
        return bld.build()
    }

    private fun bind() {
        val p = provider ?: return
        b.viewFinder.removeCallbacks(zoomTick); zooming = false; zoomBuilt = false
        p.unbindAll()
        val base = CameraSelector.Builder().requireLensFacing(lens).build()
        probe(base.filter(p.availableCameraInfos).firstOrNull(), base)

        var selector = base
        var extUsed = false
        val em = extensions
        if (mode == Mode.PORTRAIT) {
            if (em != null && em.isExtensionAvailable(base, ExtensionMode.BOKEH)) {
                selector = em.getExtensionEnabledCameraSelector(base, ExtensionMode.BOKEH); extUsed = true
            } else toast("Mode potret (bokeh) tidak didukung perangkat ini")
        } else if (mode == Mode.PHOTO && aiOn && !live && !extBlocked) {
            if (em != null) {
                val m = listOf(ExtensionMode.AUTO, ExtensionMode.HDR).firstOrNull { em.isExtensionAvailable(base, it) }
                if (m != null) { selector = em.getExtensionEnabledCameraSelector(base, m); extUsed = true }
                else toast("HDR/Auto bawaan HP tidak tersedia untuk aplikasi ini; pakai AI software")
            }
        }

        val useLive = live && mode == Mode.PHOTO
        val tar = if (mode == Mode.VIDEO || rIdx == 1) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val vr = if (mode == Mode.VIDEO) Rational(9, 16) else when (rIdx) {
            0 -> Rational(3, 4); 1 -> Rational(9, 16); else -> Rational(1, 1)
        }

        val pb = Preview.Builder().setResolutionSelector(resSel(tar, false))
        if (!extUsed) stabilize(Camera2Interop.Extender(pb), mode == Mode.VIDEO)
        val preview = pb.build().also { it.setSurfaceProvider(b.viewFinder.surfaceProvider) }
        val gb = UseCaseGroup.Builder().addUseCase(preview)
            .setViewPort(ViewPort.Builder(vr, b.viewFinder.display.rotation).build())

        imageCapture = null; videoCapture = null
        if (mode != Mode.VIDEO) {
            val high = maxRes && !useLive && !extUsed
            val ib = ImageCapture.Builder().setResolutionSelector(resSel(tar, high)).setJpegQuality(97)
                .setCaptureMode(if (useLive) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
            if (!extUsed) {
                applyHq(Camera2Interop.Extender(ib))
                stabilize(Camera2Interop.Extender(ib), false)
            }
            imageCapture = ib.build()
            gb.addUseCase(imageCapture!!)
        }
        val opt = if (mode == Mode.VIDEO) allVOpts[vIdx] else allVOpts[0]
        if (mode == Mode.VIDEO || useLive) {
            val rec = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(opt.q, FallbackStrategy.lowerQualityOrHigherThan(opt.q)))
                .setTargetVideoEncodingBitRate(opt.mbps * 1_000_000).build()
            val vb = VideoCapture.Builder(rec)
            if (opt.fps > 30) vb.setTargetFrameRate(Range(opt.fps, opt.fps))
            stabilize(Camera2Interop.Extender(vb), mode == Mode.VIDEO)
            videoCapture = vb.build()
            gb.addUseCase(videoCapture!!)
        }

        try {
            camera = p.bindToLifecycle(this, selector, gb.build())
        } catch (e: Exception) {
            if (extUsed && mode == Mode.PHOTO) {
                extBlocked = true; toast("Ekstensi HP gagal dipakai, pakai AI software"); bind()
            } else if (mode == Mode.VIDEO && opt.fps > 30) {
                toast("${opt.label} tidak didukung kamera ini, pakai 30 fps")
                vIdx = allVOpts.indexOfFirst { it.q == opt.q && it.fps == 30 }.takeIf { it >= 0 } ?: 1
                bind()
            } else if (useLive) {
                live = false; toast("Foto Live tidak didukung perangkat ini"); bind()
            } else if (maxRes) {
                maxRes = false; toast("Resolusi MAX tidak didukung, kembali ke standar"); bind()
            } else toast("Gagal membuka kamera: ${e.message}")
            return
        }
        camera?.cameraInfo?.zoomState?.observe(this) { z ->
            if (!zooming) { zoomCur = z.zoomRatio; zoomTarget = zoomCur }
            if (!zoomBuilt) { buildZoomButtons(z); zoomBuilt = true }
            updateZoomButtons(z)
        }
        torchOn = false
        updateUi()
    }

    // ------------------------------------------------------------ UI

    private fun setupUi() {
        b.mVideo.setOnClickListener { setMode(Mode.VIDEO) }
        b.mPhoto.setOnClickListener { setMode(Mode.PHOTO) }
        b.mPortrait.setOnClickListener { setMode(Mode.PORTRAIT) }
        b.btnFlip.setOnClickListener {
            if (recording != null) return@setOnClickListener
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
            bind()
        }
        b.btnRes.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (mode == Mode.VIDEO) vIdx = (vIdx + 1) % allVOpts.size else rIdx = (rIdx + 1) % ratios.size
            bind()
        }
        b.btnMax.setOnClickListener {
            maxRes = !maxRes; bind()
            toast(if (maxRes) "Resolusi foto MAX (lihat INFO untuk ukuran)" else "Resolusi standar")
        }
        b.btnLive.setOnClickListener {
            if (mode != Mode.PHOTO) { toast("Foto Live hanya di mode Foto"); return@setOnClickListener }
            live = !live; bind()
            toast(if (live) "Live aktif (AI bawaan HP & MAX dinonaktifkan)" else "Live mati")
        }
        b.btnAi.setOnClickListener {
            aiOn = !aiOn; extBlocked = false; bind()
            toast(if (aiOn) "AI aktif: HDR/Auto bawaan HP bila ada + AI software" else "AI mati")
        }
        b.btnTone.setOnClickListener {
            toneOn = !toneOn; updateUi()
            toast(if (toneOn) "Tone iPhone aktif (diterapkan ke hasil simpan)" else "Tone standar")
        }
        b.btnStab.setOnClickListener {
            if (recording != null) return@setOnClickListener
            stabOn = !stabOn; bind()
            toast(if (!stabOn) "Stabilisasi mati"
                  else "OIS: ${if (oisOk) "ya" else "tidak ada"}, EIS: ${if (eisOk) "ya" else "tidak ada"}")
        }
        b.btnSnd.setOnClickListener { soundOn = !soundOn; updateUi() }
        b.btnInfo.setOnClickListener { showInfo() }
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
            toast(if (timerSec == 0) "Timer mati" else "Timer $timerSec detik")
        }
        b.btnGrid.setOnClickListener {
            val show = b.gridView.visibility != View.VISIBLE
            b.gridView.visibility = if (show) View.VISIBLE else View.GONE
            b.btnGrid.setTextColor(if (show) Color.YELLOW else Color.WHITE)
        }
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

    private fun updateUi() {
        val map = mapOf<Mode, TextView>(Mode.VIDEO to b.mVideo, Mode.PHOTO to b.mPhoto, Mode.PORTRAIT to b.mPortrait)
        map.forEach { (k, v) -> v.setTextColor(if (k == mode) Color.YELLOW else Color.WHITE) }
        b.shutterInner.setBackgroundResource(if (mode == Mode.VIDEO) R.drawable.shutter_video else R.drawable.shutter_photo)
        b.btnFlash.setTextColor(
            if (mode != Mode.VIDEO && flashMode != ImageCapture.FLASH_MODE_OFF) Color.YELLOW else Color.WHITE)
        b.btnRes.text = if (mode == Mode.VIDEO) allVOpts[vIdx].label else ratios[rIdx]
        b.btnLive.setTextColor(if (live && mode == Mode.PHOTO) Color.YELLOW else Color.WHITE)
        b.btnLive.alpha = if (mode == Mode.PHOTO) 1f else 0.4f
        b.btnAi.setTextColor(if (aiOn) Color.YELLOW else Color.WHITE)
        b.btnTone.setTextColor(if (toneOn) Color.YELLOW else Color.WHITE)
        b.btnStab.setTextColor(if (stabOn) Color.YELLOW else Color.WHITE)
        b.btnMax.setTextColor(if (maxRes) Color.YELLOW else Color.WHITE)
        b.btnSnd.setTextColor(if (soundOn) Color.YELLOW else Color.WHITE)
    }

    private fun setMode(m: Mode) { if (recording != null) return; mode = m; bind() }

    private fun busy(msg: String?) {
        b.txtBusy.visibility = if (msg == null) View.GONE else View.VISIBLE
        if (msg != null) b.txtBusy.text = msg
    }

    private fun showInfo() {
        val sb = StringBuilder()
        sb.append("Kamera: ").append(if (lens == CameraSelector.LENS_FACING_BACK) "belakang" else "depan").append('\n')
        sb.append("Lensa (ekuiv. 35mm→zoom): ").append(lensText).append('\n')
        camera?.cameraInfo?.zoomState?.value?.let { sb.append("Zoom: ${fmt(it.minZoomRatio)}x – ${fmt(it.maxZoomRatio)}x\n") }
        imageCapture?.resolutionInfo?.resolution?.let {
            sb.append("Foto aktif: ${it.width}x${it.height} (${"%.1f".format(Locale.US, it.width * it.height / 1e6)} MP)\n")
        }
        sb.append("FPS (AE): ").append(fpsText).append('\n')
        sb.append("Maks fps 4K (encoder): ").append(fps4k).append('\n')
        sb.append("Kualitas video: ").append(qualText).append('\n')
        sb.append("OIS: ").append(if (oisOk) "ya" else "tidak")
            .append("   EIS: ").append(if (eisOk) "ya" else "tidak").append('\n')
        sb.append("Mode HQ hardware: ").append(hq.size).append(" aktif\n")
        sb.append("Ekstensi HP: ").append(extText)
        AlertDialog.Builder(this).setTitle("Info kamera").setMessage(sb.toString())
            .setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------ zoom

    private fun fmt(v: Float): String =
        if (v >= 10f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")

    private fun buildZoomButtons(z: ZoomState) {
        b.zoomBar.removeAllViews(); zoomBtns.clear()
        val set = sortedSetOf(1f)
        lensRatios.forEach { set.add(it) }
        if (z.minZoomRatio <= 0.65f) set.add(0.5f)
        if (z.maxZoomRatio >= 2f) set.add(2f)
        if (z.maxZoomRatio >= 10f) set.add(10f)
        if (z.maxZoomRatio >= 20f) set.add(minOf(floor(z.maxZoomRatio / 10f) * 10f, 30f))
        for (r in set) {
            if (r < z.minZoomRatio * 0.8f || r > z.maxZoomRatio) continue
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.setMargins(dp(3), 0, dp(3), 0) }
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.zoom_bg)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                text = fmt(r)
                setOnClickListener { zoomTo(r) }
            }
            b.zoomBar.addView(tv)
            zoomBtns.add(r to tv)
        }
    }

    private fun updateZoomButtons(z: ZoomState) {
        val cur = z.zoomRatio
        var active = 0
        zoomBtns.forEachIndexed { i, (r, _) -> if (r <= cur * 1.03f) active = i }
        zoomBtns.forEachIndexed { i, (r, tv) ->
            tv.text = if (i == active) fmt(cur) + "x" else fmt(r)
            tv.setTextColor(if (i == active) Color.YELLOW else Color.WHITE)
        }
    }

    private val zoomTick = object : Runnable {
        override fun run() {
            val z = camera?.cameraInfo?.zoomState?.value
            if (z == null) { zooming = false; return }
            val d = zoomTarget - zoomCur
            zoomCur = if (kotlin.math.abs(d) < 0.003f) zoomTarget else zoomCur + d * 0.18f
            camera?.cameraControl?.setZoomRatio(zoomCur.coerceIn(z.minZoomRatio, z.maxZoomRatio))
            if (zoomCur != zoomTarget) b.viewFinder.postOnAnimation(this) else zooming = false
        }
    }

    private fun zoomTo(r: Float) {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        zoomTarget = r.coerceIn(z.minZoomRatio, z.maxZoomRatio)
        if (!zooming) { zooming = true; b.viewFinder.postOnAnimation(zoomTick) }
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { zoomTo(zoomTarget * d.scaleFactor); return true }
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

    // ------------------------------------------------------------ foto

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
        val photo = File(cacheDir, "p_${stamp()}.jpg")
        val vc = videoCapture
        val withClip = live && mode == Mode.PHOTO && vc != null
        var clip: File? = null
        var parts = if (withClip) 2 else 1
        fun done() { if (--parts == 0) finishPhoto(photo, clip) }

        play(MediaActionSound.SHUTTER_CLICK)
        if (withClip) {
            val f = File(cacheDir, "c_${stamp()}.mp4")
            var pr = vc!!.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                pr = pr.withAudioEnabled()
            liveRec = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
                if (ev is VideoRecordEvent.Finalize) { clip = if (ev.hasError()) null else f; done() }
            }
            handler.postDelayed({ liveRec?.stop(); liveRec = null }, 2500)
        }
        b.viewFinder.alpha = 0.2f; b.viewFinder.animate().alpha(1f).setDuration(150)
        handler.postDelayed({
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(),
                ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { done() }
                    override fun onError(e: ImageCaptureException) {
                        toast("Gagal mengambil foto: ${e.message}")
                        liveRec?.stop(); liveRec = null
                    }
                })
        }, if (withClip) 250L else 0L)
    }

    private fun finishPhoto(photo: File, clip: File?) {
        pendingJobs++; busy("Memproses foto…")
        val tone = toneOn; val enh = aiOn
        exec.execute {
            var dims = ""
            try {
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.path, bo)
                dims = "${bo.outWidth}x${bo.outHeight}"
                if (tone || enh) try { Enhancer.process(photo, tone, enh) } catch (t: Throwable) { }
                val s = stamp()
                val uri: Uri?
                if (clip != null) {
                    val m = embedMotion(photo, clip)
                    uri = saveToGallery(m, "MVIMG_$s.jpg", "image/jpeg", "Pictures/IOSCamera", false)
                    saveToGallery(clip, "LIVE_$s.mp4", "video/mp4", "Movies/IOSCamera", true)
                    m.delete(); clip.delete()
                } else uri = saveToGallery(photo, "IMG_$s.jpg", "image/jpeg", "Pictures/IOSCamera", false)
                photo.delete()
                if (uri != null) setThumb(uri)
            } catch (t: Throwable) {
                runOnUiThread { toast("Gagal menyimpan: ${t.message}") }
            }
            runOnUiThread {
                pendingJobs--
                if (pendingJobs == 0) busy(null)
                toast("Foto tersimpan $dims")
            }
        }
    }

    /** JPEG + MP4 -> Motion Photo (XMP gaya Google: Container + MicroVideo). */
    private fun embedMotion(jpg: File, mp4: File): File {
        val j = jpg.readBytes(); val v = mp4.readBytes()
        var pos = 2
        while (pos + 4 <= j.size && (j[pos].toInt() and 0xFF) == 0xFF) {
            val mk = j[pos + 1].toInt() and 0xFF
            if (mk < 0xE0 || mk > 0xEF) break
            val len = ((j[pos + 2].toInt() and 0xFF) shl 8) or (j[pos + 3].toInt() and 0xFF)
            pos += 2 + len
        }
        val xmp = ("""<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/">""" +
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" """ +
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            """xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" """ +
            """GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="0" """ +
            """GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${v.size}" """ +
            """GCamera:MicroVideoPresentationTimestampUs="0"><Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${v.size}" Item:Padding="0"/></rdf:li>""" +
            """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>""")
            .toByteArray(Charsets.UTF_8)
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val segLen = 2 + header.size + xmp.size
        val o = ByteArrayOutputStream(j.size + v.size + segLen + 8)
        o.write(j, 0, pos)
        o.write(0xFF); o.write(0xE1); o.write(segLen shr 8); o.write(segLen and 0xFF)
        o.write(header); o.write(xmp)
        o.write(j, pos, j.size - pos)
        o.write(v)
        val out = File(cacheDir, "m_${stamp()}.jpg")
        out.writeBytes(o.toByteArray())
        return out
    }

    // ------------------------------------------------------------ video

    private fun toggleRecording() {
        if (startingRec) return
        recording?.let { it.stop(); recording = null; return }
        play(MediaActionSound.START_VIDEO_RECORDING)
        startingRec = true
        handler.postDelayed({ startingRec = false; startRecording() }, if (soundOn) 450L else 0L)
    }

    private fun startRecording() {
        val vc = videoCapture ?: return
        val f = File(cacheDir, "v_${stamp()}.mp4")
        var pr = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pr = pr.withAudioEnabled()
        recording = pr.start(ContextCompat.getMainExecutor(this)) { ev ->
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
                    play(MediaActionSound.STOP_VIDEO_RECORDING)
                    if (ev.hasError()) toast("Gagal merekam video") else afterVideo(f)
                }
                else -> {}
            }
        }
    }

    private fun afterVideo(f: File) {
        if (!toneOn) { saveVideo(f, null); return }
        busy("Menerapkan tone iPhone…\n(video 4K bisa lama)")
        try {
            val out = File(cacheDir, "g_${stamp()}.mp4")
            val lut = SingleColorLut.createFromCube(Tone.cube())
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f)))
                .setEffects(Effects(emptyList(), listOf<Effect>(lut))).build()
            val t = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { saveVideo(f, out) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete(); saveVideo(f, null)
                    toast("Tone video gagal, disimpan tanpa tone")
                }
            }).build()
            t.start(item, out.path)
        } catch (e: Throwable) {
            saveVideo(f, null); toast("Tone video gagal, disimpan tanpa tone")
        }
    }

    private fun videoInfo(f: File): String? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0
        val fr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull() ?: 0.0
        r.release()
        if (dur > 0 && fr > 0) "${w}x$h @ ${(fr / (dur / 1000.0)).roundToInt()} fps" else "${w}x$h"
    } catch (e: Exception) { null }

    private fun saveVideo(orig: File, graded: File?) {
        busy("Menyimpan…")
        exec.execute {
            val src = graded ?: orig
            val info = videoInfo(src)
            val uri = try { saveToGallery(src, "VID_${stamp()}.mp4", "video/mp4", "Movies/IOSCamera", true) }
                      catch (t: Throwable) { null }
            orig.delete(); graded?.delete()
            runOnUiThread {
                busy(null)
                toast(if (uri == null) "Gagal menyimpan video" else "Video tersimpan ${info ?: ""}")
            }
            if (uri != null) setThumb(uri)
        }
    }

    // ------------------------------------------------------------ galeri

    private fun saveToGallery(src: File, name: String, mime: String, rel: String, video: Boolean): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(coll, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
        return uri
    }

    private fun setThumb(uri: Uri) {
        lastUri = uri
        exec.execute {
            try {
                val bm = contentResolver.loadThumbnail(uri, Size(256, 256), null)
                runOnUiThread { b.btnGallery.setImageBitmap(bm) }
            } catch (e: Exception) { }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
