package ir.example.digikalapricealert

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** نتیجه‌ی انتخاب کاربر از دیالوگ رنگ. */
data class VariantSelection(
    val trackAllColors: Boolean,
    val variant: ProductVariant?
)

object ColorPickerHelper {

    /**
     * اگر محصول بیش از یک رنگ داشته باشد، دیالوگ انتخاب رنگ (به‌همراه گزینه‌ی
     * «همه‌ی رنگ‌ها») را نشان می‌دهد. اگر فقط یک رنگ/گونه داشته باشد، دیالوگ
     * نشان داده نمی‌شود و مستقیم همان یک گزینه به callback داده می‌شود.
     */
    fun selectVariant(context: Context, variants: List<ProductVariant>, onSelected: (VariantSelection) -> Unit) {
        if (variants.size <= 1) {
            onSelected(VariantSelection(trackAllColors = false, variant = variants.firstOrNull()))
            return
        }

        val labels = mutableListOf("🎨 همه‌ی رنگ‌ها (پایش کمترین قیمت بین رنگ‌ها)")
        for (v in variants) {
            val colorLabel = v.colorName ?: "بدون‌نام"
            val priceLabel = if (v.priceToman != null) "${PersianNumberUtils.formatToman(v.priceToman)} تومان" else "قیمت نامشخص"
            labels.add("$colorLabel — $priceLabel")
        }

        AlertDialog.Builder(context)
            .setTitle("این محصول چند رنگ دارد؛ کدام را پایش کنیم؟")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    onSelected(VariantSelection(trackAllColors = true, variant = null))
                } else {
                    onSelected(VariantSelection(trackAllColors = false, variant = variants[which - 1]))
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
