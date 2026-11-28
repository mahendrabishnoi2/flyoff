package io.github.mahendrabishnoi2.flyoff.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.mahendrabishnoi2.flyoff.R
import io.github.mahendrabishnoi2.flyoff.credits.Credit
import io.github.mahendrabishnoi2.flyoff.credits.Credits
import io.github.mahendrabishnoi2.flyoff.credits.displayUrl
import io.github.mahendrabishnoi2.flyoff.credits.licenceParagraphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Credits and licences: every bundled data source, font and library ([Credits.all]) with its
 * licence. Licence texts come from the APK's assets, so they read offline; web links open in
 * the browser only when tapped. The screen itself uses no network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onClose: () -> Unit) {
    // The licence text on show (its asset and title), or null for the list.
    var licenceAsset by rememberSaveable { mutableStateOf<String?>(null) }
    var licenceTitle by rememberSaveable { mutableStateOf("") }
    // Kept here so the list's position survives opening a licence text.
    val listScroll = rememberScrollState()

    val asset = licenceAsset
    if (asset != null) {
        LicenceTextScreen(title = licenceTitle, asset = asset, onBack = { licenceAsset = null })
        return
    }
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credits_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(listScroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Long lines are hard to read on a wide screen.
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.credits_intro), style = MaterialTheme.typography.bodyMedium)
                for ((group, credits) in Credits.all.groupBy { it.group }) {
                    Text(
                        text = stringResource(group.title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .semantics { heading() },
                    )
                    for (credit in credits) {
                        val licenceName = stringResource(credit.licence.label)
                        CreditCard(
                            credit = credit,
                            licenceName = licenceName,
                            onReadLicence = { path ->
                                licenceTitle = "$licenceName · ${credit.name}"
                                licenceAsset = path
                            },
                            onOpenLink = { openLink(context, it) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(
                    text = stringResource(R.string.credits_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditCard(
    credit: Credit,
    licenceName: String,
    onReadLicence: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            // Read as one item by screen readers; the buttons below stay separate.
            Column(
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(credit.name, style = MaterialTheme.typography.titleMedium)
                credit.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(credit.usedFor), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.credits_licence, licenceName), style = MaterialTheme.typography.labelLarge)
            }
            // TextButtons keep a 48 dp touch target.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val text = credit.licence.textAsset
                if (text != null) {
                    val description = stringResource(R.string.credits_licence_text_description, licenceName, credit.name)
                    TextButton(
                        onClick = { onReadLicence(text) },
                        modifier = Modifier.semantics { contentDescription = description },
                    ) { Text(stringResource(R.string.credits_licence_text)) }
                } else if (credit.licence.url != credit.url) {
                    val description = stringResource(R.string.credits_licence_terms_description, licenceName, credit.name)
                    TextButton(
                        onClick = { onOpenLink(credit.licence.url) },
                        modifier = Modifier.semantics { contentDescription = description },
                    ) { Text(stringResource(R.string.credits_licence_terms)) }
                }
                val link = displayUrl(credit.url)
                val linkDescription = stringResource(R.string.credits_open_link_description, link)
                TextButton(
                    onClick = { onOpenLink(credit.url) },
                    modifier = Modifier.semantics { contentDescription = linkDescription },
                ) { Text(link) }
            }
        }
    }
}

/** A licence text from the assets, as paragraphs that flow to the screen's width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenceTextScreen(title: String, asset: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(initialValue = null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { licenceParagraphs(context.assets.open(asset).bufferedReader().use { it.readText() }) }
                .getOrDefault(emptyList())
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 2) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val loaded = paragraphs
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loaded != null && loaded.isEmpty()) {
                item { Text(stringResource(R.string.credits_licence_text_error), style = MaterialTheme.typography.bodyMedium) }
            }
            items(loaded.orEmpty()) { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** Opens [url] in the browser. Flyoff itself makes no network call for it. */
private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.credits_no_browser, displayUrl(url)), Toast.LENGTH_LONG).show()
    }
}
