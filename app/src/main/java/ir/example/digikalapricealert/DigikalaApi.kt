package ir.example.digikalapricealert

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.util.Log

data class ProductInfo(val title: String?, val priceToman: Long?, val imageUrl: String?)

/** یک رنگ/گونه‌ی مشخص از یک محصول (اگر محصول چند رنگ داشته باشد). */
data class ProductVariant(
    val variantId: String,
    val colorName: String?,
    val priceToman: Long?
)

/** اطلاعات کامل محصول به‌همراه فهرست رنگ‌های موجودش (اگر بیش از یکی باشد). */
data class ProductDetails(
    val title: String?,
    val imageUrl: String?,
    val variants: List<ProductVariant>
)

/** یک نتیجه‌ی خام جستجو، قبل از افزوده شدن به فهرست پایش. */
data class SearchResultItem(
    val productId: String,
    val title: String?,
    val priceToman: Long?,
    val imageUrl: String?
)

/**
 * این کلاینت از API غیررسمی و مستندنشده‌ی دیجی‌کالا استفاده می‌کند
 * (https://api.digikala.com/v2/product/{id}/).
 * چون این API رسمی نیست، دیجی‌کالا می‌تواند ساختار پاسخ را بدون اطلاع قبلی تغییر دهد.
 * به همین دلیل پارس کردن JSON با چند مسیر احتمالی و به‌صورت defensive انجام شده؛
 * اگر در آینده کار نکرد، خروجی خام را در Logcat با تگ DigikalaApi ببینید و
 * مسیر فیلدهای جدید را در تابع parseProductJson اصلاح کنید.
 */
object DigikalaApi {

    private const val TAG = "DigikalaApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * آخرین خطای دریافت اطلاعات (مثلاً «HTTP 403» یا «خطای اتصال»)؛ برای نمایش به کاربر
     * تا بدون Logcat بشود علت ناموفق‌بودن را فهمید. با هر درخواست موفق null می‌شود.
     */
    @Volatile
    var lastError: String? = null
        private set

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_UA)
        .header("Referer", "https://www.digikala.com/")
        .header("Origin", "https://www.digikala.com")
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    /** از روی لینک کامل محصول یا فقط عدد، شناسه‌ی عددی محصول را استخراج می‌کند. */
    fun extractProductId(input: String): String? {
        val trimmed = input.trim()
        // حالت ۱: کاربر فقط عدد را وارد کرده
        if (trimmed.matches(Regex("^\\d+$"))) return trimmed
        // حالت ۲: لینک کامل شامل dkp-123456
        val match = Regex("dkp-(\\d+)").find(trimmed)
        if (match != null) return match.groupValues[1]
        // حالت ۳: هر رشته‌ی دیگری که یک عدد بلند داخلش باشد
        val anyNumber = Regex("(\\d{4,})").find(trimmed)
        return anyNumber?.groupValues?.get(1)
    }

    /** درخواست همزمان (synchronous) - باید از یک ترد پس‌زمینه/Coroutine IO صدا زده شود. */
    fun fetchProductInfo(productId: String): ProductInfo? {
        val request = buildRequest("https://api.digikala.com/v2/product/$productId/")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای محصول $productId")
                    lastError = "HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string()
                if (body == null) {
                    lastError = "پاسخ خالی"
                    return null
                }
                val info = parseProductJson(body)
                lastError = if (info == null) "ساختار پاسخ تغییر کرده (JSON نامعتبر)" else null
                return info
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت اطلاعات محصول $productId", e)
            lastError = "خطای اتصال: ${e.javaClass.simpleName}"
            return null
        }
    }

    /**
     * جستجوی محصول بر اساس نام/عبارت، با استفاده از API غیررسمی جستجوی دیجی‌کالا.
     * درخواست همزمان (synchronous) است - باید از یک ترد پس‌زمینه/Coroutine IO صدا زده شود.
     * در صورت خطا یا نبود نتیجه، فهرست خالی برمی‌گرداند (نه null) تا UI ساده‌تر بماند.
     */
    fun searchProducts(query: String): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val request = buildRequest("https://api.digikala.com/v1/search/?q=$encoded")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای جستجوی «$trimmed»")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                return parseSearchJson(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در جستجوی «$trimmed»", e)
            return emptyList()
        }
    }

    private fun parseSearchJson(raw: String): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()
        try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return results
            // مسیر معمول: data.products؛ بعضی نسخه‌های API از data.items استفاده می‌کنند
            val products = data.optJSONArray("products") ?: data.optJSONArray("items") ?: return results

            for (i in 0 until products.length()) {
                val product = products.optJSONObject(i) ?: continue
                val id = product.optLong("id", -1L)
                if (id <= 0) continue

                val title = when {
                    product.has("title_fa") && !product.isNull("title_fa") -> product.getString("title_fa")
                    product.has("title_en") && !product.isNull("title_en") -> product.getString("title_en")
                    else -> null
                }

                val variant = product.optJSONObject("default_variant")
                val priceObj = variant?.optJSONObject("price")
                var priceRial: Long? = null
                if (priceObj != null) {
                    priceRial = when {
                        priceObj.has("selling_price") -> priceObj.optLong("selling_price")
                        priceObj.has("rrp_price") -> priceObj.optLong("rrp_price")
                        else -> null
                    }
                }
                if (priceRial == null) {
                    priceRial = product.optJSONObject("price")?.optLong("selling_price")
                }
                val priceToman = priceRial?.let { it / 10 }

                var imageUrl: String? = null
                val images = product.optJSONObject("images")
                val mainImage = images?.optJSONObject("main")
                val urlArray = mainImage?.optJSONArray("url")
                if (urlArray != null && urlArray.length() > 0) {
                    imageUrl = urlArray.optString(0, null)
                }
                if (imageUrl == null) {
                    imageUrl = mainImage?.optString("url", null)
                }

                results.add(
                    SearchResultItem(
                        productId = id.toString(),
                        title = title,
                        priceToman = priceToman,
                        imageUrl = imageUrl
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس JSON جستجو: $raw", e)
        }
        return results
    }

    /**
     * مثل fetchProductInfo، ولی علاوه بر عنوان/عکس/قیمت، فهرست رنگ‌های موجود
     * محصول (variants) را هم برمی‌گرداند - برای پایش قیمت بر اساس رنگ خاص.
     * اگر محصول اصلاً چند رنگ نداشته باشد، یک آیتم تکی (رنگ=null) برگردانده
     * می‌شود تا فراخوان بتواند به همان روش قبلی رفتار کند.
     * درخواست همزمان (synchronous) است - باید از یک ترد پس‌زمینه/Coroutine IO صدا زده شود.
     */
    fun fetchProductDetails(productId: String): ProductDetails? {
        val request = buildRequest("https://api.digikala.com/v2/product/$productId/")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای محصول $productId (fetchProductDetails)")
                    lastError = "HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string()
                if (body == null) {
                    lastError = "پاسخ خالی"
                    return null
                }
                val details = parseProductDetailsJson(productId, body)
                lastError = if (details == null) "ساختار پاسخ تغییر کرده (JSON نامعتبر)" else null
                return details
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت جزئیات محصول $productId", e)
            lastError = "خطای اتصال: ${e.javaClass.simpleName}"
            return null
        }
    }

    private fun parsePriceRial(priceObj: JSONObject?): Long? {
        if (priceObj == null) return null
        return when {
            priceObj.has("selling_price") -> priceObj.optLong("selling_price")
            priceObj.has("rrp_price") -> priceObj.optLong("rrp_price")
            else -> null
        }
    }

    private fun parseProductDetailsJson(productId: String, raw: String): ProductDetails? {
        return try {
            val root = JSONObject(raw)
            val product = root.optJSONObject("data")?.optJSONObject("product") ?: return null

            val title = when {
                product.has("title_fa") && !product.isNull("title_fa") -> product.getString("title_fa")
                product.has("title_en") && !product.isNull("title_en") -> product.getString("title_en")
                else -> null
            }

            var imageUrl: String? = null
            val images = product.optJSONObject("images")
            val mainImage = images?.optJSONObject("main")
            val urlArray = mainImage?.optJSONArray("url")
            if (urlArray != null && urlArray.length() > 0) {
                imageUrl = urlArray.optString(0, null)
            }
            if (imageUrl == null) {
                imageUrl = mainImage?.optString("url", null)
            }

            val variants = mutableListOf<ProductVariant>()
            val variantsArr = product.optJSONArray("variants")
            if (variantsArr != null) {
                for (i in 0 until variantsArr.length()) {
                    val v = variantsArr.optJSONObject(i) ?: continue
                    val vId = when {
                        v.has("id") -> v.optLong("id", -1L).let { if (it > 0) it.toString() else null }
                        else -> null
                    } ?: continue
                    val colorObj = v.optJSONObject("color")
                    val colorName = when {
                        colorObj != null && colorObj.has("title") && !colorObj.isNull("title") -> colorObj.getString("title")
                        else -> null
                    }
                    val priceRial = parsePriceRial(v.optJSONObject("price"))
                    variants.add(ProductVariant(vId, colorName, priceRial?.let { it / 10 }))
                }
            }

            // اگر آرایه‌ی variants خالی بود یا اصلاً وجود نداشت، یک آیتم تکی (بدون
            // رنگ مشخص) از روی default_variant بساز تا رفتار محصولات تک‌رنگ عوض نشود
            if (variants.isEmpty()) {
                val defaultVariant = product.optJSONObject("default_variant")
                val priceRial = parsePriceRial(defaultVariant?.optJSONObject("price"))
                    ?: parsePriceRial(product.optJSONObject("price"))
                val defaultId = defaultVariant?.optLong("id", -1L)?.let { if (it > 0) it.toString() else null }
                    ?: productId
                variants.add(ProductVariant(defaultId, null, priceRial?.let { it / 10 }))
            }

            ProductDetails(title = title, imageUrl = imageUrl, variants = variants)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس جزئیات محصول: $raw", e)
            null
        }
    }

    private fun parseProductJson(raw: String): ProductInfo? {
        return try {
            val root = JSONObject(raw)
            val product = root.optJSONObject("data")?.optJSONObject("product") ?: return null

            val title = when {
                product.has("title_fa") && !product.isNull("title_fa") -> product.getString("title_fa")
                product.has("title_en") && !product.isNull("title_en") -> product.getString("title_en")
                else -> null
            }

            // قیمت معمولاً داخل default_variant.price قرار دارد
            val variant = product.optJSONObject("default_variant")
            val priceObj = variant?.optJSONObject("price")

            var priceRial: Long? = null
            if (priceObj != null) {
                priceRial = when {
                    priceObj.has("selling_price") -> priceObj.optLong("selling_price")
                    priceObj.has("rrp_price") -> priceObj.optLong("rrp_price")
                    else -> null
                }
            }

            // اگر مسیر بالا جواب نداد، تلاش برای مسیرهای جایگزین احتمالی
            if (priceRial == null) {
                priceRial = product.optJSONObject("price")?.optLong("selling_price")
            }

            val priceToman = priceRial?.let { it / 10 }

            // آدرس تصویر اصلی محصول؛ در API معمولاً data.product.images.main.url یک آرایه است
            var imageUrl: String? = null
            val images = product.optJSONObject("images")
            val mainImage = images?.optJSONObject("main")
            val urlArray = mainImage?.optJSONArray("url")
            if (urlArray != null && urlArray.length() > 0) {
                imageUrl = urlArray.optString(0, null)
            }
            if (imageUrl == null) {
                imageUrl = mainImage?.optString("url", null)
            }

            ProductInfo(title = title, priceToman = priceToman, imageUrl = imageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس JSON: $raw", e)
            null
        }
    }
}
