package com.androidcast

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.samsung.multiscreen.Error
import com.samsung.multiscreen.Player
import com.samsung.multiscreen.Search
import com.samsung.multiscreen.Service
import com.samsung.multiscreen.VideoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class CastDeviceState {
    INITIAL,
    SEARCHING,
    CONNECTING,
    CONNECTED
}

enum class PlayerState {
    IDLE,
    READY,
    BUFFERING
}

open class Device(val id: String, val name: String?, val description: String?, val enable: Boolean)
data class SamsungDevice(val service: Service) :
    Device(
        service.id,
        service.name,
        service.type + " - " + service.version,
        !service.isStandbyService
    )

data class ChromeCastDevice(val route: MediaRouter.RouteInfo) :
    Device(
        route.id,
        route.name,
        route.description,
        route.isEnabled
    )

class CastViewModel(
    private val search: Search,
    private val castContext: CastContext,
    private var mediaRouter: MediaRouter,
) : ViewModel() {

    private var urlToPlay by mutableStateOf("")

    var deviceState by mutableStateOf(CastDeviceState.INITIAL)
    val deviceList = mutableStateListOf<Device>()
    var currentDevice by mutableStateOf<Device?>(null)

    var playerState by mutableStateOf(PlayerState.IDLE)

    var isPlaying by mutableStateOf(true)

    var playingMedia by mutableStateOf<String?>(null)

    val duration = mutableIntStateOf(0)
    val currentTime = mutableIntStateOf(0)
    val progress = mutableFloatStateOf(0f)

    private var videoPlayer by mutableStateOf<VideoPlayer?>(null)
    private var remoteMediaClient by mutableStateOf<RemoteMediaClient?>(null)

    private val viewModelCallback: MediaRouter.Callback = mediaRouterCallBack()
    private val viewModelClientCallback: RemoteMediaClient.Callback = mediaClientCallback()

    init {
        search.setOnServiceFoundListener(serviceFound())
        search.setOnServiceLostListener(serviceLost())

        castContext.addCastStateListener(castStateListener())

        startSearch()
    }

    private fun serviceFound() = Search.OnServiceFoundListener { service ->
        println(service.name)

        if (!deviceList.any { it.id == service.id } && !service.isStandbyService)
            deviceList.add(SamsungDevice(service = service))
    }

    private fun serviceLost() = Search.OnServiceLostListener { service ->
        println(service.name)

        val deviceById = deviceList.find { it.id == service.id }
        deviceList.remove(deviceById)
    }

    fun startSearch() {
        if (deviceState == CastDeviceState.SEARCHING) {
            log("Requested to start search but already searching…")
            return
        }
        log("Starting search…")

        search.start()

        val selector = MediaRouteSelector.Builder()
            // These are the framework-supported intents
            // .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
            // .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()

        // Load initial routes
        log("Adding initial routes…")
        for (route in mediaRouter.routes) {
            log("  -> Checking route: ${route.name}")
            if (route.matchesSelector(selector)) {
                log("  -> Adding route: ${route.name}")
                deviceList.add(ChromeCastDevice(route))
            }
        }

        // Scan to search for new routes
        mediaRouter.addCallback(
            selector,
            viewModelCallback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        )

        deviceState = CastDeviceState.SEARCHING
    }

    fun stopSearch() {
        search.stop()
        mediaRouter.removeCallback(viewModelCallback)
    }

    fun connect(url: String, device: Device) {
        urlToPlay = url
        currentDevice = device

        deviceState = CastDeviceState.CONNECTING
        stopSearch()

        when (device) {
            is SamsungDevice -> {
                videoPlayer = device.service.createVideoPlayer("AndroidCast")
                videoPlayer?.addOnMessageListener(videoPlayerListener())
                videoPlayer?.playContent(
                    Uri.parse(urlToPlay),
                    "AndroidCast",
                    Uri.parse(""),
                    object : com.samsung.multiscreen.Result<Boolean> {
                        override fun onSuccess(p0: Boolean?) {
                            //result(true, null)
                            deviceState = CastDeviceState.CONNECTED
                        }

                        override fun onError(p0: Error?) {
                            startSearch()
                        }

                    })
            }

            is ChromeCastDevice -> {
                mediaRouter.selectRoute(device.route)
            }
        }
    }

    fun play() {
        videoPlayer?.play()
        remoteMediaClient?.play()
    }

    fun pause() {
        videoPlayer?.pause()
        remoteMediaClient?.pause()
    }

    fun rewind() {
        videoPlayer?.rewind()

        remoteMediaClient?.let {
            val rewindTime = it.approximateStreamPosition - 10000
            val mso = MediaSeekOptions.Builder().setPosition(rewindTime).build()
            it.seek(mso)
        }
    }

    fun forward() {
        videoPlayer?.forward()

        remoteMediaClient?.let {
            val forwardTime = it.approximateStreamPosition + 10000
            val mso = MediaSeekOptions.Builder().setPosition(forwardTime).build()
            it.seek(mso)
        }
    }

    fun seek(time: Int) {
        videoPlayer?.seekTo(time, TimeUnit.MILLISECONDS)

        remoteMediaClient?.let {
            val mso = MediaSeekOptions.Builder().setPosition(time.toLong()).build()
            it.seek(mso)
        }
    }

    fun stop() {
        videoPlayer?.stop()
        videoPlayer?.disconnect()

        remoteMediaClient?.stop()
        mediaRouter.unselect(MediaRouter.UNSELECT_REASON_STOPPED)

        startSearch()

        resetAll()
    }

    private fun resetAll() {
        videoPlayer = null
        remoteMediaClient = null

        playerState = PlayerState.IDLE
        isPlaying = false

        duration.intValue = 0
        currentTime.intValue = 0
        progress.floatValue = 0f
    }

    private fun videoPlayerListener() = object : VideoPlayer.OnVideoPlayerListener {
        override fun onBufferingStart() {
            println("onBufferingStart")
            //onBuffering(true)
            playerState = PlayerState.BUFFERING

        }

        override fun onBufferingComplete() {
            println("onBufferingComplete")
            playerState = PlayerState.READY
        }

        override fun onBufferingProgress(progress: Int) {
        }

        override fun onCurrentPlayTime(currentProgress: Int) {
            println("onCurrentPlayTime")
            if (currentTime.intValue != currentProgress) {
                currentTime.intValue = currentProgress

                if (currentProgress > 0) {

                    val current = currentProgress.toFloat()
                    val dur = duration.intValue.toFloat()

                    println((current / dur) * 100)

                    progress.floatValue = (current / dur)
                }
            }
        }

        override fun onStreamingStarted(dur: Int) {
            println("onStreamingStarted $dur")
            duration.intValue = dur
            playerState = PlayerState.READY
        }

        override fun onStreamCompleted() {
            println("onStreamingStarted")
            videoPlayer?.stop()
        }

        override fun onPlayerInitialized() {
            println("onPlayerInitialized")
        }

        override fun onPlayerChange(p0: String?) {
            println("onPlayerChange")
        }

        override fun onPlay() {
            println("onPlay")
            isPlaying = true
        }

        override fun onPause() {
            println("onPause")
            isPlaying = false
        }

        override fun onStop() {
            println("onStop")
            isPlaying = false
        }

        override fun onForward() {
        }

        override fun onRewind() {
        }

        override fun onMute() {
        }

        override fun onUnMute() {
        }

        override fun onNext() {
        }

        override fun onPrevious() {
        }

        override fun onControlStatus(p0: Int, p1: Boolean?, p2: Player.RepeatMode?) {
        }

        override fun onVolumeChange(p0: Int) {
        }

        override fun onAddToList(p0: JSONObject?) {
        }

        override fun onRemoveFromList(p0: JSONObject?) {
        }

        override fun onClearList() {
        }

        override fun onGetList(p0: JSONArray?) {
        }

        override fun onRepeat(p0: Player.RepeatMode?) {
        }

        override fun onCurrentPlaying(p0: JSONObject?, p1: String?) {
        }

        override fun onApplicationResume() {
        }

        override fun onApplicationSuspend() {
        }

        override fun onError(p0: Error?) {
        }
    }

    /**
     * Chromecast
     */

    private fun castStateListener() = CastStateListener {
        when (it) {
            CastState.CONNECTED -> {
                deviceState = CastDeviceState.CONNECTED

                val castSession = castContext.sessionManager.currentCastSession

                remoteMediaClient = castSession?.remoteMediaClient

                val client = requireNotNull(remoteMediaClient)

                client.stop()
                client.registerCallback(viewModelClientCallback)

                val queue = listOf(
                    createQueueItem(
                        title = "Track 1",
                        urlToPlay = "https://static-test.bandlab.com/revisions-formatted/0ac20bba-bd82-46bd-aa8f-149d8a6aecd1/0ac20bba-bd82-46bd-aa8f-149d8a6aecd1.m4a"
                    ),
                    createQueueItem(
                        title = "Track 2",
                        urlToPlay = "https://static-test.bandlab.com/revisions-formatted/3f8c76b4-d8d2-4530-a5eb-b8ded473d8b6/3f8c76b4-d8d2-4530-a5eb-b8ded473d8b6.m4a"
                    ),
                    createQueueItem(
                        title = "Track 3",
                        urlToPlay = "https://static-test.bandlab.com/revisions-formatted/6a178df3-06e7-4635-8777-e91a561553cc/6a178df3-06e7-4635-8777-e91a561553cc.m4a"
                    ),
                )

                val mediaLoadRequestData = MediaLoadRequestData.Builder()
                    .setQueueData(
                        MediaQueueData.Builder()
                            .setItems(queue)
                            .build()
                    )
                    .setAutoplay(true)

                client.load(mediaLoadRequestData.build())
                    .setResultCallback { result ->
                        if (result.status.isSuccess) {
                            log("Media loaded!")
                        }
                    }

                stopSearch()
            }

            CastState.NOT_CONNECTED -> {
                remoteMediaClient?.stop()
                remoteMediaClient?.unregisterCallback(viewModelClientCallback)
                remoteMediaClient = null
                startSearch()
            }
        }
    }

    private fun createQueueItem(
        title: String,
        urlToPlay: String,
    ): MediaQueueItem {
        val mediaMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }

        val mediaInfo = MediaInfo.Builder(urlToPlay)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(mediaMetadata)
            .build()

        return MediaQueueItem.Builder(mediaInfo)
            .build()
    }

    private fun mediaRouterCallBack() = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            log("=> Route added: ${route.name}")

            if (!deviceList.any { it.id == route.id } && route.isEnabled)
                deviceList.add(ChromeCastDevice(route = route))
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            log("=> Route removed: ${route.name}")

            val deviceById = deviceList.find { it.id == route.id }
            deviceList.remove(deviceById)
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            val old = deviceList.find { it.id == route.id }
            if (old != null) {
                deviceList[deviceList.indexOf(old)] = ChromeCastDevice(route = route)
                log("=> Route changed: ${route.name}")
            } else {
                log("=> Route changed but it is not in the device list: ${route.name}")
                deviceList.add(ChromeCastDevice(route = route))
            }
        }

        override fun onRouteSelected(
            router: MediaRouter,
            selectedRoute: MediaRouter.RouteInfo,
            reason: Int,
            requestedRoute: MediaRouter.RouteInfo
        ) {
            mediaRouter = router
            log("=> Route selected: ${selectedRoute.name} - Reason: $reason")
        }

        override fun onRouteUnselected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int
        ) {
            super.onRouteUnselected(router, route, reason)
            log("=> Route unselected: ${route.name} - Reason: $reason")
        }
    }

    private fun mediaClientCallback() = object : RemoteMediaClient.Callback() {

        override fun onStatusUpdated() {
            val client = remoteMediaClient ?: return

            isPlaying = client.isPlaying
            playerState = if (client.isBuffering) PlayerState.BUFFERING else PlayerState.READY

            client.addProgressListener({ position, duration ->

                if (duration <= 0) return@addProgressListener

                this@CastViewModel.duration.intValue = duration.toInt()
                this@CastViewModel.currentTime.intValue = position.toInt()

                if (position > 0) {
                    val current = position.toFloat()
                    val dur = duration.toFloat()
                    progress.floatValue = (current / dur)
                }
            }, 500)
        }

        override fun onQueueStatusUpdated() {
            val client = remoteMediaClient ?: return

            if (client.currentItem == null) {
                playingMedia = null
                return
            }
            val title = client.currentItem?.media?.metadata?.getString(MediaMetadata.KEY_TITLE)
                ?: "Unknown Title"
            playingMedia = title
        }
    }
}
