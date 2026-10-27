package com.example.ioscamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.RgbMatrix
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

class MainActivity : AppCompatActivity() {

    enum class Mode { VIDEO, PHOTO, PORTRAIT }
    data class VOpt(val label: String, val q: Quality, val fps: Int)

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
    private var rIdx = 0
    private val ratios = listOf("4:3", "16:9", "1:1")

    private val allVOpts = listOf(
        VOpt("HD·30", Quality.HD, 30), VOpt("FHD·30", Quality.FHD, 30), VOpt("FHD·60", Quality.FHD, 60),
        VOpt("4K·30", Quality.UHD, 30), VOpt("4K·60", Quality.UHD, 60)
    )
    private var vOpts = allVOpts
    private var vIdx = 1
    private var oisOk = false
    private var eisOk = false

    private var zoomCur = 1f
    private var zoomTarget = 1f
    private var zooming = false

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

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

    // ------------------------------------------------------------ kamera

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
            ef.addListener({ extensions = ef.get(); bind() }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun probe(info: CameraInfo?) {
        val prev = vOpts.getOrNull(vIdx)?.label
        oisOk = false; eisOk = false
        var list = allVOpts
        if (info != null) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                oisOk = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                eisOk = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
                val fps60 = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.any { it.upper >= 60 } == true
                val sq = QualitySelector.getSupportedQualities(info)
                list = allVOpts.filter { it.q in sq && (it.fps <= 30 || fps60) }
            } catch (e: Exception) { /* pakai bawaan */ }
        }
        if (list.isEmpty()) list = listOf(allVOpts[1])
        vOpts = list
        vIdx = list.indexOfFirst { it.label == prev }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it.label == "FHD·30" }.takeIf { it >= 0 } ?: 0
    }

    private fun <T> stabilize(ext: Camera2Interop.Extender<T>, eis: Boolean) {
        if (!stabOn) return
        if (oisOk) ext.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        if (eis && eisOk) ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    private fun bind() {
        val p = provider ?: return
        b.viewFinder.removeCallbacks(zoomTick); zooming = false
        p.unbindAll()
        val base = CameraSelector.Builder().requireLensFacing(lens).build()
        probe(base.filter(p.availableCameraInfos).firstOrNull())

        var selector = base
        if (mode == Mode.PORTRAIT) {
            val em = extensions
            if (em != null && em.isExtensionAvailable(base, ExtensionMode.BOKEH)) {
                selector = em.getExtensionEnabledCameraSelector(base, ExtensionMode.BOKEH)
            } else toast("Mode potret (bokeh) tidak didukung perangkat ini")
        }

        val useLive = live && mode == Mode.PHOTO
        val tar = if (mode == Mode.VIDEO || rIdx == 1) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val vr = if (mode == Mode.VIDEO) Rational(9, 16) else when (rIdx) {
            0 -> Rational(3, 4); 1 -> Rational(9, 16); else -> Rational(1, 1)
        }

        val pb = Preview.Builder().setTargetAspectRatio(tar)
        stabilize(Camera2Interop.Extender(pb), mode == Mode.VIDEO)
        val preview = pb.build().also { it.setSurfaceProvider(b.viewFinder.surfaceProvider) }

        val gb = UseCaseGroup.Builder().addUseCase(preview)
            .setViewPort(ViewPort.Builder(vr, b.viewFinder.display.rotation).build())

        imageCapture = null; videoCapture = null
        if (mode != Mode.VIDEO) {
            val ib = ImageCapture.Builder().setTargetAspectRatio(tar).setJpegQuality(95)
                .setCaptureMode(if (useLive) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
            stabilize(Camera2Interop.Extender(ib), false)
            imageCapture = ib.build()
            gb.addUseCase(imageCapture!!)
        }
        val opt = if (mode == Mode.VIDEO) vOpts[vIdx] else VOpt("HD", Quality.HD, 30)
        if (mode == Mode.VIDEO || useLive) {
            val rec = Recorder.Builder().setQualitySelector(
                QualitySelector.from(opt.q, FallbackStrategy.lowerQualityOrHigherThan(opt.q))).build()
            val vb = VideoCapture.Builder(rec)
            if (opt.fps > 30) vb.setTargetFrameRate(Range(opt.fps, opt.fps))
            stabilize(Camera2Interop.Extender(vb), mode == Mode.VIDEO)
            videoCapture = vb.build()
            gb.addUseCase(videoCapture!!)
        }

        try {
            camera = p.bindToLifecycle(this, selector, gb.build())
        } catch (e: Exception) {
            if (mode == Mode.VIDEO && opt.fps > 30) {
                toast("${opt.label} tidak didukung, pakai 30 fps")
                vIdx = vOpts.indexOfFirst { it.q == opt.q && it.fps == 30 }.takeIf { it >= 0 } ?: 0
                bind()
            } else if (useLive) {
                live = false; toast("Foto Live tidak didukung perangkat ini"); bind()
            } else toast("Gagal membuka kamera: ${e.message}")
            return
        }
        camera?.cameraInfo?.zoomState?.observe(this) { z ->
            if (!zooming) { zoomCur = z.zoomRatio; zoomTarget = zoomCur }
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
            if (mode == Mode.VIDEO) vIdx = (vIdx + 1) % vOpts.size else rIdx = (rIdx + 1) % ratios.size
            bind()
        }
        b.btnLive.setOnClickListener {
            if (mode != Mode.PHOTO) { toast("Foto Live hanya di mode Foto"); return@setOnClickListener }
            live = !live; bind()
            toast(if (live) "Live aktif: foto + klip 2,5 dtk" else "Live mati")
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
        b.z05.setOnClickListener { zoomTo(0.5f) }
        b.z1.setOnClickListener { zoomTo(1f) }
        b.z2.setOnClickListener { zoomTo(2f) }
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

    private var lastUri: Uri? = null

    private fun updateUi() {
        val map = mapOf<Mode, TextView>(Mode.VIDEO to b.mVideo, Mode.PHOTO to b.mPhoto, Mode.PORTRAIT to b.mPortrait)
        map.forEach { (k, v) -> v.setTextColor(if (k == mode) Color.YELLOW else Color.WHITE) }
        b.shutterInner.setBackgroundResource(if (mode == Mode.VIDEO) R.drawable.shutter_video else R.drawable.shutter_photo)
        b.btnFlash.setTextColor(
            if (mode != Mode.VIDEO && flashMode != ImageCapture.FLASH_MODE_OFF) Color.YELLOW else Color.WHITE)
        b.btnRes.text = if (mode == Mode.VIDEO) vOpts[vIdx].label else ratios[rIdx]
        b.btnLive.setTextColor(if (live && mode == Mode.PHOTO) Color.YELLOW else Color.WHITE)
        b.btnLive.alpha = if (mode == Mode.PHOTO) 1f else 0.4f
        b.btnTone.setTextColor(if (toneOn) Color.YELLOW else Color.WHITE)
        b.btnStab.setTextColor(if (stabOn) Color.YELLOW else Color.WHITE)
    }

    private fun setMode(m: Mode) { if (recording != null) return; mode = m; bind() }

    private fun busy(msg: String?) {
        b.txtBusy.visibility = if (msg == null) View.GONE else View.VISIBLE
        if (msg != null) b.txtBusy.text = msg
    }

    // ------------------------------------------------------------ zoom halus & fokus

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

    private fun updateZoomButtons(z: ZoomState) {
        b.z05.visibility = if (z.minZoomRatio <= 0.55f) View.VISIBLE else View.GONE
        b.z2.visibility = if (z.maxZoomRatio >= 2f) View.VISIBLE else View.GONE
        val r = z.zoomRatio
        b.z05.setTextColor(if (r < 0.9f) Color.YELLOW else Color.WHITE)
        b.z1.setTextColor(if (r in 0.9f..1.9f) Color.YELLOW else Color.WHITE)
        b.z2.setTextColor(if (r >= 1.9f) Color.YELLOW else Color.WHITE)
        b.z1.text = if (r in 0.9f..1.9f) String.format(Locale.US, "%.1fx", r).replace(".0x", "x") else "1"
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
        val delay = if (withClip) 250L else 0L
        handler.postDelayed({
            ic.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(),
                ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { done() }
                    override fun onError(e: ImageCaptureException) {
                        toast("Gagal mengambil foto: ${e.message}")
                        liveRec?.stop(); liveRec = null
                    }
                })
        }, delay)
    }

    private fun finishPhoto(photo: File, clip: File?) {
        exec.execute {
            try {
                if (toneOn) try { gradePhoto(photo) } catch (t: Throwable) { /* simpan tanpa tone */ }
                val s = stamp()
                val uri: Uri?
                if (clip != null) {
                    val m = embedMotion(photo, clip)
                    uri = saveToGallery(m, "MVIMG_$s.jpg", "image/jpeg", "Pictures/IOSCamera", false)
                    m.delete(); clip.delete()
                } else uri = saveToGallery(photo, "IMG_$s.jpg", "image/jpeg", "Pictures/IOSCamera", false)
                photo.delete()
                if (uri != null) setThumb(uri)
            } catch (t: Throwable) {
                runOnUiThread { toast("Gagal menyimpan: ${t.message}") }
            }
        }
    }

    private fun gradePhoto(f: File) {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bo)
        var s = 1
        while ((bo.outWidth / s).toLong() * (bo.outHeight / s) > 16_000_000L) s *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val rot = if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { src.recycle() }
        } else src
        val out = Bitmap.createBitmap(rot.width, rot.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(Tone.photo())) }
        Canvas(out).drawBitmap(rot, 0f, 0f, paint)
        rot.recycle()
        val tmp = File(f.path + ".t")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        out.recycle()
        f.delete(); tmp.renameTo(f)
    }

    /** Gabungkan JPEG + MP4 menjadi "Motion Photo" (format MicroVideo Google). */
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
            """xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1" """ +
            """GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${v.size}" """ +
            """GCamera:MicroVideoPresentationTimestampUs="0"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>""")
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
        val vc = videoCapture ?: return
        recording?.let { it.stop(); recording = null; return }
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
            val rgb = object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = Tone.video()
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f)))
                .setEffects(Effects(emptyList(), listOf<Effect>(rgb))).build()
            val t = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveVideo(f, out)
                }
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

    private fun saveVideo(orig: File, graded: File?) {
        busy("Menyimpan…")
        exec.execute {
            val src = graded ?: orig
            val uri = try { saveToGallery(src, "VID_${stamp()}.mp4", "video/mp4", "Movies/IOSCamera", true) }
                      catch (t: Throwable) { null }
            orig.delete(); graded?.delete()
            runOnUiThread { busy(null); if (uri == null) toast("Gagal menyimpan video") }
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
            } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
