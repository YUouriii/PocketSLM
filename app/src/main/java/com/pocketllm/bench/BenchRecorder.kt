package com.pocketllm.bench

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Append-only JSONL benchmark results in the app's external files directory so
 * they can be pulled with adb and inspected independently of logcat.
 */
object BenchRecorder {

    fun resultsFile(context: Context): File =
        File(context.getExternalFilesDir(null), "bench/results.jsonl")

    fun metaFile(context: Context): File =
        File(context.getExternalFilesDir(null), "bench/meta.json")

    fun start(context: Context, meta: JSONObject) {
        val dir = resultsFile(context).parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        resultsFile(context).writeText("")
        metaFile(context).writeText(meta.toString(2))
    }

    fun append(context: Context, record: JSONObject) {
        resultsFile(context).appendText(record.toString() + "\n")
    }

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun progress(values: List<Triple<Int, Long, Long>>): JSONArray {
        val arr = JSONArray()
        for ((tok, ram, hwm) in values) {
            arr.put(JSONObject().put("token", tok).put("rssMb", ram).put("hwmMb", hwm))
        }
        return arr
    }
}
