package com.yonizilber.flownics.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Debug builds only: `adb shell am broadcast -a com.yonizilber.flownics.DEMO_SEED -p com.yonizilber.flownics [--ez hebrew true]`
 * fills an empty app with made-up demo data. Guarded by the DUMP permission, which only the shell
 * (adb) holds, and refused when the app already has data. Progress and failures go to logcat
 * (tag FlownicsDemo) and a toast.
 */
@AndroidEntryPoint
class DemoSeedReceiver : BroadcastReceiver() {
    @Inject lateinit var seeder: DemoSeeder

    override fun onReceive(context: Context, intent: Intent) {
        val hebrew = intent.getBooleanExtra("hebrew", false)
        val app = context.applicationContext
        scope.launch {
            val result = runCatching { seeder.seed(hebrew) { Log.i(TAG, "seeding $it") } }
            val message = result.fold({ "DONE" }, { "FAILED: ${it.message}" })
            result.exceptionOrNull()?.let { Log.e(TAG, "demo seed failed", it) }
            Log.i(TAG, message)
            withContext(Dispatchers.Main) { Toast.makeText(app, "Demo data: $message", Toast.LENGTH_LONG).show() }
        }
    }

    private companion object {
        const val TAG = "FlownicsDemo"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
