package com.orangeman.fortunetellerapp.tflite

import android.app.Activity
import com.orangeman.fortunetellerapp.tflite.domain.Device
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.ops.NormalizeOp


/** This TensorFlowLite classifier works with the float MobileNet model.  */
class ClassifierFloatMobileNet
/**
 * Initializes a `ClassifierFloatMobileNet`.
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
            "mobilenet_v1_1.0_224.tflite"

    override val labelPath: String
        protected get() = "labels.txt"

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
        /** Float MobileNet requires additional normalization of the used input.  */
        private val IMAGE_MEAN: Float = 127.5f
        private val IMAGE_STD: Float = 127.5f

        /**
         * Float model does not need dequantization in the post-processing. Setting mean and std as 0.0f
         * and 1.0f, repectively, to bypass the normalization.
         */
        private val PROBABILITY_MEAN: Float = 0.0f
        private val PROBABILITY_STD: Float = 1.0f
    }
}
