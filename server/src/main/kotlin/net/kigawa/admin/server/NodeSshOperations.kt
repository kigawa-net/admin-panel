package net.kigawa.admin.server

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider

/**
 * ノード(物理/VMホスト)を実際にシャットダウン・再起動する。Kubernetes APIにはノードの
 * 電源操作が存在しないため、既存のTerraform(kigawa-net/infra の hardware 配下)で使っている
 * SSH鍵・sudoパスワードを再利用してSSH経由で操作する(StrictHostKeyChecking=noの既存
 * 運用に合わせてPromiscuousVerifierを使う。既知の秘密鍵/パスワードでの接続に限られる
 * ため、このリスクは許容している)。
 */
object NodeSshOperations {
    private const val DEFAULT_SSH_USER = "kigawa"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val COMMAND_TIMEOUT_SECONDS = 30L

    private val sshUser = System.getenv("NODE_SSH_USER") ?: DEFAULT_SSH_USER
    private val privateKeyPem = System.getenv("NODE_SSH_PRIVATE_KEY")
    private val sudoPassword = System.getenv("NODE_SSH_SUDO_PASSWORD")

    val isConfigured: Boolean
        get() = !privateKeyPem.isNullOrBlank() && !sudoPassword.isNullOrBlank()

    suspend fun shutdown(hostIp: String): ActionResultDto = runSudoCommand(hostIp, "shutdown -h now", "シャットダウンを実行しました")

    suspend fun reboot(hostIp: String): ActionResultDto = runSudoCommand(hostIp, "reboot", "再起動を実行しました")

    private suspend fun runSudoCommand(hostIp: String, command: String, successMessage: String): ActionResultDto {
        val key = privateKeyPem
        val password = sudoPassword
        if (key.isNullOrBlank() || password.isNullOrBlank()) {
            return ActionResultDto(false, "ノードSSHの認証情報が未設定です")
        }

        return try {
            SSHClient().use { client ->
                client.addHostKeyVerifier(PromiscuousVerifier())
                client.connectTimeout = CONNECT_TIMEOUT_MS
                client.connect(hostIp)
                val keyProvider: KeyProvider = client.loadKeys(key, null, null)
                client.authPublickey(sshUser, keyProvider)

                client.startSession().use { session ->
                    // sudo -S でパスワードを標準入力から読ませる。シャットダウン/再起動コマンドは
                    // 実行後にSSHコネクション自体が切れる(または応答が返らない)ため、
                    // 接続断自体は失敗とみなさない。
                    val cmd = session.exec("echo '${escapeForSingleQuotedShell(password)}' | sudo -S $command")
                    try {
                        cmd.join(COMMAND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        // 接続が切れて応答を待てない場合も、コマンド自体は発行済みとして成功扱いにする
                    }
                }
            }
            ActionResultDto(true, successMessage)
        } catch (e: Exception) {
            ActionResultDto(false, e.message ?: "SSH実行に失敗しました")
        }
    }

    /** sudoパスワードをシングルクォートで囲んだシェル文字列として安全に埋め込む。 */
    private fun escapeForSingleQuotedShell(value: String): String = value.replace("'", "'\\''")
}
