# Relatório de Conformidade: Integração Android vs ESP32

Este relatório apresenta a análise de conformidade entre o documento de integração do firmware ESP32 (atualizado em 24/04/2026) e o código-fonte do aplicativo Android (`ChopponAndroid`). O objetivo é identificar discrepâncias que possam causar falhas na leitura do volume dispensado ou instabilidades na comunicação BLE.

## 1. Análise do Comportamento do Sensor (Logcat 3)

Antes de detalhar as não-conformidades de software, é crucial esclarecer o comportamento observado no último logcat:

O logcat mostra a seguinte sequência recebida do ESP32:
```text
[RX] Recebido: VP:0.170
[RX] Recebido: VP:0.170
[RX] Recebido: VP:0.170
[RX] Recebido: QP:1
[RX] Recebido: ML:0.17
```

O documento de integração especifica que a sequência esperada de `VP` deve ser **crescente** (ex: `VP:50`, `VP:150`), pois o valor representa o volume **acumulado** desde o início da dispensação. 

O fato de o ESP32 enviar `VP:0.170` repetidamente e encerrar com `QP:1` (1 pulso) indica inequivocamente que **o sensor de fluxo físico contou apenas 1 pulso e parou**. O aplicativo Android está processando corretamente esse valor (atribuindo `liberadoFloat = mlFloat`), mas o hardware não está enviando novos pulsos. **Isso aponta para um problema mecânico no sensor (hélice travada) ou fluxo de líquido insuficiente para girar a hélice continuamente.**

## 2. Não-Conformidades Críticas no Código Android

Apesar de o problema imediato do volume `0.17ml` ser de hardware, a auditoria do código Android revelou não-conformidades críticas com as APIs modernas do Android, que **causarão a perda total de leitura de volume em dispositivos mais recentes**.

### 2.1. Incompatibilidade com Android 13+ (API 33+)

O aplicativo está configurado com `targetSdk = 35` e `minSdk = 31`. No entanto, a implementação do Bluetooth GATT utiliza APIs que foram descontinuadas e tiveram seu comportamento alterado no Android 13 (API 33).

**O Problema:**
No arquivo `BluetoothServiceIndustrial.java`, o aplicativo implementa apenas a assinatura antiga do callback de notificação:
```java
@Override
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
    byte[] data = characteristic.getValue(); // Deprecated na API 33
    // ...
}
```

A partir do Android 13 (API 33), o sistema operacional **não chama mais essa assinatura**. Em vez disso, ele chama exclusivamente a nova assinatura que passa o array de bytes diretamente:
```java
@Override
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
    // Nova implementação exigida
}
```

**Impacto:**
Se o aplicativo for executado em um dispositivo com Android 13, 14 ou 15, **as notificações `VP:`, `QP:` e `ML:` enviadas pelo ESP32 chegarão ao rádio Bluetooth do celular, mas nunca serão entregues ao aplicativo**. O app ficará aguardando indefinidamente e a tela mostrará "0 ML" até o timeout disparar.

### 2.2. APIs de Escrita BLE Descontinuadas

O aplicativo utiliza métodos de escrita BLE que também foram descontinuados na API 33 e substituídos por versões assíncronas mais seguras.

**O Problema:**
No envio de comandos (`$ML:`, `$TO:`, etc.) e na habilitação de notificações (Descriptor `0x2902`), o código faz:
```java
// Escrita de Característica (Antigo)
mRxChar.setValue(bytes);
mGatt.writeCharacteristic(mRxChar);

// Escrita de Descriptor (Antigo)
descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
gatt.writeDescriptor(descriptor);
```

**Impacto:**
Embora essas APIs antigas ainda funcionem por compatibilidade retroativa na maioria dos aparelhos, elas são conhecidas por causar condições de corrida (race conditions) em conexões BLE rápidas, pois modificam o estado local do objeto `BluetoothGattCharacteristic` antes de o sistema operacional enfileirar a escrita.

## 3. Conformidades Verificadas (O que está correto)

A auditoria também confirmou que o aplicativo **está em conformidade** com os seguintes requisitos do documento de integração:

| Requisito do Documento | Status no Android | Observação |
| :--- | :--- | :--- |
| **Modo Just Works (Sem PIN)** | ✅ Conforme | O app chama `connectGatt(..., false, ..., TRANSPORT_LE)` sem tentar `createBond()` ou injetar PIN. |
| **MTU 247** | ✅ Conforme | O app solicita `requestMtu(247)` imediatamente após a conexão. |
| **Habilitar Notificações** | ✅ Conforme | O app escreve `ENABLE_NOTIFICATION_VALUE` no descriptor `0x2902` da característica TX. |
| **Estado READY** | ✅ Conforme | O app só muda para `READY` após o sucesso no `onDescriptorWrite`. |
| **Keepalive (PING/PONG)** | ✅ Conforme | O app envia `PING` a cada 5s em estado `READY` e suspende o envio durante a dispensação. |
| **Formato dos Comandos** | ✅ Conforme | O app envia `$ML:300` (com dois pontos, não vírgula) e anexa `\n` no final. |
| **Tratamento de VP Acumulado** | ✅ Conforme | O app atribui o valor de `VP` diretamente à variável de controle, sem somar deltas, o que é correto para valores acumulados. |

## 4. Recomendações de Correção

Para garantir o funcionamento perfeito em todos os dispositivos Android e resolver as não-conformidades encontradas, recomendo as seguintes ações:

### Recomendação 1: Atualizar Callbacks do GATT (Urgente)
Adicionar a nova assinatura do `onCharacteristicChanged` no `BluetoothServiceIndustrial.java` para suportar Android 13+:

```java
// Manter a antiga para Android 12 (API 31-32)
@Override
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
    processarDadosRecebidos(characteristic.getValue());
}

// Adicionar a nova para Android 13+ (API 33+)
@Override
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
    processarDadosRecebidos(value);
}

private void processarDadosRecebidos(byte[] data) {
    if (data == null) return;
    String msg = new String(data, StandardCharsets.UTF_8).trim();
    // ... resto da lógica existente
}
```

### Recomendação 2: Atualizar Métodos de Escrita BLE
Atualizar as chamadas de escrita para usar as novas APIs quando disponíveis:

```java
// Para escrita de comandos
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    mGatt.writeCharacteristic(mRxChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
} else {
    mRxChar.setValue(bytes);
    mRxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    mGatt.writeCharacteristic(mRxChar);
}
```

### Recomendação 3: Revisão de Hardware
Como o logcat comprova que o ESP32 está enviando `VP:0.170` repetidamente, a equipe de hardware deve inspecionar a válvula e o sensor de fluxo utilizados no teste para identificar por que a hélice não está girando continuamente durante a passagem do líquido.

---
*Relatório gerado por Manus AI*
