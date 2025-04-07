package com.apparence.camerawesome.cameraX

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.internal.compat.CameraCharacteristicsCompat
import androidx.camera.camera2.internal.compat.quirk.CamcorderProfileResolutionQuirk
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.apparence.camerawesome.CamerawesomePlugin
import com.apparence.camerawesome.models.FlashMode
import com.apparence.camerawesome.sensors.SensorOrientation
import com.apparence.camerawesome.utils.isMultiCamSupported
import io.flutter.plugin.common.EventChannel
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executor

data class CameraXState(
    private var cameraProvider: ProcessCameraProvider,
    val textureEntries: Map<String, TextureRegistry.SurfaceTextureEntry>,
    var sensors: List<PigeonSensor>,
    var imageCaptures: MutableList<ImageCapture> = mutableListOf(),
    var videoCaptures: MutableMap<PigeonSensor, VideoCapture<Recorder>> = mutableMapOf(),
    var previews: MutableList<Preview>? = null,
    var concurrentCamera: ConcurrentCamera? = null,
    var previewCamera: Camera? = null,
    private var currentCaptureMode: CaptureModes,
    var enableAudioRecording: Boolean = true,
    var recordings: MutableList<Recording>? = null,
    var enableImageStream: Boolean = false,
    var photoSize: Size? = null,
    var previewSize: Size? = null,
    var aspectRatio: Int? = null,
    var rational: Rational = Rational(3, 4),
    var flashMode: FlashMode = FlashMode.NONE,
    val onStreamReady: (state: CameraXState) -> Unit,
    var mirrorFrontCamera: Boolean = false,
    val videoRecordingQuality: VideoRecordingQuality?,
    val videoOptions: AndroidVideoOptions?,
) : EventChannel.StreamHandler, SensorOrientation {

    var imageAnalysisBuilder: ImageAnalysisBuilder? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val mainCameraInfos: CameraInfo
        @SuppressLint("RestrictedApi") get() {
            if (previewCamera == null && concurrentCamera == null) {
                throw Exception("Trying to access main camera infos before setting the preview")
            }
            return previewCamera?.cameraInfo ?: concurrentCamera?.cameras?.first()?.cameraInfo!!
        }

    private val mainCameraControl: CameraControl
        @SuppressLint("RestrictedApi") get() {
            if (previewCamera == null && concurrentCamera == null) {
                throw Exception("Trying to access main camera control before setting the preview")
            }
            return previewCamera?.cameraControl ?: concurrentCamera?.cameras?.first()?.cameraControl!!
        }

    val maxZoomRatio: Double
        @SuppressLint("RestrictedApi") get() = mainCameraInfos.zoomState.value!!.maxZoomRatio.toDouble()

    val minZoomRatio: Double
        get() = mainCameraInfos.zoomState.value!!.minZoomRatio.toDouble()

    val portrait: Boolean
        get() = mainCameraInfos.sensorRotationDegrees % 180 == 0

    fun executor(activity: Activity): Executor {
        return ContextCompat.getMainExecutor(activity)
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    fun updateLifecycle(activity: Activity) {
        previews = mutableListOf()
        imageCaptures.clear()
        videoCaptures.clear()
        if (cameraProvider.isMultiCamSupported() && sensors.size > 1) {
            val singleCameraConfigs = mutableListOf<ConcurrentCamera.SingleCameraConfig>()
            var isFirst = true
            for ((index, sensor) in sensors.withIndex()) {
                val useCaseGroupBuilder = UseCaseGroup.Builder()
                val cameraSelector = if (isFirst) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

                val previewBuilder = Preview.Builder()
                if (aspectRatio != null) {
                    previewBuilder.setTargetAspectRatio(aspectRatio!!)
                }
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(surfaceProvider(executor(activity), sensor.deviceId ?: "$index"))
                }
                useCaseGroupBuilder.addUseCase(preview)
                previews!!.add(preview)

                if (currentCaptureMode == CaptureModes.PHOTO) {
                    val imageCapture = ImageCapture.Builder()
                        .apply {
                            if (rational.denominator != rational.numerator) {
                                setTargetAspectRatio(aspectRatio ?: AspectRatio.RATIO_4_3)
                            }
                            setFlashMode(
                                if (isFirst) when (flashMode) {
                                    FlashMode.ALWAYS, FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                    else -> ImageCapture.FLASH_MODE_OFF
                                } else ImageCapture.FLASH_MODE_OFF
                            )
                        }.build()
                    useCaseGroupBuilder.addUseCase(imageCapture)
                    imageCaptures.add(imageCapture)
                } else {
                    val videoCapture = buildVideoCapture(videoOptions)
                    useCaseGroupBuilder.addUseCase(videoCapture)
                    videoCaptures[sensor] = videoCapture
                }

                if (isFirst && enableImageStream && imageAnalysisBuilder != null) {
                    imageAnalysis = imageAnalysisBuilder!!.build()
                    useCaseGroupBuilder.addUseCase(imageAnalysis!!)
                } else {
                    imageAnalysis = null
                }

                isFirst = false
                useCaseGroupBuilder.setViewPort(ViewPort.Builder(rational, Surface.ROTATION_0).build())
                singleCameraConfigs.add(
                    ConcurrentCamera.SingleCameraConfig(
                        cameraSelector,
                        useCaseGroupBuilder.build(),
                        activity as LifecycleOwner
                    )
                )
            }

            cameraProvider.unbindAll()
            previewCamera = null
            concurrentCamera = cameraProvider.bindToLifecycle(singleCameraConfigs)
            concurrentCamera!!.cameras.first().cameraControl.enableTorch(flashMode == FlashMode.ALWAYS)
        } else {
            val useCaseGroupBuilder = UseCaseGroup.Builder()
            val cameraSelector = if (sensors.first().position == PigeonSensorPosition.FRONT) 
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            if (currentCaptureMode != CaptureModes.ANALYSIS_ONLY) {
                val previewBuilder = Preview.Builder()
                if (aspectRatio != null) {
                    previewBuilder.setTargetAspectRatio(aspectRatio!!)
                }
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(surfaceProvider(executor(activity), sensors.first().deviceId ?: "0"))
                }
                useCaseGroupBuilder.addUseCase(preview)
                previews!!.add(preview)
            }

            if (currentCaptureMode == CaptureModes.PHOTO) {
                val imageCapture = ImageCapture.Builder()
                    .apply {
                        if (rational.denominator != rational.numerator) {
                            setTargetAspectRatio(aspectRatio ?: AspectRatio.RATIO_4_3)
                        }
                        setFlashMode(
                            when (flashMode) {
                                FlashMode.ALWAYS, FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        )
                    }.build()
                useCaseGroupBuilder.addUseCase(imageCapture)
                imageCaptures.add(imageCapture)
            } else if (currentCaptureMode == CaptureModes.VIDEO) {
                val videoCapture = buildVideoCapture(videoOptions)
                useCaseGroupBuilder.addUseCase(videoCapture)
                videoCaptures[sensors.first()] = videoCapture
            }

            val addAnalysisUseCase = enableImageStream && imageAnalysisBuilder != null
            val cameraLevel = CameraCapabilities.getCameraLevel(cameraSelector, cameraProvider)
            cameraProvider.unbindAll()
            if (addAnalysisUseCase) {
                if (currentCaptureMode == CaptureModes.VIDEO && cameraLevel < CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) {
                    Log.w(CamerawesomePlugin.TAG, "Trying to bind too many use cases for this device (level $cameraLevel), ignoring image analysis")
                } else {
                    imageAnalysis = imageAnalysisBuilder!!.build()
                    useCaseGroupBuilder.addUseCase(imageAnalysis!!)
                }
            } else {
                imageAnalysis = null
            }

            useCaseGroupBuilder.setViewPort(ViewPort.Builder(rational, Surface.ROTATION_0).build())
            concurrentCamera = null
            previewCamera = cameraProvider.bindToLifecycle(
                activity as LifecycleOwner,
                cameraSelector,
                useCaseGroupBuilder.build()
            )
            previewCamera!!.cameraControl.enableTorch(flashMode == FlashMode.ALWAYS)
        }
    }

    private fun buildVideoCapture(videoOptions: AndroidVideoOptions?): VideoCapture<Recorder> {
        val recorderBuilder = Recorder.Builder()
        if (videoRecordingQuality != null) {
            val quality = when (videoRecordingQuality) {
                VideoRecordingQuality.LOWEST -> Quality.LOWEST
                VideoRecordingQuality.SD -> Quality.SD
                VideoRecordingQuality.HD -> Quality.HD
                VideoRecordingQuality.FHD -> Quality.FHD
                VideoRecordingQuality.UHD -> Quality.UHD
                else -> Quality.HIGHEST
            }
            recorderBuilder.setQualitySelector(
                QualitySelector.from(
                    quality,
                    if (videoOptions?.fallbackStrategy == QualityFallbackStrategy.LOWER) 
                        FallbackStrategy.lowerQualityOrHigherThan(quality)
                    else FallbackStrategy.higherQualityOrLowerThan(quality)
                )
            )
        }
        if (videoOptions?.bitrate != null) {
            recorderBuilder.setTargetVideoEncodingBitRate(videoOptions.bitrate.toInt())
        }
        val recorder = recorderBuilder.build()
        return VideoCapture.Builder(recorder)
            .setMirrorMode(if (mirrorFrontCamera) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            .build()
    }

    @SuppressLint("RestrictedApi")
    private fun surfaceProvider(executor: Executor, cameraId: String): Preview.SurfaceProvider {
        return Preview.SurfaceProvider { request: SurfaceRequest ->
            val resolution = request.resolution
            val texture = textureEntries[cameraId]!!.surfaceTexture()
            texture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(texture)
            request.provideSurface(surface, executor) {
                surface.release()
            }
        }
    }

    fun setLinearZoom(zoom: Float) {
        mainCameraControl.setLinearZoom(zoom)
    }

    fun startFocusAndMetering(autoFocusAction: FocusMeteringAction) {
        mainCameraControl.startFocusAndMetering(autoFocusAction)
    }

    fun setCaptureMode(captureMode: CaptureModes) {
        currentCaptureMode = captureMode
        when (currentCaptureMode) {
            CaptureModes.PHOTO -> {
                videoCaptures.clear()
                recordings?.forEach { it.close() }
                recordings = null
            }
            CaptureModes.VIDEO -> {
                imageCaptures.clear()
            }
            else -> {
                videoCaptures.clear()
                recordings?.forEach { it.close() }
                recordings = null
                imageCaptures.clear()
            }
        }
    }

    @SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
    fun previewSizes(): List<Size> {
        val characteristics = CameraCharacteristicsCompat.toCameraCharacteristicsCompat(
            Camera2CameraInfo.extractCameraCharacteristics(mainCameraInfos),
            Camera2CameraInfo.from(mainCameraInfos).cameraId
        )
        return CamcorderProfileResolutionQuirk(characteristics).supportedResolutions
    }

    fun qualityAvailableSizes(): List<String> {
        val supportedQualities = QualitySelector.getSupportedQualities(mainCameraInfos)
        return supportedQualities.map {
            when (it) {
                Quality.UHD -> "UHD"
                Quality.HIGHEST -> "HIGHEST"
                Quality.FHD -> "FHD"
                Quality.HD -> "HD"
                Quality.LOWEST -> "LOWEST"
                Quality.SD -> "SD"
                else -> "unknown"
            }
        }
    }

    fun stop() {
        cameraProvider.unbindAll()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        val previous = imageAnalysisBuilder?.previewStreamSink
        imageAnalysisBuilder?.previewStreamSink = events
        if (previous == null && events != null) {
            onStreamReady(this)
        }
    }

    override fun onCancel(arguments: Any?) {
        imageAnalysisBuilder?.previewStreamSink?.endOfStream()
        imageAnalysisBuilder?.previewStreamSink = null
    }

    override fun onOrientationChanged(orientation: Int) {
        imageAnalysis?.targetRotation = when (orientation) {
            in 225 until 315 -> Surface.ROTATION_90
            in 135 until 225 -> Surface.ROTATION_180
            in 45 until 135 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    fun updateAspectRatio(newAspectRatio: String) {
        aspectRatio = if (newAspectRatio == "RATIO_16_9") 1 else 0
        rational = when (newAspectRatio) {
            "RATIO_16_9" -> Rational(9, 16)
            "RATIO_1_1" -> Rational(1, 1)
            else -> Rational(3, 4)
        }
    }
}
