package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TachoAmber

/** ITS PIN entry (Appendix 13): the tachograph shows or defines the PIN, the phone must send it once. */
@Composable
fun PinDialog(
    deviceName: String?,
    failedAttempts: Int,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("PIN тахографа") },
        text = {
            Column {
                Text(
                    "${deviceName ?: "Тахограф"} просит PIN для доступа к данным по ITS-интерфейсу. " +
                        "PIN показывает или задаёт тахограф (меню Bluetooth / ITS).",
                    fontSize = 13.sp
                )
                if (failedAttempts > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Неверных попыток подряд: $failedAttempts. После 3 тахограф блокирует телефон на 30 с, дальше дольше.",
                        fontSize = 12.sp,
                        color = TachoAmber
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.testTag("its_pin_input")
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { onSubmit(pin) }) { Text("Отправить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отключиться") } }
    )
}
