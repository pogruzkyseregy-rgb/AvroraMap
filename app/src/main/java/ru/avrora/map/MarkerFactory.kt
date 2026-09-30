package ru.avrora.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Маркеры карты из иконок Авроры (res/drawable/ic_*.png). */
object MarkerFactory {

    fun fromRes(ctx: Context, resId: Int, sizeDp: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val src = BitmapFactory.decodeResource(ctx.resources, resId, opts)
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
        return Bitmap.createScaledBitmap(src, px, px, true)
    }

    fun stop(ctx: Context) = fromRes(ctx, R.drawable.ic_pin, 34)
    fun myLocation(ctx: Context) = fromRes(ctx, R.drawable.ic_paw, 42)
    fun destination(ctx: Context) = fromRes(ctx, R.drawable.ic_target, 44)
    fun favorite(ctx: Context) = fromRes(ctx, R.drawable.ic_star, 30)
}
