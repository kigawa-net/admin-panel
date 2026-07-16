package net.kigawa.admin.networkmap

import androidx.compose.runtime.Composable

@Composable
expect fun NetworkMapScreen(accessToken: String, onBack: () -> Unit)
