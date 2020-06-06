package com.orangeman.fortunetellerapp

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowManager
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.orangeman.fortunetellerapp.customview.domain.Recognition
import com.orangeman.fortunetellerapp.env.ImageUtils
import com.orangeman.fortunetellerapp.env.Logger
import com.orangeman.fortunetellerapp.tflite.domain.Device
import com.orangeman.fortunetellerapp.tflite.domain.Model


abstract class CameraActivity : AppCompatActivity(), OnImageAvailableListener,
    PreviewCallback, View.OnClickListener, OnItemSelectedListener {
    protected var previewWidth = 0
    protected var previewHeight = 0
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private var useCamera2API = false
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    protected var luminanceStride = 0
        private set
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private lateinit var bottomSheetLayout: LinearLayout
    private lateinit var gestureLayout: LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    protected lateinit var recognitionTextView: TextView
    protected lateinit var recognition1TextView: TextView
    protected lateinit var recognition2TextView: TextView
    protected lateinit var recognitionValueTextView: TextView
    protected lateinit var recognition1ValueTextView: TextView
    protected lateinit var recognition2ValueTextView: TextView
    protected lateinit var frameValueTextView: TextView
    protected lateinit var cropValueTextView: TextView
    protected lateinit var cameraResolutionTextView: TextView
    protected lateinit var rotationTextView: TextView
    protected lateinit var inferenceTimeTextView: TextView
    protected lateinit var bottomSheetArrowImageView: ImageView
    private lateinit var plusImageView: ImageView
    private lateinit var minusImageView: ImageView
    private lateinit var modelSpinner: Spinner
    private lateinit var deviceSpinner: Spinner
    private lateinit var threadsTextView: TextView
    private var model = Model.QUANTIZED_EFFICIENTNET
    private var device = Device.CPU
    private var numThreads = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        LOGGER.d("onCreate $this")
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)
        if (hasPermission()) {
            setFragment()
        } else {
            requestPermission()
        }
        threadsTextView = findViewById(R.id.threads)
        plusImageView = findViewById(R.id.plus)
        minusImageView = findViewById(R.id.minus)
        modelSpinner = findViewById(R.id.model_spinner)
        deviceSpinner = findViewById(R.id.device_spinner)
        bottomSheetLayout = findViewById(R.id.bottom_sheet_layout)
        gestureLayout = findViewById(R.id.gesture_layout)
        sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow)
        val vto = gestureLayout.getViewTreeObserver()
        vto.addOnGlobalLayoutListener(
            object : OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                        gestureLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this)
                    } else {
                        gestureLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this)
                    }
                    //                int width = bottomSheetLayout.getMeasuredWidth();
                    val height = gestureLayout.getMeasuredHeight()
                    sheetBehavior!!.peekHeight = height
                }
            })
        sheetBehavior!!.isHideable = false
        sheetBehavior!!.setBottomSheetCallback(
            object : BottomSheetCallback() {
                override fun onStateChanged(
                    bottomSheet: View,
                    newState: Int
                ) {
                    when (newState) {
                        BottomSheetBehavior.STATE_HIDDEN -> {
                        }
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_down)
                        }
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up)
                        }
                        BottomSheetBehavior.STATE_DRAGGING -> {
                        }
                        BottomSheetBehavior.STATE_SETTLING -> bottomSheetArrowImageView.setImageResource(
                            R.drawable.icn_chevron_up
                        )
                    }
                }

                override fun onSlide(
                    bottomSheet: View,
                    slideOffset: Float
                ) {
                }
            })
        recognitionTextView = findViewById(R.id.detected_item)
        recognitionValueTextView = findViewById(R.id.detected_item_value)
        recognition1TextView = findViewById(R.id.detected_item1)
        recognition1ValueTextView = findViewById(R.id.detected_item1_value)
        recognition2TextView = findViewById(R.id.detected_item2)
        recognition2ValueTextView = findViewById(R.id.detected_item2_value)
        frameValueTextView = findViewById(R.id.frame_info)
        cropValueTextView = findViewById(R.id.crop_info)
        cameraResolutionTextView = findViewById(R.id.view_info)
        rotationTextView = findViewById(R.id.rotation_info)
        inferenceTimeTextView = findViewById(R.id.inference_info)
        modelSpinner.setOnItemSelectedListener(this)
        deviceSpinner.setOnItemSelectedListener(this)
        plusImageView.setOnClickListener(this)
        minusImageView.setOnClickListener(this)
        model = Model.valueOf(modelSpinner.getSelectedItem().toString().toUpperCase())
        device = Device.valueOf(deviceSpinner.getSelectedItem().toString())
        numThreads = threadsTextView.getText().toString().trim { it <= ' ' }.toInt()
    }

    protected fun getRgbBytes(): IntArray? {
        imageConverter!!.run()
        return rgbBytes
    }

    protected val luminance: ByteArray?
        protected get() = yuvBytes[0]

    /** Callback for android.hardware.Camera API  */
    override fun onPreviewFrame(
        bytes: ByteArray,
        camera: Camera
    ) {
        if (isProcessingFrame) {
            LOGGER.w("Dropping frame!")
            return
        }
        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                val previewSize =
                    camera.parameters.previewSize
                previewHeight = previewSize.height
                previewWidth = previewSize.width
                rgbBytes = IntArray(previewWidth * previewHeight)
                onPreviewSizeChosen(Size(previewSize.width, previewSize.height), 90)
            }
        } catch (e: Exception) {
            LOGGER.e(e, "Exception!")
            return
        }
        isProcessingFrame = true
        yuvBytes[0] = bytes
        luminanceStride = previewWidth
        imageConverter = Runnable {
            ImageUtils.convertYUV420SPToARGB8888(
                bytes,
                previewWidth,
                previewHeight,
                rgbBytes!!
            )
        }
        postInferenceCallback = Runnable {
            camera.addCallbackBuffer(bytes)
            isProcessingFrame = false
        }
        processImage()
    }

    /** Callback for Camera2 API  */
    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            Trace.beginSection("imageAvailable")
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            luminanceStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = object : Runnable {
                override fun run() {
                    ImageUtils.convertYUV420ToARGB8888(
                        yuvBytes[0]!!,
                        yuvBytes[1]!!,
                        yuvBytes[2]!!,
                        previewWidth,
                        previewHeight,
                        luminanceStride,
                        uvRowStride,
                        uvPixelStride,
                        rgbBytes!!
                    )
                }
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()
        } catch (e: Exception) {
            LOGGER.e(e, "Exception!")
            Trace.endSection()
            return
        }
        Trace.endSection()
    }

    @Synchronized
    public override fun onStart() {
        LOGGER.d("onStart $this")
        super.onStart()
    }

    @Synchronized
    public override fun onResume() {
        LOGGER.d("onResume $this")
        super.onResume()
        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    @Synchronized
    public override fun onPause() {
        LOGGER.d("onPause $this")
        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }
        super.onPause()
    }

    @Synchronized
    public override fun onStop() {
        LOGGER.d("onStop $this")
        super.onStop()
    }

    @Synchronized
    public override fun onDestroy() {
        LOGGER.d("onDestroy $this")
        super.onDestroy()
    }

    @Synchronized
    protected fun runInBackground(r: Runnable?) {
        if (handler != null) {
            handler!!.post(r)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionsGranted(grantResults)) {
                setFragment()
            } else {
                requestPermission()
            }
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                    this@CameraActivity,
                    "Camera permission is required for this demo",
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            requestPermissions(
                arrayOf(PERMISSION_CAMERA),
                PERMISSIONS_REQUEST
            )
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private fun isHardwareLevelSupported(
        characteristics: CameraCharacteristics, requiredLevel: Int
    ): Boolean {
        val deviceLevel =
            characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)!!
        return if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            requiredLevel == deviceLevel
        } else requiredLevel <= deviceLevel
        // deviceLevel is not LEGACY, can use numerical sort
    }

    private fun chooseCamera(): String? {
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics =
                    manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API = (facing == CameraCharacteristics.LENS_FACING_EXTERNAL
                        || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                ))
                LOGGER.i("Camera API lv2?: %s", useCamera2API)
                return cameraId
            }
        } catch (e: CameraAccessException) {
            LOGGER.e(e, "Not allowed to access camera")
        }
        return null
    }

    protected open fun setFragment() {
        val cameraId = chooseCamera()
        val fragment: Fragment
        if (useCamera2API) {
            val camera2Fragment = CameraConnectionFragment.newInstance(
                object : ConnectionCallback {
                    override fun onPreviewSizeChosen(size: Size?, cameraRotation: Int) {
                        fun onPreviewSizeChosen(
                            size: Size,
                            rotation: Int
                        ) {
                            previewHeight = size.height
                            previewWidth = size.width
                            this@CameraActivity.onPreviewSizeChosen(size, rotation)
                        }
                    }
                },
                this,
                getLayoutId(),
                getDesiredPreviewFrameSize()
            )

            camera2Fragment.setCamera(cameraId)
            fragment = camera2Fragment
        } else {
            fragment = LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize())
        }
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    protected fun fillBytes(
        planes: Array<Plane>,
        yuvBytes: Array<ByteArray?>
    ) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                LOGGER.d(
                    "Initializing buffer %d at size %d",
                    i,
                    buffer.capacity()
                )
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    protected fun readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback!!.run()
        }
    }

    protected val screenOrientation: Int
        protected get() = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }

    @UiThread
    protected fun showResultsInBottomSheet(results: List<Recognition>) {
        if (results != null && results.size >= 3) {
            val recognition = results[0]
            if (recognition != null) {
                if (recognition.title != null) recognitionTextView.setText(recognition.title)
                if (recognition.confidence != null) recognitionValueTextView!!.text =
                    java.lang.String.format(
                        "%.2f",
                        100 * recognition.confidence
                    ) + "%"
            }
            val recognition1 = results[1]
            if (recognition1 != null) {
                if (recognition1.title != null) recognition1TextView.setText(recognition1.title)
                if (recognition1.confidence != null) recognition1ValueTextView!!.text =
                    java.lang.String.format(
                        "%.2f",
                        100 * recognition1.confidence
                    ) + "%"
            }
            val recognition2 = results[2]
            if (recognition2 != null) {
                if (recognition2.title != null) recognition2TextView.setText(recognition2.title)
                if (recognition2.confidence != null) recognition2ValueTextView!!.text =
                    java.lang.String.format(
                        "%.2f",
                        100 * recognition2.confidence
                    ) + "%"
            }
        }
    }

    protected fun showFrameInfo(frameInfo: String?) {
        frameValueTextView!!.text = frameInfo
    }

    protected fun showCropInfo(cropInfo: String?) {
        cropValueTextView!!.text = cropInfo
    }

    protected fun showCameraResolution(cameraInfo: String?) {
        cameraResolutionTextView!!.text = cameraInfo
    }

    protected fun showRotationInfo(rotation: String?) {
        rotationTextView!!.text = rotation
    }

    protected fun showInference(inferenceTime: String?) {
        inferenceTimeTextView!!.text = inferenceTime
    }

    protected fun getModel(): Model {
        return model
    }

    private fun setModel(model: Model) {
        if (this.model !== model) {
            LOGGER.d("Updating  model: $model")
            this.model = model
            onInferenceConfigurationChanged()
        }
    }

    protected fun getDevice(): Device {
        return device
    }

    private fun setDevice(device: Device) {
        if (this.device !== device) {
            LOGGER.d("Updating  device: $device")
            this.device = device
            val threadsEnabled = device === Device.CPU
            plusImageView!!.isEnabled = threadsEnabled
            minusImageView!!.isEnabled = threadsEnabled
            threadsTextView!!.text = if (threadsEnabled) numThreads.toString() else "N/A"
            onInferenceConfigurationChanged()
        }
    }

    protected fun getNumThreads(): Int {
        return numThreads
    }

    private fun setNumThreads(numThreads: Int) {
        if (this.numThreads != numThreads) {
            LOGGER.d("Updating  numThreads: $numThreads")
            this.numThreads = numThreads
            onInferenceConfigurationChanged()
        }
    }

    protected abstract fun processImage()

    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)

    protected abstract fun getLayoutId(): Int

    protected abstract fun getDesiredPreviewFrameSize(): Size

    protected abstract fun onInferenceConfigurationChanged()

    override fun onClick(v: View) {
        if (v.id == R.id.plus) {
            val threads = threadsTextView!!.text.toString().trim { it <= ' ' }
            var numThreads = threads.toInt()
            if (numThreads >= 9) return
            setNumThreads(++numThreads)
            threadsTextView!!.text = numThreads.toString()
        } else if (v.id == R.id.minus) {
            val threads = threadsTextView!!.text.toString().trim { it <= ' ' }
            var numThreads = threads.toInt()
            if (numThreads == 1) {
                return
            }
            setNumThreads(--numThreads)
            threadsTextView!!.text = numThreads.toString()
        }
    }

    override fun onItemSelected(
        parent: AdapterView<*>,
        view: View,
        pos: Int,
        id: Long
    ) {
        if (parent === modelSpinner) {
            setModel(Model.valueOf(parent.getItemAtPosition(pos).toString().toUpperCase()))
        } else if (parent === deviceSpinner) {
            setDevice(Device.valueOf(parent.getItemAtPosition(pos).toString()))
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // Do nothing.
    }

    companion object {
        private val LOGGER: Logger = Logger()
        private const val PERMISSIONS_REQUEST = 1
        private const val PERMISSION_CAMERA = Manifest.permission.CAMERA
        private fun allPermissionsGranted(grantResults: IntArray): Boolean {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }
    }
}
