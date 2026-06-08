package com.itsmart.datecs.client.data.model

data class CommandLog(
    val time: String,
    val tag: String,
    val cmd: String,
    val res: String?,
    val ok: Boolean
)
