# admin-panel

Keycloak認証付き管理パネル。[Kobweb](https://kobweb.varabyte.com/)（Kotlin/JS）で実装され、Nginx静的ファイルサーバーとしてデプロイされる。

## 技術スタック

| レイヤー | 技術 |
|---------|------|
| フロントエンド | Kotlin/JS + Kobweb 0.23.3 + Compose HTML |
| 認証 | Keycloak (OIDC / Resource Owner Password) |
| HTTP クライアント | Ktor 3.0.0 |
| ビルド | Gradle 8.14.2 + Kotlin 2.2.20 |
| サーバー | Nginx (Alpine) |
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

1. ユーザーがユーザー名・パスワードを入力
2. `KeycloakAuth.kt` が Keycloak の Token エンドポイントへ Resource Owner Password フローでリクエスト
3. アクセストークン取得成功 → `AuthState.Authenticated` に遷移しダッシュボード表示
4. エラー時は `AuthState.Error` でエラーメッセージ表示

## 依存関係の注意点

- **Kotlin 2.2.20 必須**: Kobweb 0.23.3 が KSP `2.2.20-2.0.2` に依存するため、バージョンを変更すると `Internal compiler error` が発生する
- **`group = "net.kigawa.admin"` 必須**: Kobweb KSP が `@Page` アノテーションをスキャンする際にプロジェクトグループを使用するため、未設定だとページが見つからない
- **`moduleName = "admin"` 必須**: 未設定だと空文字列になり `IllegalArgumentException` が発生する
