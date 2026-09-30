package net.kigawa.admin.infrastructure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.varabyte.kobweb.compose.css.FontSize
import com.varabyte.kobweb.compose.css.FontWeight
import com.varabyte.kobweb.compose.foundation.layout.Arrangement
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.Column
import com.varabyte.kobweb.compose.foundation.layout.Row
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.graphics.Colors
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.forms.Button
import com.varabyte.kobweb.silk.components.text.SpanText
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.kigawa.admin.common.ErrorStateWithRetry
import net.kigawa.admin.servers.ActionResult
import net.kigawa.admin.servers.PENDING_OPERATION_POLL_INTERVAL_MS
import net.kigawa.admin.servers.PendingOperation
import net.kigawa.admin.servers.PendingOperationState
import net.kigawa.admin.servers.ServerCard
import net.kigawa.admin.servers.ServerCardActions
import net.kigawa.admin.servers.ServerStatus
import net.kigawa.admin.servers.SlotInventorySection
import net.kigawa.admin.servers.cordonNode
import net.kigawa.admin.servers.deletePod
import net.kigawa.admin.servers.drainNode
import net.kigawa.admin.servers.fetchServerStatuses
import net.kigawa.admin.servers.gracefulRebootNode
import net.kigawa.admin.servers.gracefulShutdownNode
import net.kigawa.admin.servers.promptDrainTimeoutAndConfirm
import net.kigawa.admin.servers.uncordonNode
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.FlexWrap
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.rgba

private sealed class InfrastructureUiState {
    object Loading : InfrastructureUiState()
    data class Loaded(val topology: InfrastructureTopology) : InfrastructureUiState()
    data class Error(val message: String) : InfrastructureUiState()
}

/**
 * 1カテゴリのロード状態(issue #166)。リソース利用量グラフのように内部で勝手に
 * 取得し直すセクションが、全体進捗へ自分の状態を報告するために使う。
 */
enum class SectionLoadState { Loading, Loaded, Failed }

/** ホストごとに取得するカテゴリ(issue #158)。読み込み中表示と全体進捗で共用する。 */
private val HOST_SECTIONS = listOf("vms", "disks", "pci", "hw", "slots", "diskusage")

/** 表示中K8sノードごとに取得するカテゴリ(issue #166)。 */
private val NODE_SECTIONS = listOf("nodeslots", "nodediskusage")

/** 進捗のキー(末尾のカテゴリ名)から、画面に出すラベルへ変換する(issue #166)。 */
private val SECTION_LABELS = mapOf(
    "topology" to "ホスト一覧",
    "servers" to "サーバー一覧",
    "vms" to "VM一覧",
    "disks" to "ディスク情報",
    "pci" to "拡張デバイス",
    "hw" to "ハードウェア情報",
    "slots" to "空きスロット",
    "diskusage" to "ディスク使用率",
    "nodeslots" to "ノード別スロット",
    "nodediskusage" to "ノード別ディスク使用率",
    "resourceusage" to "リソース利用量",
    "groupedusage" to "グループ別メトリクス"
)

private fun sectionLabel(key: String): String =
    SECTION_LABELS[key.substringAfterLast('/')] ?: key

@Composable
fun InfrastructurePage(accessToken: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<InfrastructureUiState>(InfrastructureUiState.Loading) }
    // ホスト一覧(/api/infrastructure)より後から、ホスト×カテゴリ単位の詳細
    // (/api/infrastructure/hosts/{host}/{vms,disks,pci,hw-status})を並列に読み込む。
    // 届いた部分から順次描画し、completedSectionsで「まだ届いていない」ことと
    // 「届いたが空だった」ことを区別する(issue #158)。
    var hostVms by remember { mutableStateOf<Map<String, List<InfraVm>>>(emptyMap()) }
    var hostDisks by remember { mutableStateOf<Map<String, List<InfraDisk>>>(emptyMap()) }
    var hostPci by remember { mutableStateOf<Map<String, List<InfraPciDevice>>>(emptyMap()) }
    var hostHw by remember { mutableStateOf<Map<String, InfraHostHwStatus>>(emptyMap()) }
    var hostSlots by remember { mutableStateOf<Map<String, HostSlotInventory>>(emptyMap()) }
    // 表示中k8sノードごとのスロット情報(VM系ノードはvirtualized=trueで返り、非表示になる)
    var nodeSlots by remember { mutableStateOf<Map<String, HostSlotInventory>>(emptyMap()) }
    // ホスト・ノードごとのマウントポイント別ディスク使用率(admin-panel#148)。SSH+df由来で
    // スロット取得とは独立に並列取得する。失敗時は空のまま表示されない。
    var hostDiskUsage by remember { mutableStateOf<Map<String, List<DiskUsage>>>(emptyMap()) }
    var nodeDiskUsage by remember { mutableStateOf<Map<String, List<DiskUsage>>>(emptyMap()) }
    var completedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 失敗して「空のまま」確定したカテゴリ(issue #166)。データは既存どおり空のままにし、
    // 画面上部の進捗にだけ「失敗」と分かるチップを出す。
    var failedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    // リソース利用量グラフのように内部で勝手に再取得するセクションのキー(issue #166)。
    // マウント時に報告を受けたキーだけ全体進捗の母数にする(未マウント時に止まらないように)。
    var externalSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var servers by remember { mutableStateOf<List<ServerStatus>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    // issue #116でサーバー管理ページを統合した際に持ち込んだ状態。Cordon/Drain/
    // シャットダウン/再起動の実行結果メッセージと、実行中の非同期操作の完了待ち追跡。
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingOperations by remember { mutableStateOf<Map<String, PendingOperationState>>(emptyMap()) }
    val httpClient = remember {
        HttpClient(Js) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
    val scope = rememberCoroutineScope()

    /** カテゴリの取得結果を進捗へ記録する(issue #166)。成功・失敗どちらも「完了」として
     * 扱い、失敗だけfailedSectionsに残して進捗上に表示する。 */
    fun finishSection(key: String, succeeded: Boolean) {
        completedSections = completedSections + key
        failedSections = if (succeeded) failedSections - key else failedSections + key
    }

    /** 自己申告セクション(リソース利用量グラフ等)のロード状態を受け取る(issue #166)。 */
    fun reportSection(key: String, sectionState: SectionLoadState) {
        externalSections = externalSections + key
        when (sectionState) {
            SectionLoadState.Loading -> {
                completedSections = completedSections - key
                failedSections = failedSections - key
            }
            SectionLoadState.Loaded -> finishSection(key, true)
            SectionLoadState.Failed -> finishSection(key, false)
        }
    }

    // accessTokenはKeycloakのトークン自動更新のたびに(トークン有効期限前の
    // サイレントリフレッシュで)新しい値になる。LaunchedEffectのキーにaccessTokenを
    // 直接含めると、ユーザーがこのページを開いたままにしているだけでトークン更新の
    // たびに再取得が走ってしまい、断続的なProxmox接続エラーの実運用ログで観測された
    // 「約9分間隔でのバースト状の失敗」の原因になっていた。rememberUpdatedStateで
    // 最新のトークン値だけを参照し、エフェクト自体はrefreshKey(初回表示・再試行時)
    // のみで再実行されるようにする。
    val currentAccessToken by rememberUpdatedState(accessToken)

    // リソース利用量の取得状態(issue #132/#168)。レスポンスに物理ホスト分とノード分が同時に
    // 含まれるため、取得はここで1回だけ行い、上部のカードと各ノードカードへ同じ結果を配る
    // (ノード数分のAPI呼び出しは増やさない)。refreshKeyでは取り直さない(既存の方針を維持)。
    var usageRangeMinutes by remember { mutableStateOf(60) }
    var resourceUsage by remember { mutableStateOf<ResourceUsageResponse?>(null) }
    var resourceUsageLoading by remember { mutableStateOf(true) }
    var resourceUsageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(usageRangeMinutes) {
        resourceUsageLoading = true
        resourceUsageError = null
        // 初回の読み込みだけ全体進捗に出す。レンジ変更の再取得では進捗バーを出さず、
        // ページ上部の進捗パネルが出現/消失して表がずれるのを避ける(issue #166)。
        val reportProgress = resourceUsage == null
        if (reportProgress) reportSection("resourceusage", SectionLoadState.Loading)
        try {
            resourceUsage = fetchResourceUsage(httpClient, currentAccessToken, usageRangeMinutes)
            if (reportProgress) reportSection("resourceusage", SectionLoadState.Loaded)
        } catch (e: CancellationException) {
            // レンジ変更・ページ離脱によるキャンセルは失敗ではない(次の取得側が記録する)
        } catch (e: Throwable) {
            // ktor-client-jsのfetch()失敗はExceptionをすり抜けて描画クラッシュを起こすことがある
            // (他の取得と同じ対策)ためThrowableで受ける。失敗は空のまま扱い、進捗にだけ出す。
            resourceUsage = null
            resourceUsageError = e.message ?: "取得に失敗しました"
            if (reportProgress) reportSection("resourceusage", SectionLoadState.Failed)
        }
        resourceUsageLoading = false
    }

    val hostNames = (state as? InfrastructureUiState.Loaded)?.topology?.hosts?.map { it.name } ?: emptyList()
    /** 全ホストのVM一覧が出揃ったかどうか。standaloneノードの算出とpending解決の条件。 */
    val vmsSectionsDone = hostNames.all { "$it/vms" in completedSections }

    /** matchedVM・物理専用ノードの両方をまとめた、現在表示中の全K8sノード。pendingOperationsの解決判定に使う。 */
    fun allKnownNodes(standalone: List<ServerStatus>?): List<ServerStatus> {
        val matchedFromVms = hostVms.values.flatten().mapNotNull { it.matchedNode }
        return (standalone ?: emptyList()) + matchedFromVms
    }

    /** VM一覧とサーバー一覧が出揃ったら、VMに紐付かなかったK8sノードを物理専用ノードとする。 */
    val standaloneNodes: List<ServerStatus>? =
        if (servers != null && vmsSectionsDone) {
            val matchedNames = hostVms.values.flatten().mapNotNull { it.matchedNode?.name }.toSet()
            servers!!.filter { it.name !in matchedNames }.sortedBy { it.name }
        } else {
            null
        }

    /** 指定ホストに届いた部分をマージした詳細。何も届いていなければnull(読み込み中表示用)。 */
    fun mergedDetails(hostName: String): InfraHostDetails? {
        if (!hostVms.containsKey(hostName) && !hostDisks.containsKey(hostName) &&
            !hostPci.containsKey(hostName) && !hostHw.containsKey(hostName)
        ) {
            return null
        }
        val hw = hostHw[hostName]
        return InfraHostDetails(
            disks = hostDisks[hostName].orEmpty(),
            pciDevices = hostPci[hostName].orEmpty(),
            vms = hostVms[hostName].orEmpty(),
            cpuModel = hw?.cpuModel,
            cpuSockets = hw?.cpuSockets,
            cpuPhysicalCores = hw?.cpuPhysicalCores,
            kernelVersion = hw?.kernelVersion,
            pveVersion = hw?.pveVersion,
            rootfsTotalBytes = hw?.rootfsTotalBytes,
            rootfsUsedBytes = hw?.rootfsUsedBytes
        )
    }

    /** 指定ホストで未完了のカテゴリ集合。HostCard内の区分ごとの読み込み中表示に使う。 */
    fun pendingCategories(hostName: String): Set<String> =
        HOST_SECTIONS.filter { "$hostName/$it" !in completedSections }.toSet()

    /** 指定K8sノードで未完了のカテゴリ集合(issue #166)。物理専用ノードのカードに渡す。
     * VM系ノードはスロット表示が抑止されるため、読み込み行を出さない(すぐ消えるチラつき回避)。 */
    fun nodePendingCategories(nodeName: String): Set<String> =
        listOf("nodeslots" to "slots", "nodediskusage" to "diskusage")
            .mapNotNull { (section, cardCategory) ->
                if ("$nodeName/$section" in completedSections) null else cardCategory
            }.toSet()

    /** 指定ノードへ渡すリソースグラフの表示状態(issue #168)。上部セクションと同じ取得結果を
     * ノード名で紐付ける。取得前はloading=trueでグラフ、取得済みで系列が無いノードは
     * グラフ自体を出さない(カードに無意味なボタンを並べないため)。 */
    fun nodeUsage(nodeName: String): NodeUsageUiState? {
        val loading = resourceUsageLoading
        val series = resourceUsage?.k8sNodes?.get(nodeName)
        val error = resourceUsageError
        return if (loading || error != null || series != null) {
            NodeUsageUiState(
                nodeName = nodeName,
                series = series,
                rangeMinutes = usageRangeMinutes,
                loading = loading,
                error = error
            )
        } else {
            null
        }
    }

    LaunchedEffect(refreshKey) {
        hostVms = emptyMap()
        hostDisks = emptyMap()
        hostPci = emptyMap()
        hostHw = emptyMap()
        hostSlots = emptyMap()
        nodeSlots = emptyMap()
        hostDiskUsage = emptyMap()
        nodeDiskUsage = emptyMap()
        // グラフ系(externalSections)はrefreshKeyでは取得し直さないので、完了/失敗の記録だけ
        // 残す(issue #166)。ここで消してしまうと、再取得しないセクションが未完了のまま残る。
        completedSections = completedSections.filter { it in externalSections }.toSet()
        failedSections = failedSections.filter { it in externalSections }.toSet()
        servers = null
        val topology = try {
            fetchInfrastructureTopology(httpClient, currentAccessToken)
        } catch (e: Throwable) {
            // ktor-client-jsがブラウザのfetch()失敗(CORS・オフライン等)を投げる際、
            // Kotlinのcatch (e: Exception)をすり抜けてコルーチンの未捕捉例外ハンドラに
            // まで届き、ページ全体の描画が白紙になる不具合が実機で確認された。
            // Throwableで受けることで、この描画クラッシュを防ぐ。
            state = InfrastructureUiState.Error("インフラ構成を取得できませんでした")
            return@LaunchedEffect
        }
        // まずホスト一覧(高速パス)だけで画面を表示する。VM/ディスク/PCI等の詳細は
        // 下のLaunchedEffectでホスト×カテゴリ単位に並列取得し、届いた部分から順次描画する。
        state = InfrastructureUiState.Loaded(topology)
    }

    // サーバー一覧(K8sノード)はホスト一覧とは独立(issue #166)。ホストが0件のときでも
    // 必ず走るように単独のエフェクトへ分け、「詳細情報を読み込み中...」と進捗が止まらないようにする。
    LaunchedEffect(refreshKey) {
        launch {
            try {
                servers = fetchServerStatuses(httpClient, currentAccessToken).servers
                finishSection("servers", true)
            } catch (e: CancellationException) {
                // 再取得・ページ離脱によるキャンセルは失敗ではない(再取得側が結果を記録する)
            } catch (e: Throwable) {
                // 失敗時は空のまま完了扱い(既存のグレースフルデグラデーション方針)
                servers = emptyList()
                finishSection("servers", false)
            }
        }
    }

    // ホスト×カテゴリ単位の並列取得(issue #158)。各launchはこのエフェクトの子なので、
    // refreshKeyやホスト一覧の変化で自動キャンセルされ、古い応答が新しい状態に混ざらない。
    // 1カテゴリの取得は他と独立しているため、低速なカテゴリが他を道連れにしない。
    // 結果はfinishSectionで全体進捗へ記録し、どこがまだ届いていないかを分かるようにする(issue #166)。
    LaunchedEffect(refreshKey, hostNames) {
        if (hostNames.isEmpty()) return@LaunchedEffect

        /** 1カテゴリ分を並列取得する。成功/失敗を全体進捗へ記録し、キャンセルは無視する。 */
        fun fetchCategory(key: String, block: suspend () -> Unit) {
            launch {
                try {
                    block()
                    finishSection(key, true)
                } catch (e: CancellationException) {
                    // 再取得・ページ離脱によるキャンセルは失敗ではない(再取得側が結果を記録する)
                } catch (e: Throwable) {
                    // 失敗時は空のまま完了扱い(既存のグレースフルデグラデーション方針)
                    finishSection(key, false)
                }
            }
        }

        hostNames.forEach { hostName ->
            fetchCategory("$hostName/vms") {
                hostVms = hostVms + (hostName to fetchHostVms(httpClient, currentAccessToken, hostName))
            }
            fetchCategory("$hostName/disks") {
                hostDisks = hostDisks + (hostName to fetchHostDisks(httpClient, currentAccessToken, hostName))
            }
            fetchCategory("$hostName/pci") {
                hostPci = hostPci + (hostName to fetchHostPciDevices(httpClient, currentAccessToken, hostName))
            }
            fetchCategory("$hostName/hw") {
                hostHw = hostHw + (hostName to fetchHostHwStatus(httpClient, currentAccessToken, hostName))
            }
            // 503(SSH未設定)等もここに来る。区分完了扱いにして読み込み中表示だけ外す。
            fetchCategory("$hostName/slots") {
                hostSlots = hostSlots + (hostName to fetchHostSlots(httpClient, currentAccessToken, hostName))
            }
            // ディスク使用率は空きスロット取得とは独立したエンドポイント(admin-panel#148)。
            // 両者は別SSH接続のため、このように並列に叩ける。503(SSH未設定)等もここに来る
            // が、rootfs(Proxmox API由来)の集約行にフォールバックされる。
            fetchCategory("$hostName/diskusage") {
                hostDiskUsage = hostDiskUsage + (hostName to fetchHostDiskUsage(httpClient, currentAccessToken, hostName))
            }
        }
    }

    // 表示中k8sノード(matched + standalone)ごとのスロット情報を並列取得する。
    // VM系ノードはvirtualized=trueで返り、ServerCard側で非表示になる。
    // 失敗時は空のまま(既存のグレースフルデグラデーション方針)。結果は全体進捗へ記録する(issue #166)。
    val displayedNodeNames =
        ((standaloneNodes?.map { it.name }.orEmpty() +
            hostVms.values.flatten().mapNotNull { it.matchedNode?.name }).toSet())
    LaunchedEffect(refreshKey, displayedNodeNames) {
        if (displayedNodeNames.isEmpty()) return@LaunchedEffect

        /** 1カテゴリ分を並列取得する。成功/失敗を全体進捗へ記録し、キャンセルは無視する。 */
        fun fetchCategory(key: String, block: suspend () -> Unit) {
            launch {
                try {
                    block()
                    finishSection(key, true)
                } catch (e: CancellationException) {
                    // 再取得・ページ離脱によるキャンセルは失敗ではない(再取得側が結果を記録する)
                } catch (e: Throwable) {
                    // 失敗時は空のまま完了扱い(既存のグレースフルデグラデーション方針)
                    finishSection(key, false)
                }
            }
        }

        displayedNodeNames.forEach { nodeName ->
            // 503(NODE_SSH未設定)等もここに来る。表示だけ出さない。
            fetchCategory("$nodeName/nodeslots") {
                nodeSlots = nodeSlots + (nodeName to fetchNodeSlots(httpClient, currentAccessToken, nodeName))
            }
            // 503(NODE_SSH未設定)等もここに来る。ディスク行は表示しない。
            fetchCategory("$nodeName/nodediskusage") {
                nodeDiskUsage = nodeDiskUsage + (nodeName to fetchNodeDiskUsage(httpClient, currentAccessToken, nodeName))
            }
        }
    }

    // 全体進捗(issue #166)。取得対象のキーは「ホスト一覧」「表示中のK8sノード」から導出し、
    // 完了(completedSections)との差分で「まだ届いていないカテゴリ」を出す。導出なので、
    // 対象から外れたホスト・ノードのキーは自動的に母数から外れ、進捗が止まらない。
    val expectedSections = buildSet {
        if (state is InfrastructureUiState.Loading) add("topology")
        // サーバー一覧は常時対象(下のLaunchedEffectが必ず走るため止まらない)。
        add("servers")
        if (state is InfrastructureUiState.Loaded) {
            hostNames.forEach { host -> HOST_SECTIONS.forEach { add("$host/$it") } }
            displayedNodeNames.forEach { node -> NODE_SECTIONS.forEach { add("$node/$it") } }
            // グラフ系はマウント中に報告を受けたキーだけ母数にする(未マウント時に止まらないように)。
            addAll(externalSections)
        }
    }
    /** まだ届いていないカテゴリ(取得中)。 */
    val pendingSections = expectedSections.filter { it !in completedSections }.toSet()
    /** 予定どおり届かなかったカテゴリ。データは空のまま表示し、進捗にだけ「失敗」を出す。 */
    val failedShownSections = expectedSections.filter { it in failedSections }.toSet()

    // VM一覧とサーバー一覧が出揃うたびにpending操作の解決判定を行う。
    LaunchedEffect(hostVms, servers) {
        if (!vmsSectionsDone || servers == null) return@LaunchedEffect
        val nodes = allKnownNodes(standaloneNodes)
        val stillPending = mutableMapOf<String, PendingOperationState>()
        pendingOperations.forEach { (nodeId, opState) ->
            val node = nodes.find { it.id == nodeId }
            if (node == null) {
                // ノード自体が消えた(一覧から見えなくなった)場合はこれ以上追跡できない
                return@forEach
            }
            val sawNotReady = opState.sawNotReady || !node.ready
            val resolved = when (opState.operation) {
                // 開始直後はまだReady=trueのままなので、一度NotReadyを確認してからでないと
                // 「完了」とみなさない(でなければ落ちる前に完了扱いになってしまう)
                PendingOperation.REBOOTING -> sawNotReady && node.ready
                PendingOperation.SHUTTING_DOWN -> !node.ready
            }
            if (resolved) {
                statusMessage = when (opState.operation) {
                    PendingOperation.REBOOTING -> "${node.name} の再起動が完了しました"
                    PendingOperation.SHUTTING_DOWN -> "${node.name} のシャットダウンが完了しました"
                }
            } else {
                stillPending[nodeId] = opState.copy(sawNotReady = sawNotReady)
            }
        }
        pendingOperations = stillPending
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PENDING_OPERATION_POLL_INTERVAL_MS)
            if (pendingOperations.isNotEmpty()) {
                refreshKey++
            }
        }
    }

    fun runAction(action: suspend () -> ActionResult) {
        scope.launch {
            val result = try {
                action()
            } catch (e: Exception) {
                ActionResult(false, e.message ?: "失敗しました")
            }
            statusMessage = result.message
            refreshKey++
        }
    }

    /** ノードごとのCordon/Drain/シャットダウン/再起動操作をまとめて構築する。ProxmoxのVM経由・
     * 物理専用ノードのどちらでも同じロジックで使う(issue #116)。 */
    fun buildActions(node: ServerStatus): ServerCardActions = ServerCardActions(
        onCordon = {
            if (window.confirm("${node.name} への新規Podのスケジューリングを停止しますか?(既存Podには影響しません)")) {
                runAction { cordonNode(httpClient, accessToken, node.id) }
            }
        },
        onUncordon = {
            if (window.confirm("${node.name} への新規Podのスケジューリングを再開しますか?")) {
                runAction { uncordonNode(httpClient, accessToken, node.id) }
            }
        },
        onDrain = {
            if (window.confirm("${node.name} 上の全Pod(DaemonSet管理下を除く)を退避しますか?影響範囲が大きい操作です。")) {
                scope.launch {
                    val result = try {
                        drainNode(httpClient, accessToken, node.id)
                    } catch (e: Exception) {
                        null
                    }
                    statusMessage = if (result != null) {
                        "Drain完了: 退避${result.evicted}件 / スキップ${result.skipped}件 / 失敗${result.failed}件"
                    } else {
                        "Drainに失敗しました"
                    }
                    refreshKey++
                }
            }
        },
        onDeletePod = { pod ->
            if (window.confirm("${pod.namespace}/${pod.name} を再起動(削除)しますか?")) {
                runAction { deletePod(httpClient, accessToken, pod.namespace, pod.name) }
            }
        },
        onShutdown = {
            promptDrainTimeoutAndConfirm(node, actionLabel = "シャットダウン")?.let { timeout ->
                runAction {
                    val result = gracefulShutdownNode(httpClient, accessToken, node.id, timeout)
                    if (result.success) {
                        pendingOperations = pendingOperations + (node.id to PendingOperationState(PendingOperation.SHUTTING_DOWN))
                    }
                    result
                }
            }
        },
        onReboot = {
            promptDrainTimeoutAndConfirm(node, actionLabel = "再起動")?.let { timeout ->
                runAction {
                    val result = gracefulRebootNode(httpClient, accessToken, node.id, timeout)
                    if (result.success) {
                        pendingOperations = pendingOperations + (node.id to PendingOperationState(PendingOperation.REBOOTING))
                    }
                    result
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(leftRight = 24.px, topBottom = 16.px)
                .backgroundColor(Colors.White)
                .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.1)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.px)
            ) {
                Button(onClick = { onBack() }) {
                    SpanText("← 戻る")
                }
                SpanText(
                    "インフラ構成",
                    modifier = Modifier.fontSize(FontSize.XLarge).fontWeight(FontWeight.Bold)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.px),
            verticalArrangement = Arrangement.spacedBy(16.px)
        ) {
            statusMessage?.let { message ->
                SpanText(message, modifier = Modifier.color(Colors.Blue))
            }

            // カテゴリごとのロード進捗(issue #166)。取得中のカテゴリのチップが残り、
            // 完了したカテゴリから消えていく。失敗カテゴリは空のままでも赤いチップで分かる。
            LoadProgressSection(
                done = expectedSections.size - pendingSections.size,
                total = expectedSections.size,
                pendingSections = pendingSections,
                failedSections = failedShownSections
            )

            when (val current = state) {
                is InfrastructureUiState.Loading -> SpanText("読み込み中...")
                is InfrastructureUiState.Error -> ErrorStateWithRetry(current.message, onRetry = { refreshKey++ })
                is InfrastructureUiState.Loaded -> if (!current.topology.proxmoxConfigured) {
                    SpanText(
                        "Proxmox連携が設定されていません(PROXMOX_API_TOKEN_ID / PROXMOX_API_TOKEN_SECRET未設定)",
                        modifier = Modifier.color(Colors.Gray)
                    )
                } else if (!current.topology.proxmoxReachable) {
                    ErrorStateWithRetry(
                        "Proxmoxに接続できませんでした。しばらくしてからもう一度お試しください。",
                        onRetry = { refreshKey++ }
                    )
                } else if (current.topology.hosts.isEmpty() && standaloneNodes != null && standaloneNodes.isEmpty()) {
                    SpanText("物理ホスト・ノードが見つかりませんでした", modifier = Modifier.color(Colors.Gray))
                } else {
                    // リソース利用量グラフ(issue #132)。取得はページ側のLaunchedEffectで
                    // ホスト一覧とは独立に1回だけ行い、ここでは物理ホスト分だけを描画する
                    // (ノード分は各ノードカードへ移設、issue #168)。
                    ResourceUsageSection(
                        rangeMinutes = usageRangeMinutes,
                        onRangeChange = { usageRangeMinutes = it },
                        data = resourceUsage,
                        loading = resourceUsageLoading,
                        error = resourceUsageError
                    )
                    // グルーピングされたリソース利用量(issue #147)
                    GroupedResourceUsageSection(
                        httpClient = httpClient,
                        accessToken = accessToken,
                        onLoadState = { reportSection("groupedusage", it) }
                    )
                    current.topology.hosts.forEach { host ->
                        HostCard(
                            host = host,
                            details = mergedDetails(host.name),
                            pendingCategories = pendingCategories(host.name),
                            slots = hostSlots[host.name],
                            diskUsage = hostDiskUsage[host.name],
                            nodeSlots = nodeSlots,
                            nodeDiskUsage = nodeDiskUsage,
                            nodeUsage = ::nodeUsage,
                            pendingOperations = pendingOperations,
                            httpClient = httpClient,
                            accessToken = accessToken,
                            buildActions = ::buildActions
                        )
                    }
                    val currentStandalone = standaloneNodes
                    if (currentStandalone == null) {
                        SpanText(
                            "詳細情報を読み込み中...",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small).padding(top = 4.px)
                        )
                    } else if (currentStandalone.isNotEmpty()) {
                        SpanText(
                            "物理専用ノード(VM化されていないK8sノード)",
                            modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium).padding(top = 8.px)
                        )
                        currentStandalone.forEach { node ->
                            ServerCard(
                                server = node,
                                pendingOperation = pendingOperations[node.id]?.operation,
                                httpClient = httpClient,
                                accessToken = accessToken,
                                actions = buildActions(node),
                                slots = nodeSlots[node.name],
                                diskUsage = nodeDiskUsage[node.name],
                                // ノードごとの時系列グラフ(issue #168)。物理専用ノードも
                                // VM系ノードと同じ構造でカード内から確認できるようにする。
                                usage = nodeUsage(node.name),
                                // 物理専用ノードだけカード内にも読み込み中表示を出す(issue #166)。
                                // VM系ノードはスロットが非表示になるため、読み込み行が出てすぐ
                                // 消えるチラつきを避けて渡さない。
                                pendingCategories = nodePendingCategories(node.name)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * カテゴリごとのロード進捗(issue #166)。
 * 取得中のカテゴリはチップが並び、完了したカテゴリから消えていく。失敗したカテゴリは
 * データを空のままにしたまま(既存のグレースフルデグラデーション方針)、赤いチップだけ残す。
 */
@Composable
private fun LoadProgressSection(
    done: Int,
    total: Int,
    pendingSections: Set<String>,
    failedSections: Set<String>
) {
    if (pendingSections.isEmpty() && failedSections.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.px)
            .backgroundColor(rgba(0, 0, 0, 0.03))
            .borderRadius(6.px),
        verticalArrangement = Arrangement.spacedBy(6.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(
                if (pendingSections.isNotEmpty()) "読み込み $done / $total" else "読み込み完了",
                modifier = Modifier.fontSize(FontSize.Small).fontWeight(FontWeight.Bold)
            )
            if (failedSections.isNotEmpty()) {
                SpanText(
                    "取得失敗 ${failedSections.size}件",
                    modifier = Modifier
                        .fontSize(FontSize.Small)
                        .fontWeight(FontWeight.Bold)
                        .color(Color("#E34948"))
                )
            }
        }
        if (pendingSections.isNotEmpty() && total > 0) {
            // 進捗バー。kobweb/silkに該当コンポーネントがないため、背景+塗りの2つのBoxで作る。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.px)
                    .backgroundColor(rgba(42, 120, 214, 0.15))
                    .borderRadius(3.px)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((done * 100 / total).percent)
                        .height(6.px)
                        .backgroundColor(Color("#2A78D6"))
                        .borderRadius(3.px)
                )
            }
        }
        // 同じカテゴリは件数でまとめる(ホスト×カテゴリだとチップが数百個になり得るため)。
        Row(
            modifier = Modifier.fillMaxWidth().flexWrap(FlexWrap.Wrap).rowGap(6.px),
            horizontalArrangement = Arrangement.spacedBy(6.px)
        ) {
            pendingSections.groupingBy { sectionLabel(it) }.eachCount()
                .entries.sortedBy { it.key }
                .forEach { (label, count) ->
                    SectionChip(label + if (count > 1) " ×$count" else "", failed = false)
                }
            failedSections.groupingBy { sectionLabel(it) }.eachCount()
                .entries.sortedBy { it.key }
                .forEach { (label, count) ->
                    SectionChip(label + " 失敗" + if (count > 1) " ×$count" else "", failed = true)
                }
        }
    }
}

/** 進捗チップ1個。取得中は青、失敗は赤(issue #166)。 */
@Composable
private fun SectionChip(text: String, failed: Boolean) {
    SpanText(
        text,
        modifier = Modifier
            .padding(leftRight = 8.px, topBottom = 2.px)
            .borderRadius(10.px)
            .backgroundColor(if (failed) rgba(227, 73, 72, 0.12) else rgba(42, 120, 214, 0.12))
            .color(if (failed) Color("#E34948") else Color("#2A78D6"))
            .fontSize(FontSize.Small)
            .fontWeight(FontWeight.Bold)
    )
}

@Composable
private fun HostCard(
    host: InfraHost,
    details: InfraHostDetails?,
    /** 未完了のカテゴリ集合("vms"/"disks"/"pci"/"hw"/"slots"/"diskusage")。区分ごとの読み込み中表示に使う。 */
    pendingCategories: Set<String>,
    slots: HostSlotInventory?,
    /** マウントポイント別ディスク使用率(admin-panel#148)。未取得・失敗時はnullでrootfs行にフォールバック。 */
    diskUsage: List<DiskUsage>? = null,
    /** 表示中k8sノードごとのスロット情報。VM紐付けノードのカード表示に使う。 */
    nodeSlots: Map<String, HostSlotInventory> = emptyMap(),
    /** 表示中k8sノードごとのディスク使用率。VM紐付けノードのカード表示に使う。 */
    nodeDiskUsage: Map<String, List<DiskUsage>> = emptyMap(),
    /** 表示中k8sノードごとのリソースグラフ表示状態(issue #168)。VM紐付けノードのカードに渡す。 */
    nodeUsage: (String) -> NodeUsageUiState? = { null },
    pendingOperations: Map<String, PendingOperationState>,
    httpClient: HttpClient,
    accessToken: String,
    buildActions: (ServerStatus) -> ServerCardActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.px)
            .backgroundColor(Colors.White)
            .borderRadius(8.px)
            .boxShadow(offsetX = 0.px, offsetY = 2.px, blurRadius = 8.px, color = rgba(0, 0, 0, 0.08)),
        verticalArrangement = Arrangement.spacedBy(6.px)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(host.name, modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Medium))
            OnlineBadge(host.online)
        }
        if (host.cpuCores != null || host.memoryBytes != null) {
            SpanText(
                "CPU: ${host.cpuCores ?: "-"} コア / メモリ: ${formatBytesAsGiB(host.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        if (details == null) {
            SpanText(
                "詳細情報を読み込み中...",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small).padding(top = 4.px)
            )
        } else {
            // CPUモデル・ディスク総容量・PVE/カーネルバージョンは、ノードごとに追加の
            // status呼び出しが必要なため詳細(details)側にある(issue #118でホスト一覧の
            // 高速パスから移動した)。各区分は独立に非同期取得されるため(issue #158)、
            // 未完了の区分は読み込み中表示を出す。
            if (details.cpuModel != null) {
                SpanText(
                    buildString {
                        append(details.cpuModel)
                        if (details.cpuSockets != null && details.cpuPhysicalCores != null) {
                            append(" (${details.cpuSockets}ソケット × ${details.cpuPhysicalCores}コア)")
                        }
                    },
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if ("hw" in pendingCategories) {
                SpanText(
                    "ハードウェア情報を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            }
            // マウントポイント別ディスク使用率(admin-panel#148、SSH+df由来)。ルートFSを先頭に
            // 並べ、最大3件+「他N件」で省略表示する。取得失敗時(SSH未設定等)はrootfs
            // (Proxmox API由来)の集約行にフォールバックし、それも無ければ読み込み中表示のみ。
            val diskUsages = diskUsage.orEmpty().sortedBy { if (it.mountpoint == "/") 0 else 1 }
            if (diskUsages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.px)) {
                    SpanText("ディスク使用率", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                    diskUsages.take(3).forEach { usage ->
                        SpanText(
                            "${usage.mountpoint} ${usage.percent}% " +
                                "(${formatBytesAsGiB(usage.usedBytes)} / ${formatBytesAsGiB(usage.sizeBytes)})",
                            modifier = Modifier.color(usagePercentColor(usage.percent)).fontSize(FontSize.Small)
                        )
                    }
                    if (diskUsages.size > 3) {
                        SpanText(
                            "他${diskUsages.size - 3}件",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                }
            } else if (details.rootfsTotalBytes != null) {
                SpanText(
                    "ディスク: ${formatBytesAsGiB(details.rootfsUsedBytes)} / ${formatBytesAsGiB(details.rootfsTotalBytes)}",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if ("diskusage" in pendingCategories) {
                SpanText(
                    "ディスク使用率を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            }
            if (details.pveVersion != null || details.kernelVersion != null) {
                SpanText(
                    "PVE: ${details.pveVersion ?: "-"} / Kernel: ${details.kernelVersion ?: "-"}",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            }
            if ("disks" in pendingCategories) {
                SpanText(
                    "ディスク情報を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if (details.disks.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
                    SpanText("ディスク型番", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                    details.disks.forEach { disk ->
                        SpanText(
                            "${disk.model}(${disk.type} / ${formatBytesAsGiB(disk.sizeBytes)}${disk.health?.let { " / $it" } ?: ""})",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                }
            }
            if ("pci" in pendingCategories) {
                SpanText(
                    "拡張デバイス情報を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if (details.pciDevices.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.px), verticalArrangement = Arrangement.spacedBy(2.px)) {
                    SpanText("拡張デバイス", modifier = Modifier.fontWeight(FontWeight.Bold).fontSize(FontSize.Small))
                    details.pciDevices.forEach { device ->
                        SpanText(
                            "${device.vendor?.let { "$it " } ?: ""}${device.name}",
                            modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                        )
                    }
                }
            }
            if ("slots" in pendingCategories) {
                SpanText(
                    "空きスロット情報を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if (slots != null) {
                SlotInventorySection(slots = slots)
            }

            if ("vms" in pendingCategories) {
                SpanText(
                    "VM一覧を読み込み中...",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else if (details.vms.isEmpty()) {
                SpanText(
                    if (host.online) "稼働中のVMはありません" else "オフラインのため不明",
                    modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.px),
                    verticalArrangement = Arrangement.spacedBy(8.px)
                ) {
                    details.vms.forEach { vm ->
                        VmSection(
                            vm = vm,
                            pendingOperation = vm.matchedNode?.let { pendingOperations[it.id]?.operation },
                            httpClient = httpClient,
                            accessToken = accessToken,
                            buildActions = buildActions,
                            slots = vm.matchedNode?.let { nodeSlots[it.name] },
                            diskUsage = vm.matchedNode?.let { nodeDiskUsage[it.name] },
                            // VMとして動くK8sノードも、対応するカード内で時系列を見られるように(issue #168)
                            usage = vm.matchedNode?.let { nodeUsage(it.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VmSection(
    vm: InfraVm,
    pendingOperation: PendingOperation?,
    httpClient: HttpClient,
    accessToken: String,
    buildActions: (ServerStatus) -> ServerCardActions,
    slots: HostSlotInventory? = null,
    diskUsage: List<DiskUsage>? = null,
    /** ノード別のリソースグラフ表示状態(issue #168)。未取得・対象外のときはnull。 */
    usage: NodeUsageUiState? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(topBottom = 4.px),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpanText(vm.name, modifier = Modifier.fontSize(FontSize.Small))
            SpanText(
                "vmid ${vm.vmid} · ${vm.cpuCores ?: "-"} コア / ${formatBytesAsGiB(vm.memoryBytes)}",
                modifier = Modifier.color(Colors.Gray).fontSize(FontSize.Small)
            )
        }
        // ProxmoxのVMがK8sノードとして稼働している場合、サーバー管理の全機能
        // (実使用量・Cordon/Drain・シャットダウン/再起動等)をここに直接表示する
        // (issue #116: インフラ構成とサーバー管理の統合)。
        val node = vm.matchedNode
        if (node != null) {
            ServerCard(
                server = node,
                pendingOperation = pendingOperation,
                httpClient = httpClient,
                accessToken = accessToken,
                actions = buildActions(node),
                slots = slots,
                diskUsage = diskUsage,
                usage = usage
            )
        }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color("#008300") else Color("#E34948")
    SpanText(if (online) "Online" else "Offline", modifier = Modifier.color(color).fontWeight(FontWeight.Bold))
}
