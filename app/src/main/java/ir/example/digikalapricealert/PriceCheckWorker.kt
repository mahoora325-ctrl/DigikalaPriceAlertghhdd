package ir.example.digikalapricealert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PriceCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = ProductStore(applicationContext)
        val settingsStore = SettingsStore(applicationContext)
        val products = store.getAll()
        if (products.isEmpty()) return Result.success()

        var successCount = 0
        var failCount = 0
        var firstError: String? = null

        withContext(Dispatchers.IO) {
            for (product in products) {
                val usesColorTracking = product.trackAllColors || product.variantId != null

                if (!usesColorTracking) {
                    val info = DigikalaApi.fetchProductInfo(product.productId)
                    if (info == null) {
                        failCount++
                        if (firstError == null) firstError = DigikalaApi.lastError
                        continue
                    }
                    successCount++
                    if (info.title != null) product.title = info.title
                    if (info.imageUrl != null) product.imageUrl = info.imageUrl
                    if (info.priceToman != null) {
                        applyNewPrice(product, info.priceToman, applicationContext)
                    }
                    store.update(product)
                    continue
                }

                // پایش بر اساس رنگ خاص یا «همه‌ی رنگ‌ها»: نیاز به فهرست کامل رنگ‌ها داریم
                val details = DigikalaApi.fetchProductDetails(product.productId)
                if (details == null) {
                    failCount++
                    if (firstError == null) firstError = DigikalaApi.lastError
                    continue
                }
                successCount++
                if (details.title != null) product.title = details.title
                if (details.imageUrl != null) product.imageUrl = details.imageUrl

                val relevant = if (product.trackAllColors) {
                    details.variants.filter { it.priceToman != null }.minByOrNull { it.priceToman!! }
                } else {
                    details.variants.find { it.variantId == product.variantId } ?: details.variants.firstOrNull()
                }

                if (relevant?.priceToman != null) {
                    product.colorName = relevant.colorName
                    applyNewPrice(product, relevant.priceToman, applicationContext)
                }
                store.update(product)
            }
        }

        settingsStore.setLastCheckResult(successCount, failCount)
        settingsStore.setLastCheckError(if (failCount > 0) firstError else null)
        return Result.success()
    }

    /** رکورد تاریخچه، آخرین قیمت/زمان بررسی، و هشدار (در صورت لزوم) را به‌روزرسانی می‌کند. */
    private fun applyNewPrice(product: TrackedProduct, priceToman: Long, context: Context) {
        val timestamp = JalaliDateUtil.nowFormatted()
        product.recordPriceIfChanged(priceToman, timestamp)
        product.lastPriceToman = priceToman
        product.lastChecked = timestamp

        val underThreshold = priceToman <= product.thresholdToman
        if (underThreshold) {
            // همیشه هشدار بده، حتی اگر قبلاً هم زیر سقف بوده و هشدار داده شده
            NotificationHelper.notifyPriceDrop(context, product)
            product.alerted = true
        } else {
            // قیمت دوباره بالای سقف رفت
            product.alerted = false
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "digikala_price_check"
        const val UNIQUE_ONE_TIME_WORK_NAME = "digikala_price_check_now"
    }
}
