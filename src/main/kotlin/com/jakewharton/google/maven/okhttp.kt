package com.jakewharton.google.maven

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.awaitBody(): ResponseBody {
  return suspendCancellableCoroutine {
    it.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
      override fun onResponse(call: Call, response: Response) {
        if (response.isSuccessful) {
          it.resume(response.body()!!)
        } else {
          val e = HttpException(response.request().url(), response.code(), response.message())
          it.resumeWithException(e)
        }
      }
      override fun onFailure(call: Call, e: IOException) {
        it.resumeWithException(e)
      }
    })
  }
}

class HttpException(val url: HttpUrl, val code: Int, message: String) : Exception("HTTP $code $message")
