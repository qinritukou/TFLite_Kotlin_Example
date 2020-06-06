package com.orangeman.fortunetellerapp.tflite

import android.app.Activity
import com.orangeman.fortunetellerapp.tflite.domain.Device
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.ops.NormalizeOp


/** This TensorFlow Lite classifier works with the quantized EfficientNet model.  */
class ClassifierQuantizedEfficientNet
/**
 * Initializes a `ClassifierQuantizedMobileNet`.
 *
 * @param activity
 */
    (activity: Activity?, device: Device?, numThreads: Int) :
    Classifier(activity, device, numThreads) {
    // you can download this file from
    // see build.gradle for where to obtain this file. It should be auto
    // downloaded into assets.
    override val modelPath: String
        protected get() =// you can download this file from
        // see build.gradle for where to obtain this file. It should be auto
            // downloaded into assets.
            "efficientnet-lite0-int8.tflite"

    override val labelPath: String
        protected get() = "labels_without_background.txt"

    override val preprocessNormalizeOp: TensorOperator
        protected get() = NormalizeOp(
            IMAGE_MEAN,
            IMAGE_STD
        )

    override val postprocessNormalizeOp: TensorOperator
        protected get() {
            return NormalizeOp(
                PROBABILITY_MEAN,
                PROBABILITY_STD
            )
        }

    companion object {
        /**
         * The quantized model does not require normalization, thus set mean as 0.0f, and std as 1.0f to
         * bypass the normalization.
         */
        private val IMAGE_MEAN: Float = 0.0f
        private val IMAGE_STD: Float = 1.0f

        /** Quantized MobileNet requires additional dequantization to the output probability.  */
        private val PROBABILITY_MEAN: Float = 0.0f
        private val PROBABILITY_STD: Float = 255.0f
    }
}
