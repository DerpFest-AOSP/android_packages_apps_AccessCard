// SPDX-License-Identifier: Apache-2.0
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.derpfest.accesscard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.derpfest.accesscard.WalletState
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*

val CardColors =
    listOf(
        Color(0xFF224C45),
        Color(0xFF50436E),
        Color(0xFF365581),
        Color(0xFF785932),
        Color(0xFF804955),
        Color(0xFF35434C),
    )
val CardIcons =
    listOf(
        R.drawable.ic_home,
        R.drawable.ic_work,
        R.drawable.ic_building,
        R.drawable.ic_key,
        R.drawable.ic_card,
    )
val CardIconNames =
    listOf(
        R.string.icon_home,
        R.string.icon_work,
        R.string.icon_building,
        R.string.icon_key,
        R.string.icon_card,
    )

@Composable
fun AccessCardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme =
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        content = content,
    )
}

@Composable
fun Glyph(id: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(id), description, modifier.size(24.dp))
}

@Composable
fun CardFace(
    card: AccessCardProfile,
    active: Boolean,
    isDefault: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val color = CardColors[card.color]
    Box(
        modifier
            .shadow(if (compact) 0.dp else 9.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(color, color.copy(red = (color.red + .12f).coerceAtMost(1f)))
                )
            )
            .heightIn(min = if (compact) 168.dp else 214.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                Color.White.copy(alpha = .055f),
                size.width * .62f,
                center = Offset(size.width * .96f, size.height * .05f),
            )
            drawCircle(
                Color.White.copy(alpha = .045f),
                size.width * .42f,
                center = Offset(size.width * .72f, size.height * 1.1f),
            )
            for (i in 0..2) {
                val radius = 13.dp.toPx() + i * 10.dp.toPx()
                drawArc(
                    Color.White.copy(alpha = .55f),
                    -48f,
                    96f,
                    false,
                    topLeft =
                        Offset(
                            size.width - 58.dp.toPx() - radius,
                            size.height - 40.dp.toPx() - radius,
                        ),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = .13f), shape = RoundedCornerShape(13.dp)) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        Box(Modifier.padding(10.dp)) { Glyph(CardIcons[card.icon]) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (active || isDefault) {
                    Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape) {
                        Text(
                            stringResource(
                                if (active) R.string.active_badge else R.string.default_badge
                            ),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Text(
                card.name,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style =
                    if (compact) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AccessCard",
                    color = Color.White.copy(alpha = .7f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "•• " + card.identity.uidHex.takeLast(4),
                    color = Color.White.copy(alpha = .8f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
fun HardwareStatus(
    state: WalletState,
    qualification: Qualification,
    onNfcSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = state.mode
    val title =
        when (mode) {
            AccessMode.ACTIVE ->
                state.activeCard?.let { stringResource(R.string.mode_active, it.name) }
                    ?: stringResource(R.string.mode_external)
            AccessMode.NFC_OFF -> stringResource(R.string.mode_nfc_off)
            AccessMode.UNSUPPORTED -> stringResource(R.string.mode_unsupported)
            AccessMode.ERROR -> stringResource(R.string.mode_error)
            AccessMode.APPLYING -> stringResource(R.string.mode_applying)
            AccessMode.STOPPING -> stringResource(R.string.mode_stopping)
            AccessMode.READING -> stringResource(R.string.mode_reading)
            AccessMode.IDLE -> stringResource(R.string.mode_idle)
        }
    val description =
        when (mode) {
            AccessMode.ACTIVE -> R.string.mode_active_detail
            AccessMode.NFC_OFF -> R.string.mode_nfc_off_detail
            AccessMode.UNSUPPORTED -> R.string.mode_unsupported_detail
            AccessMode.ERROR ->
                state.hardware.failure?.messageRes
                    ?: if (state.hardware.needsRecovery) R.string.mode_recovery
                    else R.string.mode_error_detail
            else -> R.string.mode_idle_detail
        }
    Surface(
        modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else
                    Glyph(if (mode == AccessMode.ACTIVE) R.drawable.ic_check else R.drawable.ic_nfc)
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (qualification == Qualification.EXPERIMENTAL) {
                Text(
                    stringResource(R.string.experimental),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (mode == AccessMode.NFC_OFF || state.hardware.needsRecovery) {
                TextButton(onNfcSettings) { Text(stringResource(R.string.nfc_settings)) }
            } else if (mode == AccessMode.ERROR) {
                TextButton(onRefresh) { Text(stringResource(R.string.refresh)) }
            }
        }
    }
}

@Composable
fun WalletScreen(
    state: WalletState,
    capabilities: BackendCapabilities,
    requestedCard: String?,
    onAdd: () -> Unit,
    onActivate: (AccessCardProfile) -> Unit,
    onStop: () -> Unit,
    onUpdate: (AccessCardProfile) -> Unit,
    onDefault: (String?) -> Unit,
    onDelete: (AccessCardProfile) -> Unit,
    onMove: (String, Int) -> Unit,
    onAddTile: () -> Unit,
    onNfcSettings: () -> Unit,
    onRefresh: () -> Unit,
    onClearMessage: () -> Unit,
    quick: Boolean = false,
    onOpenApp: () -> Unit = {},
    onClose: () -> Unit = {},
    selectionSession: Int = 0,
    onPreferences: () -> Unit = {},
) {
    var selectedId by rememberSaveable(selectionSession) { mutableStateOf<String?>(null) }
    var consumedRequest by rememberSaveable(selectionSession) { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable(selectionSession) { mutableStateOf(false) }
    var detailId by rememberSaveable(selectionSession) { mutableStateOf<String?>(null) }
    var about by rememberSaveable(selectionSession) { mutableStateOf(false) }
    val cards = state.wallet.cards
    val ids = cards.map { it.id }
    LaunchedEffect(ids, requestedCard, selectionSession) {
        if (requestedCard in ids && requestedCard != consumedRequest) {
            selectedId = requestedCard
            consumedRequest = requestedCard
        }
        if (selectedId !in ids)
            selectedId = state.activeCard?.id ?: state.wallet.defaultId ?: cards.firstOrNull()?.id
    }
    val selected = cards.firstOrNull { it.id == selectedId } ?: cards.firstOrNull()
    val scroll = rememberScrollState()
    Surface(
        shape =
            if (quick) RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            else RoundedCornerShape(0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (quick) Modifier.heightIn(max = 680.dp) else Modifier.fillMaxSize())
                .windowInsetsPadding(
                    if (quick) WindowInsets.navigationBars else WindowInsets.safeDrawing
                )
                .verticalScroll(scroll)
        ) {
            if (quick) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp)
                        .size(36.dp, 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (quick) stringResource(R.string.quick_title) else "AccessCard",
                        style =
                            if (quick) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!quick)
                        Text(
                            stringResource(R.string.app_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (!quick) IconButton(onPreferences) {
                    Glyph(R.drawable.ic_settings, stringResource(R.string.preferences))
                }
                IconButton(onClick = { if (quick) onClose() else about = true }) {
                    Glyph(
                        if (quick) R.drawable.ic_close else R.drawable.ic_info,
                        stringResource(if (quick) R.string.close else R.string.about),
                    )
                }
            }
            if (state.loading) {
                Column(
                    Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.loading))
                }
            } else if (cards.isEmpty()) {
                EmptyWallet(onAdd, !state.storageFailed && capabilities.canReadIdentity)
            } else {
                if (quick) Spacer(Modifier.height(4.dp))
                CardCarousel(
                    cards,
                    selected?.id,
                    state,
                    { selectedId = it },
                    { detailId = it },
                    quick,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            R.string.page_indicator,
                            (cards.indexOf(selected).coerceAtLeast(0) + 1),
                            cards.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton({ showAll = true }) {
                        Glyph(R.drawable.ic_grid)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.all_cards))
                    }
                }
                selected?.let { card ->
                    val active =
                        state.hardware.identity == card.identity &&
                            state.hardware.mode == AccessMode.ACTIVE
                    Button(
                        onClick = { if (active) onStop() else onActivate(card) },
                        enabled =
                            !state.busy &&
                                (active ||
                                    (!state.storageFailed &&
                                        capabilities.canApplyIdentity &&
                                        card.identity.isLegacyTypeA &&
                                        card.identity.uid.size in capabilities.allowedUidLengths &&
                                        state.hardware.mode in
                                            listOf(AccessMode.IDLE, AccessMode.ACTIVE))),
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .heightIn(min = 54.dp),
                    ) {
                        Glyph(if (active) R.drawable.ic_stop else R.drawable.ic_nfc)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (active) R.string.stop_card else R.string.use_card))
                    }
                    if (!quick)
                        TextButton(
                            { detailId = card.id },
                            Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(R.string.card_details))
                        }
                }
            }
            Spacer(Modifier.height(16.dp))
            HardwareStatus(
                state,
                capabilities.qualification,
                onNfcSettings,
                onRefresh,
                Modifier.padding(horizontal = 24.dp),
            )
            if (state.hardware.needsRecovery) {
                TextButton(
                    onStop,
                    Modifier.align(Alignment.CenterHorizontally),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.retry_recovery))
                }
            }
            if (
                state.hardware.mode == AccessMode.ACTIVE &&
                    state.hardware.identity != selected?.identity
            ) {
                TextButton(
                    onStop,
                    Modifier.align(Alignment.CenterHorizontally),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.stop_card))
                }
            }
            state.message?.let { message ->
                Surface(
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(message.messageRes),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClearMessage) {
                            Glyph(R.drawable.ic_close, stringResource(R.string.close))
                        }
                    }
                }
            }
            if (!state.loading) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (cards.isNotEmpty())
                        TextButton(
                            onAdd,
                            enabled =
                                !state.busy && !state.storageFailed && capabilities.canReadIdentity,
                        ) {
                            Glyph(R.drawable.ic_add)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.add_card))
                        }
                    TextButton(if (quick) onOpenApp else onAddTile) {
                        Text(stringResource(if (quick) R.string.open_app else R.string.add_tile))
                    }
                }
            }
        }
    }
    if (showAll)
        AllCardsSheet(
            state,
            selected?.id,
            { showAll = false },
            {
                selectedId = it
                showAll = false
            },
            onMove,
        )
    cards
        .firstOrNull { it.id == detailId }
        ?.let { card ->
            DetailsSheet(
                card,
                state,
                capabilities,
                { detailId = null },
                onActivate,
                onStop,
                onUpdate,
                onDefault,
                {
                    onDelete(card)
                    detailId = null
                },
            )
        }
    if (about)
        ModalBottomSheet({ about = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.about), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (capabilities.verifiedUidLengths.isEmpty())
                        stringResource(R.string.no_verified_lengths)
                    else
                        stringResource(
                            R.string.verified_lengths,
                            capabilities.verifiedUidLengths.sorted().joinToString(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
}

@Composable
private fun CardCarousel(
    cards: List<AccessCardProfile>,
    selectedId: String?,
    state: WalletState,
    onSelect: (String) -> Unit,
    onDetails: (String) -> Unit,
    compact: Boolean,
) {
    val ids = cards.map { it.id }
    val initial = cards.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial, pageCount = { cards.size })
    LaunchedEffect(selectedId, ids) {
        val index = cards.indexOfFirst { it.id == selectedId }
        if (index >= 0 && (index != pager.currentPage || pager.isScrollInProgress))
            pager.animateScrollToPage(index)
    }
    LaunchedEffect(pager, ids) {
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }
            // Initial composition and reordered lists must not replace an explicit selection.
            .drop(1)
            .filter { !it.second }
            .distinctUntilChanged()
            .collect { (index, _) -> cards.getOrNull(index)?.let { onSelect(it.id) } }
    }
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 24.dp),
        pageSpacing = 14.dp,
        key = { cards[it].id },
        modifier = Modifier.fillMaxWidth().testTag("card_carousel"),
    ) { index ->
        val card = cards[index]
        CardFace(
            card,
            state.hardware.identity == card.identity && state.hardware.mode == AccessMode.ACTIVE,
            state.wallet.defaultId == card.id,
            Modifier.fillMaxWidth().padding(vertical = 12.dp).clickable(
                onClickLabel = stringResource(R.string.card_details)
            ) {
                onDetails(card.id)
            },
            compact,
        )
    }
}

@Composable
private fun EmptyWallet(onAdd: () -> Unit, enabled: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(110.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_accesscard),
                null,
                Modifier.size(108.dp),
                tint = Color.Unspecified,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onAdd, enabled = enabled, modifier = Modifier.heightIn(min = 52.dp)) {
            Glyph(R.drawable.ic_add)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_card))
        }
    }
}

@Composable
private fun AllCardsSheet(
    state: WalletState,
    selectedId: String?,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleCards = if (editing) state.wallet.cards else state.wallet.cards.filter {
        it.name.contains(query.trim(), ignoreCase = true) || it.notes.contains(query.trim(), ignoreCase = true)
    }
    ModalBottomSheet(onClose) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.all_cards),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton({ editing = !editing; query = "" }) {
                Text(stringResource(if (editing) R.string.done else R.string.edit_order))
            }
        }
        OutlinedTextField(query, { query = it.take(128) }, enabled = !editing,
            label = { Text(stringResource(R.string.search_cards)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("card_search"))
        if (visibleCards.isEmpty()) Text(stringResource(R.string.no_search_results), Modifier.padding(24.dp))
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(visibleCards, key = { _, card -> card.id }) { index, card ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color =
                        if (card.id == selectedId) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !editing) { onSelect(card.id) }
                            .testTag("card_list_row_${card.id}")
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = CardColors[card.color],
                            shape = RoundedCornerShape(12.dp),
                            contentColor = Color.White,
                        ) {
                            Box(Modifier.padding(12.dp)) { Glyph(CardIcons[card.icon]) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(
                                card.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val label =
                                when {
                                    state.hardware.mode == AccessMode.ACTIVE &&
                                        state.hardware.identity == card.identity ->
                                        R.string.active_badge
                                    state.wallet.defaultId == card.id -> R.string.default_badge
                                    card.id == selectedId -> R.string.selected_badge
                                    else -> null
                                }
                            label?.let {
                                Text(
                                    stringResource(it),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        if (editing) {
                            IconButton(
                                { onMove(card.id, -1) },
                                enabled = index > 0 && !state.busy,
                            ) {
                                Glyph(R.drawable.ic_up, stringResource(R.string.move_up, card.name))
                            }
                            IconButton(
                                { onMove(card.id, 1) },
                                enabled = index < state.wallet.cards.lastIndex && !state.busy,
                            ) {
                                Glyph(
                                    R.drawable.ic_down,
                                    stringResource(R.string.move_down, card.name),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceEditor(
    name: String,
    color: Int,
    icon: Int,
    onName: (String) -> Unit,
    onColor: (Int) -> Unit,
    onIcon: (Int) -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        name,
        { if (it.length <= 64) onName(it) },
        label = { Text(stringResource(R.string.card_name)) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("${name.length}/64") },
    )
    Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardColors.forEachIndexed { index, shade ->
            val description = stringResource(R.string.color_option, index + 1)
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .background(shade)
                    .border(
                        if (index == color) 3.dp else 0.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape,
                    )
                    .clickable(enabled = enabled) { onColor(index) }
                    .semantics {
                        contentDescription = description
                        selected = index == color
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (index == color)
                    Icon(painterResource(R.drawable.ic_check), null, tint = Color.White)
            }
        }
    }
    Text(stringResource(R.string.icon), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CardIcons.forEachIndexed { index, drawable ->
            FilledTonalIconButton(
                { onIcon(index) },
                enabled = enabled,
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (icon == index) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                    ),
            ) {
                Glyph(drawable, stringResource(CardIconNames[index]))
            }
        }
    }
}

@Composable
private fun DetailsSheet(
    card: AccessCardProfile,
    state: WalletState,
    capabilities: BackendCapabilities,
    onClose: () -> Unit,
    onActivate: (AccessCardProfile) -> Unit,
    onStop: () -> Unit,
    onUpdate: (AccessCardProfile) -> Unit,
    onDefault: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable(card.id) { mutableStateOf(card.name) }
    var color by rememberSaveable(card.id) { mutableIntStateOf(card.color) }
    var icon by rememberSaveable(card.id) { mutableIntStateOf(card.icon) }
    var notes by rememberSaveable(card.id) { mutableStateOf(card.notes) }
    var showTests by rememberSaveable { mutableStateOf(false) }
    var addTest by rememberSaveable(card.id) { mutableStateOf(false) }
    var technical by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.card_details),
                style = MaterialTheme.typography.headlineSmall,
            )
            val active =
                state.hardware.mode == AccessMode.ACTIVE && state.hardware.identity == card.identity
            CardFace(card, active, card.id == state.wallet.defaultId, Modifier.fillMaxWidth())
            Button(
                onClick = { if (active) onStop() else onActivate(card) },
                enabled =
                    !state.busy &&
                        (active ||
                            (!state.storageFailed &&
                                capabilities.canApplyIdentity &&
                                card.identity.isLegacyTypeA &&
                                card.identity.uid.size in capabilities.allowedUidLengths &&
                                state.hardware.mode in listOf(AccessMode.IDLE, AccessMode.ACTIVE))),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            ) {
                Text(stringResource(if (active) R.string.stop_card else R.string.use_card))
            }
            AppearanceEditor(
                name,
                color,
                icon,
                { name = it },
                { color = it },
                { icon = it },
                !state.busy,
            )
            OutlinedTextField(notes, { if (it.length <= 512) notes = it },
                label = { Text(stringResource(R.string.card_notes)) },
                supportingText = { Text("${notes.length}/512") }, enabled = !state.busy,
                minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            Button(
                {
                    onUpdate(card.withAppearance(name, color, icon).withNotes(notes))
                    onClose()
                },
                enabled = name.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.save_changes))
            }
            val isDefault = card.id == state.wallet.defaultId
            OutlinedButton(
                { onDefault(if (isDefault) null else card.id) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (isDefault) R.string.clear_default else R.string.set_default)
                )
            }
            TextButton({ showTests = !showTests }) { Text(stringResource(R.string.test_history)) }
            if (showTests) {
                ManualTestHistory(card.tests, !state.busy) { onUpdate(card.withoutTest(it)) }
                OutlinedButton({ addTest = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.add_test_record))
                }
            }
            TextButton({ technical = !technical }) {
                Text(stringResource(R.string.technical_details))
                Glyph(if (technical) R.drawable.ic_up else R.drawable.ic_down)
            }
            if (technical) {
                Text(
                    "UID  ${card.identity.uidHex}\nATQA  ${card.identity.atqaHex}\nSAK  ${card.identity.sakHex}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.identity_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton({ confirmDelete = true }, enabled = !state.busy) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (addTest) ManualTestDialog({ addTest = false }) {
        onUpdate(card.withTest(it))
        addTest = false
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
}

@Preview(name = "Wallet • light", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "Wallet • dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Traditional Chinese • large text",
    showBackground = true,
    widthDp = 320,
    heightDp = 915,
    locale = "zh-rTW",
    fontScale = 1.6f,
)
@Composable
private fun WalletPreview() {
    val first =
        AccessCardProfile(
            "00000000-0000-0000-0000-000000000001",
            "Home · front entrance and residents' lobby",
            CardIdentity(byteArrayOf(1, 2, 3, 4), byteArrayOf(0, 4), 8),
            0,
            0,
            0,
        )
    val second =
        AccessCardProfile(
            "00000000-0000-0000-0000-000000000002",
            "Studio",
            CardIdentity(byteArrayOf(5, 6, 7, 8), byteArrayOf(0, 4), 8),
            1,
            1,
            0,
        )
    AccessCardTheme {
        WalletScreen(
            WalletState(
                WalletSnapshot(listOf(first, second), first.id),
                HardwareState(AccessMode.IDLE),
                loading = false,
            ),
            BackendCapabilities(
                true,
                true,
                true,
                Qualification.EXPERIMENTAL,
                setOf(4, 7, 10),
                emptySet(),
            ),
            null,
            {},
            {},
            {},
            {},
            {},
            {},
            { _, _ -> },
            {},
            {},
            {},
            {},
        )
    }
}

@Preview(name = "Quick picker • active", showBackground = true, widthDp = 412, heightDp = 760)
@Composable
private fun QuickPreview() {
    val card =
        AccessCardProfile(
            "00000000-0000-0000-0000-000000000001",
            "Studio",
            CardIdentity(byteArrayOf(1, 2, 3, 4), byteArrayOf(0, 4), 8),
            1,
            1,
            0,
        )
    AccessCardTheme {
        WalletScreen(
            WalletState(
                WalletSnapshot(listOf(card), null),
                HardwareState(AccessMode.ACTIVE, card.identity),
                loading = false,
            ),
            BackendCapabilities(true, true, true, Qualification.EXPERIMENTAL, setOf(4), emptySet()),
            null,
            {},
            {},
            {},
            {},
            {},
            {},
            { _, _ -> },
            {},
            {},
            {},
            {},
            quick = true,
        )
    }
}

@Preview(name = "Empty wallet", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun EmptyPreview() {
    AccessCardTheme {
        WalletScreen(
            WalletState(loading = false, hardware = HardwareState(AccessMode.UNSUPPORTED)),
            BackendCapabilities(true, false, false, Qualification.UNKNOWN, emptySet(), emptySet()),
            null,
            {},
            {},
            {},
            {},
            {},
            {},
            { _, _ -> },
            {},
            {},
            {},
            {},
        )
    }
}
