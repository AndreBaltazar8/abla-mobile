package org.abla.mobile.host

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Session-isolated decoded images. No credentials or private images are written to disk. */
internal object RemoteImageCache {
    private data class Entry(val bitmap: Bitmap, val checked: Long, val etag: String?)
    private val entries=object: LruCache<String,Entry>(8*1024*1024) {
        override fun sizeOf(key: String,value: Entry)=value.bitmap.allocationByteCount
    }
    private val lock=Any()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val pending=mutableMapOf<String,Deferred<Bitmap?>>()
    private var generation=0
    private fun key(url:String,auth:String):String = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest((auth+"\n"+url).toByteArray()),android.util.Base64.NO_WRAP)
    fun peek(url:String,auth:String):Bitmap?=synchronized(lock) { entries.get(key(url,auth))?.bitmap }
    fun clear() {
        val jobs=synchronized(lock) { generation++;entries.evictAll();pending.values.toList().also { pending.clear() } }
        jobs.forEach { it.cancel() }
    }
    suspend fun get(url:String,auth:String):Bitmap? {
        val k=key(url,auth)
        val task=synchronized(lock) {
            val old=entries.get(k)
            if(old!=null && SystemClock.elapsedRealtime()-old.checked<60_000) return old.bitmap
            val epoch=generation
            pending[k] ?: scope.async(start=CoroutineStart.LAZY) {
                var result:Entry?=old
                val connection=URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout=10000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
                    if(auth.isNotEmpty()) connection.setRequestProperty("Authorization",auth)
                    old?.etag?.let { connection.setRequestProperty("If-None-Match",it) }
                    android.util.Log.d("AblaImages",if(old==null) "fetch" else "revalidate")
                    when(connection.responseCode) {
                        304 -> if(old!=null) result=old.copy(checked=SystemClock.elapsedRealtime())
                        200 -> {
                            val bytes=connection.inputStream.use { stream ->
                                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                                while(out.size()<=65536) {val n=stream.read(buffer);if(n<0) break;out.write(buffer,0,n)}
                                out.toByteArray()
                            }
                            require(bytes.size<=65536)
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                            require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
                            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid image")
                            result=Entry(bitmap,SystemClock.elapsedRealtime(),connection.getHeaderField("ETag"))
                        }
                        401,403,404 -> result=null
                    }
                } catch(cancelled:CancellationException) {throw cancelled}
                  catch(_:Exception) { /* Keep a previously decoded image during transient failures. */ }
                finally {connection.disconnect()}
                synchronized(lock) {
                    if(epoch==generation) {
                        if(result==null) entries.remove(k) else entries.put(k,result!!)
                        result?.bitmap
                    } else null
                }
            }.also { job ->
                pending[k]=job
                job.invokeOnCompletion { synchronized(lock) {if(pending[k]===job) pending.remove(k)} }
                job.start()
            }
        }
        return task.await()
    }
}
