package com.example.choppontap.ble

import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BleManagerV2.kt — Orquestrador BLE com autenticação HMAC-SHA256 v2.0
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROTOCOLO v2.0 — ESP32 FRANQ
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ENVIO (Android → ESP32):
 *    SERVE:   SERVE|<ml>|<CMD_ID>|<SESSION_ID>|<HMAC>
 *    AUTH:    AUTH|<CMD_ID>|<SESSION_ID>|<HMAC>
 *    STOP:    STOP|<CMD_ID>|<SESSION_ID>|<HMAC>
 *    STATUS:  STATUS|<CMD_ID>
 *    PING:    PING|<CMD_ID>
 *
 *  RECEPÇÃO (ESP32 → Android):
 *    ACK|<CMD_ID>
 *    DONE|<CMD_ID>|<ML_REAL>|<SESSION_ID>
 *    AUTH:OK
 *    AUTH:FAIL
 *    ERROR:SESSION_MISMATCH
 *    ERROR:NOT_AUTHENTICATED
 *    ERROR:VOLUME_EXCEEDED
 *    ERROR:TIMEOUT
 *    ERROR:BUSY
 *    ERROR:WATCHDOG
 *    WARN:FLOW_TIMEOUT          ← barril vazio / sem fluxo
 *    WARN:VOLUME_EXCEEDED
 *    STATUS:READY
 *    STATUS:BUSY
 *    PONG|<CMD_ID>
 *    VP:<ML_PARCIAL>
 *    VALVE:OPEN
 *    VALVE:CLOSED
 *    DUPLICATE
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UUIDs do Serviço BLE — Protocolo v2.0 (7f0a0001-...)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  SERVICE:  7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001
 *  RX (TX→ESP32): 7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001
 *  TX (ESP32→RX): 7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001
 *
 * ═══════════════════════════════════════════════════════════════════════
 * ARQUITETURA
 * ═══════════════════════════════════════════════════════════════════════
 *
 *   BluetoothServiceIndustrial
 *        │
 *        ▼
 *   BleManagerV2  ◄──── PagamentoConcluido (via getCommandQueue)
 *        │
 *        ├── BleParser         (parse de mensagens BLE)
 *        ├── ConnectionManager (estados + heartbeat + reconexão)
 *        └── CommandQueue      (fila FIFO ACK/DONE)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * SEGURANÇA
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  - Token HMAC-SHA256 gerado com chave secreta compartilhada
 *  - CMD_ID único por comando (UUID truncado 8 chars)
 *  - SESSION_ID único por sessão de venda
 *  - Proteção anti-replay via CMD_ID validado no ESP32
 *
 * @author  ChoppOnTap — Equipe de Desenvolvimento
 * @version 2.0.0
 * @since   2026-03-22
 */
class BleManagerV2 {

    companion object {
        private const val TAG = "BLE_MANAGER_V2"

        // ── UUIDs do Serviço BLE v2.0 ────────────────────────────────────────
        const val NUS_SERVICE_UUID            = "7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001"
        const val NUS_RX_CHARACTERISTIC_UUID  = "7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001"
        const val NUS_TX_CHARACTERISTIC_UUID  = "7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001"
        const val CCCD_UUID                   = "00002902-0000-1000-8000-00805f9b34fb"

        // ── Prefixo do nome BLE ───────────────────────────────────────────────
        const val BLE_NAME_PREFIX = "CHOPP_"

        // ── Chave HMAC compartilhada com o firmware ESP32 ────────────────────
        // IMPORTANTE: Esta chave DEVE ser idêntica à HMAC_SECRET_KEY no config.h do ESP32
        // Em produção, considere obter esta chave via API segura (HTTPS) no login do franqueado
        private const val HMAC_SECRET_KEY = "Choppon103614@"

        // ── Algoritmo HMAC ────────────────────────────────────────────────────
        private const val HMAC_ALGORITHM = "HmacSHA256"

        // ── Comprimento do token HMAC no comando (primeiros 16 chars do hex) ─
        // Token completo (64 chars hex) — não truncar para manter compatibilidade com firmware v2.0
        private const val HMAC_TOKEN_LENGTH = 64
    }

    // ── Interface de callback para o BluetoothServiceIndustrial ─────────────
    interface Callback {
        /** Novo estado de conexão BLE */
        fun onStateChanged(newState: BleState, oldState: BleState)
        /** Solicitação de conexão GATT */
        fun onConnectRequested(mac: String, autoConnect: Boolean)
        /** Comando enviado via BLE */
        fun onCommandSent(cmdId: String, type: String)
        /** ACK recebido do ESP32 */
        fun onCommandAck(cmdId: String)
        /** DONE recebido — mlReal disponível */
        fun onCommandDone(cmdId: String, mlReal: Int, sessionId: String?)
        /** Erro no comando */
        fun onCommandError(cmdId: String?, reason: String)
        /** Alerta de barril vazio / sem fluxo */
        fun onFlowWarning(warningType: String)
        /** Heartbeat falhou */
        fun onHeartbeatFailed()
    }

    // ── Estados de conexão BLE v2.0 ──────────────────────────────────────────
    enum class BleState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED,
        READY,
        ERROR
    }

    // ── Estado atual ─────────────────────────────────────────────────────────
    private var currentState: BleState = BleState.DISCONNECTED

    // ── Callback injetado ────────────────────────────────────────────────────
    private var callback: Callback? = null

    // ── Interface de escrita BLE ─────────────────────────────────────────────
    private var bleWriter: ((String) -> Boolean)? = null

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
    // Geração de Token HMAC-SHA256
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Gera token HMAC-SHA256 no formato exigido pelo firmware v2.0:
     * payload = "$timestamp:$sessionId"
     * token   = "$hmacHex:$timestamp"
     */
    fun generateAuthToken(sessionId: String): String {
        return try {
            val timestampSeg = System.currentTimeMillis() / 1000L
            val payload = "$timestampSeg:$sessionId"
            val keySpec = SecretKeySpec(HMAC_SECRET_KEY.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(keySpec)
            val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }
            val token = "$hmacHex:$timestampSeg"
            Log.d("BLE_AUTH", "Payload: $payload")
            Log.d("BLE_AUTH", "Token gerado: $token")
            token
        } catch (e: Exception) {
            Log.e(TAG, "[HMAC] Erro ao gerar token: ${e.message}")
            "0".repeat(64) + ":0"
        }
    }

    // Mantido por compatibilidade com chamadas internas de SERVE/STOP
    fun generateAuthToken(command: String, cmdId: String, sessionId: String): String {
        return try {
            val timestampSeg = System.currentTimeMillis() / 1000L
            val payload = "$timestampSeg:$sessionId"
            val keySpec = SecretKeySpec(HMAC_SECRET_KEY.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(keySpec)
            val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            val hmacHex = hmacBytes.joinToString("") { "%02x".format(it) }
            "$hmacHex:$timestampSeg"
        } catch (e: Exception) {
            Log.e(TAG, "[HMAC] Erro ao gerar token ($command): ${e.message}")
            "0".repeat(64) + ":0"
        }
    }

    /**
     * Gera um CMD_ID único de 8 caracteres alfanuméricos maiúsculos.
     * Baseado em UUID aleatório truncado para garantir unicidade.
     */
    fun generateCmdId(): String {
        return java.util.UUID.randomUUID().toString()
            .replace("-", "")
            .take(8)
            .uppercase()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Construção de Comandos BLE v2.0
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Constrói o comando SERVE com token HMAC.
     * Formato: SERVE|<ml>|<CMD_ID>|<SESSION_ID>|<HMAC>
     */
    fun buildServeCommand(volumeMl: Int, cmdId: String, sessionId: String): String {
        val token = generateAuthToken("SERVE", cmdId, sessionId)
        return "SERVE|$volumeMl|$cmdId|$sessionId|$token"
    }

    /**
     * Constrói o comando AUTH com token HMAC.
     * Formato: AUTH|<CMD_ID>|<SESSION_ID>|<HMAC>
     */
    fun buildAuthCommand(cmdId: String, sessionId: String): String {
        val token = generateAuthToken(sessionId)
        val command = "AUTH|$token|$cmdId|$sessionId"
        Log.d("BLE_AUTH", "Comando enviado: $command")
        return command
    }

    /**
     * Constrói o comando STOP com token HMAC.
     * Formato: STOP|<CMD_ID>|<SESSION_ID>|<HMAC>
     */
    fun buildStopCommand(cmdId: String, sessionId: String): String {
        val token = generateAuthToken("STOP", cmdId, sessionId)
        return "STOP|$cmdId|$sessionId|$token"
    }

    /**
     * Constrói o comando STATUS (sem HMAC — comando de consulta).
     * Formato: STATUS|<CMD_ID>
     */
    fun buildStatusCommand(cmdId: String): String {
        return "STATUS|$cmdId"
    }

    /**
     * Constrói o comando PING (heartbeat, sem HMAC).
     * Formato: PING|<CMD_ID>
     */
    fun buildPingCommand(cmdId: String): String {
        return "PING|$cmdId"
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Envio de Comandos
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Envia um comando SERVE para o ESP32.
     * Gera CMD_ID único e token HMAC automaticamente.
     *
     * @param volumeMl  Volume em ml a ser servido
     * @param sessionId ID da sessão de venda (ex: "SES_8472ABCD")
     * @return          CMD_ID gerado, ou null se falhou
     */
    fun sendServe(volumeMl: Int, sessionId: String): String? {
        if (currentState != BleState.READY) {
            Log.e(TAG, "[SERVE] Bloqueado — estado=$currentState (esperado READY)")
            callback?.onCommandError(null, "BLE_NOT_READY")
            return null
        }

        val cmdId = generateCmdId()
        val command = buildServeCommand(volumeMl, cmdId, sessionId)

        return if (writeCommand(command)) {
            Log.i(TAG, "[SERVE] Enviado: vol=${volumeMl}ml cmd=$cmdId session=$sessionId")
            callback?.onCommandSent(cmdId, "SERVE")
            cmdId
        } else {
            Log.e(TAG, "[SERVE] Falha ao escrever no BLE")
            callback?.onCommandError(cmdId, "BLE_WRITE_FAILED")
            null
        }
    }

    /**
     * Envia autenticação AUTH após conexão BLE estabelecida.
     * Chamado automaticamente pelo BluetoothServiceIndustrial após discoverServices.
     */
    fun sendAuth(sessionId: String): String? {
        val cmdId = generateCmdId()
        val command = buildAuthCommand(cmdId, sessionId)

        return if (writeCommand(command)) {
            Log.i(TAG, "[AUTH] Enviado: cmd=$cmdId session=$sessionId")
            callback?.onCommandSent(cmdId, "AUTH")
            cmdId
        } else {
            Log.e(TAG, "[AUTH] Falha ao escrever no BLE")
            null
        }
    }

    /**
     * Envia comando STOP para interromper dispensação.
     */
    fun sendStop(cmdId: String, sessionId: String): Boolean {
        val command = buildStopCommand(cmdId, sessionId)
        return writeCommand(command).also { success ->
            if (success) Log.i(TAG, "[STOP] Enviado: cmd=$cmdId")
            else Log.e(TAG, "[STOP] Falha ao escrever no BLE")
        }
    }

    /**
     * Envia STATUS para consultar estado do ESP32.
     */
    fun sendStatus(): Boolean {
        val cmdId = generateCmdId()
        val command = buildStatusCommand(cmdId)
        return writeCommand(command).also { success ->
            if (success) Log.d(TAG, "[STATUS] Enviado: cmd=$cmdId")
        }
    }

    /**
     * Envia PING para heartbeat.
     */
    fun sendPing(): Boolean {
        val cmdId = generateCmdId()
        val command = buildPingCommand(cmdId)
        return writeCommand(command).also { success ->
            if (success) Log.d(TAG, "[PING] Enviado: cmd=$cmdId")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Processamento de Respostas do ESP32
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Processa dados recebidos do ESP32 via BLE.
     * Deve ser chamado em onCharacteristicChanged() do BluetoothServiceIndustrial.
     *
     * Suporta todos os formatos do protocolo v2.0:
     *   ACK|<CMD_ID>
     *   DONE|<CMD_ID>|<ML_REAL>|<SESSION_ID>
     *   AUTH:OK / AUTH:FAIL
     *   ERROR:<TIPO>
     *   WARN:<TIPO>
     *   STATUS:<ESTADO>
     *   PONG|<CMD_ID>
     *   VP:<ML>
     *   VALVE:OPEN / VALVE:CLOSED
     *   DUPLICATE
     */
    fun onBleDataReceived(raw: String) {
        if (raw.isBlank()) return

        val s = raw.trim()
        Log.d(TAG, "[RX] $s")

        when {
            // ── ACK ──────────────────────────────────────────────────────────
            s.startsWith("ACK|") -> {
                val cmdId = s.substringAfter("ACK|").trim()
                Log.i(TAG, "[ACK] cmd=$cmdId")
                callback?.onCommandAck(cmdId)
            }

            // ── DONE ─────────────────────────────────────────────────────────
            s.startsWith("DONE|") -> {
                val parts = s.split("|")
                val cmdId     = parts.getOrNull(1)?.trim() ?: ""
                val mlReal    = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                val sessionId = parts.getOrNull(3)?.trim()
                Log.i(TAG, "[DONE] cmd=$cmdId ml=${mlReal}ml session=$sessionId")
                callback?.onCommandDone(cmdId, mlReal, sessionId)
            }

            // ── AUTH:OK ───────────────────────────────────────────────────────
            s == "AUTH:OK" || s.startsWith("AUTH_OK") -> {
                Log.i(TAG, "[AUTH] OK → transicionando para READY")
                transitionState(BleState.READY)
            }

            // ── AUTH:FAIL ─────────────────────────────────────────────────────
            s == "AUTH:FAIL" || s.startsWith("AUTH_FAIL") -> {
                Log.e(TAG, "[AUTH] FAIL — token inválido ou sessão expirada")
                callback?.onCommandError(null, "AUTH_FAIL")
            }

            // ── WARN: alertas operacionais ────────────────────────────────────
            s.startsWith("WARN:") -> {
                val warnType = s.substringAfter("WARN:").trim()
                Log.w(TAG, "[WARN] $warnType")
                when (warnType) {
                    "FLOW_TIMEOUT"     -> {
                        Log.w(TAG, "[WARN] BARRIL VAZIO ou sem fluxo detectado!")
                        callback?.onFlowWarning("FLOW_TIMEOUT")
                    }
                    "VOLUME_EXCEEDED"  -> {
                        Log.w(TAG, "[WARN] Volume excedido!")
                        callback?.onFlowWarning("VOLUME_EXCEEDED")
                    }
                    else -> {
                        Log.w(TAG, "[WARN] Alerta desconhecido: $warnType")
                        callback?.onFlowWarning(warnType)
                    }
                }
            }

            // ── ERROR: erros do ESP32 ─────────────────────────────────────────
            s.startsWith("ERROR:") -> {
                val errorType = s.substringAfter("ERROR:").trim()
                Log.e(TAG, "[ERROR] $errorType")
                callback?.onCommandError(null, errorType)
            }

            // ── STATUS ────────────────────────────────────────────────────────
            s.startsWith("STATUS:") -> {
                val status = s.substringAfter("STATUS:").trim()
                Log.d(TAG, "[STATUS] $status")
                when (status) {
                    "READY" -> Log.i(TAG, "[STATUS] ESP32 READY")
                    "BUSY"  -> Log.w(TAG, "[STATUS] ESP32 BUSY")
                    "IDLE"  -> Log.i(TAG, "[STATUS] ESP32 IDLE")
                    else    -> Log.d(TAG, "[STATUS] Estado: $status")
                }
            }

            // ── PONG ──────────────────────────────────────────────────────────
            s.startsWith("PONG|") -> {
                val cmdId = s.substringAfter("PONG|").trim()
                Log.d(TAG, "[PONG] cmd=$cmdId — heartbeat OK")
            }

            // ── VP: progresso parcial ─────────────────────────────────────────
            s.startsWith("VP:") -> {
                val ml = s.substringAfter("VP:").trim().toDoubleOrNull()?.toInt() ?: 0
                Log.d(TAG, "[VP] progresso=${ml}ml")
                // Broadcast tratado pela Activity via BluetoothServiceIndustrial
            }

            // ── VALVE ─────────────────────────────────────────────────────────
            s.startsWith("VALVE:") -> {
                val valveState = s.substringAfter("VALVE:").trim()
                Log.d(TAG, "[VALVE] $valveState")
            }

            // ── DUPLICATE ────────────────────────────────────────────────────
            s == "DUPLICATE" || s == "ML:DUPLICATE" -> {
                Log.w(TAG, "[DUPLICATE] Comando duplicado rejeitado pelo ESP32")
                callback?.onCommandError(null, "DUPLICATE")
            }

            // ── QUEUE:FULL ────────────────────────────────────────────────────
            s == "QUEUE:FULL" -> {
                Log.e(TAG, "[QUEUE] Fila do ESP32 cheia")
                callback?.onCommandError(null, "QUEUE_FULL")
            }

            // ── Mensagem desconhecida ─────────────────────────────────────────
            else -> {
                Log.d(TAG, "[RX] Mensagem não reconhecida pelo protocolo v2.0: [$s]")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Gerenciamento de Estado
    // ═════════════════════════════════════════════════════════════════════════

    fun onGattConnected(mac: String) {
        Log.i(TAG, "[BLE] GATT conectado → $mac")
        transitionState(BleState.CONNECTED)
    }

    fun onGattDisconnected() {
        Log.w(TAG, "[BLE] GATT desconectado")
        transitionState(BleState.DISCONNECTED)
    }

    fun onAuthOk() {
        Log.i(TAG, "[BLE] AUTH:OK → READY")
        transitionState(BleState.READY)
    }

    fun onScanStarted() {
        transitionState(BleState.SCANNING)
    }

    fun getState(): BleState = currentState

    fun isReady(): Boolean = currentState == BleState.READY

    fun isConnected(): Boolean = currentState == BleState.CONNECTED
            || currentState == BleState.AUTHENTICATED
            || currentState == BleState.READY

    // ═════════════════════════════════════════════════════════════════════════
    // Internos
    // ═════════════════════════════════════════════════════════════════════════

    private fun transitionState(newState: BleState) {
        val old = currentState
        if (old == newState) return
        currentState = newState
        Log.i(TAG, "[STATE] $old → $newState")
        callback?.onStateChanged(newState, old)
    }

    private fun writeCommand(command: String): Boolean {
        val writer = bleWriter
        if (writer == null) {
            Log.e(TAG, "[WRITE] BleWriter não configurado!")
            return false
        }
        return writer(command)
    }

    override fun toString(): String {
        return "BleManagerV2{state=$currentState}"
    }
}
