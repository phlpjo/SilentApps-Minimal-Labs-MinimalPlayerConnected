package de.philipdecker.playerconnected

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** „Von Minimal Library empfangen“: Empfang läuft nur, solange dieser Bildschirm offen ist (Bildschirm bleibt an) */
@Composable
fun ReceiveScreen(ui: Actions) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val st by Receive.status.collectAsState()
    val version by Library.version.collectAsState()
    val forgotten = remember { mutableIntStateOf(0) }
    // Empfang läuft, solange die App offen ist; hier bleibt zusätzlich der Bildschirm an
    DisposableEffect(Unit) {
        Receive.start(ctx)
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val folder = Receive.folder(ctx)
    val folderName by produceState<String?>(null, folder, version) {
        value = folder?.let { f -> withContext(Dispatchers.IO) { Library.rootName(ctx, Uri.parse(f)) } }
    }
    val paired = remember(st.client, forgotten.intValue) { Receive.paired(ctx).size }
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(scroll)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        T(stringResource(R.string.receive_title), size = 32.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.floatIn(0))
        T(Receive.deviceName(ctx), size = 15.sp, color = Dim, maxLines = 1, modifier = Modifier.floatIn(1))

        Section(stringResource(R.string.receive_folder))
        if (folder == null) {
            T(stringResource(R.string.receive_need_folder), size = 15.sp, color = Dim)
            Option(stringResource(R.string.receive_pick)) { ui.pickReceiveFolder() }
        } else {
            T(folderName ?: "…", size = 22.sp, weight = FontWeight.Light, maxLines = 2)
            Option(stringResource(R.string.receive_change), color = Dim) { ui.pickReceiveFolder() }

            Section(stringResource(R.string.receive_code))
            T(st.code.chunked(3).joinToString(" "), size = 44.sp, weight = FontWeight.Light, glow = true, spacing = 4.sp)
            Spacer(Modifier.height(18.dp))
            val line = when {
                st.error != null -> st.error ?: ""
                st.finished -> stringResource(R.string.receive_done)
                st.total > 0 -> stringResource(R.string.receive_progress, st.done.coerceAtMost(st.total), st.total)
                else -> stringResource(R.string.receive_ready)
            }
            T(line, size = 18.sp)
            st.client?.let { T(stringResource(R.string.receive_from, it), size = 14.sp, color = Dim, maxLines = 1) }
            st.current?.takeIf { !st.finished }?.let { T(it, size = 13.sp, color = Faint, maxLines = 1) }
            Spacer(Modifier.height(18.dp))
            T(stringResource(R.string.receive_how), size = 14.sp, color = Dim)

            if (paired > 0) {
                Section(stringResource(R.string.receive_paired, paired))
                HoldOption(stringResource(R.string.receive_forget)) { Receive.forgetAll(ctx); forgotten.intValue++ }
            }
        }

        Spacer(Modifier.height(24.dp))
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }
}
