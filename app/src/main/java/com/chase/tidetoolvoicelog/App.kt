package com.chase.tidetoolvoicelog

import android.app.Application
import com.chase.tidetoolvoicelog.call.CallPrompt
import com.chase.tidetoolvoicelog.data.Prefs
import com.chase.tidetoolvoicelog.data.Repo

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = Repo(this)
        // a transcription that was in flight when the process died would stay 'working'
        // forever; put it back in the queue visibly instead
        runCatching { repo.resetStaleTranscriptions() }
        CallPrompt.createChannels(this)
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var prefs: Prefs
            private set

        lateinit var repo: Repo
            private set
    }
}
