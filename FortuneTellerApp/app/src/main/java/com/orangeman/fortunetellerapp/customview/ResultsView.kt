package com.orangeman.fortunetellerapp.customview

import com.orangeman.fortunetellerapp.customview.domain.Recognition

interface ResultsView {
    fun setResults(results: List<Recognition>)
}
