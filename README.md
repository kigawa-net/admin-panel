# admin-panel

Keycloak認証付き管理パネル。[Kobweb](https://kobweb.varabyte.com/)（Kotlin/JS）フロントエンドと [Ktor](https://ktor.io/) バックエンドで実装される。

## 技術スタック

| レイヤー | 技術 |
|---------|------|
| フロントエンド | Kotlin/JS + Kobweb 0.23.3 + Compose HTML |
| バックエンド | Kotlin + Ktor server |
| 認証 | Keycloak (OIDC Authorization Code + PKCE、kigawa-net realm) |
| HTTP クライアント | Ktor 3.0.0 |
| ビルド | Gradle 8.14.2 + Kotlin 2.2.20 |
| サーバー | Nginx (Alpine) + Ktor |
| コンテナレジストリ | Harbor (`harbor.kigawa.net/library/admin-panel`) |
| デプロイ | ArgoCD GitOps → Kubernetes (`kigawa-net-admin-panel` namespace) |

## ディレクトリ構成

```
admin-panel/
├── site/                        # Kobweb プロジェクト（フロントエンド）
│   ├── src/jsMain/kotlin/net/kigawa/admin/
│   │   ├── AppEntry.kt          # Kobweb アプリエントリポイント
│   │   ├── auth/
│   │   │   └── KeycloakAuth.kt  # Keycloak 認証ロジック
│   │   └── pages/
│   │       └── Index.kt         # トップページ（ログイン・ダッシュボード）
│   ├── .kobweb/conf.yaml        # Kobweb サーバー設定
│   └── build.gradle.kts
├── k8s/
│   ├── base/                    # Kubernetes 基本マニフェスト
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── ingress.yaml
│   │   └── kustomization.yaml
│   ├── overlays/production/     # 本番環境オーバーレイ（CIが自動更新）
│   ├── nginx.conf               # Nginx 設定（SPA ルーティング用）
├── gradle/libs.versions.toml    # バージョンカタログ
├── Dockerfile                   # マルチステージなし（CI でビルド済みファイルをコピー）
└── .github/workflows/build-push.yml
```

## ローカル開発

```bash
cd site
gradle kobwebStart
# ブラウザで http://localhost:8080 を開く
```

## CI/CD フロー

```
git push → main
  └─ GitHub Actions (build-push.yml)
       ├─ gradle kobwebExport -PkobwebExportLayout=STATIC
       ├─ docker build & push → harbor.kigawa.net/library/admin-panel:main-<sha>
       └─ k8s/overlays/production/kustomization.yaml の newTag を更新してコミット
            └─ ArgoCD が検知 → kigawa-net-admin-panel namespace にデプロイ
```

## Kubernetes デプロイ

- **Namespace**: `kigawa-net-admin-panel`
- **Ingress**: HAProxy、ドメインは `k8s/overlays/production/ingress-tls-patch.yaml` で定義
- **リソース**: CPU 50m–200m / Memory 64Mi–128Mi
- **イメージタグ**: CIが`main-<commit-sha>`形式で自動更新

## 認証フロー

1. ユーザーが「ログイン」を押し、`KeycloakAuth.kt` が kigawa-net realm(`user.kigawa.net`)の認可エンドポイントへ Authorization Code + PKCE フローでリダイレクト
2. 認可コードを Token エンドポイントでアクセストークンに交換し、userinfo を取得
3. userinfo に載るロール(`admin-panel` クライアントの `admin` ロール)の有無で管理者を判定(ロールが無い・取得できない間は非管理者)
4. 取得成功 → `AuthState.Authenticated` に遷移しダッシュボード表示
5. エラー時は `AuthState.Error` でエラーメッセージ表示
6. 画面表示に加え、サーバー側も userinfo のロールで管理者を再判定する(RBAC)

## CI トークンブローカーの観測ログ(issue #227 PR A)

対象は `POST /api/github-app/ci-token`(kivra・ai-scheduler・exkes・OneServerMC/RpgCore の CI が利用)。
**この段階では許可・拒否の判定は一切変えていない。** 観測ログと、想定の制約との差の WARN を出すだけ。
強制(拒否)は PR C で行う。

### 1 行ログ(トークン発行の試行ごとに 1 行)

```
event=ci.token.observe decision=allow|deny reason=<理由コード> repository=<owner/repo> repository_id=<...> ref=<...> event_name=<...> workflow_ref=<...> job_workflow_ref=<...> environment=<...> owner=<...> repositories=[...] permission_keys=[...]
```

- `-` は「値が無い(欠落・型違い・長すぎる・制御文字を含む)」を表す。
- 理由コード: `unauthenticated`(OIDC 検証失敗。caller 情報は出ない) / `github_app_not_configured` / `database_not_configured` / `policy_denied` / `installation_permission_insufficient` / `token_permission_insufficient` / `minted`(発行成功)。
- `repositories` と `permission_keys` は要求の値(リポジトリ名と権限のキー名)。権限の値は出さない。
- 値は制御文字を除去し、空白・`=`・`,` を `_` に置換し、200 文字で切る。

**出さないもの**: JWT 全文、署名部、`Authorization` ヘッダー、GitHub App のトークン、検証済みクレームのうち上の一覧以外(`sha`、`actor` を含む)。

### 想定の制約との差の観測(WARN)

環境変数 `CI_TOKEN_OBSERVE_CONSTRAINTS`(JSON)を設定したときだけ動く。未設定なら何もしない(既定)。
キーは caller リポジトリ(`owner/repo`)、値の各フィールドは省略可能で、省略したものは確認しない。

```json
{
  "kigawa-net/kivra": {
    "repositoryId": "<kivra の repository_id>",
    "workflowRefs": ["kigawa-net/kivra/.github/workflows/cd.yml@refs/heads/main"],
    "refs": ["refs/heads/main"],
    "events": ["push", "workflow_dispatch"]
  }
}
```

- 対応する検査: `repositoryId`→`repository_id`、`workflowRefs`→`workflow_ref`、`jobWorkflowRefs`→`job_workflow_ref`、`refs`→`ref`、`events`→`event_name`、`environments`→`environment`。
- 空配列は「許可する値が無い」として常に不一致になる。確認したくないフィールドは省略する(空配列にしない)。
- 不一致のとき、発行の 1 回ごとに次の WARN を出す(欠落も不一致):
  `ci-token constraint mismatch (observe-only): repository=<repo> field=<項目> expected=[...] actual=<値>`
- JSON が壊れている場合は WARN を 1 行出して、制約を無視する(判定は変わらない)。
- 上の例の `workflowRefs` は `workflow_ref` の書式 `<owner>/<repo>/<path>@<ref>` に合わせる。`job_workflow_ref` も同じ書式(reusable workflow の呼び出し先)。
- この JSON の書式は PR C(強制)で `ci_token_policy` に保存する形の素案。PR A では DB に保存しない。

### 観測期間(数日)で見ること

ログは `grep 'event=ci.token.observe'` などで集計する。

1. 呼び出し元ごとの `decision` の内訳。想定外の `deny`(特に `policy_denied`)が無いこと。
2. `allow`(`minted`)の行の `workflow_ref` / `job_workflow_ref` / `ref` / `event_name` が、想定した値の集合だけであること。想定外の workflow や ref(PR ブランチなど)が無いこと。
3. `allow` の行で `workflow_ref` や `repository_id` が `-`(欠落)になっていないこと。
4. `ci-token constraint mismatch` の WARN が 0 件であること(制約を設定した呼び出し元について)。
5. GitHub 側の実際の書式(`workflow_ref` の `@refs/...`、composite action の中で見える値)を、ここで実測して README の例と照合する。

### PR C(強制)への移行基準(素案)

- 観測期間中、対象の呼び出し元の `allow` が**すべて想定どおりのクレーム**であること(上の 1〜5)。
- 制約の WARN が 0 件で、実際に発行を要求している workflow が制約の一覧に全て含まれていること。
- 想定外の値が出た場合は、制約を直すか、呼び出し元の変更を先に入れる。観測期間を延ばす。

### ロールバック

- PR A はスキーマ変更なし。以前のイメージに戻せば観測ログだけが消える(判定は元々変わらない)。
- 観測の WARN だけ止める場合は `CI_TOKEN_OBSERVE_CONSTRAINTS` を未設定にする。

### 既知の限界

- GitHub API の呼び出しで例外になった場合(発行直前の失敗)は、`decision` の行が出ない。
- 401 の行には呼び出し元の情報が無い(署名検証に失敗した値は信用しないため)。

## 依存関係の注意点

- **Kotlin 2.2.20 必須**: Kobweb 0.23.3 が KSP `2.2.20-2.0.2` に依存するため、バージョンを変更すると `Internal compiler error` が発生する
- **`group = "net.kigawa.admin"` 必須**: Kobweb KSP が `@Page` アノテーションをスキャンする際にプロジェクトグループを使用するため、未設定だとページが見つからない
- **`moduleName = "admin"` 必須**: 未設定だと空文字列になり `IllegalArgumentException` が発生する

## MCP Server としての利用

admin-panel は MCP (Model Context Protocol) server としても利用可能です。Claude Code 等のMCPクライアントから、サーバー・Kubernetes・ネットワーク・組織等の状態確認と、権限に応じた運用操作を行えます。

### エンドポイント

- **URL**: `https://admin.kigawa.net/mcp`
- **Transport**: Streamable HTTP (POST `/mcp`)
- **認証**: Keycloak (kigawa-net realm) の OAuth。aud が `https://admin.kigawa.net/mcp` の Bearer access token が必要

### 登録方法 1: OAuth ログイン（推奨）

```bash
claude mcp add --transport http --client-id admin-panel-mcp --callback-port 8765 admin-panel https://admin.kigawa.net/mcp
```

- `--client-id admin-panel-mcp`: Keycloak の公開クライアント（認可コード + PKCE、DCR は使わない）
- `--callback-port 8765`: コールバックポートを 8765 に固定（Keycloak の redirect URI `http://localhost:8765/callback` と一致させるため）
- `--scope` は省略時 `local`（そのプロジェクトのみ）。全プロジェクトで使う場合は `--scope user` を付ける

登録後、Claude Code で `/mcp` を実行し `admin-panel` を選んで認証します。ブラウザで Keycloak のログイン画面が開くので、`admin-panel` クライアントのロールが付与されたユーザーでログインしてください。

仕組み:

1. `/mcp` へ未認証で接続すると、サーバーは 401 と `WWW-Authenticate` ヘッダーを返す
2. クライアントは `https://admin.kigawa.net/.well-known/oauth-protected-resource`（RFC 9728）を読み、認可サーバー（Keycloak）を知る
3. Keycloak で認可コード + PKCE（S256）によるログインを行い、access token を取得する
4. token（aud = `https://admin.kigawa.net/mcp`）を付けて `/mcp` を呼び出す

#### claude.ai のコネクタとして登録する場合

- MCP サーバー URL: `https://admin.kigawa.net/mcp`
- OAuth クライアント ID: `admin-panel-mcp`
- コールバック URL: `https://claude.ai/api/mcp/auth_callback`（Keycloak 側で redirect URI として登録済み）

### 登録方法 2: Bearer トークンの直接指定（代替）

```bash
claude mcp add --transport http --header "Authorization: Bearer <YOUR_KEYCLOAK_TOKEN>" admin-panel https://admin.kigawa.net/mcp
```

- token は期限切れになるため、期限切れ後は再取得して再登録が必要
- token の aud は `https://admin.kigawa.net/mcp` である必要がある（REST API 向けの `aud=admin-panel` の token は `/mcp` で 401 になる）
- 通常は方法 1（OAuth ログイン）を使ってください

### 権限ロール

| Role | 説明 |
|------|------|
| `viewer` | サーバー・ネットワーク・インフラ・メトリクスの閲覧 |
| `operator` | viewer + Kubernetes/サーバー運用操作 |
| `admin` | operator + ユーザー・組織・GitHub App管理 |

### 利用可能な主なツール

**viewer**:
- `dashboard.get`, `dashboard.getTraffic`
- `network.topology.get`
- `servers.list`
- `infrastructure.get`, `infrastructure.resourceUsage.get`
- `organizations.mine`

**operator**:
- `servers.cordon`, `servers.uncordon`, `servers.drain`
- `servers.reboot`, `servers.shutdown`
- `pods.restart`

**admin**:
- `users.list/create/enable/disable/resetPassword/delete`
- `organizations.list/create/delete`
- `github_app.installations.list`
- `list_ci_policies`, `put_ci_policy`, `delete_ci_policy`

### 注意事項・トラブルシュート

- **前提**: Keycloak 側の設定（k8s-system の `keycloak/admin-panel-mcp-oauth-job.yaml`）が適用済みであること。未適用の環境では認証が成立しません
- **401 が返る**: token の aud が `https://admin.kigawa.net/mcp` になっていないか、`admin-panel` クライアントのロール（viewer/operator/admin）が付与されていない可能性があります。ロール付与を確認し、`/mcp` から再認証してください
- **`invalid_scope` エラー**: スコープ `mcp:admin-panel` が Keycloak に定義されていません。クライアントスコープが追加されているか確認してください
- **redirect_uri 不一致**: コールバックポートは 8765 固定です。`--callback-port 8765` で登録し直してください（別のポートだと Keycloak の redirect URI と一致しません）
- **期限切れ**: token の有効期限が切れた場合は、`/mcp` から再認証（または Bearer token を再登録）してください
- RBAC が有効な現行環境では、`KEYCLOAK_RBAC_ENFORCED=false` によるフォールバックは推奨されません
