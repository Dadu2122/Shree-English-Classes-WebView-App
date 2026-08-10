package com.shreeyog.shreeenglishclasses

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    private val siteUrl = "https://dadu2122.github.io/Shree-English-Classes/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Open the site in a Chrome Custom Tab instead of a plain WebView.
        // Custom Tabs use the device's real Chrome engine, so microphone
        // access for Agora live classes works exactly like it does in Chrome.
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(this, Uri.parse(siteUrl))

        // Close this wrapper activity once the tab is launched so the back
        // button exits cleanly instead of leaving a blank screen behind.
        finish()
    }
}
