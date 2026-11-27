package com.clipme.app.net

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Wraps OkHttp with timeouts tuned for HLS segment streaming. */
class HttpClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36 ClipMe/1.0"

    suspend fun getString(url: String, timeoutSec: Long = 60): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            )
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            cont.resumeWith(
                                Result.failure(IOException("HTTP ${it.code} for $url"))
                            )
                            return
                        }
                        val body = it.body?.string() ?: ""
                        cont.resumeWith(Result.success(body))
                    }
                }
            })
        }

    suspend fun getBytes(url: String, timeoutSec: Long = 60): ByteArray =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            )
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            cont.resumeWith(
                                Result.failure(IOException("HTTP ${it.code} for $url"))
                            )
                            return
                        }
                        cont.resumeWith(Result.success(it.body?.bytes() ?: ByteArray(0)))
                    }
                }
            })
        }

    /** Downloads a URL into [dest] atomically (temp file + rename). */
    suspend fun download(url: String, dest: File): File {
        suspendCancellableCoroutine<Unit> { cont ->
            val call = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            )
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                cont.resumeWith(
                                    Result.failure(IOException("HTTP ${it.code} for $url"))
                                )
                                return
                            }
                            val body = it.body ?: throw IOException("empty body")
                            val tmp = File(dest.parentFile, dest.name + ".part")
                            body.byteStream().use { input ->
                                tmp.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (tmp.renameTo(dest)) {
                                cont.resumeWith(Result.success(Unit))
                            } else {
                                dest.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
                                tmp.delete()
                                cont.resumeWith(Result.success(Unit))
                            }
                        }
                    } catch (t: Throwable) {
                        cont.resumeWith(Result.failure(t))
                    }
                }
            })
        }
        return dest
    }

    /** Non-suspending call for callbacks (e.g. ML Kit executor). */
    fun newCall(request: Request) = client.newCall(request)

    fun requestBuilder(url: String) = Request.Builder().url(url).header("User-Agent", userAgent)
}
