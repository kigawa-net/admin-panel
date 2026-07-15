package net.kigawa.admin.networkmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceInfoPanel(device: NetworkDevice?, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (device == null) {
                Text(
                    text = "機器をタップすると詳細が表示されます",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(text = device.name, style = MaterialTheme.typography.titleMedium)
                Text(text = "種類: ${device.type.label}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "IPアドレス: ${device.ipAddress}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "用途: ${device.purpose}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
