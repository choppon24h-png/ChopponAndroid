# Diagnóstico: Válvula Abre e Fecha Imediatamente

## 1. Resumo do Problema
O problema relatado é que a válvula da chopeira abre e fecha imediatamente, sem manter o tempo necessário para a saída do fluxo de chopp. A análise do código e do logcat revela que a causa raiz está em uma colisão de comandos BLE e na interpretação prematura de respostas do ESP32.

## 2. Análise do Logcat
Observando o logcat fornecido, notamos o seguinte padrão durante a liberação do chopp:

1. O aplicativo envia o comando de timeout `$TO:10` (10 segundos).
2. O ESP32 responde com `OK`.
3. O aplicativo envia o comando de liberação `$ML:500` (500 ml).
4. O ESP32 responde com `OK`.
5. **Imediatamente após o OK**, o ESP32 envia `QP:0` (0 pulsos) e `ML:0.00` (0 ml liberados).
6. O aplicativo interpreta `ML:0.00` como o fim da liberação, fechando a tela e chamando a API `finish_sale.php` com 0 ml.
7. O aplicativo entra em um loop de "retry" (Continuar servindo), repetindo o processo e falhando da mesma forma.

Trecho do logcat evidenciando o problema:
```log
2026-05-04 00:09:53.327 ... [BLE] Enviando: $ML:500 | BLE status=ready
2026-05-04 00:09:53.377 ... [BLE] OK do $ML recebido — iniciando watchdog
2026-05-04 00:09:53.441 ... [ESP32] QP:0
2026-05-04 00:09:53.453 ... [ESP32] ML:0.00
2026-05-04 00:09:53.454 ... [BLE] Liberacao encerrada: 0mL de 500mL
```

## 3. Causa Raiz

A causa raiz é uma combinação de fatores entre o App Android e o Firmware do ESP32:

### 3.1. Unidade de Tempo Incorreta no Comando `$TO`
No arquivo `PagamentoConcluido.java`, o aplicativo envia o comando `$TO:10` acreditando que o ESP32 espera o valor em **segundos**.
```java
// PagamentoConcluido.java
// Firmware ESP32 espera SEGUNDOS no parâmetro de $TO
String cmd = "$TO:" + timeoutSegundos; // Envia $TO:10
```
No entanto, no arquivo `BleManagerV2.kt` (que documenta o protocolo v4.0), está explícito que o comando `$TO` espera o valor em **milissegundos**:
```kotlin
// BleManagerV2.kt
*    $TO:<ms>      — Configurar timeout
```
Ao enviar `$TO:10`, o aplicativo está configurando o timeout de inatividade do ESP32 para **10 milissegundos** (0,01 segundos) em vez de 10 segundos.

### 3.2. Comportamento do Firmware (ESP32)
Como o timeout de inatividade foi configurado para apenas 10ms, assim que a válvula abre após o comando `$ML:500`, o firmware do ESP32 detecta imediatamente uma "inatividade" (falta de pulsos do fluxômetro por mais de 10ms).
Como resultado, o ESP32 aborta a liberação por segurança (timeout) e envia imediatamente o fechamento da operação: `QP:0` e `ML:0.00`.

### 3.3. Colisão com o Keepalive (PING)
O `BluetoothServiceIndustrial` possui um mecanismo de keepalive que envia um comando `PING` a cada 5 segundos. No logcat, vemos que os comandos `PING` continuam sendo enviados mesmo durante a tentativa de liberação, o que pode congestionar a fila BLE ou interromper o estado do ESP32.

## 4. Recomendações de Correção

Para resolver o problema sem alterar a arquitetura atual, as seguintes correções devem ser aplicadas no aplicativo Android:

### Correção 1: Ajustar a Unidade do Timeout
No arquivo `PagamentoConcluido.java`, o valor do timeout deve ser convertido para milissegundos antes de ser enviado ao ESP32.

**Onde alterar:** `PagamentoConcluido.java` no método `enviarTimeoutESP32`
```java
// ANTES:
String cmd = "$TO:" + timeoutSegundos;

// DEPOIS:
int timeoutMs = timeoutSegundos * 1000;
String cmd = "$TO:" + timeoutMs;
```

### Correção 2: Pausar o PING durante a Liberação
O envio contínuo de `PING` a cada 5 segundos pelo `BluetoothServiceIndustrial` deve ser suspenso enquanto uma liberação de chopp (`$ML`) estiver em andamento, para evitar colisões na comunicação serial (UART) do ESP32.

## 5. Conclusão
A válvula abre e fecha imediatamente porque o aplicativo Android está configurando o timeout de inatividade do ESP32 para **10 milissegundos** em vez de 10 segundos. O ESP32 abre a válvula, mas como não detecta fluxo nos primeiros 10ms, ele aciona a trava de segurança por inatividade e encerra a operação, retornando `ML:0.00`. Corrigir a unidade de tempo no comando `$TO` resolverá o problema.
