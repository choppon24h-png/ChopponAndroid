<?php
/**
 * API - Criar Pagamento
 * POST /api/create_payment.php
 *
 * Fluxo:
 *   1. Recebe dados do pagamento antes da liberacao BLE
 *   2. Garante idempotencia por idempotency_key
 *   3. Registra transacao local em payment_transaction
 *   4. Cria checkout PIX/cartao via SumUp
 *   5. Retorna checkout_id + dados do meio de pagamento
 *
 * Campos obrigatorios:
 *   - valor
 *   - descricao
 *   - android_id
 *   - payment_method (pix|debit|credit)
 *   - quantidade
 *   - cpf
 *
 * Campos opcionais:
 *   - idempotency_key
 *   - session_id
 */

ob_start();
header('Content-Type: application/json');

register_shutdown_function(function () {
    $error = error_get_last();
    if ($error && in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR], true)) {
        if (ob_get_level() > 0) {
            ob_clean();
        }
        if (!headers_sent()) {
            http_response_code(500);
            header('Content-Type: application/json');
        }
        echo json_encode([
            'success' => false,
            'error' => 'Erro interno no servidor: ' . $error['message'],
            'error_type' => 'FATAL_ERROR',
            'debug' => [
                'file' => basename($error['file']),
                'line' => $error['line'],
            ],
        ]);
        ob_end_flush();
    }
});

function paymentEnsureTable(PDO $conn): bool {
    try {
        $conn->exec("
            CREATE TABLE IF NOT EXISTS payment_transaction (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                order_id BIGINT UNSIGNED NULL,
                session_id VARCHAR(191) NULL,
                idempotency_key VARCHAR(191) NOT NULL,
                android_id VARCHAR(191) NOT NULL,
                payment_method VARCHAR(32) NOT NULL,
                valor DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                descricao VARCHAR(255) NOT NULL,
                quantidade INT NOT NULL DEFAULT 1,
                cpf VARCHAR(32) NOT NULL,
                checkout_id VARCHAR(191) NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'CREATING',
                response_payload MEDIUMTEXT NULL,
                last_error TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_payment_transaction_idempotency (idempotency_key),
                KEY idx_payment_transaction_checkout (checkout_id),
                KEY idx_payment_transaction_order (order_id),
                KEY idx_payment_transaction_session (session_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ");
        return true;
    } catch (Throwable $e) {
        Logger::warning('create_payment: nao foi possivel garantir tabela payment_transaction', [
            'error' => $e->getMessage(),
        ]);
        return false;
    }
}

function paymentFindByIdempotency(PDO $conn, string $idempotencyKey): ?array {
    try {
        $stmt = $conn->prepare("
            SELECT id, order_id, checkout_id, status, response_payload
            FROM payment_transaction
            WHERE idempotency_key = ?
            LIMIT 1
        ");
        $stmt->execute([$idempotencyKey]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        return $row ?: null;
    } catch (Throwable $e) {
        Logger::warning('create_payment: falha ao consultar idempotencia', [
            'idempotency_key' => $idempotencyKey,
            'error' => $e->getMessage(),
        ]);
        return null;
    }
}

function paymentUpsertTransaction(PDO $conn, array $payload): ?int {
    try {
        $stmt = $conn->prepare("
            INSERT INTO payment_transaction
                (order_id, session_id, idempotency_key, android_id, payment_method, valor, descricao, quantidade, cpf, checkout_id, status, response_payload, last_error)
            VALUES
                (:order_id, :session_id, :idempotency_key, :android_id, :payment_method, :valor, :descricao, :quantidade, :cpf, :checkout_id, :status, :response_payload, :last_error)
            ON DUPLICATE KEY UPDATE
                order_id = VALUES(order_id),
                session_id = VALUES(session_id),
                android_id = VALUES(android_id),
                payment_method = VALUES(payment_method),
                valor = VALUES(valor),
                descricao = VALUES(descricao),
                quantidade = VALUES(quantidade),
                cpf = VALUES(cpf),
                checkout_id = VALUES(checkout_id),
                status = VALUES(status),
                response_payload = VALUES(response_payload),
                last_error = VALUES(last_error),
                updated_at = CURRENT_TIMESTAMP
        ");
        $stmt->execute([
            ':order_id' => $payload['order_id'] ?? null,
            ':session_id' => $payload['session_id'] ?? null,
            ':idempotency_key' => $payload['idempotency_key'],
            ':android_id' => $payload['android_id'],
            ':payment_method' => $payload['payment_method'],
            ':valor' => $payload['valor'],
            ':descricao' => $payload['descricao'],
            ':quantidade' => $payload['quantidade'],
            ':cpf' => $payload['cpf'],
            ':checkout_id' => $payload['checkout_id'] ?? null,
            ':status' => $payload['status'],
            ':response_payload' => $payload['response_payload'] ?? null,
            ':last_error' => $payload['last_error'] ?? null,
        ]);

        $row = paymentFindByIdempotency($conn, $payload['idempotency_key']);
        return $row ? (int) $row['id'] : null;
    } catch (Throwable $e) {
        Logger::warning('create_payment: falha ao gravar payment_transaction', [
            'idempotency_key' => $payload['idempotency_key'] ?? null,
            'error' => $e->getMessage(),
        ]);
        return null;
    }
}

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    $headers = getallheaders();
    $token = $headers['token'] ?? $headers['Token'] ?? '';

    if (!jwtValidate($token)) {
        http_response_code(401);
        echo json_encode([
            'success' => false,
            'error' => 'Token invalido',
            'error_type' => 'AUTH_ERROR',
        ]);
        exit;
    }

    $input = $_POST;
    $requiredFields = ['valor', 'descricao', 'android_id', 'payment_method', 'quantidade', 'cpf'];
    foreach ($requiredFields as $field) {
        if (!isset($input[$field]) || $input[$field] === '') {
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'error' => $field . ' e obrigatorio',
                'error_type' => 'MISSING_FIELD',
            ]);
            exit;
        }
    }

    $paymentMethod = strtolower(trim($input['payment_method']));
    if (!in_array($paymentMethod, ['pix', 'debit', 'credit'], true)) {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'error' => 'payment_method invalido. Use: pix, debit ou credit',
            'error_type' => 'INVALID_METHOD',
        ]);
        exit;
    }

    $androidId = trim($input['android_id']);
    $valor = (string) $input['valor'];
    $descricao = trim($input['descricao']);
    $quantidade = (int) $input['quantidade'];
    $cpf = trim($input['cpf']);
    $sessionId = trim($input['session_id'] ?? '');
    $idempotencyKey = trim($input['idempotency_key'] ?? '');
    if ($idempotencyKey === '') {
        $idempotencyKey = hash('sha256', implode('|', [
            $androidId,
            $paymentMethod,
            $valor,
            $descricao,
            $quantidade,
            $cpf,
        ]));
    }

    $conn = getDBConnection();
    $transactionTableReady = paymentEnsureTable($conn);

    if ($transactionTableReady) {
        $existing = paymentFindByIdempotency($conn, $idempotencyKey);
        if ($existing && !empty($existing['response_payload'])) {
            Logger::info('create_payment: replay idempotente', [
                'idempotency_key' => $idempotencyKey,
                'transaction_id' => $existing['id'],
                'checkout_id' => $existing['checkout_id'] ?? null,
                'status' => $existing['status'] ?? null,
            ]);
            http_response_code(200);
            echo $existing['response_payload'];
            exit;
        }
        if ($existing && in_array($existing['status'], ['CREATING', 'PROCESSING'], true)) {
            http_response_code(202);
            echo json_encode([
                'success' => false,
                'status' => 'processing',
                'error' => 'Pagamento em processamento para esta chave de idempotencia',
                'error_type' => 'PAYMENT_PROCESSING',
                'transaction_id' => (int) $existing['id'],
                'idempotency_key' => $idempotencyKey,
            ]);
            exit;
        }
    }

    $stmt = $conn->prepare("SELECT * FROM tap WHERE android_id = ? LIMIT 1");
    $stmt->execute([$androidId]);
    $tap = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$tap) {
        http_response_code(404);
        echo json_encode([
            'success' => false,
            'error' => 'TAP nao encontrada',
            'error_type' => 'TAP_NOT_FOUND',
        ]);
        exit;
    }

    $stmt = $conn->prepare("
        INSERT INTO `order`
            (tap_id, bebida_id, estabelecimento_id, method, valor, descricao, quantidade, cpf, checkout_status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
    ");
    $stmt->execute([
        $tap['id'],
        $tap['bebida_id'],
        $tap['estabelecimento_id'],
        $paymentMethod,
        $valor,
        $descricao,
        $quantidade,
        $cpf,
    ]);
    $orderId = (int) $conn->lastInsertId();

    $transactionId = null;
    if ($transactionTableReady) {
        $transactionId = paymentUpsertTransaction($conn, [
            'order_id' => $orderId,
            'session_id' => $sessionId !== '' ? $sessionId : null,
            'idempotency_key' => $idempotencyKey,
            'android_id' => $androidId,
            'payment_method' => $paymentMethod,
            'valor' => $valor,
            'descricao' => $descricao,
            'quantidade' => $quantidade,
            'cpf' => $cpf,
            'checkout_id' => null,
            'status' => 'CREATING',
            'response_payload' => null,
            'last_error' => null,
        ]);
    }

    Logger::info('create_payment: pedido criado', [
        'order_id' => $orderId,
        'transaction_id' => $transactionId,
        'idempotency_key' => $idempotencyKey,
        'payment_method' => $paymentMethod,
        'android_id' => $androidId,
    ]);

    $sumup = new SumUpIntegration();
    $orderData = [
        'id' => $orderId,
        'valor' => $valor,
        'descricao' => $descricao,
    ];

    $responsePayload = null;

    if ($paymentMethod === 'pix') {
        $result = $sumup->createCheckoutPix($orderData);
        if (!$result) {
            $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?")->execute([$orderId]);
            if ($transactionTableReady) {
                paymentUpsertTransaction($conn, [
                    'order_id' => $orderId,
                    'session_id' => $sessionId !== '' ? $sessionId : null,
                    'idempotency_key' => $idempotencyKey,
                    'android_id' => $androidId,
                    'payment_method' => $paymentMethod,
                    'valor' => $valor,
                    'descricao' => $descricao,
                    'quantidade' => $quantidade,
                    'cpf' => $cpf,
                    'checkout_id' => null,
                    'status' => 'FAILED',
                    'response_payload' => null,
                    'last_error' => 'PIX_CHECKOUT_FAILED',
                ]);
            }
            http_response_code(500);
            echo json_encode([
                'success' => false,
                'error' => 'Erro ao criar checkout PIX',
                'error_type' => 'PIX_CHECKOUT_FAILED',
                'transaction_id' => $transactionId,
                'idempotency_key' => $idempotencyKey,
            ]);
            exit;
        }

        $stmt = $conn->prepare("
            UPDATE `order`
            SET checkout_id = ?, pix_code = ?, response = ?
            WHERE id = ?
        ");
        $stmt->execute([
            $result['checkout_id'],
            $result['pix_code'],
            $result['response'],
            $orderId,
        ]);

        $qrCodeBase64 = '';
        if (!empty($result['pix_code'])) {
            $qrCodeBase64 = $sumup->generateQRCode($result['pix_code']);
        }

        $responsePayload = [
            'success' => true,
            'payment_status' => 'PENDING',
            'transaction_id' => $transactionId,
            'idempotency_key' => $idempotencyKey,
            'session_id' => $sessionId !== '' ? $sessionId : null,
            'order_id' => $orderId,
            'checkout_id' => $result['checkout_id'],
            'qr_code' => $qrCodeBase64,
            'pix_code' => $result['pix_code'],
        ];
    } else {
        if (empty($tap['reader_id'])) {
            $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?")->execute([$orderId]);
            if ($transactionTableReady) {
                paymentUpsertTransaction($conn, [
                    'order_id' => $orderId,
                    'session_id' => $sessionId !== '' ? $sessionId : null,
                    'idempotency_key' => $idempotencyKey,
                    'android_id' => $androidId,
                    'payment_method' => $paymentMethod,
                    'valor' => $valor,
                    'descricao' => $descricao,
                    'quantidade' => $quantidade,
                    'cpf' => $cpf,
                    'checkout_id' => null,
                    'status' => 'FAILED',
                    'response_payload' => null,
                    'last_error' => 'NO_READER_CONFIGURED',
                ]);
            }
            http_response_code(400);
            echo json_encode([
                'success' => false,
                'error' => 'TAP sem leitora configurada',
                'error_type' => 'NO_READER_CONFIGURED',
                'transaction_id' => $transactionId,
                'idempotency_key' => $idempotencyKey,
            ]);
            exit;
        }

        $readerName = null;
        $readerSerial = null;
        try {
            $stmtReader = $conn->prepare("SELECT name, serial FROM readers WHERE reader_id = ? LIMIT 1");
            $stmtReader->execute([$tap['reader_id']]);
            $readerRow = $stmtReader->fetch(PDO::FETCH_ASSOC);
            if ($readerRow) {
                $readerName = $readerRow['name'] ?? null;
                $readerSerial = $readerRow['serial'] ?? null;
            }
        } catch (Throwable $e) {
            Logger::debug('create_payment: tabela readers nao disponivel', []);
        }
        if (empty($readerName)) {
            $readerName = 'Leitora ' . substr($tap['reader_id'], -6);
        }

        $result = $sumup->createCheckoutCard($orderData, $tap['reader_id'], $paymentMethod);
        if (!$result) {
            $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?")->execute([$orderId]);
            if ($transactionTableReady) {
                paymentUpsertTransaction($conn, [
                    'order_id' => $orderId,
                    'session_id' => $sessionId !== '' ? $sessionId : null,
                    'idempotency_key' => $idempotencyKey,
                    'android_id' => $androidId,
                    'payment_method' => $paymentMethod,
                    'valor' => $valor,
                    'descricao' => $descricao,
                    'quantidade' => $quantidade,
                    'cpf' => $cpf,
                    'checkout_id' => null,
                    'status' => 'FAILED',
                    'response_payload' => null,
                    'last_error' => 'CARD_CHECKOUT_FAILED',
                ]);
            }
            http_response_code(500);
            echo json_encode([
                'success' => false,
                'error' => 'Erro ao criar checkout de cartao',
                'error_type' => 'CARD_CHECKOUT_FAILED',
                'transaction_id' => $transactionId,
                'idempotency_key' => $idempotencyKey,
            ]);
            exit;
        }

        $stmt = $conn->prepare("
            UPDATE `order`
            SET checkout_id = ?, response = ?, checkout_status = 'PENDING'
            WHERE id = ?
        ");
        $stmt->execute([
            $result['checkout_id'],
            $result['response'],
            $orderId,
        ]);

        $responsePayload = [
            'success' => true,
            'payment_status' => 'PENDING',
            'transaction_id' => $transactionId,
            'idempotency_key' => $idempotencyKey,
            'session_id' => $sessionId !== '' ? $sessionId : null,
            'order_id' => $orderId,
            'checkout_id' => $result['checkout_id'],
            'card_type' => $paymentMethod,
            'reader_name' => $readerName,
            'reader_serial' => $readerSerial,
            'reader_id' => $tap['reader_id'],
        ];
    }

    if ($transactionTableReady) {
        paymentUpsertTransaction($conn, [
            'order_id' => $orderId,
            'session_id' => $sessionId !== '' ? $sessionId : null,
            'idempotency_key' => $idempotencyKey,
            'android_id' => $androidId,
            'payment_method' => $paymentMethod,
            'valor' => $valor,
            'descricao' => $descricao,
            'quantidade' => $quantidade,
            'cpf' => $cpf,
            'checkout_id' => $responsePayload['checkout_id'] ?? null,
            'status' => 'PENDING',
            'response_payload' => json_encode($responsePayload),
            'last_error' => null,
        ]);
    }

    Logger::info('create_payment: pagamento criado', [
        'order_id' => $orderId,
        'transaction_id' => $transactionId,
        'checkout_id' => $responsePayload['checkout_id'] ?? null,
        'payment_method' => $paymentMethod,
    ]);

    http_response_code(200);
    echo json_encode($responsePayload);
} catch (Throwable $e) {
    Logger::error('create_payment: excecao nao tratada', [
        'message' => $e->getMessage(),
        'file' => $e->getFile(),
        'line' => $e->getLine(),
    ]);
    if (!headers_sent()) {
        http_response_code(500);
    }
    echo json_encode([
        'success' => false,
        'error' => 'Erro interno no servidor: ' . $e->getMessage(),
        'error_type' => 'EXCEPTION',
        'debug' => [
            'file' => basename($e->getFile()),
            'line' => $e->getLine(),
        ],
    ]);
}

ob_end_flush();
