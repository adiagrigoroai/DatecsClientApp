package com.itsmart.datecs.client.device

import com.itsmart.datecs.client.data.model.CommandResult
import com.itsmart.datecs.client.data.model.ConnectionType

interface IDeviceConnection {
    val isConnected: Boolean
    suspend fun connect(address: String, type: ConnectionType): Boolean
    suspend fun disconnect()
    suspend fun executeCommand(cmd: Int, params: String = ""): CommandResult
    suspend fun cmd255Read(name: String, index: String = ""): String
    suspend fun cmd255Write(name: String, index: String = "", value: String): Boolean
}
