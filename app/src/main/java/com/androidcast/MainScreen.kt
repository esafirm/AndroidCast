package com.androidcast

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(castViewModel: CastViewModel) {
    val context = LocalContext.current

    val deviceList = castViewModel.deviceList
    val deviceState = castViewModel.deviceState
    val currentDevice = castViewModel.currentDevice

    val playerState = castViewModel.playerState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        val focusRequester = remember { FocusRequester() }
        var textUrl by remember { mutableStateOf("") }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            enabled = deviceState == CastDeviceState.SEARCHING,
            value = textUrl,
            placeholder = { Text(text = "Url to play") },
            onValueChange = {
                textUrl = it
            },
        )

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()

            val cbString = stringFromClipBoard(context)
            if (cbString.startsWith("http")) {
                textUrl = cbString
            }
        }

        Text(
            text = "Device state: $deviceState",
            style = MaterialTheme.typography.bodySmall
        )

        when (deviceState) {
            // Don't render anything if the device state is initial
            CastDeviceState.INITIAL -> Unit

            CastDeviceState.CONNECTED -> {
                ConnectedSection(castViewModel, currentDevice, playerState)
            }

            CastDeviceState.CONNECTING -> {
                ConnectingSection(currentDevice)
            }

            CastDeviceState.SEARCHING -> {
                SearchingSection(
                    castViewModel = castViewModel,
                    deviceList = deviceList,
                    textUrl = textUrl,
                )
            }
        }

    }
}

@Composable
private fun ColumnScope.SearchingSection(
    castViewModel: CastViewModel,
    deviceList: List<Device>,
    textUrl: String
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .semantics(mergeDescendants = true) {}
                .padding(10.dp)
        )

        deviceList.forEach {
            DeviceItem(
                onClick = {
                    if (textUrl.isNotEmpty()) {
                        castViewModel.connect(textUrl, it)
                    }
                },
                device = it,
                icon = {
                    when (it) {
                        is ChromeCastDevice -> Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = null
                        )

                        is SamsungDevice -> Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectingSection(
    currentDevice: Device?
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        if (currentDevice != null) {
            DeviceItem(device = currentDevice, icon = {
                when (currentDevice) {
                    is ChromeCastDevice -> Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null
                    )

                    is SamsungDevice -> Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null
                    )
                }
            }, loading = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.ConnectedSection(
    castViewModel: CastViewModel,
    currentDevice: Device?,
    playerState: PlayerState
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(vertical = 16.dp)
    ) {

        if (currentDevice != null) {
            DeviceItem(device = currentDevice, icon = {
                when (currentDevice) {
                    is ChromeCastDevice -> Icon(
                        imageVector = Icons.Default.Cast,
                        contentDescription = null
                    )

                    is SamsungDevice -> Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null
                    )
                }
            })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        val isPlaying = castViewModel.isPlaying

        var currentTime by castViewModel.currentTime
        val duration by castViewModel.duration

        val playerReady = playerState == PlayerState.READY

        val startInteractionSource = remember { MutableInteractionSource() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                modifier = Modifier.then(if (!playerReady) Modifier.alpha(.5f) else Modifier),
                text = currentTime.toTimeString(),
                style = MaterialTheme.typography.bodySmall
            )

            if (!playerReady) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(6.dp)
                )
            } else {
                Slider(modifier = Modifier
                    .height(6.dp)
                    .weight(1f)
                    .padding(6.dp),
                    value = currentTime.toFloat(),
                    onValueChange = { currentTime = it.toInt() },
                    valueRange = 0f..duration.toFloat(),
                    onValueChangeFinished = {
                        castViewModel.seek(currentTime)
                    },
                    thumb = {
                        SliderDefaults.Thumb(
                            interactionSource = startInteractionSource,
                            thumbSize = DpSize(16.dp, 20.dp)
                        )
                    },
                    track = {
                        SliderDefaults.Track(
                            colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.secondary),
                            sliderPositions = it
                        )
                    }
                )
            }

            Text(
                modifier = Modifier.then(if (!playerReady) Modifier.alpha(.5f) else Modifier),
                text = duration.toTimeString(),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            IconButton(enabled = playerReady, onClick = {
                castViewModel.rewind()
            }) {
                Icon(
                    Icons.Filled.FastRewind,
                    contentDescription = "Localized description"
                )
            }

            IconButton(enabled = playerReady, onClick = {
                if (isPlaying) {
                    castViewModel.pause()
                } else {
                    castViewModel.play()
                }
            }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Localized description"
                )
            }

            IconButton(enabled = playerReady, onClick = {
                castViewModel.forward()
            }) {
                Icon(
                    Icons.Filled.FastForward,
                    contentDescription = "Localized description"
                )
            }

            IconButton(onClick = {
                castViewModel.stop()
                castViewModel.startSearch()
            }) {
                Icon(Icons.Filled.Close, contentDescription = "Localized description")
            }
        }
    }
}

@Composable
fun DeviceItem(
    onClick: (() -> Unit)? = null,
    device: Device,
    icon: @Composable (() -> Unit),
    loading: Boolean = false
) {
    ListItem(modifier = Modifier.clickable { onClick?.invoke() },
        headlineContent = {
            Text(text = device.name ?: "Unknown")
        },
        overlineContent = {
            Text(text = device.description ?: "Unknown")
        },
        leadingContent = {
            icon()
        },
        trailingContent = {
            if (loading)
                CircularProgressIndicator()
        }
    )
}

private fun Int.toTimeString(): String {
    val timeInSeconds = this / 1000

    val hour = timeInSeconds / (60 * 60) % 24
    val minutes = (timeInSeconds / 60) % 60
    val seconds = timeInSeconds % 60

    return "%02d:%02d:%02d".format(hour, minutes, seconds)
}

private fun stringFromClipBoard(context: Context): String {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipText = clipboardManager.primaryClip?.getItemAt(0)?.text

    return if (clipText.isNullOrEmpty()) "" else clipText.toString()
}
