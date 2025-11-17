package it.dogior.example

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

// Copied from DownloaderTestImpl. I only changed the cookies as the playlists wouldn't show up in search results.
// Now why is it called downloader if downloading it's not its only job I don't know
class NewPipeDownloader(builder: OkHttpClient.Builder): Downloader() {
    private val client: OkHttpClient = builder.readTimeout(30, TimeUnit.SECONDS).build()
    override fun execute(request: Request): Response {
        val httpMethod: String = request.httpMethod()
        val url: String = request.url()
        val headers: Map<String, List<String>> = YoutubeParsingHelper.getCookieHeader()
        val dataToSend: ByteArray? = request.dataToSend()
        var requestBody: RequestBody? = null
        if (dataToSend != null) {
            requestBody = dataToSend.toRequestBody(null, 0, dataToSend.size)
        }
        val requestBuilder: okhttp3.Request.Builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody).url(url)
            .addHeader("User-Agent", USER_AGENT)

        for ((headerName, headerValueList) in headers) {
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                for (headerValue in headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }
        val response = client.newCall(requestBuilder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        val body = response.body
        val responseBodyToReturn: String = body.string()
        val latestUrl = response.request.url.toString()
        return Response(
            response.code, response.message, response.headers.toMultimap(),
            responseBodyToReturn, latestUrl
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        private var instance: NewPipeDownloader? = null

        fun init(builder: OkHttpClient.Builder?): NewPipeDownloader? {
            instance = NewPipeDownloader(
                builder ?: OkHttpClient.Builder()
            )
            return instance
        }

        fun getInstance(): NewPipeDownloader? {
            if (instance == null) {
                init(null)
            }
            return instance
        }
    }
}