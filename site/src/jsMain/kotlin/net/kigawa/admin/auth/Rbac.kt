package net.kigawa.admin.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * admin-panelのKeycloak client role(issue #183)。
 * Keycloak側ではcomposite roleで admin ⊃ operator ⊃ viewer の包含を解決済み。
 */
const val ROLE_VIEWER = "viewer"
const val ROLE_OPERATOR = "operator"
const val ROLE_ADMIN = "admin"

private val KNOWN_ROLES = setOf(ROLE_VIEWER, ROLE_OPERATOR, ROLE_ADMIN)

/**
 * メニュー・ボタン・ページの表示制御に使う権限(issue #183)。
 *
 * あくまでUX用でありセキュリティ境界ではない。最終的な認可は必ずサーバー側の
 * Ktor API(各routeのrequireRole)が同じロールで判定する。
 */
data class RbacPermissions(
    /** サーバー・ネットワーク・インフラ・メトリクスの閲覧(viewer) */
    val canViewInfrastructure: Boolean,
    /** Cordon/Drain/Pod再起動・電源操作(operator) */
    val canOperateServers: Boolean,
    /** ユーザー管理・組織削除など(admin) */
    val canManageUsers: Boolean,
    /** GitHub App token発行・CI token policy(admin) */
    val canManageGithubApp: Boolean,
    /** 組織の一覧(全件)表示・削除など、adminロールそのもの */
    val isAdmin: Boolean
)

/**
 * ロール集合から表示制御用の権限を計算する。
 * composite roleで包含は解決済みだが、保存形式の移行直後などロールが1つだけ
 * 入ってきてもマトリクス通りになるよう本側でも階層を展開する。
 */
fun rbacPermissions(roles: Set<String>): RbacPermissions {
    val hasAdmin = ROLE_ADMIN in roles
    val hasOperator = hasAdmin || ROLE_OPERATOR in roles
    val hasViewer = hasOperator || ROLE_VIEWER in roles
    return RbacPermissions(
        canViewInfrastructure = hasViewer,
        canOperateServers = hasOperator,
        canManageUsers = hasAdmin,
        canManageGithubApp = hasAdmin,
        isAdmin = hasAdmin
    )
}

/** [element]が既知のロール名を含む場合に取り出す(未知の値は無視する)。 */
private fun collectKnownRoles(element: JsonElement?, into: MutableSet<String>) {
    when (element) {
        is JsonArray -> element.forEach { collectKnownRoles(it, into) }
        is JsonObject -> element.forEach { (key, value) ->
            if (key in KNOWN_ROLES) into += key else collectKnownRoles(value, into)
        }
        is JsonPrimitive -> if (element.content in KNOWN_ROLES) into += element.content
        null -> Unit
        else -> Unit
    }
}

/**
 * userinfo / access tokenのロール表現から admin-panel のロール集合を抜き出す。
 *
 * - トップレベルの `roles`: KeycloakのUser Client Roleマッパー(multivalued)が出す形式
 * - `resource_access["admin-panel"].roles`: access token で issue が指定する形式
 *
 * `realm_access` は realmロールでありclient roleと名前が衝突しうるため参照しない。
 * ロールが無い・形式が不明な場合は空集合(= 権限なし・安全側)。
 */
fun rolesFromClaims(roles: JsonElement?, resourceAccess: JsonElement?): Set<String> {
    val result = mutableSetOf<String>()
    collectKnownRoles(roles, result)
    (resourceAccess as? JsonObject)?.get("admin-panel")?.let { collectKnownRoles(it, result) }
    return result
}

/** userinfo応答全体から admin-panel のロール集合を抜き出す。 */
fun UserInfoResponse.rbacRoles(): Set<String> = rolesFromClaims(roles, resourceAccess)
