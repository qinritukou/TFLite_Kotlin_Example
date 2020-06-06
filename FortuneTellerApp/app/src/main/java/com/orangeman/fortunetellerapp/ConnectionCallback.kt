package com.orangeman.fortunetellerapp

import android.util.Size


/**
 * Callback for Activities to use to initialize their data once the selected preview size is
 * known.
 */
interface ConnectionCallback {
    fun onPreviewSizeChosen(size: Size?, cameraRotation: Int)
}
