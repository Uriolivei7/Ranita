package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class PlayhubStreamWish : ExtractorApi() {
    override val name = "PlayhubStreamWish"
    override val mainUrl = "https://vibuxer.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "$mainUrl/",
            "Accept-Language" to "es",
            "Origin" to mainUrl,
        )

        val response = app.get(url, headers = headers)

        val doc = response.document
        val scripts = doc.select("script")
        val script = scripts.find { it.data().contains(".m3u8") }?.data()
            ?: scripts.find { it.data().contains("jwplayer") }?.data()
            ?: scripts.find { it.data().contains("eval(") }?.data()

        if (script != null) {
            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val m3u8Url = m3u8Regex.find(script)?.groupValues?.getOrNull(1)
            if (m3u8Url != null) {
                callback.invoke(
                    newExtractorLink(
                        "StreamWish",
                        "StreamWish",
                        m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
                return
            }

            val evalPattern = Regex("""eval\((.*)\)""")
            val evalMatch = evalPattern.find(script)
            if (evalMatch != null) {
                try {
                    val evalContent = evalMatch.groupValues[1]
                    val params = evalContent.split(",")
                    if (params.size >= 6) {
                        val p = params[0].trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                        val a = params[1].trim().toIntOrNull() ?: 36
                        val c = params[2].trim().toIntOrNull() ?: 0
                        val kList = params.drop(3).map {
                            it.trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                        }.take(c)
                        val decoded = decodeEval(p, a, c, kList)
                        val decodedM3u8 = m3u8Regex.find(decoded)?.groupValues?.getOrNull(1)
                        if (decodedM3u8 != null) {
                            callback.invoke(
                                newExtractorLink(
                                    "StreamWish",
                                    "StreamWish",
                                    decodedM3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            return
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun decodeEval(p: String, a: Int, c: Int, k: List<String>): String {
        var decoded = p
        for (i in k.indices.reversed()) {
            if (k[i].isBlank()) continue
            val regex = Regex("\\b${i.toString(a)}\\b")
            decoded = decoded.replace(regex, k[i])
        }
        return decoded
    }
}

class PlayhubVidHide : ExtractorApi() {
    override val name = "PlayhubVidHide"
    override val mainUrl = "https://callistanise.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "$mainUrl/",
            "Accept-Language" to "es",
            "Origin" to mainUrl,
        )

        val response = app.get(url, headers = headers)

        val doc = response.document
        val scripts = doc.select("script")
        val script = scripts.find { it.data().contains(".m3u8") }?.data()
            ?: scripts.find { it.data().contains("jwplayer") }?.data()
            ?: scripts.find { it.data().contains("eval(") }?.data()

        if (script != null) {
            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val m3u8Url = m3u8Regex.find(script)?.groupValues?.getOrNull(1)
            if (m3u8Url != null) {
                callback.invoke(
                    newExtractorLink(
                        "VidHide",
                        "VidHide",
                        m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                    }
                )
                return
            }

            val evalPattern = Regex("""eval\((.*)\)""")
            val evalMatch = evalPattern.find(script)
            if (evalMatch != null) {
                try {
                    val evalContent = evalMatch.groupValues[1]
                    val params = evalContent.split(",")
                    if (params.size >= 6) {
                        val p = params[0].trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                        val a = params[1].trim().toIntOrNull() ?: 36
                        val c = params[2].trim().toIntOrNull() ?: 0
                        val kList = params.drop(3).map {
                            it.trim().removeSurrounding("'", "'").removeSurrounding("\"", "\"")
                        }.take(c)
                        val decoded = decodeEval(p, a, c, kList)
                        val decodedM3u8 = m3u8Regex.find(decoded)?.groupValues?.getOrNull(1)
                        if (decodedM3u8 != null) {
                            callback.invoke(
                                newExtractorLink(
                                    "VidHide",
                                    "VidHide",
                                    decodedM3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                }
                            )
                            return
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun decodeEval(p: String, a: Int, c: Int, k: List<String>): String {
        var decoded = p
        for (i in k.indices.reversed()) {
            if (k[i].isBlank()) continue
            val regex = Regex("\\b${i.toString(a)}\\b")
            decoded = decoded.replace(regex, k[i])
        }
        return decoded
    }
}
