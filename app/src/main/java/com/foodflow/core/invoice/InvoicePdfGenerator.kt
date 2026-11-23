package com.foodflow.core.invoice

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.foodflow.core.common.AppError
import com.foodflow.core.common.AppResult
import com.foodflow.core.common.DispatcherProvider
import com.foodflow.core.common.ErrorMapper
import com.foodflow.core.common.toInr
import com.foodflow.domain.model.InvoiceDocument
import com.foodflow.domain.model.Order
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates clean, formatted A4 PDF tax invoices using Android's native [PdfDocument] API
 * and stores them using Android Scoped Storage (MediaStore Downloads on API 29+,
 * FileProvider-backed internal storage on API 24-28) without requiring legacy storage permissions.
 */
interface InvoicePdfGenerator {
    /** Generates an order invoice, persists it, and returns the URI and saved-location label. */
    suspend fun generateAndSaveInvoice(
        order: Order,
        customerName: String,
        customerPhone: String
    ): AppResult<InvoiceDocument>
}

@Singleton
class AndroidInvoicePdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : InvoicePdfGenerator {

    /** Renders on the I/O dispatcher, saves an app-accessible copy, and publishes to Downloads when supported. */
    override suspend fun generateAndSaveInvoice(
        order: Order,
        customerName: String,
        customerPhone: String
    ): AppResult<InvoiceDocument> = withContext(dispatchers.io) {
        try {
            val safeOrderId = order.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val fileName = "FoodFlow_Invoice_${safeOrderId}.pdf"
            val pdfBytes = renderInvoiceBytes(order, customerName, customerPhone)

            // Always persist a FileProvider-accessible copy for reliable Share & Open actions
            val invoicesDir = File(context.filesDir, "invoices").apply { mkdirs() }
            val localFile = File(invoicesDir, fileName)
            FileOutputStream(localFile).use { it.write(pdfBytes) }

            val fileProviderUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    localFile
                )
            }.getOrElse { Uri.fromFile(localFile) }

            var savedLabel = "Saved in App Invoices"
            var resolvedUri: Uri = fileProviderUri

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FoodFlow")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val mediaUri = runCatching {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                }.getOrNull()

                if (mediaUri != null) {
                    val written = runCatching {
                        resolver.openOutputStream(mediaUri)?.use { out ->
                            out.write(pdfBytes)
                            out.flush()
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(mediaUri, values, null, null)
                        true
                    }.getOrDefault(false)

                    if (written) {
                        resolvedUri = mediaUri
                        savedLabel = "Saved to Downloads/FoodFlow/$fileName"
                    }
                }
            }

            AppResult.Success(
                InvoiceDocument(
                    orderId = order.id,
                    fileName = fileName,
                    uriString = resolvedUri.toString(),
                    savedLocationLabel = savedLabel
                )
            )
        } catch (t: Throwable) {
            AppResult.Error(
                AppError.Unknown(
                    userMessage = "Unable to generate invoice PDF right now. Please try again.",
                    technicalDetail = ErrorMapper.map(t).technicalDetail
                )
            )
        }
    }

    /** Prefers Android's native PDF renderer and falls back to a minimal PDF if rendering fails. */
    internal fun renderInvoiceBytes(
        order: Order,
        customerName: String,
        customerPhone: String
    ): ByteArray {
        return runCatching {
            renderWithAndroidPdfDocument(order, customerName, customerPhone)
        }.getOrElse {
            renderFallbackPdf14Bytes(order, customerName, customerPhone)
        }
    }

    /** Draws the formatted invoice onto a fixed A4 page using Android PdfDocument and Canvas. */
    private fun renderWithAndroidPdfDocument(
        order: Order,
        customerName: String,
        customerPhone: String
    ): ByteArray {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val headerBgPaint = Paint().apply { color = Color.parseColor("#10A37F") }
        val accentBarPaint = Paint().apply { color = Color.parseColor("#06B6D4") }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#E2E8F0")
            strokeWidth = 1f
        }
        val brandTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val brandSubtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6FFF6")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val rightBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 12f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rightBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 11f
            textAlign = Paint.Align.RIGHT
        }
        val totalAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10A37F")
            textSize = 15f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val left = 40f
        val right = pageWidth - 40f

        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 96f, headerBgPaint)
        canvas.drawRect(0f, 96f, pageWidth.toFloat(), 100f, accentBarPaint)
        canvas.drawText("FoodFlow", left, 48f, brandTitlePaint)
        canvas.drawText("TAX INVOICE / ORDER RECEIPT", left, 70f, brandSubtitlePaint)

        val dateFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        val formattedDate = dateFormatter.format(Date(order.createdAtMs))

        val rightHeaderPaint = Paint(brandSubtitlePaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("Order #${order.id}", right, 46f, rightHeaderPaint)
        canvas.drawText(formattedDate, right, 66f, rightHeaderPaint)

        var y = 132f

        canvas.drawText("RESTAURANT DETAILS", left, y, headingPaint)
        canvas.drawText("DELIVERED TO", 320f, y, headingPaint)
        y += 18f

        canvas.drawText(order.restaurantName, left, y, bodyPaint)
        canvas.drawText(customerName.ifBlank { "FoodFlow Customer" }, 320f, y, bodyPaint)
        y += 16f

        canvas.drawText(order.restaurantAddress.take(42), left, y, mutedPaint)
        canvas.drawText(customerPhone.ifBlank { "+91 98765 43210" }, 320f, y, mutedPaint)
        y += 16f

        canvas.drawText("Order Status: ${order.orderStatus.label}", left, y, mutedPaint)
        canvas.drawText(order.deliveryAddress.take(42), 320f, y, mutedPaint)
        y += 24f

        canvas.drawLine(left, y, right, y, dividerPaint)
        y += 22f

        canvas.drawText("ITEM DESCRIPTION", left, y, headingPaint)
        canvas.drawText("QTY", 370f, y, headingPaint)
        canvas.drawText("UNIT PRICE", 460f, y, rightBoldPaint)
        canvas.drawText("AMOUNT", right, y, rightBoldPaint)
        y += 12f
        canvas.drawLine(left, y, right, y, dividerPaint)
        y += 20f

        order.items.forEach { item ->
            canvas.drawText(item.foodName.take(44), left, y, bodyPaint)
            canvas.drawText("${item.quantity}", 375f, y, bodyPaint)
            canvas.drawText(item.unitPrice.toInr(), 460f, y, rightBodyPaint)
            canvas.drawText(item.lineTotal.toInr(), right, y, rightBodyPaint)
            y += 16f
            if (item.addonsSummary.isNotBlank()) {
                canvas.drawText("Add-ons: ${item.addonsSummary.take(48)}", left + 8f, y, mutedPaint)
                y += 14f
            }
            y += 4f
        }

        y += 8f
        canvas.drawLine(left, y, right, y, dividerPaint)
        y += 22f

        /** Draws one aligned billing line and advances the shared vertical cursor. */
        fun drawBillRow(label: String, value: String, bold: Boolean = false) {
            canvas.drawText(label, 290f, y, if (bold) headingPaint else bodyPaint)
            canvas.drawText(value, right, y, if (bold) totalAccentPaint else rightBodyPaint)
            y += 18f
        }

        drawBillRow("Item Subtotal", order.subtotal.toInr())
        drawBillRow("Delivery Fee", if (order.deliveryFee == 0.0) "FREE" else order.deliveryFee.toInr())
        drawBillRow("Packaging Charge", order.packagingCharge.toInr())
        drawBillRow("GST & Restaurant Taxes (5%)", order.taxes.toInr())
        if (order.discount > 0.0) {
            val codeSuffix = order.couponCode?.let { " ($it)" }.orEmpty()
            drawBillRow("Coupon Discount$codeSuffix", "-${order.discount.toInr()}")
        }

        y += 6f
        canvas.drawLine(290f, y, right, y, dividerPaint)
        y += 22f
        drawBillRow("TOTAL PAYABLE", order.totalPayable.toInr(), bold = true)

        y += 24f
        canvas.drawLine(left, y, right, y, dividerPaint)
        y += 22f

        canvas.drawText("PAYMENT DETAILS", left, y, headingPaint)
        y += 18f
        canvas.drawText("Method: ${order.paymentMethod.displayName}", left, y, bodyPaint)
        y += 16f
        canvas.drawText("Payment Status: ${order.paymentStatus.label}", left, y, bodyPaint)

        val footerY = pageHeight - 54f
        canvas.drawLine(left, footerY - 16f, right, footerY - 16f, dividerPaint)
        canvas.drawText("Thank you for ordering with FoodFlow. Fresh meals delivered with care.", left, footerY, mutedPaint)
        canvas.drawText("Computer-generated GST invoice · No signature required", left, footerY + 14f, mutedPaint)

        document.finishPage(page)

        val stream = ByteArrayOutputStream()
        document.writeTo(stream)
        document.close()
        return stream.toByteArray()
    }

    /** Builds a one-page PDF 1.4 byte stream when native document rendering is unavailable. */
    private fun renderFallbackPdf14Bytes(
        order: Order,
        customerName: String,
        customerPhone: String
    ): ByteArray {
        val dateFormatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        val lines = buildList {
            add("FoodFlow - TAX INVOICE / ORDER RECEIPT")
            add("Order ID: ${order.id}  |  Date: ${dateFormatter.format(Date(order.createdAtMs))}")
            add("Restaurant: ${order.restaurantName} (${order.restaurantAddress})")
            add("Customer: ${customerName.ifBlank { "FoodFlow Customer" }}  |  ${customerPhone.ifBlank { "+91 98765 43210" }}")
            add("Delivery Address: ${order.deliveryAddress}")
            add("------------------------------------------------------------")
            order.items.forEach { item ->
                add("${item.quantity}x ${item.foodName} - INR ${String.format(Locale.US, "%.2f", item.lineTotal)}")
            }
            add("------------------------------------------------------------")
            add("Subtotal: INR ${String.format(Locale.US, "%.2f", order.subtotal)}")
            add("Delivery Fee: INR ${String.format(Locale.US, "%.2f", order.deliveryFee)}")
            add("Packaging: INR ${String.format(Locale.US, "%.2f", order.packagingCharge)}")
            add("GST & Taxes: INR ${String.format(Locale.US, "%.2f", order.taxes)}")
            if (order.discount > 0.0) {
                add("Discount: -INR ${String.format(Locale.US, "%.2f", order.discount)}")
            }
            add("TOTAL PAYABLE: INR ${String.format(Locale.US, "%.2f", order.totalPayable)}")
            add("Payment: ${order.paymentMethod.displayName} (${order.paymentStatus.label})")
        }

        /** Escapes PDF string delimiters and replaces unsupported non-ASCII characters. */
        fun escapePdf(text: String): String = text
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(Regex("[^\\x20-\\x7E]"), " ")

        val contentStream = buildString {
            append("BT\n/F1 11 Tf\n40 790 Td\n16 TL\n")
            lines.forEachIndexed { index, line ->
                if (index > 0) append("T*\n")
                append("(${escapePdf(line)}) Tj\n")
            }
            append("ET\n")
        }

        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
            "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
            "5 0 obj\n<< /Length ${contentStream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${contentStream}endstream\nendobj\n"
        )

        val out = ByteArrayOutputStream()
        /** Appends syntax using the ASCII encoding required by this fallback PDF writer. */
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        writeAscii("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEach { obj ->
            offsets += out.size()
            writeAscii(obj)
        }
        val xrefOffset = out.size()
        writeAscii("xref\n0 ${objects.size + 1}\n")
        writeAscii("0000000000 65535 f \n")
        offsets.forEach { offset ->
            writeAscii(String.format(Locale.US, "%010d 00000 n \n", offset))
        }
        writeAscii("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }
}

object InvoiceIntentHelper {
    /** Creates a read-granted VIEW intent for an invoice PDF URI. */
    fun buildOpenPdfIntent(uriString: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uriString), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Creates a chooser intent with the invoice attachment and order-specific share text. */
    fun buildSharePdfIntent(uriString: String, orderId: String): Intent {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
            putExtra(Intent.EXTRA_SUBJECT, "FoodFlow Invoice - $orderId")
            putExtra(Intent.EXTRA_TEXT, "Here is the tax invoice for FoodFlow order $orderId.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(shareIntent, "Share Invoice PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Launches a PDF viewer and returns false if no handler can open the invoice. */
    fun openInvoice(context: Context, uri: Uri): Boolean = runCatching {
        context.startActivity(buildOpenPdfIntent(uri.toString()))
        true
    }.getOrDefault(false)

    /** Opens Android's share chooser for the invoice and reports whether launch succeeded. */
    fun shareInvoice(context: Context, document: InvoiceDocument): Boolean = runCatching {
        context.startActivity(buildSharePdfIntent(document.uriString, document.orderId))
        true
    }.getOrDefault(false)
}
