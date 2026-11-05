package com.example

/*
    CEO: Ranita
    Empresa: RANITA TECH
    Versión software: 1.0     fecha: 09/08/2025
*/

import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class Cubeembed : ExtractorApi() {
    override var name = "Cubeembed"
    override var mainUrl = "https://cubeembed.rpmvid.com"
    override val requiresReferer = true

    companion object {
        var pluginContext: Context? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Cubeembed", "resolve start host=${try { URL(url).host } catch (_: Exception) { "unknown" }}")
        val turboResult = resolveTurboEmbed(url)
        if (turboResult != null && turboResult.streamUrl.isNotBlank()) {
            Log.d("Cubeembed", "TurboEmbed JW source found host=${try { URL(turboResult.streamUrl).host } catch (_: Exception) { "unknown" }}")
            callback(newExtractorLink(this.name, "$name [TurboEmbed]", turboResult.streamUrl, ExtractorLinkType.M3U8) {
                this.referer = turboResult.pageUrl
                this.quality = Qualities.Unknown.value
            })
            return
        }
        Log.w("Cubeembed", "TurboEmbed WebView terminó sin fuente JW; intento extractor API legado")

        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseUrl = getBaseUrl(url)
        Log.d("Cubeembed", "hash=$hash baseUrl=$baseUrl")

        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val apiResponse = app.get("$baseUrl/api/v1/video?id=$hash", headers = headers)
        val encrypted = apiResponse.text.trim()
        Log.d("Cubeembed", "Legacy API HTTP ${apiResponse.code} contentType=${apiResponse.headers["content-type"]} responseLength=${encrypted.length}")
        if (encrypted.isEmpty()) {
            Log.w("Cubeembed", "API devolvió cuerpo vacío para id=$hash")
            return
        }

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                decryptAES(encrypted, key, iv)
            } catch (e: Exception) {
                Log.w("Cubeembed", "Decrypt failed with IV=$iv: ${e.message}")
                null
            }
        } ?: throw Exception("Failed to decrypt with all IVs")

        Log.d("Cubeembed", "Decrypted payload length=${decryptedText.length}")

        val source = Regex("\"source\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        Log.d("Cubeembed", "parsed source=${source.isNotBlank()} sourceLength=${source.length}")

        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)
        subtitleSection?.let { section ->
            Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"").findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$baseUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        if (source.isNotEmpty()) {
            val sourceHttp = source.replaceFirst("https://", "http://")
            Log.d("Cubeembed", "Source M3U8 (HTTP): $sourceHttp")
            callback.invoke(
                newExtractorLink(this.name, "$name ", sourceHttp, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d("Cubeembed", "emitted source M3U8")
        }
        val hlsTiktok = Regex("\"hlsVideoTiktok\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        Log.d("Cubeembed", "parsed hlsVideoTiktok=${hlsTiktok.isNotBlank()} pathLength=${hlsTiktok.length}")
        if (hlsTiktok.isNotEmpty()) {
            val tiktokUrl = "$baseUrl$hlsTiktok"
            Log.d("Cubeembed", "TikTok M3U8: $tiktokUrl")
            callback.invoke(
                newExtractorLink(this.name, "$name TK", tiktokUrl, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d("Cubeembed", "emitted TikTok M3U8")
        }
        if (source.isBlank() && hlsTiktok.isBlank()) {
            Log.w("Cubeembed", "payload descifrado sin sources reconocidos; no se emitieron enlaces")
        }
    }

    private suspend fun resolveTurboEmbed(pageUrl: String): TurboEmbedResolved? = withTimeoutOrNull(35_000L) {
        cubeEmbedWebViewMutex.withLock {
            withContext(Dispatchers.Main) {
                val appContext = pluginContext?.applicationContext ?: return@withContext null
                val handler = Handler(Looper.getMainLooper())
                val result = CompletableDeferred<TurboEmbedResolved?>()
                val webView = WebView(appContext)
                var attempts = 0

                fun poll() {
                    if (result.isCompleted) return
                    attempts++
                    val script = """(function(){try{
                        var p=window.jwplayer&&window.jwplayer('media-player');
                        if(p&&p.getPlaylistItem){var it=p.getPlaylistItem();var f=it&&(it.file||(it.sources&&it.sources[0]&&it.sources[0].file));
                          if(f&&/^https?:/i.test(f)){NativeBridge.onStream(window.location.href,f);return true;}}
                        if(!window.__csCubeClicked){window.__csCubeClicked=true;var b=document.querySelector('#player-button,.tvp-poster-play,.tvp-center-play');if(b)b.click();else if(p&&p.play)p.play();}
                        return false;
                    }catch(e){return false;}})()"""
                    webView.evaluateJavascript(script, null)
                    if (attempts < 28) handler.postDelayed({ poll() }, 1000L)
                    else if (!result.isCompleted) result.complete(null)
                }

                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                    }
                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onStream(currentPage: String, stream: String) {
                            handler.post { if (!result.isCompleted) result.complete(TurboEmbedResolved(currentPage, stream)) }
                        }
                    }, "NativeBridge")
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, currentUrl: String?) {
                            val safePage = try {
                                URL(currentUrl ?: "").let { it.host + it.path }
                            } catch (_: Exception) {
                                "unknown"
                            }
                            Log.d("Cubeembed", "TurboEmbed page loaded: " + safePage)
                            handler.postDelayed({ poll() }, 500L)
                        }
                    }
                    webView.loadUrl(pageUrl)
                    result.await()
                } catch (e: Exception) {
                    Log.w("Cubeembed", "TurboEmbed WebView failed: " + e::class.simpleName + ": " + e.message)
                    null
                } finally {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.removeJavascriptInterface("NativeBridge")
                    webView.destroy()
                }
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            val parsed = URL(url)
            "${parsed.protocol}://${parsed.host}"
        } catch (e: Exception) {
            Log.e("Cubeembed", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }

    private fun decryptAES(inputHex: String, key: String, iv: String): String {
        val keySpec = SecretKeySpec(key.encodeToByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.encodeToByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(hexToBytes(inputHex))
        return String(decrypted, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have an even length" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class Turboembed : Cubeembed() {
    override var name = "TurboEmbed"
    override var mainUrl = "https://turboembed.com"
}

private data class TurboEmbedResolved(val pageUrl: String, val streamUrl: String)

private val cubeEmbedWebViewMutex = Mutex()

class Sendvid : ExtractorApi() {
    override var name = "Sendvid"
    override val mainUrl = "https://sendvid.com"
    override val requiresReferer = false
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document
        val urlString = doc.select("head meta[property=og:video:secure_url]").attr("content")
        if (urlString.contains("m3u8")) {
            generateM3u8(
                name,
                urlString,
                mainUrl,
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = urlString,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
