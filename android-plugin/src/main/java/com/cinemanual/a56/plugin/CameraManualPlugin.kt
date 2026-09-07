package com.cinemanual.a56.plugin

import android.Manifest
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Ponte entre a interface HTML (JavaScript) e a câmera real (CameraX/Camera2).
 *
 * O PreviewView é anexado por trás da WebView do Capacitor — por isso o
 * index.html usa fundo transparente quando roda dentro do app instalado
 * (ver classe CSS `native-app` no index.html).
 */
@CapacitorPlugin(
    name = "CameraManual",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone")
    ]
)
class CameraManualPlugin : Plugin() {

    private lateinit var cameraExecutor: ExecutorService
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraController: CameraController? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var currentFacing = CameraSelector.LENS_FACING_BACK
    private var currentQuality: Quality = Quality.FHD

    override fun load() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @PluginMethod
    fun startPreview(call: PluginCall) {
        if (!hasRequiredPermissions()) {
            requestPermissionForAliases(arrayOf("camera", "microphone"), call, "onPermissionResult")
            return
        }
        attachPreviewViewBehindWebView()
        bindCameraUseCases(call)
    }

    @PermissionCallback
    private fun onPermissionResult(call: PluginCall) {
        if (hasRequiredPermissions()) {
            attachPreviewViewBehindWebView()
            bindCameraUseCases(call)
        } else {
            call.reject("Permissão de câmera/microfone negada")
        }
    }

    /** Cria o PreviewView e o coloca atrás da WebView do Capacitor, uma única vez. */
    private fun attachPreviewViewBehindWebView() {
        if (previewView != null) return

        val activity = activity ?: return
        activity.runOnUiThread {
            val pv = PreviewView(activity)
            pv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            pv.implementationMode = PreviewView.ImplementationMode.PERFORMANCE

            // Insere o preview como a camada mais ao fundo da Activity,
            // e deixa a WebView do Capacitor transparente por cima dele.
            val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            root.addView(pv, 0)
            bridge.webView.setBackgroundColor(Color.TRANSPARENT)

            previewView = pv
        }
    }

    private fun bindCameraUseCases(call: PluginCall) {
        val activity = activity ?: run { call.reject("Activity indisponível"); return }
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val pv = previewView ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(currentQuality))
                    .build()
                val newVideoCapture = VideoCapture.withOutput(recorder)
                videoCapture = newVideoCapture

                val selector = CameraSelector.Builder().requireLensFacing(currentFacing).build()

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    activity as androidx.lifecycle.LifecycleOwner,
                    selector, preview, newVideoCapture
                )
                camera = boundCamera

                if (cameraController == null) {
                    cameraController = CameraController(boundCamera)
                } else {
                    cameraController?.updateCamera(boundCamera)
                }

                call.resolve()
            } catch (e: Exception) {
                Log.e("CameraManualPlugin", "Falha ao iniciar câmera", e)
                call.reject("Falha ao iniciar câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @PluginMethod
    fun setISO(call: PluginCall) {
        val auto = call.getBoolean("auto", true) ?: true
        val value = call.getInt("value", 400) ?: 400
        cameraController?.setManualIso(if (auto) null else value)
        call.resolve()
    }

    @PluginMethod
    fun setShutter(call: PluginCall) {
        val auto = call.getBoolean("auto", true) ?: true
        val fraction = call.getInt("fraction", 120) ?: 120
        val nanoseconds = if (fraction > 0) 1_000_000_000L / fraction else null
        cameraController?.setManualShutter(if (auto) null else nanoseconds)
        call.resolve()
    }

    @PluginMethod
    fun setFocus(call: PluginCall) {
        val auto = call.getBoolean("auto", true) ?: true
        val distance = call.getFloat("distance", 0f) ?: 0f
        cameraController?.setManualFocus(if (auto) null else distance)
        call.resolve()
    }

    @PluginMethod
    fun setWhiteBalance(call: PluginCall) {
        val auto = call.getBoolean("auto", true) ?: true
        // WB manual por Kelvin exigiria RAW/matriz de cor por dispositivo;
        // por ora alternamos só entre automático e travado no valor atual.
        cameraController?.setWhiteBalanceMode(
            if (auto) null else android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
        )
        call.resolve()
    }

    @PluginMethod
    fun setZoom(call: PluginCall) {
        val level = call.getFloat("level", 1f) ?: 1f
        camera?.cameraControl?.setZoomRatio(level)
        call.resolve()
    }

    @PluginMethod
    fun setFlash(call: PluginCall) {
        val mode = call.getString("mode", "off")
        // Em modo vídeo, "flash" é a lanterna (torch) ligada continuamente.
        camera?.cameraControl?.enableTorch(mode == "on")
        call.resolve()
    }

    @PluginMethod
    fun setQuality(call: PluginCall) {
        val width = call.getInt("width", 1920) ?: 1920
        currentQuality = when {
            width >= 3840 -> Quality.UHD
            width >= 1920 -> Quality.FHD
            else -> Quality.HD
        }
        bindCameraUseCases(call)
    }

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        val facing = call.getString("facing", "environment")
        currentFacing = if (facing == "user") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCameraUseCases(call)
    }

    @PluginMethod
    fun startRecording(call: PluginCall) {
        val capture = videoCapture ?: run { call.reject("Câmera ainda não iniciada"); return }
        val activity = activity ?: run { call.reject("Activity indisponível"); return }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val moviesDir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        val outputFile = File(moviesDir, "CINE_$name.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        activeRecording = capture.output
            .prepareRecording(activity, outputOptions)
            .apply { if (hasAudioPermission) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize && event.hasError()) {
                    Log.e("CameraManualPlugin", "Erro ao gravar: ${event.cause?.message}")
                }
            }
        call.resolve()
    }

    @PluginMethod
    fun stopRecording(call: PluginCall) {
        activeRecording?.stop()
        activeRecording = null
        val result = JSObject()
        result.put("savedTo", "Movies/CineManualA56")
        call.resolve(result)
    }
}
