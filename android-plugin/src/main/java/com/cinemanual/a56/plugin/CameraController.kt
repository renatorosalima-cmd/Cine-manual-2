package com.cinemanual.a56.plugin

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera

/**
 * Controla os parâmetros manuais do sensor (ISO, obturador, foco, WB) via
 * Camera2Interop, por baixo do CameraX. Mesma lógica do projeto Android
 * nativo original, reaproveitada aqui dentro do plugin Capacitor.
 */
class CameraController(private var camera: Camera) {

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var focusDistanceMax: Float = 0f

    private var manualIso: Int? = null
    private var manualShutterNs: Long? = null
    private var manualFocusDistance: Float? = null
    private var manualWbMode: Int? = null

    fun updateCamera(newCamera: Camera) {
        camera = newCamera
        loadCapabilities()
        applyOptions()
    }

    fun loadCapabilities() {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        isoRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureRange = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        focusDistanceMax = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    }

    fun getIsoRange(): Range<Int> = isoRange ?: Range(50, 1600)
    fun getExposureRange(): Range<Long> = exposureRange ?: Range(1_000_000L, 250_000_000L)
    fun getMaxFocusDistance(): Float = focusDistanceMax

    fun setManualIso(iso: Int?) {
        // Garante que o valor pedido pelo JS caiba na faixa real do sensor
        manualIso = iso?.coerceIn(getIsoRange().lower, getIsoRange().upper)
        applyOptions()
    }

    fun setManualShutter(nanoseconds: Long?) {
        manualShutterNs = nanoseconds?.coerceIn(getExposureRange().lower, getExposureRange().upper)
        applyOptions()
    }

    fun setManualFocus(distance: Float?) {
        manualFocusDistance = distance?.coerceIn(0f, getMaxFocusDistance())
        applyOptions()
    }

    fun setWhiteBalanceMode(mode: Int?) {
        manualWbMode = mode
        applyOptions()
    }

    private fun applyOptions() {
        val builder = CaptureRequestOptions.Builder()

        if (manualIso != null || manualShutterNs != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualShutterNs?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }

        if (manualFocusDistance != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        } else {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            manualWbMode ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        )

        try {
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            // Alguns dispositivos/hardware level "LEGACY" rejeitam certas combinações;
            // evita derrubar o app se um valor não for aceito.
        }
    }
}
