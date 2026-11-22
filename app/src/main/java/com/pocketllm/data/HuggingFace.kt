package com.pocketllm.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HfRepo(
    val id: String,
    val downloads: Int,
    val likes: Int,
    val gated: Boolean,
    val license: String?,
)

data class HfFile(
    val name: String,
    val sizeMb: Long,
)

data class HfModelDetail(
    val repo: String,
    val gated: Boolean,
    val license: String?,
    val files: List<HfFile>,
)

/**
 * Thin client for the public Hugging Face API. No key needed for public repos;
 * a user token is only used for gated ones (e.g. Gemma).
 */
class HuggingFace {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(query: String, token: String, limit: Int = 30): List<HfRepo> =
        withContext(Dispatchers.IO) {
            val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .addQueryParameter("filter", "gguf")
                .addQueryParameter("sort", "downloads")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", limit.toString())
                .build()

            val body = get(url.toString(), token)
            val arr = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                HfRepo(
                    id = o["id"]?.jsonString() ?: return@mapNotNull null,
                    downloads = o["downloads"]?.jsonLong()?.toInt() ?: 0,
                    likes = o["likes"]?.jsonLong()?.toInt() ?: 0,
                    gated = o.isGated(),
                    license = o.licenseFromTags(),
                )
            }
        }

    suspend fun detail(repo: String, token: String): HfModelDetail = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addPathSegments(repo)
            .addQueryParameter("blobs", "true")
            .build()

        val o = json.parseToJsonElement(get(url.toString(), token)) as? JsonObject
            ?: throw IOException("Unexpected response")

        val files = (o["siblings"] as? JsonArray).orEmpty()
            .mapNotNull { el ->
                val f = el as? JsonObject ?: return@mapNotNull null
                val name = f["rfilename"]?.jsonString() ?: return@mapNotNull null
                val lower = name.lowercase()
                if (!lower.endsWith(".gguf")) return@mapNotNull null
                if (lower.contains("mmproj")) return@mapNotNull null
                // Skip later shards of a split model; the first shard pulls the rest.
                if (Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(lower) &&
                    !lower.contains("-00001-of-")
                ) return@mapNotNull null
                HfFile(name = name, sizeMb = (f["size"]?.jsonLong() ?: 0L) / (1024 * 1024))
            }
            .sortedWith(compareBy({ quantRank(it.name) }, { it.sizeMb }))

        HfModelDetail(
            repo = o["id"]?.jsonString() ?: repo,
            gated = o.isGated(),
            license = o.licenseFromTags(),
            files = files,
        )
    }

    fun downloadUrl(repo: String, file: String): String =
        "https://huggingface.co".toHttpUrl().newBuilder()
            .addPathSegments(repo)
            .addPathSegments("resolve/main")
            .addPathSegments(file)
            .build()
            .toString()

    private fun get(url: String, token: String): String {
        val builder = Request.Builder().url(url).get()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw IOException("This repository needs a Hugging Face token")
            if (response.code == 404) throw IOException("Not found")
            if (!response.isSuccessful) throw IOException("Hugging Face error ${response.code}")
            return response.body.string()
        }
    }

    private fun quantRank(name: String): Int {
        val upper = name.uppercase()
        return when {
            upper.contains("Q4_K_M") -> 0
            upper.contains("Q4_K_S") -> 1
            upper.contains("Q5_K_M") -> 2
            upper.contains("Q3_K_M") -> 3
            upper.contains("Q6_K") -> 4
            upper.contains("Q8_0") -> 5
            upper.contains("Q2_K") -> 6
            else -> 7
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.jsonString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement?.jsonLong(): Long? =
    (this as? JsonPrimitive)?.longOrNull

private fun JsonObject.isGated(): Boolean {
    val v = this["gated"] ?: return false
    val p = v as? JsonPrimitive ?: return false
    return if (p.isString) p.content != "false" else p.booleanOrNull ?: false
}

private fun JsonObject.licenseFromTags(): String? {
    val tags = this["tags"] as? JsonArray ?: return null
    return tags.mapNotNull { it.jsonString() }
        .firstOrNull { it.startsWith("license:") }
        ?.removePrefix("license:")
}
