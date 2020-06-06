package com.orangeman.fortunetellerapp.customview.domain

import android.graphics.RectF


/** An immutable result returned by a Classifier describing what was recognized.  */
class Recognition(
    /**
     * A unique identifier for what has been recognized. Specific to the class, not the instance of
     * the object.
     */
    val id: String,
    /** Display name for the recognition.  */
    val title: String,
    /**
     * A sortable score for how good the recognition is relative to others. Higher should be better.
     */
    val confidence: Float,
    /** Optional location within the source image for the location of the recognized object.  */
    var location: RectF?
) {
    override fun toString(): String {
        var resultString = ""
        resultString += "[$id] "
        resultString += "$title "
        resultString += String.format("(%.1f%%) ", confidence * 100.0f)
        resultString += location.toString() + " "

        return resultString.trim { it <= ' ' }
    }

}
