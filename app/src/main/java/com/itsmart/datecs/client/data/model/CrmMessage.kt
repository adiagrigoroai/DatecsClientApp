package com.itsmart.datecs.client.data.model

data class CrmMessage(
    val id: String,
    val type: String,       // "revizie", "factura", "notificare"
    val title: String,
    val body: String,
    val actionUrl: String = "",
    val date: String = "",
    val read: Boolean = false
)

data class CrmInvoice(
    val id: String,
    val number: String,
    val date: String,
    val amount: String,
    val pdfUrl: String = ""
)
