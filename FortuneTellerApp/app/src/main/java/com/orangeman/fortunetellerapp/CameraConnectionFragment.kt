package com.orangeman.fortunetellerapp


import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.TextUtils
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.orangeman.fortunetellerapp.customview.AutoFitTextureView
import com.orangeman.fortunetellerapp.env.Logger
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


/**
 * Camera Connection Fragment that captures images from camera.
 *
 *
 * Instantiated by newInstance.
 */
@SuppressLint("ValidFragment")
class CameraConnectionFragment @SuppressLint("ValidFragment") private constructor(
    private val cameraConnectionCallback: ConnectionCallback,
    /** A [OnImageAvailableListener] to receive frames as they are available.  */
    private val imageListener: OnImageAvailableListener,
    /** The layout identifier to inflate for this Fragment.  */
    private val layout: Int,
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap).  */
    private val inputSize: Size
) : Fragment() {
    companion object {
        private val LOGGER = Logger()

        /**
         * The camera preview size will be chosen to be the smallest frame by pixel size capable of
         * containing a DESIRED_SIZE x DESIRED_SIZE square.
         */
        private const val MINIMUM_PREVIEW_SIZE = 320

        /** Conversion from screen rotation to JPEG orientation.  */
        private val ORIENTATIONS = SparseIntArray()
        private const val FRAGMENT_DIALOG = "dialog"

        /**
         * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
         * width and height are at least as large as the minimum of both, or an exact match if possible.
         *
         * @param choices The list of sizes that the camera supports for the intended output class
         * @param width The minimum desired width
         * @param height The minimum desired height
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        public fun chooseOptimalSize(
            choices: Array<Size?>,
            width: Int,
            height: Int
        ): Size? {
            val minSize = Math.max(
                Math.min(width, height),
                MINIMUM_PREVIEW_SIZE
            )
            val desiredSize = Size(width, height)

            // Collect the supported resolutions that are at least as big as the preview Surface
            var exactSizeFound = false
            val bigEnough: MutableList<Size?> =
                ArrayList()
            val tooSmall: MutableList<Size?> =
                ArrayList()
            for (option in choices) {
                if (option == desiredSize) {
                    // Set the size but don't return yet so that remaining sizes will still be logged.
                    exactSizeFound = true
                }
                if (option!!.height >= minSize && option.width >= minSize) {
                    bigEnough.add(option)
                } else {
                    tooSmall.add(option)
                }
            }
            LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize)
            LOGGER.i(
                "Valid preview sizes: [" + TextUtils.join(
                    ", ",
                    bigEnough
                ) + "]"
            )
            LOGGER.i(
                "Rejected preview sizes: [" + TextUtils.join(
                    ", ",
                    tooSmall
                ) + "]"
            )
            if (exactSizeFound) {
                LOGGER.i("Exact size match found.")
                return desiredSize
            }

            // Pick the smallest of those, assuming we found any
            return if (bigEnough.size > 0) {
                val chosenSize = Collections.min(bigEnough, CompareSizesByArea())
                LOGGER.i("Chosen size: " + chosenSize!!.width + "x" + chosenSize.height)
                chosenSize
            } else {
                LOGGER.e("Couldn't find any suitable preview size")
                choices[0]
            }
        }

        fun newInstance(
            callback: ConnectionCallback,
            imageListener: OnImageAvailableListener,
            layout: Int,
            inputSize: Size
        ): CameraConnectionFragment {
            return CameraConnectionFragment(callback, imageListener, layout, inputSize)
        }

        init {
            ORIENTATIONS.append(
                Surface.ROTATION_0,
                90
            )
            ORIENTATIONS.append(
                Surface.ROTATION_90,
                0
            )
            ORIENTATIONS.append(
                Surface.ROTATION_180,
                270
            )
            ORIENTATIONS.append(
                Surface.ROTATION_270,
                180
            )
        }
    }

    /** Compares two `Size`s based on their areas.  */
    internal class CompareSizesByArea : Comparator<Size?> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(
                lhs!!.width.toLong() * lhs.height - rhs!!.width.toLong() * rhs.height
            )
        }
    }

    /** A [Semaphore] to prevent the app from exiting before closing the camera.  */
    private val cameraOpenCloseLock =
        Semaphore(1)

    private val captureCallback: CaptureCallback = object : CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
        }
    }

    /** ID of the current [CameraDevice].  */
    private var cameraId: String? = null

    /** An [AutoFitTextureView] for camera preview.  */
    private lateinit var textureView: AutoFitTextureView

    /** A [CameraCaptureSession] for camera preview.  */
    private lateinit var captureSession: CameraCaptureSession

    /** A reference to the opened [CameraDevice].  */
    private lateinit var cameraDevice: CameraDevice

    /** The rotation in degrees of the camera sensor from the display.  */
    private var sensorOrientation: Int? = null

    /** The [Size] of camera preview.  */
    private var previewSize: Size? = null

    /** An additional thread for running tasks that shouldn't block the UI.  */
    private var backgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background.  */
    private var backgroundHandler: Handler? = null

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
     */
    private val surfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture, width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /** An [ImageReader] that handles preview frame capture.  */
    private lateinit var previewReader: ImageReader

    /** [CaptureRequest.Builder] for the camera preview  */
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    /** [CaptureRequest] generated by [.previewRequestBuilder]  */
    private var previewRequest: CaptureRequest? = null

    /** [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.  */
    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cd: CameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            cameraOpenCloseLock.release()
            cameraDevice = cd
            createCameraPreviewSession()
        }

        override fun onDisconnected(cd: CameraDevice) {
            cameraOpenCloseLock.release()
            cd.close()
        }

        override fun onError(cd: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cd.close()
            activity.finish()
        }
    }

    /**
     * Shows a [Toast] on the UI thread.
     *
     * @param text The message to show
     */
    private fun showToast(text: String) {
        val activity = activity
        activity?.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle
    ): View? {
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        textureView = view.findViewById<View>(R.id.texture) as AutoFitTextureView
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener)
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    fun setCamera(cameraId: String?) {
        this.cameraId = cameraId
    }

    /** Sets up member variables related to camera.  */
    private fun setUpCameraOutputs() {
        val activity = activity
        val manager =
            activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize = chooseOptimalSize(
                map!!.getOutputSizes(SurfaceTexture::class.java),
                inputSize.width,
                inputSize.height
            )

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize!!.width, previewSize!!.height)
            } else {
                textureView.setAspectRatio(previewSize!!.height, previewSize!!.width)
            }
        } catch (e: CameraAccessException) {
            LOGGER.e(e, "Exception!")
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.tfe_ic_camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
            throw IllegalStateException(getString(R.string.tfe_ic_camera_error))
        }
        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation!!)
    }

    /** Opens the camera specified by [CameraConnectionFragment.cameraId].  */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun openCamera(width: Int, height: Int) {
        setUpCameraOutputs()
        configureTransform(width, height)
        val activity = activity
        val manager =
            activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            if (ActivityCompat.checkSelfPermission(
                    this.context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            manager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            LOGGER.e(e, "Exception!")
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /** Closes the current [CameraDevice].  */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            if (null != captureSession) {
                captureSession!!.close()
            }
            if (null != cameraDevice) {
                cameraDevice!!.close()
            }
            if (null != previewReader) {
                previewReader!!.close()
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /** Starts a background thread and its [Handler].  */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /** Stops the background thread and its [Handler].  */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }
    }

    /** Creates a new [CameraCaptureSession] for camera preview.  */
    private fun createCameraPreviewSession() {
        try {
            val texture: SurfaceTexture = textureView.getSurfaceTexture()!!

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(surface)
            LOGGER.i("Opening camera preview: " + previewSize!!.width + "x" + previewSize!!.height)

            // Create the reader for the preview frames.
            previewReader = ImageReader.newInstance(
                previewSize!!.width, previewSize!!.height, ImageFormat.YUV_420_888, 2
            )
            previewReader.setOnImageAvailableListener(imageListener, backgroundHandler)
            previewRequestBuilder!!.addTarget(previewReader.getSurface())

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice!!.createCaptureSession(
                Arrays.asList(surface, previewReader.getSurface()),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }

                        // When the session is ready, we start displaying the preview.
                        captureSession = cameraCaptureSession
                        try {
                            // Auto focus should be continuous for camera preview.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Flash is automatically enabled when necessary.
                            previewRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            )

                            // Finally, we start displaying the camera preview.
                            previewRequest = previewRequestBuilder!!.build()
                            captureSession!!.setRepeatingRequest(
                                previewRequest!!, captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            LOGGER.e(e, "Exception!")
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        showToast("Failed")
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            LOGGER.e(e, "Exception!")
        }
    }

    /**
     * Configures the necessary [Matrix] transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == textureView || null == previewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(
            0f, 0f,
            previewSize!!.height.toFloat(),
            previewSize!!.width.toFloat()
        )
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /** Shows an error message dialog.  */
    class ErrorDialog : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            val activity = activity
            return AlertDialog.Builder(activity)
                .setMessage(arguments.getString(ARG_MESSAGE))
                .setPositiveButton(
                    android.R.string.ok
                ) { dialogInterface, i -> activity.finish() }
                .create()
        }

        companion object {
            private const val ARG_MESSAGE = "message"
            fun newInstance(message: String?): ErrorDialog {
                val dialog =
                    ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }
    }

}
