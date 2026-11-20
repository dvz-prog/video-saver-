package com.videosaver.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object JobStore {

    private val counter = AtomicInteger(0)
    val entries: CopyOnWriteArrayList<Job> = CopyOnWriteArrayList()
    private val listeners: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    fun add(url: String, playlist: Boolean, mode: Int = 0): Job {
        val old = entries.firstOrNull { it.url == url && !it.cancelled }
        if (old != null) return old
        val job = Job(
            id = counter.incrementAndGet(),
            url = url,
            playlist = playlist,
            mode = mode
        )
        entries.add(job)
        changed()
        return job
    }

    fun remove(job: Job) {
        entries.remove(job)
        changed()
    }

    fun clearFinished() {
        entries.removeAll { it.status == "Concluído" || it.cancelled }
        changed()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun changed() {
        for (l in listeners) l()
    }
}
