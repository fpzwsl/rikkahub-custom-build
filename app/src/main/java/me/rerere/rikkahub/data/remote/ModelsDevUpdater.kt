package me.rerere.rikkahub.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import java.io.File

private const val TAG = "ModelsDevUpdater"
private const val ASSET_NAME = "models_dev.json"

/**
 * 给 [ModelRegistry] 提供 models.dev 数据：先装上本地缓存（没有就用 assets 里的快照），
 * 不再自动从网络刷新数据。
 */
class ModelsDevUpdater(
    private val context: Context,
) {
    private val dir = File(context.filesDir, "models_dev")
    private val dataFile = File(dir, "api.json")

    suspend fun start() = withContext(Dispatchers.IO) {
        loadLocal()
    }

    /** 返回是否用上了下载的缓存 */
    private fun loadLocal(): Boolean {
        if (dataFile.exists()) {
            val cached = runCatching { dataFile.inputStream().use { ModelsDevCatalog.decode(it) } }
                .onFailure { Log.w(TAG, "cached catalog is broken", it) }
                .getOrNull()
            if (cached != null) {
                ModelRegistry.installCatalog(cached)
                return true
            }
            dataFile.delete()
        }
        runCatching { context.assets.open(ASSET_NAME).use { ModelsDevCatalog.decode(it) } }
            .onSuccess { ModelRegistry.installCatalog(it) }
            .onFailure { Log.e(TAG, "failed to load bundled catalog", it) }
        return false
    }
}
