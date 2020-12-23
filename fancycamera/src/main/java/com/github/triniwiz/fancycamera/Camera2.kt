package com.github.triniwiz.fancycamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.min


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Camera2 @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : CameraBase(context, attrs, defStyleAttr) {
    private var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: androidx.camera.core.ImageAnalysis? = null
    private var videoCapture: VideoCapture? = null
    private var previewExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysisExecutor = Executors.newSingleThreadExecutor()
    private var imageCaptureExecutor = Executors.newSingleThreadExecutor()
    private var videoCaptureExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceRequest: SurfaceRequest? = null
    private var surface: Surface? = null
    private var isStarted = false
    private var isRecording = false
    private var file: File? = null
    private var isForceStopping = false
    private var mLock = Any()
    private var cameraManager: CameraManager? = null
    private fun handleZoom() {
        camera?.cameraControl?.setLinearZoom(
                zoom
        )
    }

    override var zoom: Float = 0.0F
        set(value) {
            field = when {
                value > 1 -> {
                    1f
                }
                value < 0 -> {
                    0f
                }
                else -> {
                    value
                }
            }
            handleZoom()
        }
    override var whiteBalance: WhiteBalance = WhiteBalance.Auto
        set(value) {
            if (!isRecording) {
                field = value
                refreshCamera()
            }
        }
    override var displayRatio = "4:3"
        set(value) {
            if (value == field) return
            field = when (value) {
                "16:9" -> {
                    value
                }
                "4:3" -> value
                else -> return
            }
            if (!isRecording) {
                refreshCamera()
            }
        }
    override var pictureSize: String = "0x0"
        get() {
            if (field == "0x0") {
                val size = cachedPictureRatioSizeMap[displayRatio]?.get(0)
                if (size != null) {
                    return when (resources.configuration.orientation) {
                        Configuration.ORIENTATION_LANDSCAPE -> "${size.width}x${size.height}"
                        Configuration.ORIENTATION_PORTRAIT -> "${size.height}x${size.width}"
                        else -> field
                    }
                }
            }
            return field
        }
        set(value) {
            val size = stringSizeToSize(value)
            if (cachedPictureRatioSizeMap[displayRatio]?.contains(size) == true) {
                field = value
            }
        }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handleBarcodeScanning(proxy: ImageProxy): Task<Boolean>? {
        if (!isBarcodeScanningSupported || !(detectorType == DetectorType.Barcode || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val BarcodeScannerClazz = Class.forName("com.github.triniwiz.fancycamera.barcodescanning.BarcodeScanner")
        val barcodeScanner = BarcodeScannerClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val BarcodeScannerOptionsClazz = Class.forName("com.github.triniwiz.fancycamera.barcodescanning.BarcodeScanner\$Options")
        val processImageMethod = BarcodeScannerClazz.getDeclaredMethod("processImage", InputImageClazz, BarcodeScannerOptionsClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(barcodeScanner, inputImage, barcodeScannerOptions!!) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onBarcodeScanningListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onBarcodeScanningListener?.onError(it.message
                        ?: "Failed to complete face detection.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handleFaceDetection(proxy: ImageProxy): Task<Boolean>? {
        if (!isFaceDetectionSupported || !(detectorType == DetectorType.Face || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val FaceDetectionClazz = Class.forName("com.github.triniwiz.fancycamera.facedetection.FaceDetection")
        val faceDetection = FaceDetectionClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val FaceDetectionOptionsClazz = Class.forName("com.github.triniwiz.fancycamera.facedetection.FaceDetection\$Options")
        val processImageMethod = FaceDetectionClazz.getDeclaredMethod("processImage", InputImageClazz, FaceDetectionOptionsClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(faceDetection, inputImage, faceDetectionOptions!!) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onFacesDetectedListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onFacesDetectedListener?.onError(it.message
                        ?: "Failed to complete face detection.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handleImageLabeling(proxy: ImageProxy): Task<Boolean>? {
        if (!isImageLabelingSupported || !(detectorType == DetectorType.Image || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val ImageLabelingClazz = Class.forName("com.github.triniwiz.fancycamera.imagelabeling.ImageLabeling")
        val faceDetection = ImageLabelingClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val ImageLabelingOptionsClazz = Class.forName("com.github.triniwiz.fancycamera.imagelabeling.ImageLabeling\$Options")
        val processImageMethod = ImageLabelingClazz.getDeclaredMethod("processImage", InputImageClazz, ImageLabelingOptionsClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(faceDetection, inputImage, imageLabelingOptions!!) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onImageLabelingListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onImageLabelingListener?.onError(it.message
                        ?: "Failed to complete face detection.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handleObjectDetection(proxy: ImageProxy): Task<Boolean>? {
        if (!isObjectDetectionSupported || !(detectorType == DetectorType.Object || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val ObjectDetectionClazz = Class.forName("com.github.triniwiz.fancycamera.objectdetection.ObjectDetection")
        val objectDetection = ObjectDetectionClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val ObjectDetectionOptionsClazz = Class.forName("com.github.triniwiz.fancycamera.objectdetection.ObjectDetection\$Options")
        val processImageMethod = ObjectDetectionClazz.getDeclaredMethod("processImage", InputImageClazz, ObjectDetectionOptionsClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(objectDetection, inputImage, objectDetectionOptions!!) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onObjectDetectedListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onObjectDetectedListener?.onError(it.message
                        ?: "Failed to complete face detection.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handlePoseDetection(proxy: ImageProxy): Task<Boolean>? {
        if (!isPoseDetectionSupported || !(detectorType == DetectorType.Pose || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val PoseDetectionClazz = Class.forName("com.github.triniwiz.fancycamera.posedetection.PoseDetection")
        val poseDetection = PoseDetectionClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val processImageMethod = PoseDetectionClazz.getDeclaredMethod("processImage", InputImageClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(poseDetection, inputImage) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onPoseDetectedListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onPoseDetectedListener?.onError(it.message
                        ?: "Failed to complete text recognition.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun handleTextRecognition(proxy: ImageProxy): Task<Boolean>? {
        if (!isTextRecognitionSupported || !(detectorType == DetectorType.Text || detectorType == DetectorType.All)) {
            return null
        }
        val image = proxy.image ?: return null
        val rotationAngle = proxy.imageInfo.rotationDegrees
        val InputImageClazz = Class.forName("com.google.mlkit.vision.common.InputImage")
        val TextRecognitionClazz = Class.forName("com.github.triniwiz.fancycamera.textrecognition.TextRecognition")
        val textRecognition = TextRecognitionClazz.newInstance()
        val fromMediaImage = InputImageClazz.getMethod("fromMediaImage", Image::class.java, Int::class.java)
        val inputImage = fromMediaImage.invoke(null, image, rotationAngle)
        val processImageMethod = TextRecognitionClazz.getDeclaredMethod("processImage", InputImageClazz)
        val returnTask = TaskCompletionSource<Boolean>()
        val task = processImageMethod.invoke(textRecognition, inputImage) as Task<String>
        task.addOnSuccessListener(imageAnalysisExecutor, {
            if (it.isNotEmpty()) {
                mainHandler.post {
                    onTextRecognitionListener?.onSuccess(it)
                }
            }
        }).addOnFailureListener(imageAnalysisExecutor, {
            mainHandler.post {
                onTextRecognitionListener?.onError(it.message
                        ?: "Failed to complete text recognition.", it)
            }
        }).addOnCompleteListener(imageAnalysisExecutor, {
            returnTask.setResult(true)
        })
        return returnTask.task
    }

    private var lastOrientation = -1
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (newConfig != null) {
            if (lastOrientation != newConfig.orientation) {
                lastOrientation = newConfig.orientation
                val rotation = display.rotation
                Log.d("Camera2", "onConfigurationChanged orientation:${newConfig.orientation} rotation:$rotation")
                val targetRotation = getTargetRotation()
                imageCapture?.targetRotation = targetRotation

                @SuppressLint("UnsafeExperimentalUsageError")
                preview?.targetRotation = (4 - rotation) % 4
            }
        }
    }

    init {
        detectSupport()
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                initSurface()
                listener?.onReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {

            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceRequest?.willNotProvideSurface()
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                onSurfaceUpdateListener?.onUpdate()
            }
        }

        // TODO: Bind this to the view's onCreate method
        cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider?.unbindAll()
                cameraProvider = cameraProviderFuture.get()
                refreshCamera() // or just initPreview() ?
            } catch (e: Exception) {
                listener?.onCameraError("Failed to get camera", e)
                isStarted = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private val surfaceProvider = object: Preview.SurfaceProvider {
        override fun onSurfaceRequested(request: SurfaceRequest) {
            surfaceRequest = request
            initSurface()
        }
    }

    override var allowExifRotation: Boolean = true
    override var autoSquareCrop: Boolean = false
    override var autoFocus: Boolean = false
    override var saveToGallery: Boolean = false
    override var maxAudioBitRate: Int = -1
    override var maxVideoBitrate: Int = -1
    override var maxVideoFrameRate: Int = -1
    override var disableHEVC: Boolean = false
    override var detectorType: DetectorType = DetectorType.None
        set(value) {
            field = value
            if (!isRecording) {
                if (imageAnalysis != null) {
                    if (getTargetRotation() != -1) {
                        imageAnalysis?.targetRotation = getTargetRotation()
                    }
                } else {
                    setUpAnalysis()
                }
                if (value == DetectorType.None) {
                    if (cameraProvider?.isBound(imageAnalysis!!) == true) {
                        cameraProvider?.unbind(imageAnalysis!!)
                    }
                } else {
                    if (cameraProvider?.isBound(imageAnalysis!!) == false) {
                        camera = cameraProvider?.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), imageAnalysis)
                    }
                }
            }
        }

    override val numberOfCameras: Int
        get() {
            if (cameraManager == null) {
                cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
            }
            var count = 0
            try {
                count = cameraManager?.cameraIdList?.size ?: 0
            } catch (e: CameraAccessException) {
            }
            return count
        }

    private fun getFlashMode(): Int {
        return when (flashMode) {
            CameraFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            CameraFlashMode.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    override var position: CameraPosition = CameraPosition.BACK

    private fun selectorFromPosition(): CameraSelector {
        return CameraSelector.Builder()
                .apply {
                    if (position == CameraPosition.FRONT) {
                        requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    } else {
                        requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    }
                }
                .build()
    }

    /** Rotation specified by client (external code)
     * TODO: link this to the code, overriding or affecting targetRotation logic */
    override var rotation: CameraOrientation = CameraOrientation.UNKNOWN

    private fun getDeviceRotation(): Int {
        return when (this.rotation) {
            CameraOrientation.PORTRAIT_UPSIDE_DOWN -> Surface.ROTATION_270
            CameraOrientation.PORTRAIT -> Surface.ROTATION_90
            CameraOrientation.LANDSCAPE_LEFT -> Surface.ROTATION_0
            CameraOrientation.LANDSCAPE_RIGHT -> Surface.ROTATION_180
            else -> -1
        }
    }

    /** Values 0=undefined, 1=portait, 2=landscape */
    private fun getViewOrientation(): Int {
        // https://stackoverflow.com/questions/2795833/check-orientation-on-android-phone
        return if (context is AppCompatActivity)
            context.resources.configuration.orientation
        else
            resources.configuration.orientation
    }

    /** Values -1 is undefiend, 1 = 90 degrees, 2 = 180 degrees, 3 = 270 degrees */
    private fun getViewRotation(): Int {
        // Note: API R ( 30 ) has context.display
        if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val display = context.display
            if (display != null)
                return display.rotation
        } else {
            val windowService = (context as? AppCompatActivity)?.let { it.windowManager }
                ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            return windowService.getDefaultDisplay().getRotation()
        }
        return 0
    }

    /**
     * Calculates the targetRotation provided to CameraX components
     * Values -1 is undefiend, 1 = 90 degrees, 2 = 180 degrees, 3 = 270 degrees */
    private fun getTargetRotation(): Int {
        val viewOrientation = resources.configuration.orientation
        val viewRotation = this.display.rotation

        // Device orientation
        val ori = if (currentOrientation >= 0) currentOrientation / 90 else 0

        // Camera direction
        val cam = when (position) { CameraPosition.BACK -> 1; CameraPosition.FRONT -> -1 else -> 0}

        // Main equation
        val target = (4 + viewRotation * cam) % 4
        // TODO: Allow switching between different equations (depends on how the App is designed)

        Log.d("Camera2", "getDeviceRotation variables: o$ori, c$cam, vo$viewOrientation, vr$viewRotation, t$target")

        return target
    }

    private fun safeUnbindAll() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
        } finally {
            isStarted = false
        }
    }

    override var quality: Quality = Quality.MAX_480P
        set(value) {
            if (!isRecording && field != value) {
                field = value
                safeUnbindAll()
                refreshCamera()
            }
        }
    override var db: Double
        get() {
            return 0.0
        }
        set(value) {}
    override var amplitude: Double
        get() {
            return 0.0
        }
        set(value) {}
    override var amplitudeEMA: Double
        get() {
            return 0.0
        }
        set(value) {}
    override var isAudioLevelsEnabled: Boolean
        get() {
            return false
        }
        set(value) {}


    @SuppressLint("UnsafeExperimentalUsageError")
    private fun setUpAnalysis() {
        val builder = androidx.camera.core.ImageAnalysis.Builder()
                .apply {
                    if (getTargetRotation() != -1) {
                        setTargetRotation(getTargetRotation())
                    }
                    setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
                }
        val extender = Camera2Interop.Extender(builder)
        imageAnalysis = builder.build()
        imageAnalysis?.setAnalyzer(imageAnalysisExecutor, {
            if (it.image != null) {
                val tasks = mutableListOf<Task<*>>()
                //BarcodeScanning
                val barcodeTask = handleBarcodeScanning(it)
                if (barcodeTask != null) {
                    tasks.add(barcodeTask)
                }

                // FaceDetection
                val faceTask = handleFaceDetection(it)
                if (faceTask != null) {
                    tasks.add(faceTask)
                }

                //PoseDetection
                val poseTask = handlePoseDetection(it)
                if (poseTask != null) {
                    tasks.add(poseTask)
                }

                //ImageLabeling
                val imageTask = handleImageLabeling(it)
                if (imageTask != null) {
                    tasks.add(imageTask)
                }

                //ObjectDetection
                val objectTask = handleObjectDetection(it)
                if (objectTask != null) {
                    tasks.add(objectTask)
                }

                // TextRecognition
                val textTask = handleTextRecognition(it)
                if (textTask != null) {
                    tasks.add(textTask)
                }

                if (tasks.isNotEmpty()) {
                    val proxy = it
                    Tasks.whenAllComplete(tasks).addOnCompleteListener {
                        proxy.close()
                    }
                }
            }
        })
    }

    private var cachedPictureRatioSizeMap: MutableMap<String, MutableList<Size>> = HashMap()
    private var cachedPreviewRatioSizeMap: MutableMap<String, MutableList<Size>> = HashMap()

    private fun updateImageCapture() {
        var wasBounded = false
        if (imageCapture != null) {
            wasBounded = cameraProvider?.isBound(imageCapture!!) ?: false
            if (wasBounded) {
                cameraProvider?.unbind(imageCapture)
                imageCapture = null
            }
        }

        val builder = ImageCapture.Builder().apply {
            if (pictureSize == "0x0") {
                setTargetAspectRatio(
                        when (displayRatio) {
                            "16:9" -> AspectRatio.RATIO_16_9
                            else -> AspectRatio.RATIO_4_3
                        }
                )
            } else {
                try {
                    setTargetResolution(
                            android.util.Size.parseSize(pictureSize)
                    )
                } catch (e: Exception) {
                    setTargetAspectRatio(
                            when (displayRatio) {
                                "16:9" -> AspectRatio.RATIO_16_9
                                else -> AspectRatio.RATIO_4_3
                            }
                    )
                }
            }
            setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            if (getTargetRotation() != -1) {
                setTargetRotation(getTargetRotation())
            }
            setFlashMode(getFlashMode())
        }

        val extender = Camera2Interop.Extender(builder)

        when (whiteBalance) {
            WhiteBalance.Auto -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO
                )
            }
            WhiteBalance.Sunny -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                )
            }
            WhiteBalance.Cloudy -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                )
            }
            WhiteBalance.Shadow -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_SHADE
                )
            }
            WhiteBalance.Twilight -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
                )
            }
            WhiteBalance.Fluorescent -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                )
            }
            WhiteBalance.Incandescent -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                )
            }
            WhiteBalance.WarmFluorescent -> {
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
                )
            }
        }

        imageCapture = builder.build()

        if (wasBounded) {
            if (!cameraProvider!!.isBound(imageCapture!!)) {
                cameraProvider?.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), imageCapture!!)
            }
        }
    }
    
    private fun initPreview() {
        preview = Preview.Builder()
                .apply {
                    setTargetAspectRatio(
                            when (displayRatio) {
                                "16:9" -> AspectRatio.RATIO_16_9
                                else -> AspectRatio.RATIO_4_3
                            }
                    )
                    val rotation = display.rotation
                    if (rotation != -1)
                        setTargetRotation((4 - rotation) % 4)
                }
                .build()
                .also {
                    it.setSurfaceProvider(this.surfaceProvider)
                }

        camera = if (detectorType != DetectorType.None && isMLSupported) {
            cameraProvider?.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), preview, imageAnalysis)
        } else {
            cameraProvider?.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), preview)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun initVideoCapture() {
        if (hasAudioPermission()) {
            val profile = getCamcorderProfile(quality)
            val builder = VideoCapture.Builder()
                    .apply {
                        if (getTargetRotation() != -1) {
                            setTargetRotation(getTargetRotation())
                        }
                        setTargetResolution(android.util.Size(profile.videoFrameWidth, profile.videoFrameHeight))
                        setMaxResolution(android.util.Size(profile.videoFrameWidth, profile.videoFrameHeight))

                        var _maxVideoBitrate = profile.videoBitRate
                        if (maxVideoBitrate > -1) {
                            _maxVideoBitrate = maxVideoBitrate
                        }
                        var _maxVideoFrameRate = profile.videoFrameRate
                        if (maxVideoFrameRate > -1) {
                            _maxVideoFrameRate = maxVideoFrameRate
                        }
                        var _maxAudioBitRate = profile.audioBitRate
                        if (maxAudioBitRate > -1) {
                            _maxAudioBitRate = maxAudioBitRate
                        }

                        setAudioBitRate(min(profile.audioBitRate, _maxAudioBitRate))
                        setBitRate(min(profile.videoBitRate, _maxVideoBitrate))
                        setVideoFrameRate(min(profile.videoFrameRate, _maxVideoFrameRate))
                    }
            videoCapture = builder.build()
        }
    }


    @SuppressLint("RestrictedApi", "UnsafeExperimentalUsageError")
    private fun refreshCamera() {
        if (!hasCameraPermission()) return
        cachedPictureRatioSizeMap.clear()
        cachedPreviewRatioSizeMap.clear()

        videoCapture = null
        imageCapture = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        preview?.setSurfaceProvider(null)
        preview = null
        surfaceRequest?.willNotProvideSurface()
        surfaceRequest = null
        safeUnbindAll()

        if (detectorType != DetectorType.None) {
            setUpAnalysis()
        }

        initPreview()

        initVideoCapture()

        handleZoom()

        if (camera?.cameraInfo != null) {
            val streamMap = Camera2CameraInfo.from(camera!!.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            if (streamMap != null) {
                val sizes =
                    streamMap.getOutputSizes(ImageFormat.JPEG) +
                    streamMap.getOutputSizes(SurfaceTexture::class.java)
                for (size in sizes) {
                    val aspect = size.width.toFloat() / size.height.toFloat()
                    var key: String? = null
                    val value = Size(size.width, size.height)
                    when (aspect) {
                        1.0F -> key = "1:1"
                        in 1.2F..1.2222222F -> key = "6:5"
                        in 1.3F..1.3333334F -> key = "4:3"
                        in 1.77F..1.7777778F -> key = "16:9"
                        1.5F -> key = "3:2"
                    }

                    if (key != null) {
                        val list = cachedPictureRatioSizeMap.get(key)
                        if (list == null) {
                            cachedPictureRatioSizeMap.put(key, mutableListOf(value))
                        } else {
                            list.add(value)
                        }
                    }
                }
            }
        }

        updateImageCapture()

        isStarted = true
    }

    private fun initSurface() {
        if (isAvailable && surfaceRequest != null) {
            surfaceTexture!!.setDefaultBufferSize(surfaceRequest!!.resolution.width, surfaceRequest!!.resolution.height)
            surface = Surface(surfaceTexture!!)
            surfaceRequest!!.provideSurface(surface!!, previewExecutor, {
                listener?.onCameraClose()
                surface = null
                isStarted = false
            })
            isStarted = true
            listener?.onCameraOpen()
        }
    }


    override fun startPreview() {
        if (!isStarted) {
            refreshCamera()
        }
    }

    override fun stopPreview() {
        if (isStarted) {
            safeUnbindAll()
        }
    }

    override var flashMode: CameraFlashMode
        get() {
            return CameraFlashMode.OFF
        }
        set(value) {
            if (camera != null) {
                when (value) {
                    CameraFlashMode.OFF -> {
                        camera?.cameraControl?.enableTorch(false)
                        imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                    }
                    CameraFlashMode.ON -> imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                    CameraFlashMode.AUTO -> imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                    CameraFlashMode.RED_EYE -> ImageCapture.FLASH_MODE_ON
                    CameraFlashMode.TORCH -> camera?.cameraControl?.enableTorch(false)
                }
            }
        }

    @SuppressLint("RestrictedApi")
    override fun startRecording() {
        if (!hasAudioPermission() || !hasCameraPermission()) {
            return
        }
        deInitListener()
        val df = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val today = Calendar.getInstance().time
        val fileName = "VID_" + df.format(today) + ".mp4"
        if (saveToGallery && hasStoragePermission()) {
            val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DCIM)
            if (externalDir == null) {
                listener?.onCameraError("Cannot save video to gallery", Exception("Failed to create uri"))
                return
            } else {
                if (!externalDir.exists()) {
                    externalDir.mkdirs()
                }
                file = File(externalDir, fileName)
            }

        } else {
            file = File(context.getExternalFilesDir(null), fileName)
        }

        try {
            if (videoCapture == null) {
                initVideoCapture()
            }
            if (cameraProvider != null) {
                if (cameraProvider!!.isBound(imageCapture!!)) {
                    cameraProvider?.unbind(imageCapture!!)
                }

                if (!cameraProvider!!.isBound(videoCapture!!)) {
                    cameraProvider?.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), videoCapture!!)
                }
            }
            val meta = VideoCapture.Metadata().apply {}

            val options = VideoCapture.OutputFileOptions.Builder(file!!)
            options.setMetadata(meta)
            videoCapture?.startRecording(options.build(), videoCaptureExecutor, object : VideoCapture.OnVideoSavedCallback {
                override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {

                    isRecording = false
                    stopDurationTimer()

                    if (isForceStopping) {
                        if (file != null) {
                            file!!.delete()
                        }
                        ContextCompat.getMainExecutor(context).execute {
                            safeUnbindAll()
                        }
                        synchronized(mLock) {
                            isForceStopping = false
                        }
                    } else {
                        if (saveToGallery && hasStoragePermission()) {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis())
                                // hardcoded video/avc
                                put(MediaStore.MediaColumns.MIME_TYPE, "video/avc")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                    put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                                }

                            }

                            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri == null) {
                                listener?.onCameraError("Failed to add video to gallery", Exception("Failed to create uri"))
                            } else {
                                val fos = context.contentResolver.openOutputStream(uri)
                                val fis = FileInputStream(file!!)
                                fos.use {
                                    if (it != null) {
                                        fis.copyTo(it)
                                        it.flush()
                                        it.close()
                                        fis.close()
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                                    values.clear();
                                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                                    context.contentResolver.update(uri, values, null, null);
                                }
                                listener?.onCameraVideo(file)
                            }

                        } else {
                            listener?.onCameraVideo(file)
                        }
                    }
                }


                override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                    isRecording = false
                    stopDurationTimer()
                    file = null
                    val e = if (cause != null) {
                        Exception(cause)
                    } else {
                        Exception()
                    }
                    listener?.onCameraError(message, e)
                    if (isForceStopping) {
                        ContextCompat.getMainExecutor(context).execute {
                            safeUnbindAll()
                        }

                        synchronized(mLock) {
                            isForceStopping = false
                        }
                    }
                }
            })
            isRecording = true
            startDurationTimer()
            listener?.onCameraVideoStart()
        } catch (e: Exception) {
            isRecording = false
            stopDurationTimer()
            if (file != null) {
                file!!.delete()
            }
            if (cameraProvider != null) {
                if (cameraProvider!!.isBound(videoCapture!!)) {
                    cameraProvider?.unbind(videoCapture!!)
                }
                if (cameraProvider!!.isBound(imageCapture!!)) {
                    cameraProvider?.unbind(imageCapture!!)
                }
            }
            isForceStopping = false
            listener?.onCameraError("Failed to record video.", e)
        }
    }

    @SuppressLint("RestrictedApi")
    override fun stopRecording() {
        videoCapture?.stopRecording()
    }

    override fun takePhoto() {
        val df = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val today = Calendar.getInstance().time
        val fileName = "PIC_" + df.format(today) + ".jpg"
        if (saveToGallery && hasStoragePermission()) {
            val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DCIM)
            if (externalDir == null) {
                listener?.onCameraError("Cannot save photo to gallery storage", Exception("Failed to get external directory"))
                return
            } else {
                if (!externalDir.exists()) {
                    externalDir.mkdirs()
                }
                file = File(externalDir, fileName)
            }

        } else {
            file = File(context.getExternalFilesDir(null), fileName)
        }

        cameraProvider?.let { provider ->
            videoCapture?.let { if (provider.isBound(it)) provider.unbind(it) }

            if (imageCapture == null) updateImageCapture()
            imageCapture?.let { capture ->
                if (!provider.isBound(capture)) {
                    provider.bindToLifecycle(context as LifecycleOwner, selectorFromPosition(), capture)
                }
            } ?: run {
                listener?.onCameraError("Cannot take photo", Exception("imageCapture not set"))
                return
            }
        } ?: run {
            listener?.onCameraError("Cannot take photo", Exception("cameraProvider not set"))
            return
        }

        val useImageProxy = autoSquareCrop || (allowExifRotation == false)
        if (useImageProxy) {
            imageCapture?.takePicture(imageCaptureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processImageProxy(image, fileName)
                }

                override fun onError(exception: ImageCaptureException) {
                    listener?.onCameraError("Failed to take photo image", exception)
                }
            })
        } else {
            val meta = ImageCapture.Metadata().apply {
                isReversedHorizontal = position == CameraPosition.FRONT
            }
            val options = ImageCapture.OutputFileOptions.Builder(file!!)
            options.setMetadata(meta)
            imageCapture?.takePicture(options.build(), imageCaptureExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processImageFile(fileName) // outputFileResults.savedUri.toString() is null
                }

                override fun onError(exception: ImageCaptureException) {
                    listener?.onCameraError("Failed to take photo image", exception)
                }
            })
        }
    }

    private fun processImageProxy(image: ImageProxy, fileName: String) {
        var isError = false
        var outputStream: FileOutputStream? = null
        try {
            val meta = ImageCapture.Metadata().apply {
                isReversedHorizontal = position == CameraPosition.FRONT
            }

            val buffer = image.planes.first().buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val matrix = Matrix()

            // Registering image's required rotation, provided by Androidx ImageAnalysis
            var imageTargetRotation = image.imageInfo.rotationDegrees
            matrix.postRotate(imageTargetRotation.toFloat())
            Log.d("Camera2", "takePhoto: imageTargetRotation=$imageTargetRotation")

            // Flipping over the image in case it is the front camera
            if (position == CameraPosition.FRONT)
                matrix.postScale(-1f, 1f)

            var originalWidth = bm.width
            var originalHeight = bm.height
            var offsetWidth = 0
            var offsetHeight = 0
            if (autoSquareCrop) {
                if (originalWidth < originalHeight) {
                    offsetHeight = (originalHeight - originalWidth) / 2;
                    originalHeight = originalWidth;
                } else {
                    offsetWidth = (originalWidth - originalHeight) / 2;
                    originalWidth = originalHeight;
                }
            }
            Log.d("Camera2", "takePhoto: originalWidth=$originalWidth, originalHeight=$originalHeight")
            val rotated = Bitmap.createBitmap(bm, offsetWidth, offsetHeight, originalWidth, originalHeight, matrix, false);

            outputStream = FileOutputStream(file!!, false)
            rotated.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)

            val exif = ExifInterface(file!!.absolutePath)

            val now = System.currentTimeMillis()
            val datetime = convertToExifDateTime(now)

            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, datetime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, datetime)

            try {
                val subsec = (now - convertFromExifDateTime(datetime).time).toString()
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subsec)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subsec)
            } catch (e: ParseException) {
            }

            exif.rotate(image.imageInfo.rotationDegrees)
            if (meta.isReversedHorizontal) {
                exif.flipHorizontally()
            }
            if (meta.isReversedVertical) {
                exif.flipVertically()
            }
            if (meta.location != null) {
                exif.setGpsInfo(meta.location!!)
            }
            exif.saveAttributes()

            bm.recycle()
            rotated.recycle()
        } catch (e: Exception) {
            isError = true
            listener?.onCameraError("Failed to save photo.", e)
        } finally {
            try {
                outputStream?.close()
            } catch (e: IOException) {
                //NOOP
            }
            try {
                image.close()
            } catch (e: Exception) {

            }
            if (!isError) {
                if (saveToGallery && hasStoragePermission()) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/*")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                        }
                    }

                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri == null) {
                        listener?.onCameraError("Failed to add photo to gallery", Exception("Failed to create uri"))
                    } else {
                        val fos = context.contentResolver.openOutputStream(uri)
                        val fis = FileInputStream(file!!)
                        fos.use {
                            if (it != null) {
                                fis.copyTo(it)
                                it.flush()
                                it.close()
                                fis.close()
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                            values.clear();
                            values.put(MediaStore.Images.Media.IS_PENDING, 0);
                            context.contentResolver.update(uri, values, null, null);
                        }
                        listener?.onCameraPhoto(file)
                    }

                } else {
                    listener?.onCameraPhoto(file)
                }

            }
        }
    }

    private fun processImageFile(fileName: String) {
        // Saving image to user gallery
        if (saveToGallery && hasStoragePermission()) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())

                put(MediaStore.MediaColumns.MIME_TYPE, "image/*")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                listener?.onCameraError("Failed to add photo to gallery", Exception("Failed to create uri"))
            } else {
                val fos = context.contentResolver.openOutputStream(uri)
                val fis = FileInputStream(file!!)
                fos.use {
                    if (it != null) {
                        fis.copyTo(it)
                        it.flush()
                        it.close()
                        fis.close()
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                listener?.onCameraPhoto(file)
            }

        } else {
            listener?.onCameraPhoto(file)
        }
    }

    override fun hasFlash(): Boolean {
        return camera?.cameraInfo?.hasFlashUnit() ?: false
    }

    override fun cameraRecording(): Boolean {
        TODO("Not yet implemented")
    }


    override fun toggleCamera() {
        if (!isRecording) {
            position = when (position) {
                CameraPosition.BACK -> CameraPosition.FRONT
                CameraPosition.FRONT -> CameraPosition.BACK
            }
            safeUnbindAll()
            refreshCamera()
        }
    }

    override fun getSupportedRatios(): Array<String> {
        return cachedPreviewRatioSizeMap.keys.toTypedArray()
    }

    override fun getAvailablePictureSizes(ratio: String): Array<Size> {
        return cachedPictureRatioSizeMap[ratio]?.toTypedArray() ?: arrayOf()
    }

    override fun stop() {
        if (!isForceStopping) {
            if (isRecording) {
                isForceStopping = true
                stopRecording()
            } else {
                safeUnbindAll()
            }
        }
    }


    override fun release() {
        stop()
        if (!isForceStopping) {
            safeUnbindAll()
            preview?.setSurfaceProvider(null)
            preview = null
            imageCapture = null
            videoCapture = null
            imageAnalysis = null
            camera = null
        }
        deInitListener()
    }
}