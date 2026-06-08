package com.itsmart.datecs.client.data.model

data class DeviceState(
    val serialNumber: String = "",
    val fiscalNumber: String = "",
    val model: String = "",
    val devName: String = "",
    val devType: String = "",
    val fwRev: String = "",
    val fwDate: String = "",
    val fwTime: String = "",
    val bootloaderVer: String = "",
    val hardwareVer: String = "",
    val fiscalized: String = "",
    val fmNumber: String = "",
    val deviceDateTime: String = "",
    val lastConnectionDate: String = "",
    val lastConnectionState: String = "",
    val lastConnectionErrorCode: String = "",
    val lastConnectionZrepNum: String = "",
    val nZreport: String = "",
    val nBon: String = "",
    val ecrMode: String = "0",  // 0=NORMAL, 2=SERVICE, 3=TRAINING
    val vatRates: List<String> = listOf("0.00", "0.00", "0.00", "0.00", "0.00"),
    val headers: List<String> = listOf("", "", "", "", "", ""),
    val footers: List<String> = listOf("", ""),
    val serviceDate: String = "",
    val servMessages: List<String> = List(10) { "" },
    val serverUrls: List<String> = List(6) { "" },
    val syncPeriod: String = "",
    val servicePassword: String = "",
    val remainingVatChanges: Int = 30,
    val country: String = "",
    val lanIp: String = "",
    val lanNetmask: String = "",
    val lanGateway: String = "",
    val netInterface: String = "",
    val fiscalizationDate: String = "",
    val cifNumber: String = "",
)

data class CommandResult(
    val success: Boolean,
    val returnCode: Int,
    val rawOutput: String,
    val fields: Map<String, String>
)

data class RevizieResult(
    val serviceDateSet: Boolean = false,
    val anafCheckOk: Boolean = false,
    val simOk: Boolean = false,
    val cryptoOk: Boolean = false,
    val modemOk: Boolean = false,
    val zPrinted: Boolean = false,
    val newServiceDate: String = "",
    val ejText: String = "",
    val error: String = "",
    val log: String = "",
    val pvText: String = "",
    val pdfFileName: String = ""
)
