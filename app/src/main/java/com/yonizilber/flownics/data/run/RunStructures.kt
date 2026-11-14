package com.yonizilber.flownics.data.run

import android.content.Context
import com.yonizilber.flownics.data.local.db.dao.RunStructureDao
import com.yonizilber.flownics.data.local.db.entity.RunStructureEntity
import com.yonizilber.flownics.domain.fit.FitDecoder
import com.yonizilber.flownics.domain.fit.FitException
import com.yonizilber.flownics.domain.model.Activity
import com.yonizilber.flownics.domain.model.ActivityType
import com.yonizilber.flownics.domain.run.RunAnalysis
import com.yonizilber.flownics.domain.run.RunSample
import com.yonizilber.flownics.domain.run.RunStructure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The structure of each recorded run, walk or hike (RunAnalysis), kept so it's made once per
 * recording and algorithm version. Only activities whose FIT file is on the phone have one.
 */
@Singleton
class RunStructures @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RunStructureDao,
    private val json: Json,
) {
    private val mutex = Mutex()

    fun applies(a: Activity): Boolean =
        a.type in TYPES && a.externalId != null && file(a.id).exists()

    /** Made now if missing or from an older version; null when the activity has no recording to read. */
    suspend fun get(a: Activity): RunStructure? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!applies(a)) return@withContext null
            val ext = a.externalId!!
            dao.get(a.id)?.takeIf { it.version == RunAnalysis.VERSION && it.externalId == ext }?.let { row ->
                try {
                    return@withContext json.decodeFromString(RunStructure.serializer(), row.json)
                } catch (_: SerializationException) {
                    // Made again below.
                }
            }
            val structure = try {
                RunAnalysis.analyze(FitDecoder.decode(file(a.id).readBytes()))
            } catch (_: FitException) {
                null
            } ?: return@withContext null
            dao.put(RunStructureEntity(a.id, ext, RunAnalysis.VERSION, json.encodeToString(RunStructure.serializer(), structure)))
            structure
        }
    }

    /** The recording's readings, for looking closer at a stretch. */
    suspend fun samples(a: Activity): List<RunSample>? = withContext(Dispatchers.IO) {
        if (!applies(a)) return@withContext null
        try {
            RunAnalysis.fromFit(FitDecoder.decode(file(a.id).readBytes())).first
        } catch (_: FitException) {
            null
        }
    }

    /** Every run with a recording gets its structure (after an import, or when the algorithm changed). */
    suspend fun backfill(all: List<Activity>) {
        all.filter(::applies).forEach { get(it) }
    }

    suspend fun forget(activityId: Long) = dao.delete(activityId)

    private fun file(id: Long) = File(context.filesDir, "fit/$id.fit")

    private companion object {
        val TYPES = setOf(ActivityType.RUN, ActivityType.WALK, ActivityType.HIKE)
    }
}
