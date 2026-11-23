package com.foodflow.core.designsystem.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.BuildConfig
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MapStyleOptions

/**
 * Shared Google Maps helpers for FoodFlow:
 * - API key readiness check
 * - Light/Dark theme-aware MapStyleOptions
 * - High-DPI directional delivery bike BitmapDescriptor (0° = North) for smooth heading rotation
 */
object FoodFlowMapUtils {

    private const val DARK_MAP_STYLE_JSON = """
        [
          {"elementType":"geometry","stylers":[{"color":"#161b22"}]},
          {"elementType":"labels.text.fill","stylers":[{"color":"#9aa5b1"}]},
          {"elementType":"labels.text.stroke","stylers":[{"color":"#0d1117"}]},
          {"featureType":"road","elementType":"geometry","stylers":[{"color":"#26323e"}]},
          {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#161b22"}]},
          {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#2e4452"}]},
          {"featureType":"water","elementType":"geometry","stylers":[{"color":"#0e2433"}]},
          {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#122c24"}]}
        ]
    """

    /** Rejects an unset or placeholder Maps key before attempting SDK-backed rendering. */
    fun hasValidMapsApiKey(): Boolean {
        val key = BuildConfig.MAPS_API_KEY
        return key.isNotBlank() && key != "MAPS_API_KEY_DEFAULT_VALUE"
    }

    /** Creates the dark map style only for dark mode and returns null if its JSON cannot be parsed. */
    fun mapStyleForTheme(isDarkTheme: Boolean): MapStyleOptions? =
        if (isDarkTheme) {
            runCatching { MapStyleOptions(DARK_MAP_STYLE_JSON) }.getOrNull()
        } else {
            null
        }

    /** Initializes Google Maps with application context while containing SDK initialization failures. */
    fun ensureInitialized(context: Context) {
        runCatching {
            MapsInitializer.initialize(context.applicationContext)
        }
    }

    /** Creates a standard marker safely, returning null if the Maps descriptor factory is unavailable. */
    fun safeDefaultMarker(hue: Float): BitmapDescriptor? =
        runCatching { BitmapDescriptorFactory.defaultMarker(hue) }.getOrNull()

    /**
     * Renders a top-down directional delivery bike badge facing North (0°).
     * When used on Google Maps with `flat = true` and `rotation = bearingDegrees`,
     * the bike physically points along the road direction of travel.
     *
     * Must only be invoked after Google Maps SDK initialization (e.g., inside the
     * `GoogleMap` content composable); returns `null` safely if `IBitmapDescriptorFactory`
     * is unavailable so the default marker is used instead of crashing.
     */
    fun createDeliveryBikeDescriptor(
        primaryColor: Int,
        secondaryColor: Int,
        surfaceColor: Int
    ): BitmapDescriptor? {
        val sizePx = 112
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        // Outer soft halo
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            alpha = 52
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, sizePx * 0.46f, haloPaint)

        // Elevated circular badge background
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = surfaceColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, sizePx * 0.36f, badgePaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(cx, cy, sizePx * 0.36f, ringPaint)

        // Direction indicator nose pointer at top (North = 0°)
        val nosePath = Path().apply {
            moveTo(cx, cy - sizePx * 0.45f)
            lineTo(cx - 10f, cy - sizePx * 0.31f)
            lineTo(cx + 10f, cy - sizePx * 0.31f)
            close()
        }
        val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(nosePath, nosePaint)

        // Front wheel (top)
        val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F2937.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(cx - 4.5f, cy - 27f, cx + 4.5f, cy - 11f),
            4f,
            4f,
            wheelPaint
        )

        // Handlebar across front
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryColor
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        canvas.drawLine(cx - 16f, cy - 13f, cx + 16f, cy - 13f, barPaint)

        // Scooter chassis body
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(cx - 8f, cy - 13f, cx + 8f, cy + 14f),
            7f,
            7f,
            bodyPaint
        )

        // Rear delivery thermal food box
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = secondaryColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(cx - 12f, cy + 4f, cx + 12f, cy + 23f),
            5f,
            5f,
            boxPaint
        )

        // White rider helmet dot in center
        val helmetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy - 2f, 6.5f, helmetPaint)

        return runCatching { BitmapDescriptorFactory.fromBitmap(bitmap) }.getOrNull()
    }
}
