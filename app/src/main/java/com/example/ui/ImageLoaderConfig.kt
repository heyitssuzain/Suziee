package com.example.ui

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * Tuned Coil loader for Suziee artwork.
 *
 * - Memory cache: 15% of the process heap (artwork is what fills it).
 * - Disk cache: 150 MB, capped so a large library can never fill the device.
 * - Crossfade: short, so track changes feel instant without flashing.
 */
object ImageLoaderConfig {

    private const val DISK_CACHE_BYTES = 150L * 1024 * 1024

    fun create(context: Context): ImageLoader {
        val memoryBytes = (Runtime.getRuntime().maxMemory() * 0.15).toInt()
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(memoryBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
