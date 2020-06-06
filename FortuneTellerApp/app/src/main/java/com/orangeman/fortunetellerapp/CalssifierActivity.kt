package com.orangeman.fortunetellerapp

import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ImageReader.OnImageAvailableListener
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import com.orangeman.fortunetellerapp.customview.domain.Recognition
import com.orangeman.fortunetellerapp.env.BorderedText
import com.orangeman.fortunetellerapp.env.Logger
import com.orangeman.fortunetellerapp.tflite.Classifier
import com.orangeman.fortunetellerapp.tflite.domain.Device
import com.orangeman.fortunetellerapp.tflite.domain.Model
import java.io.IOException


class ClassifierActivity : CameraActivity(), OnImageAvailableListener {
    private var rgbFrameBitmap: Bitmap? = null
    private var lastProcessingTimeMs: Long = 0
    private var sensorOrientation: Int = 0
    private var classifier: Classifier? = null
    private lateinit var borderedText: BorderedText

    /** Input image size of the model along x axis.  */
    private var imageSizeX = 0

    /** Input image size of the model along y axis.  */
    private var imageSizeY = 0
    override fun getLayoutId(): Int {
        return R.layout.camera_connection_fragment
    }

    override fun getDesiredPreviewFrameSize(): Size {
        return DESIRED_PREVIEW_SIZE
    }

    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            TEXT_SIZE_DIP,
            resources.displayMetrics
        )
        borderedText = BorderedText(textSizePx)
        borderedText.setTypeface(Typeface.MONOSPACE)
        recreateClassifier(getModel(), getDevice(), getNumThreads())
        if (classifier == null) {
            LOGGER.e("No classifier on preview!")
            return
        }
        previewWidth = size.width
        previewHeight = size.height
        sensorOrientation = rotation - screenOrientation
        LOGGER.i(
            "Camera orientation relative to screen canvas: %d",
            sensorOrientation
        )
        LOGGER.i(
            "Initializing at size %dx%d",
            previewWidth,
            previewHeight
        )
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
    }

    override fun processImage() {
        rgbFrameBitmap!!.setPixels(
            getRgbBytes(),
            0,
            previewWidth,
            0,
            0,
            previewWidth,
            previewHeight
        )
        val cropSize = Math.min(previewWidth, previewHeight)
        runInBackground(
            Runnable {
                if (classifier != null) {
                    val startTime = SystemClock.uptimeMillis()
                    val results: List<Recognition> =
                        classifier!!.recognizeImage(rgbFrameBitmap!!, sensorOrientation)
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
                    LOGGER.v("Detect: %s", results)
                    runOnUiThread {
                        showResultsInBottomSheet(results)
                        showFrameInfo(previewWidth.toString() + "x" + previewHeight)
                        showCropInfo(imageSizeX.toString() + "x" + imageSizeY)
                        showCameraResolution(cropSize.toString() + "x" + cropSize)
                        showRotationInfo(sensorOrientation.toString())
                        showInference(lastProcessingTimeMs.toString() + "ms")
                    }
                }
                readyForNextImage()
            })
    }

    override fun onInferenceConfigurationChanged() {
        if (rgbFrameBitmap == null) {
            // Defer creation until we're getting camera frames.
            return
        }
        val device = getDevice()
        val model = getModel()
        val numThreads = getNumThreads()
        runInBackground(Runnable {
            recreateClassifier(model, device, numThreads)
        })

    }

    private fun recreateClassifier(model: Model, device: Device, numThreads: Int) {
        if (classifier != null) {
            LOGGER.d("Closing classifier.")
            classifier!!.close()
            classifier = null
        }
        if (device === Device.GPU
            && (model === Model.QUANTIZED_MOBILENET || model === Model.QUANTIZED_EFFICIENTNET)
        ) {
            LOGGER.d("Not creating classifier: GPU doesn't support quantized models.")
            runOnUiThread {
                Toast.makeText(this, R.string.tfe_ic_gpu_quant_error, Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            LOGGER.d(
                "Creating classifier (model=%s, device=%s, numThreads=%d)",
                model,
                device,
                numThreads
            )
            classifier = Classifier.create(this, model, device, numThreads)
        } catch (e: IOException) {
            LOGGER.e(e, "Failed to create classifier.")
        }

        // Updates the input image size.
        imageSizeX = classifier!!.imageSizeX
        imageSizeY = classifier!!.imageSizeY
    }

    companion object {
        private val LOGGER: Logger = Logger()
        private val DESIRED_PREVIEW_SIZE = Size(640, 480)
        private const val TEXT_SIZE_DIP = 10f
    }
}
