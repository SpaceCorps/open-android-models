package com.spacecorps.oam.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder entry point; the Mira innkeeper sample replaces it. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.placeholder) })
    }
}
