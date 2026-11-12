package vn.tutu.musemobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WifiPickerScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Chọn Wi‑Fi nhà",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Tính năng quét Wi‑Fi và ưu tiên 2.4 GHz sẽ được bổ sung ở bước tiếp theo.",
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
