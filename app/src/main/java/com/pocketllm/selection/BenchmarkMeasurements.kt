package com.pocketllm.selection

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads measurements the app itself already wrote to its own files dir.
 * Host-only benchmark logs are never imported.
 */
class BenchmarkMeasurements(private val context: Context) : MeasurementProvider {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cachedStamp: Long = Long.MIN_VALUE

    @Volatile
    private var cachedRecords: List<MeasurementRecord> = emptyList()

    override fun records(): List<MeasurementRecord> {
        val dir = runCatching { File(context.getExternalFilesDir(null), "bench") }.getOrNull() ?: return emptyList()
        val metaFile = File(dir, "meta.json")
        val results = File(dir, "results.jsonl")
        val stamp = metaFile.lastModified() * 31 + results.lastModified()
        if (stamp == cachedStamp) return cachedRecords
        val meta = readObject(metaFile) ?: return emptyList()
        if (!results.isFile) return emptyList()
        val parsed = runCatching {
            results.readLines().mapNotNull { line ->
                val record = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                val gpuLayers = int(record, "gpuLayers") ?: int(meta, "gpuLayers")
                MeasurementRecord(
                    device = string(record, "device") ?: string(meta, "device"),
                    abi = string(record, "abi") ?: string(meta, "abi"),
                    modelPath = string(record, "modelPath") ?: string(meta, "modelPath"),
                    contextSize = int(record, "ctx") ?: int(meta, "ctx"),
                    kvQuant = string(record, "kv") ?: string(meta, "kv"),
                    threads = int(record, "threads") ?: int(meta, "threads"),
                    backend = if ((gpuLayers ?: 0) > 0) Backend.VULKAN else Backend.CPU,
                    tokensPerSecond = double(record, "tokPerSec"),
                    gpuLayers = gpuLayers,
                    status = string(record, "status"),
                    rssMb = long(record, "rssMb"),
                    hwmMb = long(record, "hwmMb"),
                    loadMs = long(record, "loadMs"),
                    ttftMs = long(record, "ttftMs"),
                    quantization = string(record, "quant") ?: string(meta, "quant"),
                )
            }
        }.getOrDefault(emptyList())
        cachedRecords = parsed
        cachedStamp = stamp
        return parsed
    }

    private fun readObject(file: File): JsonObject? = runCatching {
        if (!file.isFile) return null
        json.parseToJsonElement(file.readText()) as? JsonObject
    }.getOrNull()

    private fun string(obj: JsonObject, key: String): String? =
        runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()

    private fun int(obj: JsonObject, key: String): Int? =
        runCatching { string(obj, key)?.toIntOrNull() }.getOrNull()

    private fun double(obj: JsonObject, key: String): Double? =
        runCatching { string(obj, key)?.toDoubleOrNull() }.getOrNull()

    private fun long(obj: JsonObject, key: String): Long? =
        runCatching { string(obj, key)?.toLongOrNull() }.getOrNull()
}
