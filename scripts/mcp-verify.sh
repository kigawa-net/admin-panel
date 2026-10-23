#!/usr/bin/env bash
# admin-panel MCP / RBAC 動作確認スクリプト
#
# 前提:
#   - kubectl が対象クラスタを向いていること
#   - ローカルに jq / curl があること
#   - 次の port-forward が張られていること（スクリプトは自動では張らない）
#       kubectl port-forward -n keycloak svc/keycloak-service 8443:8443
#
# 環境変数で上書き可能:
#   KC_URL        Keycloak の URL (既定 https://localhost:8443)
#   PANEL_URL     admin-panel の URL (既定 https://admin.kigawa.net)
#   REALM         realm (既定 kigawa-net)
#   MCP_CLIENT    MCP 用トークンを発行するクライアント (既定 admin-panel-org-service)
#   MCP_SCOPE     MCP 用スコープ (既定 mcp:admin-panel)
set -euo pipefail

KC_URL="${KC_URL:-https://localhost:8443}"
PANEL_URL="${PANEL_URL:-https://admin.kigawa.net}"
REALM="${REALM:-kigawa-net}"
MCP_CLIENT="${MCP_CLIENT:-admin-panel-org-service}"
MCP_SCOPE="${MCP_SCOPE:-mcp:admin-panel}"
SECRET_NAME="${SECRET_NAME:-${MCP_CLIENT//_/-}-keycloak}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 が見つかりません" >&2; exit 1; }; }
need curl
need jq
need kubectl

kc() { curl -sk --max-time 15 "$@"; }

echo "== 1. Keycloak 管理者トークン取得 (master realm) =="
ADMIN_USER=$(kubectl get secret keycloak-initial-admin -n keycloak -o jsonpath='{.data.username}' | base64 -d)
ADMIN_PASS=$(kubectl get secret keycloak-initial-admin -n keycloak -o jsonpath='{.data.password}' | base64 -d)
ADMIN_TOKEN=$(kc -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" -d "client_id=admin-cli" \
  -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" | jq -r '.access_token')
[ "$ADMIN_TOKEN" != "null" ] || { echo "ERROR: 管理者トークン取得失敗" >&2; exit 1; }
echo "   OK"

echo
echo "== 2. $REALM realm / admin-panel クライアントロール =="
CID=$(kc -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC_URL/admin/realms/$REALM/clients?clientId=admin-panel" | jq -r '.[0].id')
kc -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$KC_URL/admin/realms/$REALM/clients/$CID/roles" \
  | jq -r '.[] | "   role: \(.name) — \(.description // "")"'
for ROLE in viewer operator admin; do
  echo "   --- $ROLE の包含ロール ---"
  kc -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$KC_URL/admin/realms/$REALM/clients/$CID/roles/$ROLE/composites" \
    | jq -r '.[]? | "       ⊃ \(.name)"'
done

echo
echo "== 3. MCP 用トークン取得 (scope=$MCP_SCOPE) =="
CLIENT_SECRET=$(kubectl get secret "$SECRET_NAME" -n keycloak -o jsonpath='{.data.client-secret}' | base64 -d)
MCP_TOKEN=$(kc -X POST "$KC_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" -d "client_id=$MCP_CLIENT" \
  -d "client_secret=$CLIENT_SECRET" -d "scope=$MCP_SCOPE" | jq -r '.access_token')
[ "$MCP_TOKEN" != "null" ] || { echo "ERROR: MCP トークン取得失敗" >&2; exit 1; }
echo -n "$MCP_TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null \
  | jq -c '{iss, aud, azp, roles: .resource_access["admin-panel"].roles}' | sed 's/^/   /'

echo
echo "== 4. 認証なし / 不正トークン (401 期待) =="
printf '   no token   : %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PANEL_URL/mcp" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' --max-time 15)"
printf '   bad token  : %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PANEL_URL/mcp" \
  -H "Authorization: Bearer invalid" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' --max-time 15)"

echo
echo "== 5. MCP initialize / tools/list =="
INIT=$(curl -s -X POST "$PANEL_URL/mcp" \
  -H "Authorization: Bearer $MCP_TOKEN" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"mcp-verify","version":"1.0"}}}' --max-time 20)
echo "$INIT" | jq -c '.result.serverInfo // .error' | sed 's/^/   initialize: /'
TOOLS=$(curl -s -X POST "$PANEL_URL/mcp" \
  -H "Authorization: Bearer $MCP_TOKEN" -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' --max-time 20)
echo "   tools: $(echo "$TOOLS" | jq -r '.result.tools | length') 件"
echo "$TOOLS" | jq -r '.result.tools[]?.name' | sed 's/^/     - /'

echo
echo "== 6. OAuth Protected Resource Metadata =="
curl -s "$PANEL_URL/.well-known/oauth-protected-resource" --max-time 10 | jq -c . | sed 's/^/   /'

echo
echo "完了"
