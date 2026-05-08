<?php
/**
 * request_master_qr.php — Geração e polling de QR Code para acesso master.
 *
 * Protocolo:
 *   action=generate  → gera token, grava na tabela master_qr_tokens, retorna
 *                       {"success":true,"token_id":42,"qr_data":"CHOPPON_MASTER_<uuid>"}
 *
 *   action=poll      → verifica se o token foi aprovado no ERP
 *                       {"success":true,"status":"pending|approved|expired"}
 *
 * A tabela master_qr_tokens é criada automaticamente se não existir.
 *
 * Campos POST:
 *   - action    : "generate" ou "poll"   (obrigatório)
 *   - device_id : android_id do tablet   (obrigatório em generate)
 *   - token_id  : id retornado em generate (obrigatório em poll)
 *
 * Autenticação: JWT via header 'token'
 */
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate');

require_once '../includes/config.php';
require_once '../includes/jwt.php';

// ── Autenticação JWT ──────────────────────────────────────────────────────────
$headers = getallheaders();
$jwtToken = $headers['token'] ?? $headers['Token'] ?? '';
if (!jwtValidate($jwtToken)) {
    http_response_code(401);
    echo json_encode(['success' => false, 'message' => 'Token JWT ausente.']);
    exit;
}

// ── Parâmetros ────────────────────────────────────────────────────────────────
$action    = trim($_POST['action']    ?? '');
$device_id = trim($_POST['device_id'] ?? '');
$token_id  = (int)($_POST['token_id'] ?? 0);

if (empty($action)) {
    http_response_code(400);
    echo json_encode(['success' => false, 'message' => 'Parâmetro action é obrigatório.']);
    exit;
}

$conn = getDBConnection();

// ── Garantir que a tabela existe ──────────────────────────────────────────────
$conn->exec("
    CREATE TABLE IF NOT EXISTS master_qr_tokens (
        id         INT AUTO_INCREMENT PRIMARY KEY,
        device_id  VARCHAR(64)  NOT NULL,
        qr_data    VARCHAR(128) NOT NULL UNIQUE,
        status     ENUM('pending','approved','expired') NOT NULL DEFAULT 'pending',
        created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
        expires_at DATETIME     NOT NULL,
        approved_at DATETIME    NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
");

// ── ACTION: generate ──────────────────────────────────────────────────────────
if ($action === 'generate') {
    if (empty($device_id)) {
        http_response_code(400);
        echo json_encode(['success' => false, 'message' => 'Parâmetro device_id é obrigatório.']);
        exit;
    }

    // Expirar tokens antigos do mesmo device
    $conn->prepare("
        UPDATE master_qr_tokens
        SET status = 'expired'
        WHERE device_id = ? AND status = 'pending' AND expires_at < NOW()
    ")->execute([$device_id]);

    // Gerar UUID único para o QR Code
    $uuid    = bin2hex(random_bytes(16));
    $qr_data = 'CHOPPON_MASTER_' . strtoupper($uuid);

    // Expiração: 5 minutos
    $stmt = $conn->prepare("
        INSERT INTO master_qr_tokens (device_id, qr_data, status, created_at, expires_at)
        VALUES (?, ?, 'pending', NOW(), DATE_ADD(NOW(), INTERVAL 5 MINUTE))
    ");
    $stmt->execute([$device_id, $qr_data]);
    $new_token_id = (int)$conn->lastInsertId();

    http_response_code(200);
    echo json_encode([
        'success'  => true,
        'token_id' => $new_token_id,
        'qr_data'  => $qr_data,
    ]);
    exit;
}

// ── ACTION: poll ──────────────────────────────────────────────────────────────
if ($action === 'poll') {
    if ($token_id <= 0) {
        http_response_code(400);
        echo json_encode(['success' => false, 'message' => 'Parâmetro token_id é obrigatório.']);
        exit;
    }

    // Expirar automaticamente se passou do prazo
    $conn->prepare("
        UPDATE master_qr_tokens
        SET status = 'expired'
        WHERE id = ? AND status = 'pending' AND expires_at < NOW()
    ")->execute([$token_id]);

    $stmt = $conn->prepare("SELECT status FROM master_qr_tokens WHERE id = ?");
    $stmt->execute([$token_id]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$row) {
        http_response_code(404);
        echo json_encode(['success' => false, 'message' => 'Token não encontrado.']);
        exit;
    }

    http_response_code(200);
    echo json_encode([
        'success' => true,
        'status'  => $row['status'],
    ]);
    exit;
}

// ── ACTION desconhecida ───────────────────────────────────────────────────────
http_response_code(400);
echo json_encode(['success' => false, 'message' => 'Action inválida. Use generate ou poll.']);
