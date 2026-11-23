package com.foodflow.domain.usecase.order

import com.foodflow.core.common.AppResult
import com.foodflow.core.invoice.InvoicePdfGenerator
import com.foodflow.domain.model.InvoiceDocument
import com.foodflow.domain.model.Order
import javax.inject.Inject

class GenerateOrderInvoiceUseCase @Inject constructor(
    private val invoicePdfGenerator: InvoicePdfGenerator
) {
    /** Creates and saves an invoice PDF for the supplied order and customer. */
    suspend operator fun invoke(
        order: Order,
        customerName: String,
        customerPhone: String
    ): AppResult<InvoiceDocument> = invoicePdfGenerator.generateAndSaveInvoice(
        order = order,
        customerName = customerName,
        customerPhone = customerPhone
    )
}
