<?php
/**
 * API - Verificar Checkout (Versao Estabilizada)
 */

ini_set('display_errors', 0);
header('Content-Type: application/json');

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    $headers = getallheaders();
    $token = $headers['token'] ?? $headers['Token'] ?? '';

    if (!jwtValidate($token)) {
        http_response_code(401);
        echo json_encode(['status' => 'failed', 'error' => 'Sessao expirada']);
        exit;
    }

    $checkout_id = $_POST['checkout_id'] ?? '';
    if (empty($checkout_id)) {
        echo json_encode(['status' => 'false', 'error' => 'ID ausente']);
        exit;
    }

    $conn = getDBConnection();

    $stmt = $conn->prepare("SELECT checkout_status FROM `order` WHERE checkout_id = ? LIMIT 1");
    $stmt->execute([$checkout_id]);
    $order = $stmt->fetch();

    if ($order && $order['checkout_status'] === 'SUCCESSFUL') {
        try {
            $conn->prepare("UPDATE payment_transaction SET status = 'SUCCESSFUL', updated_at = CURRENT_TIMESTAMP WHERE checkout_id = ?")
                ->execute([$checkout_id]);
        } catch (Exception $ignored) {}

        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
        exit;
    }

    $sumup = new SumUpIntegration();

    try {
        $sumupStatus = $sumup->getCheckoutStatus($checkout_id);
    } catch (Exception $e) {
        $sumupStatus = 'PENDING';
    }

    if ($sumupStatus === 'SUCCESSFUL') {
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'SUCCESSFUL' WHERE checkout_id = ?");
        $stmt->execute([$checkout_id]);

        try {
            $conn->prepare("UPDATE payment_transaction SET status = 'SUCCESSFUL', updated_at = CURRENT_TIMESTAMP WHERE checkout_id = ?")
                ->execute([$checkout_id]);
        } catch (Exception $ignored) {}

        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
    } else {
        try {
            $conn->prepare("UPDATE payment_transaction SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE checkout_id = ?")
                ->execute([$sumupStatus ?: 'PENDING', $checkout_id]);
        } catch (Exception $ignored) {}

        echo json_encode([
            'status' => 'pending',
            'checkout_status' => $sumupStatus ?: 'PENDING'
        ]);
    }
} catch (Exception $e) {
    echo json_encode([
        'status' => 'error',
        'error' => 'Falha tecnica no processamento'
    ]);
}
