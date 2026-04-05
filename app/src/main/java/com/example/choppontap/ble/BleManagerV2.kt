package com.example.choppontap.ble

import android.util.Log

/**
 * BleManagerV2.kt — Orquestrador BLE para protocolo NUS v4.0
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROTOCOLO NUS v4.0 — ESP32 ChoppON
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ENVIO (Android → ESP32):
 *    $ML:<volume>  — Liberar volume em ml
 *    $LB:          — Liberação contínua
 *    $ML:0         — Parar liberação
 *    $PL:<pulsos>  — Configurar pulsos/litro
 *    $PL:0         — Consultar pulsos/litro
 *    $TO:<ms>      — Configurar timeout
 *    $TO:          — Consultar timeout
 *
 *  RECEPÇÃO (ESP32 → Android):
 *    OK            — Comando aceito
 *    ERRO          — Comando rejeitado
 *    VP:<valor>    — Volume parcial liberado
 *    ML:<valor>    — Volume total final (operação concluída)
 *    PL:<valor>    — Pulsos por litro
 *    QP:<valor>    — Quantidade de pulsos
 *    TO:<valor>    — Timeout atual
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UUIDs do Nordic UART Service (NUS) — Protocolo v4.0
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  SERVICE:        6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *  RX (App→ESP32): 6E400002-B5A3-F393-E0A9-E50E24DCCA9E
 *  TX (ESP32→App): 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
 *
 * NÃO existe mais: HMAC, AUTH, SERVE, STOP, STATUS, PING, PONG,
 *                  ACK, DONE, CMD_ID, SESSION_ID
 *
 * @version 4.0.0
 */
class BleManagerV2 {

    companion object {
        private const val TAG = "BLE_MANAGER_V2"

        // ── UUIDs do Nordic UART Service (NUS) v4.0 ──────────────────────────
        const val NUS_SERVICE_UUID            = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        const val NUS_RX_CHARACTERISTIC_UUID  = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        const val NUS_TX_CHARACTERISTIC_UUID  = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        const val CCCD_UUID                   = "00002902-0000-1000-8000-00805f9b34fb"

        // ── Prefixo do nome BLE ───────────────────────────────────────────────
        const val BLE_NAME_PREFIX = "CHOPP_"
    }

    // ── Interface de callback para o BluetoothServiceIndustrial ─────────────
    interface Callback {
        /** Novo estado de conexão BLE */
        fun onStateChanged(newState: BleState, oldState: BleState)
        /** Solicitação de conexão GATT */
        fun onConnectRequested(mac: String, autoConnect: Boolean)
        /** Comando enviado via BLE */
        fun onCommandSent(command: String)
        /** Resposta recebida do ESP32 */
        fun onCommandResponse(command: String, response: String)
        /** Erro no comando */
        fun onCommandError(command: String?, reason: String)
        /** Keepalive falhou */
        fun onHeartbeatFailed()
    }

    // ── Estado atual ─────────────────────────────────────────────────────────
    private var currentState: BleState = BleState.DISCONNECTED

    // ── Callback injetado ────────────────────────────────────────────────────
    private var callback: Callback? = null

    // ── Interface de escrita BLE ─────────────────────────────────────────────
    private var bleWriter: ((String) -> Boolean)? = null

    // ── Comando ativo ────────────────────────────────────────────────────────
    private var activeCommand: String? = null

    // ═════════════════════════════════════════════════════════════════════════
    // Configuração
    // ═════════════════════════════════════════════════════════════════════════

    fun setCallback(cb: Callback) {
        this.callback = cb
    }

    fun setBleWriter(writer: (String) -> Boolean) {
        this.bleWriter = writer
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Envio de Comandos — Protocolo NUS v4.0
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Envia comando de liberação de volume.
     * Formato: $ML:<volume>
     */
    fun sendServe(volumeMl: Int): Boolean {
        val command = "\$ML:$volumeMl"
        return sendCommand(command)
    }

    /**
     * Envia comando de liberação contínua.
     * Formato: $LB:
     */
    fun sendContinuous(): Boolean {
        return sendCommand("\$LB:")
    }

    /**
     * Envia comando para parar liberação.
     * Formato: $ML:0
     */
    fun sendStop(): Boolean {
        return sendCommand("\$ML:0")
    }

    /**
     * Consulta pulsos por litro.
     * Formato: $PL:0
     */
    fun queryPulses(): Boolean {
        return sendCommand("\$PL:0")
    }

    /**
     * Configura pulsos por litro.
     * Formato: $PL:<pulsos>
     */
    fun setPulses(pulsesPerLiter: Int): Boolean {
        return sendCommand("\$PL:$pulsesPerLiter")
    }

    /**
     * Consulta timeout.
     * Formato: $TO:
     */
    fun queryTimeout(): Boolean {
        return sendCommand("\$TO:")
    }

    /**
     * Configura timeout.
     * Formato: $TO:<ms>
     */
    fun setTimeout(timeoutMs: Int): Boolean {
        return sendCommand("\$TO:$timeoutMs")
    }

    /**
     * Envia um comando genérico ao ESP32.
     */
    fun sendCommand(command: String): Boolean {
        if (currentState != BleState.READY) {
            Log.e(TAG, "[SEND] Bloqueado — estado=$currentState (esperado READY)")
            callback?.onCommandError(command, "Estado inválido: $currentState")
            return false
        }

        val writer = bleWriter
        if (writer == null) {
            Log.e(TAG, "[SEND] BLE writer não configurado")
            callback?.onCommandError(command, "BLE writer não configurado")
            return false
        }

        activeCommand = command
        val ok = writer(command)
        if (ok) {
            Log.i(TAG, "[SEND] Enviado: $command")
            callback?.onCommandSent(command)
        } else {
            Log.e(TAG, "[SEND] Falha ao enviar: $command")
            callback?.onCommandError(command, "Falha no write BLE")
            activeCommand = null
        }
        return ok
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Processamento de Respostas — Protocolo NUS v4.0
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processa dados recebidos do ESP32 via notificação BLE.
     * Respostas válidas: OK, ERRO, VP:, ML:, PL:, QP:, TO:
     */
    fun onBleDataReceived(data: String) {
        val trimmed = data.trim()
        Log.d(TAG, "[RX] $trimmed | ativo=$activeCommand")

        when {
            // OK — comando aceito
            trimmed.equals("OK", ignoreCase = true) -> {
                Log.i(TAG, "[RX] OK recebido")
                val cmd = activeCommand
                // Para comandos de configuração, OK é a resposta final
                if (cmd != null && !cmd.startsWith("\$ML:") && cmd != "\$LB:") {
                    activeCommand = null
                    callback?.onCommandResponse(cmd, trimmed)
                }
            }

            // ERRO — comando rejeitado
            trimmed.equals("ERRO", ignoreCase = true) -> {
                Log.e(TAG, "[RX] ERRO recebido")
                val cmd = activeCommand
                activeCommand = null
                callback?.onCommandError(cmd, "ESP32 retornou ERRO")
            }

            // ML: — Volume total final (operação concluída)
            trimmed.startsWith("ML:") -> {
                Log.i(TAG, "[RX] ML recebido — operação concluída: $trimmed")
                val cmd = activeCommand
                activeCommand = null
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }

            // VP: — Volume parcial
            trimmed.startsWith("VP:") -> {
                Log.d(TAG, "[RX] VP parcial: $trimmed")
                val cmd = activeCommand
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }

            // QP: — Quantidade de pulsos
            trimmed.startsWith("QP:") -> {
                Log.d(TAG, "[RX] QP: $trimmed")
                val cmd = activeCommand
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }

            // PL: — Pulsos por litro
            trimmed.startsWith("PL:") -> {
                Log.d(TAG, "[RX] PL: $trimmed")
                val cmd = activeCommand
                activeCommand = null
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }

            // TO: — Timeout
            trimmed.startsWith("TO:") -> {
                Log.d(TAG, "[RX] TO: $trimmed")
                val cmd = activeCommand
                activeCommand = null
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }

            else -> {
                Log.w(TAG, "[RX] Resposta desconhecida: $trimmed")
                val cmd = activeCommand
                if (cmd != null) callback?.onCommandResponse(cmd, trimmed)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Gerenciamento de Estado
    // ═════════════════════════════════════════════════════════════════════════

    fun setState(newState: BleState) {
        if (currentState == newState) return
        val old = currentState
        currentState = newState
        Log.i(TAG, "[STATE] $old -> $newState")
        callback?.onStateChanged(newState, old)
    }

    fun getState(): BleState = currentState

    fun isReady(): Boolean = currentState == BleState.READY

    fun isConnected(): Boolean =
        currentState == BleState.CONNECTED || currentState == BleState.READY

    fun getActiveCommand(): String? = activeCommand

    fun reset() {
        activeCommand = null
        currentState = BleState.DISCONNECTED
        Log.i(TAG, "[RESET] Estado resetado")
    }
}
