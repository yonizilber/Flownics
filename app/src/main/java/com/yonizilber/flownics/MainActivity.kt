package com.yonizilber.flownics

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import com.yonizilber.flownics.data.transfer.AutoBackup
import com.yonizilber.flownics.navigation.FlownicsApp
import com.yonizilber.flownics.ui.theme.FlownicsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var autoBackup: AutoBackup

    // Bumped each time the workout notification is tapped; the app navigates to the player on change.
    private val openPlayerRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation the same intent comes back; only a fresh launch counts as a request.
        if (savedInstanceState == null) handle(intent)
        setContent {
            // Layout direction follows the app language: Hebrew renders RTL, English LTR.
            FlownicsTheme {
                // No stretch at the end of a list: on some phones (OnePlus/Oppo) the system's edge
                // effect makes Compose lists shake when they reach the end.
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    FlownicsApp(openPlayerRequest = openPlayerRequests.intValue, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    // Leaving the app is when the day's changes are done; a backup then (if due) catches them.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) autoBackup.onAppLeft()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayerRequests.intValue++
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.yonizilber.flownics.OPEN_PLAYER"
    }
}
