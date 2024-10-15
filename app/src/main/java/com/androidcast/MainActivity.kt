package com.androidcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.media.MediaRouter
import com.samsung.multiscreen.Service
import com.androidcast.ui.theme.AndroidCastTheme
import com.google.android.gms.cast.framework.CastContext

class MainActivity : ComponentActivity() {

    private lateinit var castViewModel: CastViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val service = Service.search(this)
        val castContext = CastContext.getSharedInstance(this)
        val mediaRouter = MediaRouter.getInstance(this)

        VolumeRouter(
            context = this,
            lifecycle = lifecycle,
            mediaRouter = mediaRouter,
        )
        castViewModel = CastViewModel(service, castContext, mediaRouter)

        setContent {
            AndroidCastTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(castViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startSearchIfNotConnected()
    }

    private fun startSearchIfNotConnected() {
        if (castViewModel.deviceState != CastDeviceState.CONNECTED) {
            castViewModel.startSearch()
        }
    }

    override fun onStop() {
        super.onStop()

        castViewModel.stopSearch()
    }
}
