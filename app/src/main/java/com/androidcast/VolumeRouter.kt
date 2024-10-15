package com.androidcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VolumeRouter(
    context: Context,
    lifecycle: Lifecycle,
    private val mediaRouter: MediaRouter,
) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var selectedRoute: MediaRouter.RouteInfo? = null

    init {
        setupMediaRouterCallback(lifecycle)

        lifecycle.coroutineScope.launch {
            context.musicVolumeFlow.collectLatest {
                syncVolumeToRoute()
            }
        }
    }

    private fun setupMediaRouterCallback(lifecycle: Lifecycle) {
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()

        // Initial setup
        selectedRoute = mediaRouter.selectedRoute

        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                selectedRoute = route
                syncVolumeToRoute()
            }

            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                selectedRoute = null
            }
        }

        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaRouter.addCallback(
                    selector,
                    callback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                )
            }
            lifecycle.repeatOnLifecycle(Lifecycle.State.DESTROYED) {
                mediaRouter.removeCallback(callback)
            }
        }
    }

    fun syncVolumeToRoute() {
        val deviceVolume = audioManager.musicVolume()
        val maxDeviceVolume = audioManager.musicMaxVolume()
        val routeVolume = (deviceVolume.toFloat() / maxDeviceVolume * 100).toInt()
        selectedRoute?.requestSetVolume(routeVolume)
    }

    fun syncVolumeToDevice() {
        selectedRoute?.let { route ->
            val routeVolume = route.volume
            val maxDeviceVolume = audioManager.musicMaxVolume()
            val deviceVolume = (routeVolume.toFloat() / 100 * maxDeviceVolume).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, deviceVolume, 0)
        }
    }

    private fun AudioManager.musicVolume() =
        getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun AudioManager.musicMaxVolume() =
        getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val Context.musicVolumeFlow
        get() = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", 0)) {
                        AudioManager.STREAM_MUSIC -> trySend(
                            intent.getIntExtra(
                                "android.media.EXTRA_VOLUME_STREAM_VALUE",
                                0
                            )
                        )
                    }
                }
            }
            registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
            awaitClose { unregisterReceiver(receiver) }
        }
}
