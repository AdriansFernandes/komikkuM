package eu.kanade.presentation.manga

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.interactor.FindCrossLanguageMangaMatches
import eu.kanade.domain.manga.interactor.SmartSearchMerge
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
private fun groupKeyOf(group: List<Manga>): Long = group.firstOrNull()?.id ?: -1L

/**
 * High-performance Preview, Batch Suggestion & Confirmation sheet for integrating manga entries
 * across languages (e.g., PT-BR, EN-US, Japanese) into unified Library banners.
 *
 * Uses [LazyColumn] for smooth scrolling across many groups and [SmartSearchMerge.integrateMangaGroupsBatch]
 * for single-transaction batch database updates without freezing the UI.
 */
@Composable
fun MangaIntegrationPreviewDialog(
    mangas: List<Manga>,
    onDismissRequest: () -> Unit,
    onIntegrated: (Manga) -> Unit,
    onBatchIntegrated: ((List<Manga>) -> Unit)? = null,
    onAddSeparately: (() -> Unit)? = null,
    candidateGroups: List<List<Manga>> = emptyList(),
    scanLibrary: Boolean = false,
    onEmptyScanResult: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sourceManager = remember { Injekt.get<SourceManager>() }
    val getLibraryManga = remember { Injekt.get<GetLibraryManga>() }
    val getMergedMangaById = remember { Injekt.get<GetMergedMangaById>() }
    val smartSearchMerge = remember { Injekt.get<SmartSearchMerge>() }
    val findCrossLanguageMatches = remember { Injekt.get<FindCrossLanguageMangaMatches>() }
    val scope = rememberCoroutineScope()

    val distinctInputMangas = remember(mangas) { mangas.distinctBy { it.id } }

    var isScanning by remember {
        mutableStateOf(scanLibrary || (candidateGroups.isEmpty() && distinctInputMangas.size == 1))
    }
    var stopScanRequested by remember { mutableStateOf(false) }
    var scanProcessed by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var scanCurrentTitle by remember { mutableStateOf("") }
    var liveGroupsCount by remember { mutableIntStateOf(candidateGroups.size) }
    var latestScannedGroups by remember { mutableStateOf(candidateGroups) }

    var discoveredGroups by remember(candidateGroups) { mutableStateOf(candidateGroups) }
    var autoDetectedSelectionGroups by remember { mutableStateOf<List<List<Manga>>>(emptyList()) }
    var useAutoGroupsForSelection by remember { mutableStateOf(true) }

    // Run scan if requested (whole library, single manga lookup, or multi-selection grouping)
    LaunchedEffect(scanLibrary, distinctInputMangas, candidateGroups) {
        if (scanLibrary) {
            isScanning = true
            val found = findCrossLanguageMatches.findLibraryIntegrationGroups(
                targetMangas = null,
                includeRemoteLookup = true,
                shouldStop = { stopScanRequested },
                onProgress = { processed, total, title, liveGroups ->
                    scanProcessed = processed
                    scanTotal = total
                    scanCurrentTitle = title
                    liveGroupsCount = liveGroups.size
                    latestScannedGroups = liveGroups
                },
            )
            val finalGroups = if (found.isNotEmpty()) found else latestScannedGroups
            discoveredGroups = finalGroups
            isScanning = false
            if (finalGroups.isEmpty()) {
                onEmptyScanResult?.invoke()
                onDismissRequest()
            }
        } else if (candidateGroups.isEmpty() && distinctInputMangas.size == 1) {
            isScanning = true
            val single = distinctInputMangas.first()
            scanTotal = 1
            scanCurrentTitle = single.title
            val matches = findCrossLanguageMatches(single, includeRemoteLookup = true)
            if (matches.isNotEmpty()) {
                discoveredGroups = listOf((listOf(single) + matches.map { it.manga }).distinctBy { it.id })
                isScanning = false
            } else {
                isScanning = false
                onEmptyScanResult?.invoke()
                onDismissRequest()
            }
        } else if (candidateGroups.isEmpty() && distinctInputMangas.size > 2) {
            val subGroups = findCrossLanguageMatches.findLibraryIntegrationGroups(
                targetMangas = distinctInputMangas,
                includeRemoteLookup = false,
            )
            if (subGroups.size >= 2) {
                autoDetectedSelectionGroups = subGroups
            }
        }
    }

    val allRawGroups = remember(
        discoveredGroups,
        autoDetectedSelectionGroups,
        useAutoGroupsForSelection,
        distinctInputMangas,
    ) {
        when {
            discoveredGroups.isNotEmpty() -> discoveredGroups
            autoDetectedSelectionGroups.size >= 2 && useAutoGroupsForSelection -> autoDetectedSelectionGroups
            distinctInputMangas.isNotEmpty() -> listOf(distinctInputMangas)
            else -> emptyList()
        }
    }

    // Track groups already integrated during this session so we can stay in the list 1 by 1
    val integratedGroupKeys = remember { mutableStateMapOf<Long, Boolean>() }

    val activeGroups = remember(allRawGroups, integratedGroupKeys.size) {
        allRawGroups.filterNot { integratedGroupKeys[groupKeyOf(it)] == true }
    }

    val isGeneralListMode = scanLibrary || candidateGroups.size > 1 || allRawGroups.size > 1

    // Stable maps keyed by groupKey (Long) and mangaId (Long) so removing 1 group never shifts other groups
    val groupCheckedMap = remember { mutableStateMapOf<Long, Boolean>() }
    val mangaCheckedMap = remember { mutableStateMapOf<Long, Boolean>() }
    val primaryMangaPerGroup = remember { mutableStateMapOf<Long, Long>() }

    LaunchedEffect(allRawGroups, isGeneralListMode) {
        for (group in allRawGroups) {
            val gKey = groupKeyOf(group)
            if (!groupCheckedMap.containsKey(gKey)) {
                // In the general integration list, groups start UNSELECTED by default.
                // Only in single manual pair mode does the 1 group start selected.
                groupCheckedMap[gKey] = !isGeneralListMode
            }
            if (!primaryMangaPerGroup.containsKey(gKey)) {
                primaryMangaPerGroup[gKey] = group.firstOrNull { it.source != MERGED_SOURCE_ID }?.id
                    ?: group.firstOrNull()?.id
                    ?: -1L
            }
            for (m in group) {
                if (!mangaCheckedMap.containsKey(m.id)) {
                    mangaCheckedMap[m.id] = true
                }
            }
        }
    }

    var expandedChildrenByParentId by remember { mutableStateOf<Map<Long, List<Manga>>>(emptyMap()) }
    var chapterCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }

    // Fast single-query metadata load once scanning is finished
    LaunchedEffect(allRawGroups, isScanning) {
        if (isScanning || allRawGroups.isEmpty()) return@LaunchedEffect
        withIOContext {
            val libraryMap = getLibraryManga.await().associateBy { it.manga.id }
            val childrenMap = HashMap<Long, List<Manga>>()
            val counts = HashMap<Long, Int>()

            for (group in allRawGroups) {
                for (m in group) {
                    if (m.source == MERGED_SOURCE_ID) {
                        val children = getMergedMangaById.await(m.id).filter { it.source != MERGED_SOURCE_ID }
                        childrenMap[m.id] = children
                        counts[m.id] = libraryMap[m.id]?.totalChapters?.toInt() ?: 0
                    } else {
                        counts[m.id] = libraryMap[m.id]?.totalChapters?.toInt() ?: 0
                    }
                }
            }
            expandedChildrenByParentId = childrenMap
            chapterCounts = counts
        }
    }

    var keepSingleBanner by remember { mutableStateOf(true) }
    var isIntegratingBatch by remember { mutableStateOf(false) }
    var integratingSingleGroupKey by remember { mutableLongStateOf(-1L) }
    var mergeCurrentIndex by remember { mutableIntStateOf(0) }
    var mergeTotalCount by remember { mutableIntStateOf(0) }
    var mergeCurrentTitle by remember { mutableStateOf("") }

    val isBusyIntegrating = isIntegratingBatch || integratingSingleGroupKey != -1L

    val validSelectedGroups = activeGroups.filter { group ->
        val gKey = groupKeyOf(group)
        groupCheckedMap[gKey] == true &&
            group.count { mangaCheckedMap[it.id] == true } >= 2
    }

    fun integrateGroups(groupsToMerge: List<List<Manga>>, isSingleCardAction: Boolean) {
        if (groupsToMerge.isEmpty() || isBusyIntegrating) return
        val batchGroups = groupsToMerge.mapNotNull { group ->
            val gKey = groupKeyOf(group)
            val chosen = group.filter { mangaCheckedMap[it.id] == true }
            if (chosen.size < 2) return@mapNotNull null
            val primaryId = primaryMangaPerGroup[gKey]?.takeIf { id ->
                chosen.any { it.id == id }
            } ?: chosen.firstOrNull { it.source != MERGED_SOURCE_ID }?.id
                ?: chosen.first().id

            gKey to SmartSearchMerge.BatchIntegrationGroup(
                mangasToMerge = chosen,
                infoMangaId = primaryId,
            )
        }
        if (batchGroups.isEmpty()) return

        val remainingAfterMerge = activeGroups.count { group ->
            val gKey = groupKeyOf(group)
            batchGroups.none { it.first == gKey }
        }

        if (isSingleCardAction && batchGroups.size == 1 && remainingAfterMerge > 0) {
            // Inline 1-by-1 integration without closing the list or losing scroll position!
            val (targetKey, singleBatchGroup) = batchGroups.first()
            integratingSingleGroupKey = targetKey
            scope.launch {
                val merged = withIOContext {
                    smartSearchMerge.integrateMangas(
                        mangasToMerge = singleBatchGroup.mangasToMerge,
                        infoMangaId = singleBatchGroup.infoMangaId,
                        hideChildrenFromLibrary = keepSingleBanner,
                    )
                }
                integratingSingleGroupKey = -1L
                if (merged != null) {
                    integratedGroupKeys[targetKey] = true
                    groupCheckedMap[targetKey] = false
                    context.toast(SYMR.strings.entry_merged)
                }
            }
            return
        }

        // Batch integration (or final remaining group)
        isIntegratingBatch = true
        mergeTotalCount = batchGroups.size
        mergeCurrentIndex = 0

        scope.launch {
            val mergedResults = withIOContext {
                smartSearchMerge.integrateMangaGroupsBatch(
                    groups = batchGroups.map { it.second },
                    hideChildrenFromLibrary = keepSingleBanner,
                    onProgress = { completed, total, title ->
                        mergeCurrentIndex = completed
                        mergeTotalCount = total
                        mergeCurrentTitle = title
                    },
                )
            }
            isIntegratingBatch = false

            for ((gKey, _) in batchGroups) {
                integratedGroupKeys[gKey] = true
                groupCheckedMap[gKey] = false
            }

            if (remainingAfterMerge > 0 && isGeneralListMode) {
                // Keep the dialog open so the user can continue integrating the remaining groups!
                if (mergedResults.size == 1) {
                    context.toast(SYMR.strings.entry_merged)
                } else if (mergedResults.size > 1) {
                    context.toast(
                        context.stringResource(
                            KMR.strings.manga_integration_batch_success,
                            mergedResults.size,
                        ),
                    )
                }
            } else {
                // All groups integrated (or single manual pair mode) -> close dialog
                onDismissRequest()
                if (mergedResults.size > 1 && onBatchIntegrated != null) {
                    onBatchIntegrated(mergedResults)
                } else if (mergedResults.isNotEmpty()) {
                    if (onBatchIntegrated != null && isGeneralListMode) {
                        onBatchIntegrated(mergedResults)
                    } else {
                        onIntegrated(mergedResults.first())
                    }
                }
            }
        }
    }

    val minHeight = LocalPreferenceMinHeight.current
    val horizontalPadding = PaddingValues(horizontal = TabbedDialogPaddings.Horizontal)
    val horizontalPaddingModifier = Modifier.padding(horizontalPadding)

    AdaptiveSheet(
        onDismissRequest = {
            if (!isBusyIntegrating) {
                stopScanRequested = true
                onDismissRequest()
            }
        },
    ) {
        // 1. PROCESSING / LOADING SCREEN (Scanning Library OR Integrating Multiple Groups)
        if (isScanning || isIntegratingBatch) {
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .padding(horizontal = TabbedDialogPaddings.Horizontal)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.padding.medium))

                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = if (isScanning) {
                        stringResource(KMR.strings.manga_integration_processing_scan_title)
                    } else {
                        stringResource(KMR.strings.manga_integration_processing_merge_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                if (isScanning) {
                    Text(
                        text = stringResource(KMR.strings.manga_integration_processing_scan_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.secondaryItemAlpha(),
                    )

                    if (scanTotal > 1) {
                        val progress = (scanProcessed.toFloat() / scanTotal.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Text(
                            text = stringResource(
                                KMR.strings.manga_integration_processing_scan_progress,
                                scanProcessed,
                                scanTotal,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (scanCurrentTitle.isNotBlank()) {
                        Text(
                            text = stringResource(
                                KMR.strings.manga_integration_processing_scan_current,
                                scanCurrentTitle,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.secondaryItemAlpha(),
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = stringResource(
                                KMR.strings.manga_integration_processing_groups_found_live,
                                liveGroupsCount,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    if (liveGroupsCount > 0) {
                        Button(
                            onClick = {
                                stopScanRequested = true
                                discoveredGroups = latestScannedGroups
                                isScanning = false
                            },
                            modifier = Modifier
                                .heightIn(min = minHeight)
                                .fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    KMR.strings.manga_integration_stop_scan_and_view,
                                    liveGroupsCount,
                                ),
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            stopScanRequested = true
                            onDismissRequest()
                        },
                        modifier = Modifier
                            .heightIn(min = minHeight)
                            .fillMaxWidth(),
                    ) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                } else {
                    val progress = if (mergeTotalCount > 0) {
                        (mergeCurrentIndex.toFloat() / mergeTotalCount.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        text = stringResource(
                            KMR.strings.manga_integration_processing_merge_progress,
                            mergeCurrentIndex,
                            mergeTotalCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (mergeCurrentTitle.isNotBlank()) {
                        Text(
                            text = mergeCurrentTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(MaterialTheme.padding.medium))
            }
            return@AdaptiveSheet
        }

        // 2. MULTI-GROUP PREVIEW & CONFIRMATION SCREEN (LazyColumn + Sticky Footer)
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            // Fixed Top Header
            Row(
                modifier = horizontalPaddingModifier.padding(top = MaterialTheme.padding.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.CallMerge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = stringResource(KMR.strings.manga_integration_preview_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(KMR.strings.manga_integration_preview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                modifier = horizontalPaddingModifier.secondaryItemAlpha(),
            )

            // Toggle between Auto-Grouped vs Single Group when user selected 3+ mangas manually
            if (autoDetectedSelectionGroups.size >= 2 && discoveredGroups.isEmpty()) {
                FlowRow(
                    modifier = horizontalPaddingModifier,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    FilterChip(
                        selected = useAutoGroupsForSelection,
                        onClick = { useAutoGroupsForSelection = true },
                        label = {
                            Text(
                                text = stringResource(
                                    KMR.strings.manga_integration_mode_auto_groups,
                                    autoDetectedSelectionGroups.size,
                                ),
                            )
                        },
                    )
                    FilterChip(
                        selected = !useAutoGroupsForSelection,
                        onClick = { useAutoGroupsForSelection = false },
                        label = {
                            Text(text = stringResource(KMR.strings.manga_integration_mode_single_group))
                        },
                    )
                }
            }

            // Multi-group Select All / Deselect All bar
            if (isGeneralListMode && activeGroups.isNotEmpty()) {
                val allSelected = activeGroups.all { groupCheckedMap[groupKeyOf(it)] == true }
                Row(
                    modifier = horizontalPaddingModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            KMR.strings.manga_integration_groups_found,
                            activeGroups.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val target = !allSelected
                            activeGroups.forEach { groupCheckedMap[groupKeyOf(it)] = target }
                        },
                    ) {
                        Text(
                            text = if (allSelected) {
                                stringResource(KMR.strings.manga_integration_deselect_all_groups)
                            } else {
                                stringResource(KMR.strings.manga_integration_select_all_groups, activeGroups.size)
                            },
                        )
                    }
                }
            }

            // Virtualized LazyColumn for butter-smooth scrolling across any number of groups
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = TabbedDialogPaddings.Horizontal,
                    vertical = MaterialTheme.padding.extraSmall,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                itemsIndexed(
                    items = activeGroups,
                    key = { _, group -> groupKeyOf(group) },
                ) { groupIdx, groupMangas ->
                    val gKey = groupKeyOf(groupMangas)
                    val isGroupChecked = groupCheckedMap[gKey] == true
                    val isThisCardIntegrating = integratingSingleGroupKey == gKey
                    val chosenInGroup = groupMangas.filter { mangaCheckedMap[it.id] == true }
                    val primaryId = primaryMangaPerGroup[gKey]
                        ?: groupMangas.firstOrNull { it.source != MERGED_SOURCE_ID }?.id
                        ?: groupMangas.firstOrNull()?.id
                        ?: -1L

                    val displayLanguagesList = buildList {
                        for (m in groupMangas) {
                            if (mangaCheckedMap[m.id] != true) continue
                            if (m.source == MERGED_SOURCE_ID) {
                                val children = expandedChildrenByParentId[m.id]
                                if (!children.isNullOrEmpty()) {
                                    addAll(children)
                                } else {
                                    add(m)
                                }
                            } else {
                                add(m)
                            }
                        }
                    }.distinctBy { it.id }

                    val primaryManga = groupMangas.find { it.id == primaryId }
                        ?: displayLanguagesList.find { it.id == primaryId }
                        ?: chosenInGroup.firstOrNull()
                        ?: groupMangas.firstOrNull()

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = if (isGroupChecked) 4.dp else 1.dp,
                        border = BorderStroke(
                            width = if (isGroupChecked) 1.5.dp else 1.dp,
                            color = if (isGroupChecked) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(MaterialTheme.padding.medium),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            if (isGeneralListMode) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { groupCheckedMap[gKey] = !isGroupChecked },
                                    ) {
                                        Checkbox(
                                            checked = isGroupChecked,
                                            onCheckedChange = { groupCheckedMap[gKey] = it },
                                        )
                                        Text(
                                            text = stringResource(
                                                KMR.strings.manga_integration_group_header,
                                                groupIdx + 1,
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }

                                    Button(
                                        onClick = { integrateGroups(listOf(groupMangas), isSingleCardAction = true) },
                                        enabled = chosenInGroup.size >= 2 && !isBusyIntegrating,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        if (isThisCardIntegrating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = stringResource(KMR.strings.manga_integration_confirm_single_group),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }

                            // 1. Unified Library Banner Preview for this group
                            if (primaryManga != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Translate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(KMR.strings.manga_integration_banner_preview_label),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(MaterialTheme.padding.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                                ) {
                                    MangaCover.Book(
                                        data = primaryManga,
                                        modifier = Modifier.width(64.dp),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = primaryManga.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!primaryManga.author.isNullOrBlank()) {
                                            Text(
                                                text = primaryManga.author!!,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.secondaryItemAlpha(),
                                            )
                                        }

                                        Text(
                                            text = stringResource(KMR.strings.manga_integration_available_languages),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.secondaryItemAlpha(),
                                        )

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            displayLanguagesList.forEach { itemManga ->
                                                val src = remember(itemManga.source) {
                                                    sourceManager.getOrStub(itemManga.source)
                                                }
                                                val flag = remember(src.lang) {
                                                    FlagEmoji.getEmojiLangFlag(src.lang)
                                                }
                                                val langCode = remember(src.lang) {
                                                    LocaleHelper.getShortDisplayName(src.lang).uppercase()
                                                        .ifBlank { src.lang.uppercase() }
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                ) {
                                                    Text(
                                                        text = "$flag $langCode • ${src.name}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Suggested Mangas to integrate into this banner
                            Text(
                                text = stringResource(KMR.strings.manga_integration_suggested_entries_label),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 2.dp),
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                            ) {
                                groupMangas.forEach { itemManga ->
                                    val isMangaChecked = mangaCheckedMap[itemManga.id] == true
                                    val isMergedContainer = itemManga.source == MERGED_SOURCE_ID
                                    val source = remember(itemManga.source) {
                                        sourceManager.getOrStub(itemManga.source)
                                    }
                                    val flag = remember(isMergedContainer, source.lang) {
                                        if (isMergedContainer) "🌐" else FlagEmoji.getEmojiLangFlag(source.lang)
                                    }
                                    val langDisplay = if (isMergedContainer) {
                                        stringResource(MR.strings.multi_lang)
                                    } else {
                                        remember(source.lang) {
                                            LocaleHelper.getLocalizedDisplayName(source.lang)
                                                .ifBlank { source.lang.uppercase() }
                                        }
                                    }
                                    val langShort = remember(source.lang) {
                                        LocaleHelper.getShortDisplayName(source.lang).uppercase()
                                    }
                                    val isPrimary = primaryId == itemManga.id
                                    val count = chapterCounts[itemManga.id] ?: 0

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .border(
                                                width = if (isPrimary) 1.5.dp else 0.dp,
                                                color = if (isPrimary) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerLow
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                            )
                                            .clickable {
                                                mangaCheckedMap[itemManga.id] = !isMangaChecked
                                            }
                                            .padding(MaterialTheme.padding.small),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                                    ) {
                                        Checkbox(
                                            checked = isMangaChecked,
                                            onCheckedChange = { mangaCheckedMap[itemManga.id] = it },
                                        )

                                        MangaCover.Book(
                                            data = itemManga,
                                            modifier = Modifier.width(42.dp),
                                        )

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = itemManga.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "$flag $langDisplay ($langShort) • ${source.name}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (count > 0) {
                                                Text(
                                                    text = stringResource(
                                                        KMR.strings.manga_integration_chapters_count,
                                                        count,
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.secondaryItemAlpha(),
                                                )
                                            }
                                        }

                                        if (!isMergedContainer) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                RadioButton(
                                                    selected = isPrimary,
                                                    onClick = { primaryMangaPerGroup[gKey] = itemManga.id },
                                                )
                                                Text(
                                                    text = stringResource(KMR.strings.manga_integration_primary_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.secondaryItemAlpha(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = horizontalPaddingModifier)

            // Fixed Sticky Footer (Single banner option + Action buttons)
            Row(
                modifier = horizontalPaddingModifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { keepSingleBanner = !keepSingleBanner }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Checkbox(
                    checked = keepSingleBanner,
                    onCheckedChange = { keepSingleBanner = it },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(KMR.strings.manga_integration_single_banner_option),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(KMR.strings.manga_integration_single_banner_summary),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            }

            Row(
                modifier = horizontalPaddingModifier
                    .padding(bottom = MaterialTheme.padding.extraSmall)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    enabled = !isBusyIntegrating,
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .weight(1f),
                ) {
                    Text(
                        text = stringResource(MR.strings.action_cancel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    onClick = { integrateGroups(validSelectedGroups, isSingleCardAction = false) },
                    enabled = validSelectedGroups.isNotEmpty() && !isBusyIntegrating,
                    modifier = Modifier
                        .heightIn(min = minHeight)
                        .weight(1.6f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isGeneralListMode) {
                            stringResource(
                                KMR.strings.manga_integration_confirm_multiple,
                                validSelectedGroups.size,
                            )
                        } else {
                            stringResource(KMR.strings.manga_integration_confirm)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (onAddSeparately != null) {
                OutlinedButton(
                    onClick = {
                        onDismissRequest()
                        onAddSeparately()
                    },
                    enabled = !isBusyIntegrating,
                    modifier = horizontalPaddingModifier
                        .heightIn(min = minHeight)
                        .fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(MR.strings.action_add_anyway),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
// KMK <--
